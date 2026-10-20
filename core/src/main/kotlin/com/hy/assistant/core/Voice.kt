package com.hy.assistant.core

/** Voice: wake word, spoken confirmations, and turning answers into something pleasant to hear. */
object WakeWord {
    /**
     * Phrases the offline recognizer listens for ("[unk]" absorbs everything else). Restricting
     * the vocabulary like this makes a small model a reliable keyword spotter.
     */
    val PHRASES = listOf("hey alfred", "what's up alfred", "whats up alfred", "alfred", "[unk]")

    fun grammarJson(): String = PHRASES.joinToString(",", "[", "]") { "\"$it\"" }

    private val textField = Regex(""""text"\s*:\s*"([^"]*)"""")
    private val exact = PHRASES.filter { it != "[unk]" }.toSet()

    /**
     * True when a completed Vosk result is exactly a wake phrase, allowing unrecognised sound
     * ("[unk]") before or after it ("Hey Alfred, what's the weather"). Partial results are ignored
     * and fragments like "up alfred" (heard in "Alfredo is a pasta") are rejected: tested with
     * synthesized speech against vosk-model-small-en-us-0.15.
     */
    fun detected(resultJson: String): Boolean {
        val text = textField.find(resultJson)?.groupValues?.get(1)?.lowercase() ?: return false
        val core = text.split(' ').filter { it.isNotBlank() }.dropWhile { it == "[unk]" }.dropLastWhile { it == "[unk]" }.joinToString(" ")
        return core in exact
    }
}

/** What a spoken reply means while Hy is waiting on a draft or in a conversation. */
sealed interface VoiceReply {
    data object Confirm : VoiceReply
    data object Cancel : VoiceReply
    data class Edit(val newText: String) : VoiceReply
    data object EndConversation : VoiceReply
    /** Anything else: treat as a new request. */
    data class Request(val text: String) : VoiceReply
}

object VoiceCommands {
    private val confirm = Regex("""^(yes|yeah|yep|yup|ok|okay|sure|send|send it|send that|go ahead|do it|confirm|yes send( it)?|ok send( it)?)( please| now)?$""")
    private val cancel = Regex("""^(no|nope|cancel|cancel it|don'?t send( it)?|do not send( it)?|never ?mind|leave it|scrap (it|that))( please)?$""")
    private val edit = Regex("""^(?:change it to|make it|say|instead say|change that to|replace it with|no,? say)\s+(.+)$""")
    private val end = Regex("""^(stop|bye|goodbye|that'?s all|that'?s it|thanks|thank you|thanks alfred|thank you alfred|ok thanks|nothing|go to sleep|exit|done)( alfred)?$""")

    /** [awaitingConfirm]: a drafted message is waiting for "send"/"cancel"/"change it to…". */
    fun interpret(transcript: String, awaitingConfirm: Boolean): VoiceReply {
        val t = transcript.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("^(hey |ok )?alfred,? "), "")
        if (awaitingConfirm) {
            if (confirm.matches(t)) return VoiceReply.Confirm
            if (cancel.matches(t)) return VoiceReply.Cancel
            edit.matchEntire(t)?.let { m ->
                val start = transcript.length - m.groupValues[1].length
                return VoiceReply.Edit(transcript.trim().trimEnd('.', '!').substring(start.coerceIn(0, transcript.length)).trim().ifEmpty { m.groupValues[1] })
            }
        }
        if (end.matches(t)) return VoiceReply.EndConversation
        return VoiceReply.Request(transcript.trim())
    }
}

/** Prepares assistant text for text-to-speech. */
object SpeakText {
    const val MAX_SPOKEN = 600

    /** Markdown, links, code and emoji read badly aloud: keep the words, drop the markup. */
    fun forSpeech(text: String): String {
        var s = text
        s = Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL).replace(s, " (code on screen) ")
        s = Regex("""\[([^\]]+)]\([^)]+\)""").replace(s, "$1")
        s = Regex("""https?://\S+""").replace(s, "a link")
        s = Regex("""^\s*\|.*\|\s*$""", RegexOption.MULTILINE).replace(s, "") // tables stay on screen
        s = Regex("""^\s*#{1,6}\s*""", RegexOption.MULTILINE).replace(s, "")
        s = Regex("""^\s*([-*•]|\d+[.)])\s+""", RegexOption.MULTILINE).replace(s, "")
        s = s.replace(Regex("""[*_`~>]"""), "")
        s = buildString { s.codePoints().forEach { cp -> if (!(cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D)) appendCodePoint(cp) } }
        s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { if (it.last() in ".!?:;,") it else "$it." }
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.length > MAX_SPOKEN) {
            val cut = s.take(MAX_SPOKEN)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?')).takeIf { it > MAX_SPOKEN / 2 } ?: cut.lastIndexOf(' ')
            s = cut.take(end + 1).trim() + " The rest is on screen."
        }
        return s
    }

    /** Sentences, so speaking can start before the whole answer is written. */
    fun sentences(text: String): List<String> =
        Regex("""[^.!?]+[.!?]+|[^.!?]+$""").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
}
