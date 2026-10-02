package com.hy.assistant.core

/** One exchange in the conversation with Hy. */
data class Turn(val user: String, val assistant: String, val timestamp: Long = 0L)

/** A fact the user asked Hy to remember. */
data class MemoryFact(val id: Long, val text: String, val createdAt: Long)

object Conversation {
    /**
     * The most recent turns that fit in [maxChars], oldest first, as plain text for a prompt.
     * Long answers are shortened: follow-ups mostly need what was asked and the gist of the reply.
     */
    fun historyBlock(turns: List<Turn>, maxChars: Int, maxAnswerChars: Int = 400): String {
        val out = ArrayDeque<String>()
        var used = 0
        for (t in turns.asReversed()) {
            val answer = t.assistant.trim().let { if (it.length > maxAnswerChars) it.take(maxAnswerChars) + "…" else it }
            val entry = "User: ${t.user.trim()}\nHy: $answer"
            if (used + entry.length + 1 > maxChars) break
            out.addFirst(entry)
            used += entry.length + 1
        }
        return out.joinToString("\n")
    }
}

object Memory {
    const val MAX_FACTS = 50

    sealed interface AddResult {
        data class Added(val fact: String) : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    /** Cleans "that my boss is Priya." into "My boss is Priya" and refuses secrets. */
    fun prepare(raw: String): AddResult {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        t = t.removePrefix("that ").removePrefix("That ").trim().trimEnd('.', '!')
        if (t.length < 3) return AddResult.Rejected("That's too short to remember.")
        if (t.length > 300) return AddResult.Rejected("That's too long — keep it to one sentence.")
        when (SafetyFilter.blockReason(t)) {
            SafetyFilter.Reason.OTP, SafetyFilter.Reason.CREDENTIALS ->
                return AddResult.Rejected("I won't store passwords, PINs or codes. Keep those in a password manager.")
            else -> Unit
        }
        return AddResult.Added(t.replaceFirstChar { it.uppercase() })
    }

    /** Facts matching "forget …": every meaningful word of the query appears in the fact. */
    fun matching(facts: List<MemoryFact>, query: String): List<MemoryFact> {
        val words = words(query).filter { it !in STOP }
        if (words.isEmpty()) return emptyList()
        return facts.filter { f ->
            val fw = words(f.text).toSet()
            words.all { w -> fw.any { it == w || (w.length >= 4 && it.startsWith(w)) } }
        }
    }

    /** Facts for a prompt, newest first, within [maxChars]. */
    fun block(facts: List<MemoryFact>, maxChars: Int = 800): String {
        val sb = StringBuilder()
        for (f in facts.sortedByDescending { it.createdAt }) {
            val line = "- ${f.text}\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private val STOP = setOf("that", "the", "a", "an", "my", "about", "is", "are", "was", "i", "me", "to", "of", "it", "what")
}
