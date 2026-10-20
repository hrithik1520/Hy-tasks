package com.hy.assistant.auto

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.hy.assistant.ReplyMode
import com.hy.assistant.Settings
import com.hy.assistant.core.AutoDecision
import com.hy.assistant.core.AutoReply
import com.hy.assistant.core.AutomationPolicy
import com.hy.assistant.core.Prompts
import com.hy.assistant.core.ReplyChoices
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
import java.util.concurrent.atomic.AtomicInteger

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

    // One shared wake lock, counted by hand (a refcounted lock with timeouts can throw on release).
    // It is held from the first message until the reply is sent or abandoned: a phone asleep in a
    // pocket would otherwise stall the debounce and countdown timers below.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hy:pipeline")
        .apply { setReferenceCounted(false) }
    private val holders = AtomicInteger(0)

    private fun hold() {
        holders.incrementAndGet()
        wakeLock.acquire(HOLD_TIMEOUT_MS) // safety net: never held longer than this
    }

    private fun release() {
        if (holders.decrementAndGet() <= 0) {
            holders.set(0)
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    /** Launches [block] with the CPU held awake until it finishes, fails, or is cancelled before it even starts. */
    private fun launchHeld(block: suspend CoroutineScope.() -> Unit): Job {
        hold()
        val job = scope.launch(block = block)
        job.invokeOnCompletion { release() }
        return job
    }

    fun onIncoming(chatKey: String) {
        // A new message cancels a pending auto-send for that chat (we re-draft with full context),
        // and its "Auto-replying in 10s" card with it, so a dead Cancel button can't linger.
        pendingSends.remove(chatKey)?.let {
            it.job.cancel()
            HyNotifications.cancel(context, chatKey)
        }
        debounce.remove(chatKey)?.cancel()
        debounce[chatKey] = launchHeld {
            val self = coroutineContext[Job]
            delay(DEBOUNCE_MS) // wait for bursts ("hey" … "you there?" … "call me")
            try {
                process(chatKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "automation failed for $chatKey", e)
            } finally {
                // Only remove our own entry: a newer message may already have replaced it.
                self?.let { debounce.remove(chatKey, it) }
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
                inCooldown = System.currentTimeMillis() - (lastAutoAt[chat.key] ?: 0L) < s.autoCooldownMin * 60_000L,
                dailyLimitReached = ActivityLog.autoSentToday() >= DAILY_AUTO_LIMIT,
            ),
        )
        // Someone who may be in distress: no bot-written reply is appropriate; just tell the user.
        if (decision is AutoDecision.NeedsYou) {
            HyNotifications.sensitive(context, chat.key, chat.name, pendingIncoming.last().text, decision.reason)
            ActivityLog.add(ActivityLog.Kind.HELD, chat.name, "(no reply drafted)", decision.reason)
            return
        }
        if (decision == AutoDecision.None) return
        val model = models.activeModelFile() // may be null: a fixed holding message / quick reply needs no AI
        val sendAutomatically = decision == AutoDecision.Auto
        val holdReason = (decision as? AutoDecision.Hold)?.reason

        // Manual suggestions offer 3 tap-to-send choices when enabled (one AI call either way).
        val withChoices = !sendAutomatically && s.replyChoices
        val lines = chat.messages.map { it.toChatLine() }
        val prompt = when {
            sendAutomatically -> Prompts.autoReply(chat.name, lines, s.userName, s.tone, ReplyStyle.promptRules(chat, s))
            withChoices -> Prompts.replyChoices(chat.name, lines, s.userName, s.tone, ReplyStyle.promptRules(chat, s))
            else -> Prompts.draftReply(chat.name, lines, s.userName, s.tone, styleRules = ReplyStyle.promptRules(chat, s))
        }
        val holdSeed = (System.currentTimeMillis() / 60_000L).toInt() + chat.key.hashCode()
        // Auto: questions/requests aimed at the user are held by rule (no AI needed); otherwise the
        // model only chooses hold/reply, and a hold sends a holding message, never invented facts.
        val quick = if (sendAutomatically) pendingIncoming.lastOrNull()?.let { AutoReply.quickReply(it.text) } else null
        var choices = emptyList<String>()
        val draft = if (sendAutomatically && pendingIncoming.any { AutoReply.mustHold(it.text) }) {
            AutoReply.holdingText(holdSeed)
        } else if (quick != null) {
            quick
        } else {
            val m = model ?: return
            val raw = llm.withLock { engine.complete(m, prompt, s.threads, s.contextSize) }
            when {
                sendAutomatically -> AutoReply.replyText(raw) ?: AutoReply.holdingText(holdSeed)
                withChoices -> {
                    choices = ReplyChoices.parse(raw) { ReplyStyle.finish(it, chat, s) }
                    choices.firstOrNull() ?: return
                }
                else -> raw
            }
        }
        val text = if (choices.isNotEmpty()) choices.first() else ReplyStyle.finish(draft, chat, s)
        if (text.isBlank()) return

        // The user may have replied themselves while we were thinking.
        val now = MessageStore.chat(chatKey) ?: return
        if (now.messages.lastOrNull()?.fromMe == true) return

        val incoming = pendingIncoming.joinToString("\n") { it.text }
        if (sendAutomatically) {
            scheduleAutoSend(chat, if (s.appendSignature) "$text\n— sent by my assistant" else text, s.autoSendDelaySec)
        } else {
            HyNotifications.suggestion(context, chat.key, chat.name, incoming, choices.ifEmpty { listOf(text) }, holdReason)
            ActivityLog.add(if (holdReason != null) ActivityLog.Kind.HELD else ActivityLog.Kind.SUGGESTED, chat.name, text, holdReason)
        }
    }

    private fun scheduleAutoSend(chat: Chat, text: String, delaySec: Int) {
        pendingSends.remove(chat.key)?.job?.cancel()
        val job = launchHeld {
            if (delaySec > 0) {
                HyNotifications.autoPending(context, chat.key, chat.name, text, delaySec)
                delay(delaySec * 1000L)
            }
            pendingSends.remove(chat.key)
            val latest = MessageStore.chat(chat.key)
            if (latest == null || latest.messages.lastOrNull()?.fromMe == true) {
                HyNotifications.cancel(context, chat.key)
                return@launchHeld
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
        val p = pendingSends.remove(chatKey)
        p?.job?.cancel()
        // Also clears a stale countdown card whose send was already superseded by a newer message.
        HyNotifications.cancel(context, chatKey)
        if (p != null) ActivityLog.add(ActivityLog.Kind.CANCELLED, p.chatName, p.text)
    }

    fun sendPendingNow(chatKey: String) {
        val p = pendingSends.remove(chatKey) ?: return
        p.job.cancel()
        sendNow(chatKey, p.text, auto = true)
    }

    fun hasPending(chatKey: String) = pendingSends.containsKey(chatKey)

    companion object {
        private const val TAG = "Automation"
        private const val DEBOUNCE_MS = 6_000L
        private const val DAILY_AUTO_LIMIT = 40
        private const val HOLD_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
