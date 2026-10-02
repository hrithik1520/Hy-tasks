package com.hy.assistant.agents

import android.content.Context
import com.hy.assistant.Settings
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.auto.ReplyStyle
import com.hy.assistant.core.AgentKind
import com.hy.assistant.core.AgentStepRecord
import com.hy.assistant.core.CommandSafety
import com.hy.assistant.core.ContactMatcher
import com.hy.assistant.core.Orchestrator
import com.hy.assistant.core.PlannerDecision
import com.hy.assistant.core.Prompt
import com.hy.assistant.core.Prompts
import com.hy.assistant.core.SearchResult
import com.hy.assistant.core.Specialists
import com.hy.assistant.core.Specialists.MemoryOp
import com.hy.assistant.core.Specialists.MessagesOp
import com.hy.assistant.core.TextCleanup
import com.hy.assistant.core.Web
import com.hy.assistant.memory.MemoryStore
import com.hy.assistant.core.Memory
import com.hy.assistant.notifications.Chat
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.ReplySender
import com.hy.assistant.core.Export
import com.hy.assistant.core.ExportFormat
import com.hy.assistant.tools.FileSaver
import com.hy.assistant.tools.SavedFile
import com.hy.assistant.tools.Terminal
import com.hy.assistant.tools.TerminalBackend
import com.hy.assistant.tools.SearchService
import com.hy.assistant.tools.WebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class StepStatus { PLANNING, WORKING, WAITING, DONE, FAILED, SKIPPED }

data class AgentStepUi(
    val index: Int,
    val agent: AgentKind?,
    val thought: String,
    val task: String,
    val observation: String = "",
    val status: StepStatus,
)

/** An outward action the agent wants to take. Nothing happens until the user approves. */
data class Approval(
    val kind: Kind,
    val title: String,
    /** Editable: message text or command. */
    val text: String,
    val warnings: List<String> = emptyList(),
) {
    enum class Kind { SEND_MESSAGE, RUN_COMMAND }
}

data class AgentRun(
    val goal: String,
    val maxSteps: Int,
    val steps: List<AgentStepUi> = emptyList(),
    val approval: Approval? = null,
    val running: Boolean = true,
    val answer: String? = null,
    val error: String? = null,
    val links: List<SearchResult> = emptyList(),
    /** Files the Files agent created during this run. */
    val files: List<SavedFile> = emptyList(),
)

/**
 * Multi-step agent: an orchestrator LLM plans each step and delegates it to a specialist
 * sub-agent (research, messages, terminal, browser, memory). Specialists run tools and report
 * an observation back. Sending messages and running commands pause for the user's approval.
 */
class AgentRunner(
    private val context: Context,
    private val settings: Settings,
    private val complete: suspend (Prompt) -> String,
) {
    private val _run = MutableStateFlow<AgentRun?>(null)
    val run: StateFlow<AgentRun?> = _run.asStateFlow()
    private var pendingApproval: CompletableDeferred<String?>? = null

    fun approve(editedText: String) {
        pendingApproval?.complete(editedText.trim().ifEmpty { null })
    }

    fun skip() {
        pendingApproval?.complete(null)
    }

    fun clear() {
        pendingApproval?.complete(null)
        _run.value = null
    }

    /** Runs the whole loop; returns the final answer. Cancel the calling coroutine to stop. */
    suspend fun execute(goal: String, extraContext: String, scope: CoroutineScope): String {
        val maxSteps = settings.current.agentMaxSteps
        _run.value = AgentRun(goal, maxSteps)
        val records = mutableListOf<AgentStepRecord>()
        val budget = if (settings.current.contextSize >= 4096) 6000 else 2200
        try {
            while (records.size < maxSteps) {
                val index = records.size + 1
                addStep(AgentStepUi(index, null, "", "Planning…", status = StepStatus.PLANNING))
                val raw = complete(Orchestrator.prompt(goal, records, maxSteps, extraContext, budget))
                val decision = Orchestrator.parse(raw)
                when (decision) {
                    is PlannerDecision.Finish, null -> {
                        removeLastStep()
                        val answer = (decision as? PlannerDecision.Finish)?.answer ?: wrapUp(goal, records)
                        return finish(answer)
                    }
                    is PlannerDecision.Delegate -> {
                        updateLastStep { it.copy(agent = decision.agent, thought = decision.thought, task = decision.task, status = StepStatus.WORKING) }
                        val (observation, status) = try {
                            runSpecialist(decision.agent, decision.task, scope, records)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Error: ${e.message}" to StepStatus.FAILED
                        }
                        val obs = observation.ifBlank { "(no result)" }.take(Orchestrator.MAX_OBSERVATION)
                        updateLastStep { it.copy(observation = obs, status = status) }
                        records += AgentStepRecord(decision.agent, decision.task, decision.thought, obs)
                    }
                }
            }
            return finish(wrapUp(goal, records))
        } catch (e: kotlinx.coroutines.CancellationException) {
            _run.update { it?.copy(running = false, approval = null, error = "Stopped") }
            throw e
        } catch (e: Exception) {
            _run.update { it?.copy(running = false, approval = null, error = e.message ?: "Agent failed") }
            throw e
        }
    }

    private suspend fun wrapUp(goal: String, records: List<AgentStepRecord>): String =
        if (records.isEmpty()) "I couldn't plan this task. Try rephrasing it."
        else TextCleanup.cleanReply(complete(Orchestrator.wrapUpPrompt(goal, records)))

    private fun finish(answer: String): String {
        _run.update { it?.copy(running = false, answer = answer, approval = null) }
        return answer
    }

    // ---- Specialists -----------------------------------------------------------------------

    private suspend fun runSpecialist(
        agent: AgentKind,
        task: String,
        scope: CoroutineScope,
        records: List<AgentStepRecord>,
    ): Pair<String, StepStatus> = when (agent) {
        AgentKind.FILES -> files(task, records)
        AgentKind.RESEARCH -> research(task)
        AgentKind.BROWSER -> browse(task)
        AgentKind.MESSAGES -> messages(task)
        AgentKind.TERMINAL -> terminal(task, scope)
        AgentKind.MEMORY -> memory(task)
    }

    private suspend fun research(task: String): Pair<String, StepStatus> {
        if (!settings.current.webSearch) return "Web search is turned off in Settings." to StepStatus.FAILED
        val outcome = SearchService.search(context, task, settings.current.searchEngine)
        if (outcome.results.isEmpty()) return "No web results (offline or blocked)." to StepStatus.FAILED
        _run.update { r -> r?.copy(links = (r.links + outcome.results.take(2)).distinctBy { it.url }.take(6)) }
        val facts = complete(Specialists.extractPrompt(task, "web search", Web.formatResults(outcome.results, outcome.topText)))
        val sources = outcome.source + ": " + outcome.results.take(2).joinToString(", ") { Web.host(it.url) }
        return "${facts.trim()}\n(sources: $sources)" to StepStatus.DONE
    }

    private suspend fun browse(task: String): Pair<String, StepStatus> {
        val target = Specialists.parseBrowser(complete(Specialists.browserPrompt(task))) ?: task
        val url = Web.browseUrl(target)
        val text = withContext(Dispatchers.IO) { runCatching { Web.htmlToText(WebSearch.get(url), 4000) }.getOrNull() }
        if (text.isNullOrBlank() || text.length < 80) {
            return "Opened $url but it had no readable text (the site may need JavaScript)." to StepStatus.FAILED
        }
        _run.update { r -> r?.copy(links = (r.links + SearchResult(Web.host(url), url, "")).distinctBy { it.url }.take(6)) }
        val facts = complete(Specialists.extractPrompt(task, url, text))
        return "${facts.trim()}\n(page: $url)" to StepStatus.DONE
    }

    private suspend fun messages(task: String): Pair<String, StepStatus> {
        val chats = MessageStore.state.value
        val op = Specialists.parseMessages(complete(Specialists.messagesPrompt(task, chats.map { it.name })))
            ?: return "Couldn't understand the messaging task." to StepStatus.FAILED
        return when (op) {
            MessagesOp.ListChats -> {
                val unread = chats.filter { it.unread.isNotEmpty() }
                (if (unread.isEmpty()) "No unread chats." else unread.take(10).joinToString("\n") { c ->
                    "- ${c.name} (${c.unread.size} unread): ${c.unread.last().text.take(80)}"
                }) to StepStatus.DONE
            }
            is MessagesOp.Read -> findChat(op.contact, chats)?.let { c ->
                "Recent messages in ${c.name}:\n" + Prompts.transcript(c.messages.map { it.toChatLine() }, 600) to StepStatus.DONE
            } ?: (notFound(op.contact) to StepStatus.FAILED)
            is MessagesOp.Draft -> findChat(op.contact, chats)?.let { c ->
                val s = settings.current
                val draft = ReplyStyle.finish(
                    complete(
                        Prompts.draftReply(
                            c.name, c.messages.map { it.toChatLine() }, s.userName, s.tone,
                            memory = MemoryStore.promptBlock(400), styleRules = ReplyStyle.promptRules(c, s),
                        ),
                    ),
                    c, s,
                )
                sendWithApproval(c, draft)
            } ?: (notFound(op.contact) to StepStatus.FAILED)
            is MessagesOp.Send -> findChat(op.contact, chats)?.let { c -> sendWithApproval(c, ReplyStyle.finish(op.text, c, settings.current)) }
                ?: (notFound(op.contact) to StepStatus.FAILED)
        }
    }

    private suspend fun sendWithApproval(chat: Chat, text: String): Pair<String, StepStatus> {
        val approved = askApproval(Approval(Approval.Kind.SEND_MESSAGE, "Send to ${chat.name}?", text))
            ?: return "The user chose not to send the message to ${chat.name}." to StepStatus.SKIPPED
        return when (val r = ReplySender.send(context, chat.key, approved)) {
            ReplySender.Result.Sent -> {
                ActivityLog.add(ActivityLog.Kind.SENT, chat.name, approved, "agent")
                "Sent to ${chat.name}: \"$approved\"" to StepStatus.DONE
            }
            is ReplySender.Result.Failed -> {
                ReplySender.copyAndOpenWhatsApp(context, chat.packageName, approved)
                "Couldn't send directly (${r.reason}). The text was copied and WhatsApp opened for the user to paste." to StepStatus.FAILED
            }
        }
    }

    private suspend fun terminal(task: String, scope: CoroutineScope): Pair<String, StepStatus> {
        val backend = settings.current.terminalBackend
        val command = Specialists.parseTerminal(complete(Specialists.terminalPrompt(task, backend == TerminalBackend.TERMUX)))
            ?: return "Couldn't write a command for that." to StepStatus.FAILED
        val approved = askApproval(
            Approval(Approval.Kind.RUN_COMMAND, "Run in ${backend.label}?", command, CommandSafety.warnings(command)),
        ) ?: return "The user chose not to run: $command" to StepStatus.SKIPPED
        val id = Terminal.run(context, scope, approved, backend) ?: return "Empty command." to StepStatus.FAILED
        val entry = withTimeoutOrNull(75_000) {
            Terminal.entries.first { list -> list.firstOrNull { it.id == id }?.running == false }.first { it.id == id }
        } ?: return "$ $approved\n(no result within 75 s)" to StepStatus.FAILED
        val status = if (entry.exitCode == 0) StepStatus.DONE else StepStatus.FAILED
        return "$ $approved\nexit ${entry.exitCode}\n${entry.output.trim().takeLast(550)}" to status
    }

    private suspend fun files(task: String, records: List<AgentStepRecord>): Pair<String, StepStatus> {
        val format = Export.requestedFormat(task) ?: ExportFormat.MD
        val gathered = records.joinToString("\n\n") { "[${it.agent.id}] ${it.task}\n${it.observation}" }
        val content = complete(Specialists.filePrompt(task, gathered)).trim()
        if (content.isBlank()) return "Nothing to write." to StepStatus.FAILED
        val title = task.substringAfter(':', task).trim().ifBlank { "hy-file" }
        val file = withContext(Dispatchers.IO) { FileSaver.save(context, content, title, format) }
        _run.update { it?.copy(files = it.files + file) }
        return "Saved ${format.label} file: ${file.location}" to StepStatus.DONE
    }

    private fun memory(task: String): Pair<String, StepStatus> = when (val op = Specialists.parseMemoryTask(task)) {
        is MemoryOp.Remember -> when (val r = MemoryStore.add(op.fact)) {
            is Memory.AddResult.Added -> "Saved to memory: ${r.fact}" to StepStatus.DONE
            is Memory.AddResult.Rejected -> r.reason to StepStatus.FAILED
        }
        is MemoryOp.Recall -> {
            val facts = MemoryStore.facts.value
            val hits = Memory.matching(facts, op.about).ifEmpty { facts }
            (if (hits.isEmpty()) "Memory is empty." else hits.take(10).joinToString("\n") { "- ${it.text}" }) to StepStatus.DONE
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    private suspend fun askApproval(a: Approval): String? {
        val d = CompletableDeferred<String?>()
        pendingApproval = d
        _run.update { it?.copy(approval = a) }
        updateLastStep { it.copy(status = StepStatus.WAITING) }
        try {
            return d.await()
        } finally {
            pendingApproval = null
            _run.update { it?.copy(approval = null) }
            updateLastStep { it.copy(status = StepStatus.WORKING) }
        }
    }

    private fun findChat(name: String, chats: List<Chat>): Chat? =
        when (val r = ContactMatcher.resolve(name, chats.map { it.name })) {
            is ContactMatcher.Result.Unique -> chats.first { it.name == r.name }
            // Agents can't ask "which one?" mid-step; take the best match (the send still needs approval).
            is ContactMatcher.Result.Ambiguous -> chats.first { it.name == r.candidates.first() }
            ContactMatcher.Result.NotFound -> null
        }

    private fun notFound(name: String) =
        "No chat named \"$name\" in the last 3 days. Known chats: ${MessageStore.state.value.take(10).joinToString(", ") { it.name }}"

    private fun addStep(s: AgentStepUi) = _run.update { it?.copy(steps = it.steps + s) }
    private fun removeLastStep() = _run.update { it?.copy(steps = it.steps.dropLast(1)) }
    private fun updateLastStep(f: (AgentStepUi) -> AgentStepUi) =
        _run.update { r -> r?.copy(steps = r.steps.dropLast(1) + listOfNotNull(r.steps.lastOrNull()?.let(f))) }
}
