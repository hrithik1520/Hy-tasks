package com.hy.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hy.assistant.core.Agent
import com.hy.assistant.core.AgentAction
import com.hy.assistant.core.Command
import com.hy.assistant.core.Conversation
import com.hy.assistant.core.Memory
import com.hy.assistant.core.MemoryFact
import com.hy.assistant.core.Turn
import com.hy.assistant.agents.AgentRunner
import com.hy.assistant.core.Export
import com.hy.assistant.core.ExportFormat
import com.hy.assistant.tools.FileSaver
import com.hy.assistant.tools.SavedFile
import com.hy.assistant.memory.ConversationStore
import com.hy.assistant.memory.MemoryStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hy.assistant.core.SearchResult
import com.hy.assistant.core.Web
import com.hy.assistant.tools.Browser
import com.hy.assistant.tools.Terminal
import com.hy.assistant.tools.WebSearch

/** Result of a read-only assistant task (summary / catch-up). */
data class AssistantOutput(
    val title: String,
    val text: String,
    val running: Boolean,
    val error: String? = null,
    /** Web sources behind the answer (tap to open in the in-app browser). */
    val links: List<SearchResult> = emptyList(),
)

/** A reply waiting for the user's explicit confirmation. Nothing is sent without a tap. */
data class ReplyProposal(
    val chatKey: String,
    val chatName: String,
    val packageName: String,
    val text: String,
    val generating: Boolean,
    val canSend: Boolean,
)

data class Disambiguation(val query: String, val candidates: List<Chat>, val onPick: (Chat) -> Unit)

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

    /** Screen the assistant wants to show ("browser", "terminal"). */
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    fun consumeRoute() {
        _route.value = null
    }

    fun openInBrowser(url: String) {
        Browser.open(url)
        _route.value = "browser"
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

    // ---- Conversation & memory ---------------------------------------------------------

    val turns: StateFlow<List<Turn>> = ConversationStore.turns
    val memoryFacts: StateFlow<List<MemoryFact>> = MemoryStore.facts

    /** The typed request whose answer is still being produced (becomes a conversation turn). */
    private val _pendingRequest = MutableStateFlow<String?>(null)
    val pendingRequest: StateFlow<String?> = _pendingRequest.asStateFlow()
    private var pendingTurn: String?
        get() = _pendingRequest.value
        set(v) {
            _pendingRequest.value = v
        }

    /** The finished output that is already shown as the last conversation turn (not repeated as a card). */
    private val _threadedOutput = MutableStateFlow<AssistantOutput?>(null)
    val threadedOutput: StateFlow<AssistantOutput?> = _threadedOutput.asStateFlow()

    init {
        // A request becomes a turn once its answer (or reply draft) is final.
        viewModelScope.launch {
            _output.collect { o ->
                if (o != null && !o.running && pendingTurn != null) {
                    finishTurn(o.error?.let { e -> listOf(o.text, "($e)").filter { it.isNotBlank() }.joinToString("\n") } ?: o.text)
                    _threadedOutput.value = o
                }
            }
        }
        viewModelScope.launch {
            _proposal.collect { p ->
                if (p != null && !p.generating) finishTurn("Drafted a reply to ${p.chatName}: \"${p.text}\" (shown for you to send)")
            }
        }
    }

    private fun finishTurn(answer: String) {
        val user = pendingTurn ?: return
        pendingTurn = null
        if (answer.isNotBlank()) ConversationStore.add(user, answer)
    }

    /** Actions started outside the home conversation (chat screen, browser) aren't turns. */
    fun endTurn() {
        pendingTurn = null
    }

    fun newChat() {
        job?.cancel()
        agents.clear()
        pendingTurn = null
        ConversationStore.clear()
        _output.value = null
    }

    private val big get() = settings.current.contextSize >= 4096
    private fun history(maxChars: Int) = Conversation.historyBlock(ConversationStore.turns.value, maxChars)
    private fun memory() = MemoryStore.promptBlock(if (big) 800 else 400)

    // ---- Files (.md / .csv / .docx / .txt) ---------------------------------------------------

    /** The file saved most recently (shown with Open / Share). */
    private val _savedFile = MutableStateFlow<SavedFile?>(null)
    val savedFile: StateFlow<SavedFile?> = _savedFile.asStateFlow()

    fun exportText(text: String, title: String, format: ExportFormat) {
        viewModelScope.launch { saveFile(text, title, format) }
    }

    private suspend fun saveFile(text: String, title: String, format: ExportFormat): SavedFile? = try {
        withContext(Dispatchers.IO) { FileSaver.save(app, text, title, format) }.also {
            _savedFile.value = it
            _messages.tryEmit("Saved to ${it.location}")
        }
    } catch (e: Exception) {
        _messages.tryEmit("Couldn't save the file: ${e.message}")
        null
    }

    /** "make a CSV of …" → tell the model how to lay it out. */
    private fun withFormatHint(request: String): String =
        Export.requestedFormat(request)?.let { request + Export.promptHint(it) } ?: request

    /** After an answer finishes, save it if the request asked for a file format. */
    private suspend fun autoExport(request: String) {
        val format = Export.requestedFormat(request) ?: return
        val o = _output.value ?: return
        if (o.running || o.error != null || o.text.isBlank()) return
        saveFile(o.text, request, format)
    }

    fun openSaved(file: SavedFile) {
        if (!FileSaver.open(app, file)) _messages.tryEmit("No app on this phone opens .${file.format.ext} files. Use Share → Google Drive/Docs.")
    }

    fun shareSaved(file: SavedFile) = FileSaver.share(app, file)

    fun dismissSaved() {
        _savedFile.value = null
    }

    // ---- Multi-step agents --------------------------------------------------------------

    val agents = AgentRunner(app, settings) { prompt ->
        val sb = StringBuilder()
        stream(prompt) { sb.append(it) }
        sb.toString()
    }
    val agentRun = agents.run

    /** When on, every typed request goes to the multi-step agent. */
    private val _agentMode = MutableStateFlow(false)
    val agentMode: StateFlow<Boolean> = _agentMode.asStateFlow()

    fun setAgentMode(on: Boolean) {
        _agentMode.value = on
    }

    private fun startAgent(goal: String) {
        if (models.activeModelFile() == null) {
            _output.value = AssistantOutput("Agent", "", false, error = NO_MODEL)
            return
        }
        job?.cancel()
        _output.value = null
        val ctx = buildString {
            settings.current.userName.takeIf { it.isNotBlank() }?.let { append("User's name: ").append(it).append("\n") }
            memory().takeIf { it.isNotBlank() }?.let { append("Facts about the user:\n").append(it).append("\n") }
            history(600).takeIf { it.isNotBlank() }?.let { append("Earlier conversation:\n").append(it).append("\n") }
        }
        job = viewModelScope.launch {
            try {
                val answer = agents.execute(goal + (Export.requestedFormat(goal)?.let { " (Save the result with the files agent.)" } ?: ""), ctx, viewModelScope)
                _output.value = AssistantOutput("Agent", answer, running = false, links = agents.run.value?.links.orEmpty())
                val made = agents.run.value?.files.orEmpty()
                if (made.isNotEmpty()) _savedFile.value = made.last() else autoExport(goal)
            } catch (e: CancellationException) {
                pendingTurn = null
                throw e
            } catch (e: Exception) {
                _output.value = AssistantOutput("Agent", "", running = false, error = e.message ?: "Agent failed")
            }
        }
    }

    fun stopAgent() {
        job?.cancel()
        agents.skip()
    }

    fun closeAgent() {
        if (agents.run.value?.running == true) stopAgent()
        agents.clear()
    }

    private fun handleMemory(cmd: Command): Boolean {
        val text = when (cmd) {
            is Command.Remember -> rememberText(cmd.fact)
            is Command.Forget -> {
                val gone = MemoryStore.forget(cmd.query)
                if (gone.isEmpty()) "I couldn't find anything about \"${cmd.query}\" in my memory."
                else "Forgotten:\n" + gone.joinToString("\n") { "• ${it.text}" }
            }
            Command.ForgetAll -> {
                MemoryStore.clear()
                "Done — I've forgotten everything you told me to remember."
            }
            Command.ListMemory -> {
                val facts = MemoryStore.facts.value
                if (facts.isEmpty()) "I don't have anything saved yet. Say \"remember that …\" to teach me."
                else "Here's what I remember:\n" + facts.sortedByDescending { it.createdAt }.joinToString("\n") { "• ${it.text}" }
            }
            else -> return false
        }
        _output.value = AssistantOutput("Memory", text, running = false)
        return true
    }

    private fun rememberText(fact: String): String = when (val r = MemoryStore.add(fact)) {
        is Memory.AddResult.Added -> "Got it — I'll remember: ${r.fact}"
        is Memory.AddResult.Rejected -> r.reason
    }

    fun refreshStatus() {
        _listenerEnabled.value = WhatsAppListenerService.isEnabled(app)
        _canNotify.value = HyNotifications.canPost(app)
        models.refresh()
    }

    // ---- Commands -------------------------------------------------------------------

    /**
     * Any English request. Common phrasings are handled instantly by rules; everything else
     * goes to the on-device model, which picks an action (grammar-constrained) or answers.
     */
    fun runCommand(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        job?.cancel()
        agents.clear()
        val parsed = CommandParser.parse(text, chats.value.map { it.name })
        if (parsed == Command.NewChat) return newChat()
        pendingTurn = text
        if (handleMemory(parsed)) return
        // "agent: …" or Agent mode → multi-step run.
        val agentGoal = Regex("""^(?:agent|/agent)[:\s]+(.+)$""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        if (agentGoal != null || _agentMode.value) return startAgent(agentGoal ?: text)
        val names = chats.value.map { it.name }
        val hasModel = models.activeModelFile() != null
        // If a rule matched but the name isn't a known chat, the rule probably misfired
        // ("summarize the news") — let the AI handle the whole request instead.
        val fallback: () -> Unit = { if (hasModel) runAgent(text) else showNotFoundOrHelp(text) }
        when (val cmd = parsed) {
            Command.Digest -> digest()
            is Command.Summarize -> withChat(cmd.contact, fallback) { summarize(it.key) }
            is Command.DraftReply -> withChat(cmd.contact, fallback) { draftReply(it.key, null) }
            is Command.Reply -> withChat(cmd.contact, fallback) { draftReply(it.key, cmd.gist) }
            is Command.Unknown -> if (hasModel) runAgent(text) else showNotFoundOrHelp(text)
            else -> Unit
        }
    }

    private fun showNotFoundOrHelp(text: String) {
        _output.value = AssistantOutput(
            title = "Need the AI model",
            text = "Without a model I only understand:\n• What did I miss?\n• Summarize <chat>\n• Reply to <chat> saying …\n\n" +
                "Download a model (Models) and you can ask anything, e.g. \"$text\".",
            running = false,
        )
    }

    private fun runAgent(request: String) {
        job?.cancel()
        _output.value = AssistantOutput("Hy", "", running = true)
        val names = chats.value.map { it.name }
        job = viewModelScope.launch {
            val action = try {
                val raw = StringBuilder()
                stream(Agent.routePrompt(request, names, history(600))) { raw.append(it) }
                Agent.parse(raw.toString()) ?: AgentAction.Answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentAction.Answer
            }
            execute(action, request)
        }
    }

    private fun execute(action: AgentAction, request: String) {
        when (action) {
            AgentAction.Answer -> launchTask("Hy") {
                answerWithSearchFallback("Hy", withFormatHint(request), buildContext())
                autoExport(request)
            }
            is AgentAction.Search -> launchTask("Searching…") {
                searchAndAnswer(withFormatHint(request), action.query)
                autoExport(request)
            }
            is AgentAction.Browse -> {
                _output.value = null
                val url = Web.browseUrl(action.target)
                finishTurn("Opened $url in the browser.")
                openInBrowser(url)
            }
            is AgentAction.RunCommand -> {
                _output.value = null
                finishTurn("Suggested the terminal command: ${action.command} (waiting for you to tap Run)")
                Terminal.propose(action.command)
                _route.value = "terminal"
            }
            AgentAction.MultiStep -> startAgent(request)
            is AgentAction.Remember -> _output.value = AssistantOutput("Memory", rememberText(action.fact), running = false)
            AgentAction.Digest -> digest()
            is AgentAction.Summarize -> withChat(action.contact, notFound(action.contact)) { summarize(it.key) }
            is AgentAction.DraftReply -> withChat(action.contact, notFound(action.contact)) { draftReply(it.key, null) }
            is AgentAction.Reply -> withChat(action.contact, notFound(action.contact)) { chat ->
                _output.value = null
                // The model already wrote the final text; the confirmation card still applies.
                _proposal.value = ReplyProposal(chat.key, chat.name, chat.packageName, TextCleanup.cleanReply(action.message), false, chat.canReply)
            }
            is AgentAction.SetMode -> {
                _output.value = null
                setReplyMode(if (action.auto) ReplyMode.AUTO else ReplyMode.MANUAL)
                finishTurn(if (action.auto) "Switched to Auto mode." else "Switched to Manual mode.")
            }
            is AgentAction.SetChatMode -> withChat(action.contact, notFound(action.contact)) { chat ->
                val mode = ChatMode.valueOf(action.mode.uppercase())
                setChatMode(chat.key, mode)
                _output.value = null
                _messages.tryEmit("${chat.name}: ${mode.label}")
                finishTurn("Set ${chat.name} to ${mode.label}.")
            }
        }
    }

    private fun notFound(query: String): () -> Unit = {
        _output.value = AssistantOutput(
            title = "No chat found",
            text = "I don't have recent messages from \"$query\". I can only see chats that sent a notification in the last 3 days.",
            running = false,
        )
    }

    /** Recent chats + notifications for free-form questions, sized to fit the context window. */
    private fun buildContext(): String {
        val budget = if (settings.current.contextSize >= 4096) 7000 else 3000
        val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.append("Now: ").append(fmt.format(java.util.Date())).append("\n")
        for (c in chats.value.take(10)) {
            if (sb.length > budget * 2 / 3) break
            sb.append("\n## Chat: ").append(c.name).append(" (").append(c.appName)
            if (c.unread.isNotEmpty()) sb.append(", ").append(c.unread.size).append(" unread")
            sb.append(")\n")
            for (m in c.messages.takeLast(4)) {
                sb.append(fmt.format(java.util.Date(m.timestamp))).append(" ")
                    .append(if (m.fromMe) "Me" else m.sender).append(": ").append(m.text.take(160)).append("\n")
            }
        }
        val items = feed.value
        if (items.isNotEmpty()) sb.append("\n## Other notifications\n")
        for (f in items.take(20)) {
            if (sb.length > budget) break
            sb.append("- ").append(fmt.format(java.util.Date(f.timestamp))).append(" [").append(f.category.label).append("] ")
                .append(f.appName).append(": ").append(listOf(f.title, f.text).filter { it.isNotBlank() }.joinToString(" — ").take(140))
                .append("\n")
        }
        return sb.toString().take(budget)
    }

    /** Free-form question about one chat ("did she confirm the time?", "list what he asked for"). */
    fun askAboutChat(chatKey: String, question: String) {
        endTurn()
        val chat = MessageStore.chat(chatKey) ?: return
        if (question.isBlank()) return
        if (models.activeModelFile() == null) {
            _output.value = AssistantOutput(chat.name, "", false, error = NO_MODEL)
            return
        }
        val ctx = "## Chat: ${chat.name}\n" + Prompts.transcript(chat.messages.map { it.toChatLine() }, 3000)
        runReadOnly(chat.name, Agent.answerPrompt(question, ctx, settings.current.userName))
    }

    fun chooseCandidate(chat: Chat) {
        val d = _disambiguation.value ?: return
        _disambiguation.value = null
        d.onPick(chat)
    }

    fun dismissDisambiguation() {
        _disambiguation.value = null
    }

    private fun withChat(query: String, onNotFound: () -> Unit, action: (Chat) -> Unit) {
        val all = chats.value
        when (val r = ContactMatcher.resolve(query, all.map { it.name })) {
            is ContactMatcher.Result.Unique -> action(all.first { it.name == r.name })
            is ContactMatcher.Result.Ambiguous -> {
                _output.value = null
                _disambiguation.value = Disambiguation(query, r.candidates.mapNotNull { n -> all.firstOrNull { it.name == n } }, action)
            }
            ContactMatcher.Result.NotFound -> onNotFound()
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

    private fun launchTask(title: String, block: suspend () -> Unit) {
        job?.cancel()
        _output.value = AssistantOutput(title, "", running = true)
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _output.value = AssistantOutput(title, _output.value?.text.orEmpty(), running = false, error = e.message ?: "Failed")
            }
        }
    }

    /** Streams [prompt] into the output card. Text that starts like "SEARCH:" is held back, not shown. */
    private suspend fun streamToOutput(title: String, prompt: Prompt, links: List<SearchResult> = emptyList()): String {
        val sb = StringBuilder()
        stream(prompt) { chunk ->
            sb.append(chunk)
            val t = sb.toString().trim()
            val maybeSearch = t.length <= Agent.SEARCH_PREFIX.length && Agent.SEARCH_PREFIX.startsWith(t, ignoreCase = true) ||
                t.startsWith(Agent.SEARCH_PREFIX, ignoreCase = true)
            if (!maybeSearch) _output.value = AssistantOutput(title, t, running = true, links = links)
        }
        return sb.toString().trim()
    }

    /** Answers from local context; if the model says it needs facts, does a quick web search. */
    private suspend fun answerWithSearchFallback(title: String, request: String, context: String) {
        val allow = settings.current.webSearch
        val text = streamToOutput(title, Agent.answerPrompt(
                request, context, settings.current.userName, allowSearch = allow,
                history = history(if (big) 1500 else 600), memory = memory(),
            ))
        val query = if (allow) Agent.searchRequest(text) else null
        if (query != null) searchAndAnswer(request, query) else _output.value = AssistantOutput(title, text, running = false)
    }

    private suspend fun searchAndAnswer(request: String, query: String) {
        if (!settings.current.webSearch) {
            _output.value = AssistantOutput("Web search is off", "Turn on \"Web search\" in Settings to let Hy look this up.", false)
            return
        }
        val title = "Web · $query"
        _output.value = AssistantOutput(title, "Searching the web…", running = true)
        val outcome = withContext(Dispatchers.IO) { WebSearch.search(query) }
        if (outcome.results.isEmpty()) {
            _output.value = AssistantOutput(title, "", false, error = "No results — check your internet connection.")
            return
        }
        val links = outcome.results.take(3)
        _output.value = AssistantOutput(title, "Reading results…", running = true, links = links)
        val answer = streamToOutput(title, Agent.searchAnswerPrompt(request, query, Web.formatResults(outcome.results, outcome.topText), history(if (big) 800 else 300)), links)
        _output.value = AssistantOutput(title, answer.removePrefix(Agent.SEARCH_PREFIX).trim(), running = false, links = links)
    }

    /** Question about the page open in the in-app browser. */
    fun askAboutPage(question: String, url: String, pageText: String) {
        endTurn()
        if (models.activeModelFile() == null) {
            _output.value = AssistantOutput("This page", "", false, error = NO_MODEL)
            return
        }
        val q = question.ifBlank { "Summarize this page in 5 bullet points." }
        val ctx = "## Web page: $url\n" + pageText.take(if (settings.current.contextSize >= 4096) 7000 else 3000)
        launchTask(Web.host(url)) {
            // Page text is untrusted: the model only produces text, no actions.
            val text = streamToOutput(Web.host(url), Agent.answerPrompt(q, ctx, settings.current.userName))
            _output.value = AssistantOutput(Web.host(url), text, running = false)
        }
    }

    fun dismissOutput() {
        pendingTurn = null
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
        val prompt = Prompts.draftReply(chat.name, chat.messages.map { it.toChatLine() }, s.userName, s.tone, gist, memory = memory())
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
        endTurn()
        val chat = MessageStore.chat(chatKey) ?: return
        job?.cancel()
        _proposal.value = ReplyProposal(chat.key, chat.name, chat.packageName, "", generating = false, canSend = chat.canReply)
    }

    fun editProposal(text: String) {
        _proposal.value = _proposal.value?.copy(text = text)
    }

    fun dismissProposal() {
        pendingTurn = null
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
