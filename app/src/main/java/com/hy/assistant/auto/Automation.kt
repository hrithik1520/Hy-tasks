package com.hy.assistant.auto

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.hy.assistant.ReplyMode
import com.hy.assistant.Settings
import com.hy.assistant.SettingsData
import com.hy.assistant.core.AutoDecision
import com.hy.assistant.core.AutoReply
import com.hy.assistant.core.AutomationPolicy
import com.hy.assistant.core.Outreach
import com.hy.assistant.core.Prompts
import com.hy.assistant.llm.LlamaEngine
import com.hy.assistant.models.ModelManager
import com.hy.assistant.notifications.Chat
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.ReplySender
import com.hy.assistant.notifications.StoredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Reacts to new incoming messages without the user asking:
 *  - Auto mode: runs [Outreach] — asks whoever writes in whether they have a message for the
 *    user, then confirms it will be passed on. The words are fixed, so no model is needed and
 *    nothing can be invented.
 *  - Manual mode: drafts a reply with the model and posts it as a notification with a Send
 *    button. Messages held back from Auto (money, emergencies, cooldown) land here too.
 */
class Automation(
    private val context: Context,
    private val settings: Settings,
    private val models: ModelManager,
    private val engine: LlamaEngine,
    private val scope: CoroutineScope,
) {
    private data class PendingSend(val job: Job, val chatName: String, val text: String, val onSent: (() -> Unit)?)

    private val debounce = ConcurrentHashMap<String, Job>()
    private val pendingSends = ConcurrentHashMap<String, PendingSend>()
    private val outreach = OutreachState(context)
    private val llm = Mutex()

    fun onIncoming(chatKey: String) {
        // A new message cancels a pending auto-send for that chat: we'll re-draft with full context.
        pendingSends.remove(chatKey)?.job?.cancel()
        debounce.remove(chatKey)?.cancel()
        debounce[chatKey] = scope.launch {
            delay(DEBOUNCE_MS) // wait for bursts ("hey" … "you there?" … "call me")
            try {
                process(chatKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "automation failed for $chatKey", e)
            } finally {
                debounce.remove(chatKey)
            }
        }
    }

    private suspend fun process(chatKey: String) {
        val s = settings.current
        val chat = MessageStore.chat(chatKey) ?: return
        val last = chat.messages.lastOrNull() ?: return
        if (last.fromMe) return // already answered
        val pendingIncoming = chat.messages.takeLastWhile { !it.fromMe }

        val decision = AutomationPolicy.decide(
            AutomationPolicy.Input(
                chatName = chat.name,
                isWhatsApp = chat.isWhatsApp,
                isGroup = chat.isGroup,
                canReply = chat.canReply,
                incoming = pendingIncoming.map { it.text },
                chatSetting = AutomationPolicy.ChatSetting.valueOf(s.modeFor(chatKey).name),
                globalAuto = s.replyMode == ReplyMode.AUTO,
                proactiveSuggestions = s.proactiveSuggestions,
                includeGroups = s.autoReplyGroups,
                includeOtherApps = s.autoReplyOtherApps,
                // The cooldown starts only once Alfrid has confirmed a message here, so the
                // answer to its own opening question is never held back.
                inCooldown = outreach.lastAckAt(chat.key)
                    .let { it > 0 && System.currentTimeMillis() - it < s.autoCooldownMin * 60_000L },
                dailyLimitReached = ActivityLog.autoSentToday() >= DAILY_AUTO_LIMIT,
            ),
        )
        when (decision) {
            AutoDecision.None -> return
            AutoDecision.Auto -> runOutreach(chat, pendingIncoming, s)
            else -> suggest(chat, pendingIncoming, s, (decision as? AutoDecision.Hold)?.reason)
        }
    }

    /**
     * Alfrid's own two-step script: the opening question the first time someone writes in, then a
     * confirmation once their answer actually carries a message or task.
     */
    private fun runOutreach(chat: Chat, pendingIncoming: List<StoredMessage>, s: SettingsData) {
        if (!outreach.introduced(chat.key)) {
            scheduleAutoSend(
                chat,
                Outreach.intro(s.userName),
                s.autoSendDelaySec,
                onSent = { outreach.markIntroduced(chat.key) },
            )
            return
        }
        val message = pendingIncoming.joinToString("\n") { it.text }
        if (!Outreach.looksLikeMessage(message)) {
            ActivityLog.add(ActivityLog.Kind.NOTED, chat.name, message, "nothing to pass on")
            return
        }
        ActivityLog.add(ActivityLog.Kind.MESSAGE, chat.name, message)
        // "happy birthday", "thanks", "running late" have one obviously right answer of their own.
        val reply = pendingIncoming.lastOrNull()?.let { AutoReply.quickReply(it.text) } ?: Outreach.ack(s.userName)
        val text = if (s.appendSignature) "$reply\n— sent by my assistant" else reply
        scheduleAutoSend(chat, text, s.autoSendDelaySec, onSent = { outreach.markAcked(chat.key) })
    }

    /** Manual mode, and anything Auto held back: a drafted reply with a Send button. */
    private suspend fun suggest(chat: Chat, pendingIncoming: List<StoredMessage>, s: SettingsData, holdReason: String?) {
        val model = models.activeModelFile() ?: return
        val prompt = Prompts.draftReply(
            chat.name,
            chat.messages.map { it.toChatLine() },
            s.userName,
            s.tone,
            styleRules = ReplyStyle.promptRules(chat, s),
        )
        val raw = withWakeLock { llm.withLock { engine.complete(model, prompt, s.threads, s.contextSize) } }
        val text = ReplyStyle.finish(raw, chat, s)
        if (text.isBlank()) return

        // The user may have replied themselves while we were thinking.
        val now = MessageStore.chat(chat.key) ?: return
        if (now.messages.lastOrNull()?.fromMe == true) return

        val incoming = pendingIncoming.joinToString("\n") { it.text }
        HyNotifications.suggestion(context, chat.key, chat.name, incoming, text, holdReason)
        ActivityLog.add(if (holdReason != null) ActivityLog.Kind.HELD else ActivityLog.Kind.SUGGESTED, chat.name, text, holdReason)
    }

    private fun scheduleAutoSend(chat: Chat, text: String, delaySec: Int, onSent: (() -> Unit)? = null) {
        pendingSends.remove(chat.key)?.job?.cancel()
        val job = scope.launch {
            if (delaySec > 0) {
                HyNotifications.autoPending(context, chat.key, chat.name, text, delaySec)
                delay(delaySec * 1000L)
            }
            pendingSends.remove(chat.key)
            val latest = MessageStore.chat(chat.key)
            if (latest == null || latest.messages.lastOrNull()?.fromMe == true) {
                HyNotifications.cancel(context, chat.key)
                return@launch
            }
            sendNow(chat.key, text, auto = true, onSent = onSent)
        }
        pendingSends[chat.key] = PendingSend(job, chat.name, text, onSent)
    }

    /** Sends immediately (notification "Send" button, or the end of an auto countdown). */
    fun sendNow(chatKey: String, text: String, auto: Boolean, onSent: (() -> Unit)? = null) {
        val chatName = MessageStore.chat(chatKey)?.name ?: "chat"
        when (val r = ReplySender.send(context, chatKey, text)) {
            ReplySender.Result.Sent -> {
                onSent?.invoke()
                HyNotifications.sent(context, chatKey, chatName, text, auto)
                ActivityLog.add(if (auto) ActivityLog.Kind.AUTO_SENT else ActivityLog.Kind.SENT, chatName, text)
            }
            is ReplySender.Result.Failed -> {
                HyNotifications.failed(context, chatKey, chatName, r.reason)
                ActivityLog.add(ActivityLog.Kind.FAILED, chatName, text, r.reason)
            }
        }
    }

    fun cancelAuto(chatKey: String) {
        val p = pendingSends.remove(chatKey) ?: return
        p.job.cancel()
        HyNotifications.cancel(context, chatKey)
        ActivityLog.add(ActivityLog.Kind.CANCELLED, p.chatName, p.text)
    }

    fun sendPendingNow(chatKey: String) {
        val p = pendingSends.remove(chatKey) ?: return
        p.job.cancel()
        sendNow(chatKey, p.text, auto = true, onSent = p.onSent)
    }

    /** Lets every chat be greeted again (used when the stored messages are cleared). */
    fun forgetOutreach() = outreach.clear()

    fun hasPending(chatKey: String) = pendingSends.containsKey(chatKey)

    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val pm = context.getSystemService(PowerManager::class.java)
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hy:draft")
        lock.acquire(90_000L)
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    companion object {
        private const val TAG = "Automation"
        private const val DEBOUNCE_MS = 6_000L
        /** Across all chats, per 24 h — the script is two messages per contact. */
        private const val DAILY_AUTO_LIMIT = 200
    }
}
