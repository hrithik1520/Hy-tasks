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
        fun promptHint(emoji: EmojiMode = EmojiMode.MATCH): String {
            if (emoji == EmojiMode.OFF) return " No emojis."
            if (samples < 3) return if (emoji == EmojiMode.FITS) " One fitting emoji is fine." else ""
            val length = when {
                avgWords <= 6 -> "very short (a few words)"
                avgWords <= 14 -> "short (one sentence)"
                else -> "a sentence or two"
            }
            return " Match how Me writes in this chat: $length" +
                (if (mostlyLowercase) ", lowercase" else "") +
                (if (usesEmoji || emoji == EmojiMode.FITS) ", one fitting emoji is fine" else ", no emojis") + "."
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
    /** How drafted replies use emojis. */
    enum class EmojiMode(val label: String) {
        /** Add one fitting emoji when the reply's mood is clear (never in Formal tone). */
        FITS("When it fits"),
        /** Only if the user or the other person uses emojis in this chat. */
        MATCH("Match my style"),
        OFF("Off"),
    }

    // First match wins: the reply's mood → one emoji people commonly use for it on WhatsApp.
    private val emojiRules: List<Pair<Regex, String>> = listOf(
        Regex("""\b(happy (birthday|bday|anniversary)|congrat\w*|well done|proud of you|so happy for)\b""") to "🎉",
        Regex("""\b(haha+|hehe+|lol|lmao|rofl|that's hilarious|so funny)\b""") to "😂",
        Regex("""\b(good ?night|gn|sleep well|sweet dreams)\b""") to "🌙",
        Regex("""\b(good ?morning|gm)\b""") to "☀️",
        Regex("""\b(love you|miss you|love it|lots of love)\b""") to "❤️",
        Regex("""\b(thank(s| you)?|thx|ty|grateful)\b""") to "😊",
        Regex("""\b(sorry|my bad|apologies)\b""") to "😅",
        Regex("""\b(busy|tied up|in the middle of|will (text|call|reply|get back)|get back to you)\b""") to "🙏",
        Regex("""\b(see you|see ya|bye|talk (later|soon)|ttyl|catch you)\b""") to "👋",
        Regex("""\b(awesome|amazing|great news|that's great|so cool|nice one|wow)\b""") to "😄",
        Regex("""\b(ok|okay|sure|done|sounds good|no problem|no worries|alright|got it|will do|take your time)\b""") to "👍",
    )

    /** One emoji that fits the reply's mood, or null when nothing clearly fits. */
    fun fittingEmoji(reply: String): String? {
        val t = reply.lowercase()
        return emojiRules.firstOrNull { (r, _) -> r.containsMatchIn(t) }?.second
    }

    fun apply(
        text: String,
        tone: Tone,
        style: Style = Style.NONE,
        theyUseEmoji: Boolean = true,
        emoji: EmojiMode = EmojiMode.MATCH,
    ): String {
        var s = text.trim()
        s = openers.replace(s, "")
        s = closerSentences.replace(s, "")
        // Dashes: "word — word" / "word—word" → comma; spaced en dash too.
        s = s.replace(Regex("""\s*—\s*"""), ", ").replace(Regex("""\s+–\s+"""), ", ")
        s = s.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
        for ((r, to) in swaps) s = r.replace(s) { m -> matchCase(m.value, to) }
        if (tone != Tone.FORMAL) for ((r, to) in contractions) s = r.replace(s) { m -> matchCase(m.value, to) }
        s = s.replace(Regex("""!{2,}"""), "!").replace(Regex("""\?{2,}"""), "?")
        when (emoji) {
            EmojiMode.OFF -> s = stripEmoji(s)
            EmojiMode.MATCH -> if (!style.usesEmoji && !theyUseEmoji && style.samples >= 3) s = stripEmoji(s)
            EmojiMode.FITS -> if (tone == Tone.FORMAL) s = stripEmoji(s)
        }
        s = s.replace(Regex("""\s+([,.!?])"""), "$1").replace(Regex(""",\s*,"""), ",")
            .replace(Regex("""^[,\s]+|[,\s]+$"""), "").replace(Regex("""\s{2,}"""), " ")
        if (tone != Tone.FORMAL && style.samples >= 3) {
            if (style.mostlyLowercase && s.isNotEmpty() && !s.startsWith("I ") && !s.startsWith("I'")) {
                s = s.replaceFirstChar { it.lowercase() }
            }
            // People texting often skip the final full stop on a single sentence.
            if (!style.endsWithPeriod && s.endsWith('.') && !s.endsWith("..") && s.count { it == '.' } == 1) s = s.dropLast(1)
        }
        s = s.ifBlank { text.trim() }
        // Small models rarely add emojis themselves: add one that fits, at the end.
        val wantsEmoji = tone != Tone.FORMAL && !hasEmoji(s) && when (emoji) {
            EmojiMode.FITS -> true
            EmojiMode.MATCH -> style.usesEmoji
            EmojiMode.OFF -> false
        }
        if (wantsEmoji) fittingEmoji(s)?.let { e -> s = s.trimEnd() + " " + e }
        return s
    }

    fun hasEmoji(s: String): Boolean = s.codePoints().anyMatch { isEmoji(it) }

    private fun stripEmoji(s: String): String =
        buildString { s.codePoints().forEach { if (!isEmoji(it) && it != 0xFE0F && it != 0x200D) appendCodePoint(it) } }

    private fun isEmoji(cp: Int) = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F000..0x1F2FF

    /** Keeps the capitalisation of the original at sentence starts ("Ensure" → "Make sure"). */
    private fun matchCase(original: String, replacement: String): String =
        if (replacement.isEmpty()) "" else if (original.first().isUpperCase()) replacement.replaceFirstChar { it.uppercase() } else replacement
}
