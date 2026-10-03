package com.hy.assistant.core

import kotlin.test.Test

/**
 * Model evaluation set (the "test set" from docs/android-local-agent-architecture.md §8).
 * `-Dhy.evalOut=<file.jsonl>` writes every case with the app's real prompts and grammars; the
 * native host test runs them on a model and tools/eval/score.py scores the outputs.
 */
class EvalCases {
    private val chats = listOf("Rahul Sharma", "Mom", "Priya", "College Gang", "Office Team", "Dad")

    private data class Case(val id: String, val kind: String, val prompt: Prompt, val expect: Map<String, String>)

    private fun router(id: String, text: String, vararg expect: Pair<String, String>) =
        Case(id, "router", Agent.routePrompt(text, chats), mapOf(*expect))

    private fun cases(): List<Case> {
        val out = mutableListOf<Case>()
        // --- Router: one request → one action ---------------------------------------------
        out += router("r-reply-1", "tell mom i'll be late for dinner", "action" to "reply", "contact~" to "mom")
        out += router("r-reply-2", "text rahul happy birthday bro", "action" to "reply", "contact~" to "rahul")
        out += router("r-reply-3", "let priya know the meeting moved to 4pm", "action" to "reply", "contact~" to "priya")
        out += router("r-draft-1", "what should i reply to dad", "action" to "draft_reply", "contact~" to "dad")
        out += router("r-draft-2", "help me answer the office team", "action" to "draft_reply", "contact~" to "office")
        out += router("r-sum-1", "what's going on in college gang", "action" to "summarize", "contact~" to "college")
        out += router("r-sum-2", "what did priya say", "action" to "summarize", "contact~" to "priya")
        out += router("r-digest-1", "anything important today?", "action" to "digest")
        out += router("r-digest-2", "catch me up on everything", "action" to "digest")
        out += router("r-mode-1", "stop auto replying", "action" to "set_mode", "mode" to "manual")
        out += router("r-mode-2", "turn on auto reply", "action" to "set_mode", "mode" to "auto")
        out += router("r-chatmode-1", "never auto reply to the office team", "action" to "set_chat_mode", "contact~" to "office", "mode" to "off")
        out += router("r-search-1", "who won the cricket match yesterday", "action" to "search")
        out += router("r-search-2", "what is the price of iphone 17 in india", "action" to "search")
        out += router("r-search-3", "weather in goa tomorrow", "action" to "search")
        out += router("r-browse-1", "open youtube and search lofi music", "action" to "browse")
        out += router("r-browse-2", "open amazon.in", "action" to "browse")
        out += router("r-cmd-1", "show storage usage in termux", "action" to "run_command")
        out += router("r-cmd-2", "list the files in my downloads folder", "action" to "run_command")
        out += router("r-mem-1", "note that i'm allergic to peanuts", "action" to "remember", "fact~" to "peanut")
        out += router("r-agent-1", "find today's gold price and send it to dad", "action" to "agent")
        out += router("r-agent-2", "compare pixel 10 and iphone 17 prices and save it as a word file", "action" to "agent")
        out += router("r-answer-1", "write a leave application for tomorrow", "action" to "answer")
        out += router("r-answer-2", "explain what an emi is in simple words", "action" to "answer")
        out += router("r-answer-3", "did anyone mention dinner?", "action" to "answer")

        // --- Orchestrator: first step of multi-step goals ------------------------------------
        fun orch(id: String, goal: String, agent: String) =
            Case(id, "orchestrator", Orchestrator.prompt(goal, emptyList(), 10), mapOf("agent" to agent))
        out += orch("o-1", "find today's gold price and send it to dad", "research")
        out += orch("o-2", "check how much storage is free and remember it", "terminal")
        out += orch("o-3", "what do you remember about my boss", "memory")
        out += orch("o-4", "read my chat with priya and draft a reply", "messages")

        // --- Free-text replies (scored for chatbot tells and length) -------------------------
        val lines = listOf(
            ChatLine("Rahul", "bro are you coming tonight?", 1, false),
            ChatLine("Rahul", "we're meeting at 8 near the station", 2, false),
        )
        out += Case(
            "w-reply-1", "reply",
            Prompts.draftReply("Rahul", lines, "Hrithik", Tone.CASUAL, styleRules = Humanizer.PROMPT_RULES), emptyMap(),
        )
        out += Case(
            "w-reply-2", "reply",
            Prompts.autoReply("Rahul", lines, "Hrithik", Tone.CASUAL, Humanizer.PROMPT_RULES), emptyMap(),
        )
        out += Case(
            "w-answer-1", "answer",
            Agent.answerPrompt("give me 3 tips to save money, as a short list", "", "Hrithik"), emptyMap(),
        )
        // --- Context-fit replies (from a real bad auto-reply, 2 Oct 2026) ------------------------
        // "any": at least one must appear; "none": none may appear (comma-separated).
        fun reply(id: String, chat: String, lines: List<ChatLine>, any: String, none: String = "", auto: Boolean = true, kind: String = "hold") {
            if (auto) {
                // Auto: the model must choose hold vs reply; reply text is checked like a draft.
                out += Case(id, "autoreply", Prompts.autoReply(chat, lines, "Hrithik", Tone.CASUAL, Humanizer.PROMPT_RULES),
                    mapOf("kind" to kind, "any" to any, "none" to none))
            } else {
                out += Case(id, "reply", Prompts.draftReply(chat, lines, "Hrithik", Tone.CASUAL, styleRules = Humanizer.PROMPT_RULES),
                    mapOf("any" to any, "none" to none))
            }
        }
        val youChat = listOf(
            ChatLine("You", ".", 1, false),
            ChatLine("Me", "Hey, I'll check your message later.\n— sent by my assistant", 2, true),
            ChatLine("You", "What are you doing", 3, false),
        )
        val busyWords = "busy,doing,working,free,bit,soon,later,nothing,chill,just,call,text you"
        // Questions aimed at the user are held by AutoReply.mustHold (unit-tested), so the model only
        // sees the rest: statements, wishes and acknowledgements.
        reply("c-you-draft", "You", youChat, busyWords, "no worries,when you're free,when you are free", auto = false)
        reply("c-bday", "Priya", listOf(ChatLine("Priya", "happy birthday!! 🎉 have a great day", 1, false)), "thank", "happy birthday", kind = "reply")
        reply("c-see-you", "Rahul", listOf(
            ChatLine("Me", "let's meet at 8 near the station", 1, true),
            ChatLine("Rahul", "ok see you at 8", 2, false),
        ), "see you,ok,sure,cool,done,great,sounds good,👍", "no worries", kind = "reply")
        reply("c-coming-draft", "Mom", listOf(ChatLine("Mom", "are you coming home for dinner?", 1, false)), "dinner,home,coming,let you know,soon,later,yes,bit", "", auto = false)
        reply("c-news", "Rahul", listOf(ChatLine("Rahul", "bro i got the job!!", 1, false)), "congrat,awesome,great,amazing,proud,wow", "", kind = "reply")
        reply("c-traffic", "Priya", listOf(ChatLine("Priya", "stuck in traffic, will be 10 mins late", 1, false)), "no problem,no worries,ok,sure,take your time,np,sorry to hear,hope,drive safe", "sorry for the delay,i'm late,i'll be late,i'm running late", kind = "reply")
        reply("c-gm", "Dad", listOf(ChatLine("Dad", "good night beta, sleep well", 1, false)), "night,sleep", "", kind = "reply")
        return out
    }

    @Test
    fun writeEvalFileIfRequested() {
        val path = System.getProperty("hy.evalOut") ?: return
        val sb = StringBuilder()
        for (c in cases()) {
            sb.append("{")
                .append("\"id\":").append(q(c.id)).append(",\"kind\":").append(q(c.kind))
                .append(",\"system\":").append(q(c.prompt.system)).append(",\"user\":").append(q(c.prompt.user))
                .append(",\"grammar\":").append(q(c.prompt.grammar ?: "")).append(",\"max\":").append(c.prompt.maxTokens).append(",\"temp\":").append(c.prompt.temperature)
                .append(",\"expect\":{").append(c.expect.entries.joinToString(",") { q(it.key) + ":" + q(it.value) }).append("}}\n")
        }
        java.io.File(path).writeText(sb.toString())
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
}
