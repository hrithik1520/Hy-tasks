package com.hy.assistant.auto

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.hy.assistant.ChatMode
import com.hy.assistant.ReplyMode
import com.hy.assistant.Settings
import com.hy.assistant.core.Prompts
import com.hy.assistant.core.SafetyFilter
import com.hy.assistant.llm.LlamaEngine
import com.hy.assistant.models.ModelManager
import com.hy.assistant.notifications.Chat
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.ReplySender
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
 *  - Manual mode: drafts a reply and posts it as a notification with a Send button.
 *  - Auto mode: drafts and sends it after a short cancellable delay, unless a safety rule
 *    holds it back (then it falls back to a Manual suggestion).
 */
class Automation(
    private val context: Context,
    private val settings: Settings,
    private val models: ModelManager,
    private val engine: LlamaEngine,
    private val scope: CoroutineScope,
) {
    private data class PendingSend(val job: Job, val chatName: String, val text: String)

    private val debounce = ConcurrentHashMap<String, Job>()
    private val pendingSends = ConcurrentHashMap<String, PendingSend>()
    private val lastAutoAt = ConcurrentHashMap<String, Long>()
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
        val chatMode = s.modeFor(chatKey)
        if (chatMode == ChatMode.OFF) return
        val last = chat.messages.lastOrNull() ?: return
        if (last.fromMe) return // already answered

        val wantAuto = when (chatMode) {
            ChatMode.AUTO -> true
            ChatMode.MANUAL -> false
            else -> s.replyMode == ReplyMode.AUTO
        }
        if (!wantAuto && !s.proactiveSuggestions) return
        val model = models.activeModelFile() ?: return

        val pendingIncoming = chat.messages.takeLastWhile { !it.fromMe }
        val holdReason = if (wantAuto) autoBlockReason(chat, chatMode, pendingIncoming.map { it.text }) else null
        val sendAutomatically = wantAuto && holdReason == null

        val prompt = if (sendAutomatically) {
            Prompts.autoReply(chat.name, chat.messages.map { it.toChatLine() }, s.userName, s.tone, ReplyStyle.promptRules(chat, s))
        } else {
            Prompts.draftReply(chat.name, chat.messages.map { it.toChatLine() }, s.userName, s.tone, styleRules = ReplyStyle.promptRules(chat, s))
        }
        val raw = withWakeLock { llm.withLock { engine.complete(model, prompt, s.threads, s.contextSize) } }
        val text = ReplyStyle.finish(raw, chat, s)
        if (text.isBlank()) return

        // The user may have replied themselves while we were thinking.
        val now = MessageStore.chat(chatKey) ?: return
        if (now.messages.lastOrNull()?.fromMe == true) return

        val incoming = pendingIncoming.joinToString("\n") { it.text }
        if (sendAutomatically) {
            scheduleAutoSend(chat, if (s.appendSignature) "$text\n— sent by my assistant" else text, s.autoSendDelaySec)
        } else {
            HyNotifications.suggestion(context, chat.key, chat.name, incoming, text, holdReason)
            ActivityLog.add(if (holdReason != null) ActivityLog.Kind.HELD else ActivityLog.Kind.SUGGESTED, chat.name, text, holdReason)
        }
    }

    /** Why this chat must not be auto-answered right now (null = OK to auto-send). */
    private fun autoBlockReason(chat: Chat, chatMode: ChatMode, incoming: List<String>): String? {
        val s = settings.current
        val explicit = chatMode == ChatMode.AUTO
        incoming.firstNotNullOfOrNull { SafetyFilter.blockReason(it) }?.let { return it.label }
        if (!chat.canReply) return "no direct reply available"
        if (chat.isGroup && !s.autoReplyGroups && !explicit) return "group chat"
        if (!chat.isWhatsApp && !s.autoReplyOtherApps && !explicit) return "${chat.appName} chat"
        val since = System.currentTimeMillis() - (lastAutoAt[chat.key] ?: 0L)
        if (since < s.autoCooldownMin * 60_000L) return "already auto-replied recently"
        if (ActivityLog.autoSentToday() >= DAILY_AUTO_LIMIT) return "daily auto-reply limit reached"
        return null
    }

    private fun scheduleAutoSend(chat: Chat, text: String, delaySec: Int) {
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
            sendNow(chat.key, text, auto = true)
        }
        pendingSends[chat.key] = PendingSend(job, chat.name, text)
    }

    /** Sends immediately (notification "Send" button, or the end of an auto countdown). */
    fun sendNow(chatKey: String, text: String, auto: Boolean) {
        val chatName = MessageStore.chat(chatKey)?.name ?: "chat"
        when (val r = ReplySender.send(context, chatKey, text)) {
            ReplySender.Result.Sent -> {
                if (auto) lastAutoAt[chatKey] = System.currentTimeMillis()
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
        sendNow(chatKey, p.text, auto = true)
    }

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
        private const val DAILY_AUTO_LIMIT = 40
    }
}
