package com.hy.assistant.core

/** A user command, parsed deterministically (no LLM). */
sealed interface Command {
    /** "what did I miss", "catch me up" */
    data object Digest : Command

    /** "summarize Rahul", "what did Rahul say" */
    data class Summarize(val contact: String) : Command

    /** "suggest a reply to Rahul" — the LLM writes the whole reply. */
    data class DraftReply(val contact: String) : Command

    /** "reply to Rahul saying I'm late" — the user gives the gist; the LLM may polish it. */
    data class Reply(val contact: String, val gist: String) : Command

    data class Unknown(val text: String) : Command
}

object CommandParser {
    private val digest = Regex(
        """^(what did i miss|what have i missed|catch me up|any (new )?messages|summari[sz]e (everything|all( chats)?|my messages)|digest)\??$""",
    )
    private val reply = Regex(
        """^(?:reply|respond|answer|say|text|message|tell|send)(?: (?:to|back to))? (.+?)(?:,|:| saying| that| with| to say)\s+(.+)$""",
    )
    private val draft = Regex(
        """^(?:suggest|draft|write|give me)(?: me)?(?: an?)?(?: quick)? (?:reply|response|answer)(?: to| for)? (.+)$""",
    )
    private val summarize = Regex(
        """^(?:summari[sz]e|sum up|catch me up on|what did|what's new (?:from|with)|whats new (?:from|with))(?: my)?(?: chat with| messages from| chat)? (.+?)(?: say| said| send| sent)?\??$""",
    )

    private val replyVerb = Regex("""^(?:reply|respond|text|message|tell|send)(?: (?:to|back to))? (.+)$""")

    /**
     * @param knownNames chat names currently known to the app; lets "text Rahul I'm late"
     * (no separator word) be split into contact + message.
     */
    fun parse(input: String, knownNames: Collection<String> = emptyList()): Command {
        val text = input.trim().replace(Regex("\\s+"), " ")
        val lower = text.lowercase().trimEnd('.', '!')
        if (lower.isEmpty()) return Command.Unknown(text)
        // Original-casing view of `lower`, for extracting the message gist.
        val original = text.trimEnd('.', '!')

        if (digest.matches(lower)) return Command.Digest

        draft.matchEntire(lower)?.let { m ->
            return Command.DraftReply(cleanContact(m.groupValues[1]))
        }
        reply.matchEntire(lower)?.let { m ->
            val gistStart = lower.length - m.groupValues[2].length
            return Command.Reply(cleanContact(m.groupValues[1]), original.substring(gistStart).trim())
        }
        replyVerb.matchEntire(lower)?.let { m ->
            val rest = m.groupValues[1]
            val name = knownNames
                .map { it to it.lowercase() }
                .filter { (_, n) -> rest.startsWith("$n ") }
                .maxByOrNull { (_, n) -> n.length }
            if (name != null) {
                val gistStart = lower.length - rest.length + name.second.length
                val gist = original.substring(gistStart).trim().removePrefix(",").removePrefix(":").trim()
                if (gist.isNotEmpty()) return Command.Reply(name.first, gist)
            }
        }
        summarize.matchEntire(lower)?.let { m ->
            return Command.Summarize(cleanContact(m.groupValues[1]))
        }
        return Command.Unknown(text)
    }

    private fun cleanContact(raw: String): String =
        raw.trim().removePrefix("the ").removeSuffix("'s").removeSuffix(" chat").trim()
}
