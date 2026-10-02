package com.hy.assistant.core

/**
 * Makes drafted WhatsApp replies read like a person texting, not a chatbot. Ported from the
 * "humanizer" guide (Wikipedia: Signs of AI writing), reduced to what fits a small on-device model:
 * a few prompt rules, plus a deterministic clean-up pass and voice matching (no extra LLM call).
 */
object Humanizer {

    /** Added to reply prompts. Short on purpose: small models follow a few clear rules best. */
    const val PROMPT_RULES =
        "Write like a real person texting a friend, not an assistant: plain words, contractions (I'm, don't), " +
            "no greetings like \"Certainly\" or \"Great question\", no \"I hope this helps\" or \"Let me know if\", " +
            "no em dashes, no fancy words (delve, crucial, ensure, additionally), no fake enthusiasm."

    /** How the user writes in this chat, learned from their own past messages. */
    data class Style(
        val samples: Int,
        val avgWords: Double,
        val usesEmoji: Boolean,
        val mostlyLowercase: Boolean,
        val endsWithPeriod: Boolean,
    ) {
        /** One line for the prompt so the model matches length and feel. */
        fun promptHint(): String {
            if (samples < 3) return ""
            val length = when {
                avgWords <= 6 -> "very short (a few words)"
                avgWords <= 14 -> "short (one sentence)"
                else -> "a sentence or two"
            }
            return " Match how Me writes in this chat: $length" +
                (if (mostlyLowercase) ", lowercase" else "") +
                (if (usesEmoji) ", emojis are fine" else ", no emojis") + "."
        }

        companion object {
            val NONE = Style(0, 0.0, usesEmoji = false, mostlyLowercase = false, endsWithPeriod = true)

            fun from(myMessages: List<String>): Style {
                val msgs = myMessages.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(30)
                if (msgs.isEmpty()) return NONE
                val letters = msgs.filter { m -> m.any { it.isLetter() } }
                val lower = letters.count { m -> m.first { it.isLetter() }.isLowerCase() }
                return Style(
                    samples = msgs.size,
                    avgWords = msgs.map { it.split(Regex("\\s+")).size }.average(),
                    usesEmoji = msgs.count { hasEmoji(it) } * 4 >= msgs.size, // in at least ~25% of messages
                    mostlyLowercase = letters.isNotEmpty() && lower * 3 >= letters.size * 2,
                    endsWithPeriod = msgs.count { it.endsWith('.') } * 2 >= msgs.size,
                )
            }
        }
    }

    private val openers = Regex(
        """^(?:(?:certainly|absolutely|of course|sure thing|great question|good question|i'?d be happy to help|i'?m happy to help|thank you for (?:your|the) message|thanks for reaching out)[!.,:]*\s*)+""",
        RegexOption.IGNORE_CASE,
    )
    private val closerSentences = Regex(
        """\s*(?:i hope (?:this|that) helps|hope (?:this|that) helps|let me know if (?:you|there)[^.!?]*|feel free to [^.!?]*|please don'?t hesitate to [^.!?]*|is there anything else[^.!?]*|i'?m here (?:to help|if you need)[^.!?]*)[.!?]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Word/phrase swaps: AI vocabulary and filler → what people actually type. */
    private val swaps: List<Pair<Regex, String>> = listOf(
        "in order to" to "to",
        "due to the fact that" to "because",
        "at this point in time" to "now",
        "at the moment" to "right now",
        "i apologi[sz]e for the inconvenience" to "sorry about that",
        "i apologi[sz]e" to "sorry",
        "please be advised that" to "",
        "it is important to note that" to "",
        "additionally," to "also,",
        "furthermore," to "also,",
        "moreover," to "also,",
        "delve into" to "look into",
        "utili[sz]e" to "use",
        "ensure" to "make sure",
        "assist you" to "help you",
        "regarding" to "about",
        "crucial" to "important",
        "commence" to "start",
        "endeavou?r" to "try",
        "purchase" to "buy",
        "approximately" to "about",
    ).map { (from, to) ->
        // A trailing \b only works after a letter ("additionally," ends with a comma).
        Regex("\\b" + from + (if (from.last().isLetter()) "\\b" else ""), RegexOption.IGNORE_CASE) to to
    }

    private val contractions: List<Pair<Regex, String>> = listOf(
        "I am" to "I'm", "I will" to "I'll", "I would" to "I'd", "do not" to "don't", "does not" to "doesn't",
        "did not" to "didn't", "cannot" to "can't", "can not" to "can't", "will not" to "won't", "is not" to "isn't",
        "are not" to "aren't", "it is" to "it's", "that is" to "that's", "you are" to "you're", "we are" to "we're",
        "they are" to "they're", "let us" to "let's", "have not" to "haven't", "was not" to "wasn't",
    ).map { (from, to) -> Regex("""\b$from\b(?=\s+[\w'])""", RegexOption.IGNORE_CASE) to to } // "what it is." stays

    /**
     * Cleans a drafted reply. [theyUseEmoji]: whether the other person uses emojis in this chat.
     * Formal tone keeps full forms and punctuation; casual/short get contractions and voice matching.
     */
    fun apply(text: String, tone: Tone, style: Style = Style.NONE, theyUseEmoji: Boolean = true): String {
        var s = text.trim()
        s = openers.replace(s, "")
        s = closerSentences.replace(s, "")
        // Dashes: "word — word" / "word—word" → comma; spaced en dash too.
        s = s.replace(Regex("""\s*—\s*"""), ", ").replace(Regex("""\s+–\s+"""), ", ")
        s = s.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
        for ((r, to) in swaps) s = r.replace(s) { m -> matchCase(m.value, to) }
        if (tone != Tone.FORMAL) for ((r, to) in contractions) s = r.replace(s) { m -> matchCase(m.value, to) }
        s = s.replace(Regex("""!{2,}"""), "!").replace(Regex("""\?{2,}"""), "?")
        if (!style.usesEmoji && !theyUseEmoji && style.samples >= 3) s = stripEmoji(s)
        s = s.replace(Regex("""\s+([,.!?])"""), "$1").replace(Regex(""",\s*,"""), ",")
            .replace(Regex("""^[,\s]+|[,\s]+$"""), "").replace(Regex("""\s{2,}"""), " ")
        if (tone != Tone.FORMAL && style.samples >= 3) {
            if (style.mostlyLowercase && s.isNotEmpty() && !s.startsWith("I ") && !s.startsWith("I'")) {
                s = s.replaceFirstChar { it.lowercase() }
            }
            // People texting often skip the final full stop on a single sentence.
            if (!style.endsWithPeriod && s.endsWith('.') && !s.endsWith("..") && s.count { it == '.' } == 1) s = s.dropLast(1)
        }
        return s.ifBlank { text.trim() }
    }

    fun hasEmoji(s: String): Boolean = s.codePoints().anyMatch { isEmoji(it) }

    private fun stripEmoji(s: String): String =
        buildString { s.codePoints().forEach { if (!isEmoji(it) && it != 0xFE0F && it != 0x200D) appendCodePoint(it) } }

    private fun isEmoji(cp: Int) = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F000..0x1F2FF

    /** Keeps the capitalisation of the original at sentence starts ("Ensure" → "Make sure"). */
    private fun matchCase(original: String, replacement: String): String =
        if (replacement.isEmpty()) "" else if (original.first().isUpperCase()) replacement.replaceFirstChar { it.uppercase() } else replacement
}
