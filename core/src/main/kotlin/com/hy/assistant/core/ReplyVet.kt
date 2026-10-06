package com.hy.assistant.core

/**
 * Last check on text the model wrote for an automatic reply. The prompt asks the model not to
 * promise anything, but a small model can't be relied on, so the text is vetted here too.
 */
object ReplyVet {
    private val links = Regex("""https?:|www\.|\.com\b|\.in\b|@""")
    private val commitment = Regex(
        """\b(yes|yeah|yep|sure|definitely|certainly|of course|absolutely|promise|guarantee|confirmed?|i will|i'll|i'm coming|i am coming|on my way|sounds good|deal|agreed|will do|count me in)\b""",
    )
    private val holding = Regex("""\b(get back|reply|respond|text you back|call you back|talk)\b.{0,20}\b(soon|later|shortly|as soon as|in a (bit|while|moment))\b|\bbusy\b|\bunavailable\b|\btied up\b|\bcan'?t talk\b|\bin the middle of\b""")
    private val leaks = Regex("""\bas an ai\b|\blanguage model\b|\bi'?m an ai\b""")

    /**
     * Returns the reply (whitespace tidied) if it is safe to send unattended, otherwise null.
     * Rejects numbers, links, money/credential talk, promises and commitments, and anything long.
     */
    fun vet(reply: String): String? {
        val t = reply.trim().replace(Regex("""\s+"""), " ")
        if (t.isEmpty() || t.length > 200) return null
        if (t.any { it.isDigit() }) return null
        if (links.containsMatchIn(t.lowercase())) return null
        if (SafetyFilter.blockReason(t) != null) return null
        val low = SafetyFilter.normalize(t)
        if (leaks.containsMatchIn(low)) return null
        if (commitment.containsMatchIn(low) && !holding.containsMatchIn(low)) return null
        return t
    }
}

/** Masks one-time codes before notifications are stored, so they never sit on disk in clear text. */
object Redactor {
    private val codes = Regex("""\b\d{4,8}\b|\b\d{3}[ -]\d{3}\b""")
    private const val MASK = "••••"

    fun maskCodes(text: String): String = codes.replace(text, MASK)
}
