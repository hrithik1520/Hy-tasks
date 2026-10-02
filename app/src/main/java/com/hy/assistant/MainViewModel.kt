package com.hy.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hy.assistant.core.Command
import com.hy.assistant.core.CommandParser
import com.hy.assistant.core.ContactMatcher
import com.hy.assistant.core.Prompt
import com.hy.assistant.core.Prompts
import com.hy.assistant.core.NotificationClassifier.Category
import com.hy.assistant.core.TextCleanup
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.auto.HyNotifications
import com.hy.assistant.notifications.FeedItem
import com.hy.assistant.notifications.NotificationFeed
import com.hy.assistant.notifications.Chat
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.ReplySender
import com.hy.assistant.notifications.WhatsAppListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Result of a read-only assistant task (summary / catch-up). */
data class AssistantOutput(val title: String, val text: String, val running: Boolean, val error: String? = null)

/** A reply waiting for the user's explicit confirmation. Nothing is sent without a tap. */
data class ReplyProposal(
    val chatKey: String,
    val chatName: String,
    val packageName: String,
    val text: String,
    val generating: Boolean,
    val canSend: Boolean,
)

data class Disambiguation(val query: String, val candidates: List<Chat>, val command: Command)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HyApp
    val settings = app.settings
    val models = app.models
    val engine = app.engine
    val chats: StateFlow<List<Chat>> = MessageStore.state
    val feed: StateFlow<List<FeedItem>> = NotificationFeed.state
    val activity: StateFlow<List<ActivityLog.Entry>> = ActivityLog.entries

    private val _canNotify = MutableStateFlow(HyNotifications.canPost(app))
    val canNotify: StateFlow<Boolean> = _canNotify.asStateFlow()

    /** Chat to open, e.g. after tapping a Hy notification. */
    private val _openChat = MutableStateFlow<String?>(null)
    val openChat: StateFlow<String?> = _openChat.asStateFlow()

    fun requestOpenChat(key: String?) {
        _openChat.value = key
    }

    fun consumeOpenChat() {
        _openChat.value = null
    }

    fun setReplyMode(mode: ReplyMode) {
        settings.update { it.copy(replyMode = mode) }
        _messages.tryEmit(
            if (mode == ReplyMode.AUTO) "Auto mode on — Hy will reply by itself (safety rules apply)"
            else "Manual mode — Hy suggests, you tap Send",
        )
    }

    fun setChatMode(chatKey: String, mode: ChatMode) = settings.setChatMode(chatKey, mode)

    private val _listenerEnabled = MutableStateFlow(WhatsAppListenerService.isEnabled(app))
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()

    private val _output = MutableStateFlow<AssistantOutput?>(null)
    val output: StateFlow<AssistantOutput?> = _output.asStateFlow()

    private val _proposal = MutableStateFlow<ReplyProposal?>(null)
    val proposal: StateFlow<ReplyProposal?> = _proposal.asStateFlow()

    private val _disambiguation = MutableStateFlow<Disambiguation?>(null)
    val disambiguation: StateFlow<Disambiguation?> = _disambiguation.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var job: Job? = null

    fun refreshStatus() {
        _listenerEnabled.value = WhatsAppListenerService.isEnabled(app)
        _canNotify.value = HyNotifications.canPost(app)
        models.refresh()
    }

    // ---- Commands -------------------------------------------------------------------

    fun runCommand(input: String) {
        val names = chats.value.map { it.name }
        when (val cmd = CommandParser.parse(input, names)) {
            Command.Digest -> digest()
            is Command.Summarize -> withChat(cmd.contact, cmd) { summarize(it.key) }
            is Command.DraftReply -> withChat(cmd.contact, cmd) { draftReply(it.key, null) }
            is Command.Reply -> withChat(cmd.contact, cmd) { draftReply(it.key, cmd.gist) }
            is Command.Unknown -> _output.value = AssistantOutput(
                title = "I didn't get that",
                text = "Try:\n• What did I miss?\n• Summarize Rahul\n• Suggest a reply to Mom\n• Reply to Rahul saying I'm in a meeting",
                running = false,
            )
        }
    }

    fun chooseCandidate(chat: Chat) {
        val d = _disambiguation.value ?: return
        _disambiguation.value = null
        when (val cmd = d.command) {
            is Command.Summarize -> summarize(chat.key)
            is Command.DraftReply -> draftReply(chat.key, null)
            is Command.Reply -> draftReply(chat.key, cmd.gist)
            else -> Unit
        }
    }

    fun dismissDisambiguation() {
        _disambiguation.value = null
    }

    private fun withChat(query: String, cmd: Command, action: (Chat) -> Unit) {
        val all = chats.value
        when (val r = ContactMatcher.resolve(query, all.map { it.name })) {
            is ContactMatcher.Result.Unique -> action(all.first { it.name == r.name })
            is ContactMatcher.Result.Ambiguous ->
                _disambiguation.value = Disambiguation(query, r.candidates.mapNotNull { n -> all.firstOrNull { it.name == n } }, cmd)
            ContactMatcher.Result.NotFound -> _output.value = AssistantOutput(
                title = "No chat found",
                text = "I don't have recent WhatsApp messages from \"$query\". I can only see chats that sent a notification in the last 3 days.",
                running = false,
            )
        }
    }

    // ---- Read-only tasks -------------------------------------------------------------

    fun digest() {
        val unread = chats.value.filter { it.unread.isNotEmpty() }
        val others = feedDigest(NotificationFeed.unseen())
        NotificationFeed.markSeen()
        if (unread.isEmpty()) {
            val text = "No unread messages." + if (others.isNotEmpty()) "\n\n$others" else ""
            _output.value = AssistantOutput("Catch-up", text, false)
            return
        }
        // Without a model, fall back to a plain list so the feature still works.
        if (models.activeModelFile() == null) {
            _output.value = AssistantOutput("Catch-up", plainDigest(unread) + if (others.isNotEmpty()) "\n\n$others" else "", false)
            return
        }
        val prompt = Prompts.digest(unread.take(8).associate { c -> c.name to c.unread.map { it.toChatLine() } })
        runReadOnly("Catch-up", prompt, suffix = others)
    }

    /** Plain-text roundup of other apps' notifications (no AI needed). */
    private fun feedDigest(items: List<FeedItem>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder("Other notifications:")
        val important = listOf(Category.OTP, Category.PAYMENT, Category.DELIVERY, Category.CALENDAR, Category.OTHER)
        for (cat in important) {
            val list = items.filter { it.category == cat }
            if (list.isEmpty()) continue
            sb.append("\n").append(cat.label).append(":")
            list.take(3).forEach { sb.append("\n• ").append(it.appName).append(" — ").append(listOf(it.title, it.text).filter { t -> t.isNotBlank() }.joinToString(": ").take(90)) }
            if (list.size > 3) sb.append("\n  …and ${list.size - 3} more")
        }
        val minor = items.count { it.category == Category.SOCIAL || it.category == Category.PROMO }
        if (minor > 0) sb.append("\nPlus $minor social/offer notifications.")
        return sb.toString()
    }

    fun summarize(chatKey: String) {
        val chat = MessageStore.chat(chatKey) ?: return
        if (models.activeModelFile() == null) {
            _output.value = AssistantOutput("Summary · ${chat.name}", "", false, error = NO_MODEL)
            return
        }
        runReadOnly("Summary · ${chat.name}", Prompts.summarizeChat(chat.name, chat.messages.map { it.toChatLine() }))
    }

    private fun plainDigest(unread: List<Chat>) = unread.joinToString("\n") { c ->
        val last = c.unread.last()
        val who = if (c.isGroup) "${last.sender}: " else ""
        "• ${c.name} (${c.unread.size}): $who${last.text.take(80)}"
    }

    private fun runReadOnly(title: String, prompt: Prompt, suffix: String = "") {
        job?.cancel()
        _output.value = AssistantOutput(title, "", running = true)
        job = viewModelScope.launch {
            val sb = StringBuilder()
            try {
                stream(prompt) { chunk ->
                    sb.append(chunk)
                    _output.value = AssistantOutput(title, sb.toString().trim(), running = true)
                }
                val full = sb.toString().trim() + if (suffix.isNotEmpty()) "\n\n$suffix" else ""
                _output.value = AssistantOutput(title, full, running = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _output.value = AssistantOutput(title, sb.toString(), running = false, error = e.message ?: "Generation failed")
            }
        }
    }

    fun dismissOutput() {
        job?.cancel()
        _output.value = null
    }

    // ---- Replies ---------------------------------------------------------------------

    /** Drafts a reply (LLM) or, without a model, uses the user's own words as-is. */
    fun draftReply(chatKey: String, gist: String?) {
        val chat = MessageStore.chat(chatKey) ?: return
        val s = settings.current
        val base = ReplyProposal(chat.key, chat.name, chat.packageName, gist.orEmpty(), generating = false, canSend = chat.canReply)
        val model = models.activeModelFile()
        if (model == null || (gist != null && !s.polishReplies)) {
            if (gist == null) {
                _output.value = AssistantOutput("Suggest reply", "", false, error = NO_MODEL)
                return
            }
            _proposal.value = base
            return
        }
        job?.cancel()
        _proposal.value = base.copy(text = "", generating = true)
        val prompt = Prompts.draftReply(chat.name, chat.messages.map { it.toChatLine() }, s.userName, s.tone, gist)
        job = viewModelScope.launch {
            val sb = StringBuilder()
            try {
                stream(prompt) { chunk ->
                    sb.append(chunk)
                    _proposal.value = _proposal.value?.copy(text = sb.toString().trimStart())
                }
                val cleaned = TextCleanup.cleanReply(sb.toString()).ifBlank { gist.orEmpty() }
                _proposal.value = _proposal.value?.copy(text = cleaned, generating = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _proposal.value = _proposal.value?.copy(text = gist.orEmpty(), generating = false)
                _messages.tryEmit("Couldn't draft a reply: ${e.message}")
            }
        }
    }

    /** Opens an empty reply box for the user to type their own message (no AI). */
    fun editOwnReply(chatKey: String) {
        val chat = MessageStore.chat(chatKey) ?: return
        job?.cancel()
        _proposal.value = ReplyProposal(chat.key, chat.name, chat.packageName, "", generating = false, canSend = chat.canReply)
    }

    fun editProposal(text: String) {
        _proposal.value = _proposal.value?.copy(text = text)
    }

    fun dismissProposal() {
        job?.cancel()
        _proposal.value = null
    }

    /** Called only from the explicit "Send" button on the confirmation card. */
    fun confirmSend() {
        val p = _proposal.value ?: return
        val text = p.text.trim()
        if (text.isEmpty() || p.generating) return
        when (val r = ReplySender.send(app, p.chatKey, text)) {
            ReplySender.Result.Sent -> {
                _proposal.value = null
                HyNotifications.cancel(app, p.chatKey)
                ActivityLog.add(ActivityLog.Kind.SENT, p.chatName, text)
                _messages.tryEmit("Sent to ${p.chatName}")
            }
            is ReplySender.Result.Failed -> {
                _proposal.value = p.copy(canSend = false)
                _messages.tryEmit(r.reason)
            }
        }
    }

    fun copyAndOpenWhatsApp() {
        val p = _proposal.value ?: return
        if (!ReplySender.copyAndOpenWhatsApp(app, p.packageName, p.text)) _messages.tryEmit("WhatsApp is not installed")
        else _messages.tryEmit("Reply copied — paste it in WhatsApp")
    }

    private suspend fun stream(prompt: Prompt, onChunk: (String) -> Unit) {
        val model = models.activeModelFile() ?: throw IllegalStateException(NO_MODEL)
        val s = settings.current
        engine.generate(model, prompt, s.threads, s.contextSize).collect { onChunk(it) }
    }

    companion object {
        const val NO_MODEL = "No AI model installed yet. Open Models and download one (Wi-Fi recommended)."
    }
}
