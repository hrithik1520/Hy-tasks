package com.hy.assistant.core

/**
 * The "Butler" style: a calm British voice and a courteous butler personality for how Hy talks
 * to the user (never in messages sent to other people). Not modelled on any real person.
 */
object Butler {
    /** A text-to-speech voice as Android reports it. */
    data class VoiceInfo(val name: String, val locale: String, val offline: Boolean, val quality: Int = 0)

    /**
     * Google TTS's British male voices, best first (Android has no gender API; these names are
     * stable across Google TTS versions). Any other British voice is the fallback.
     */
    private val preferredBritishMale = listOf("en-gb-x-rjs", "en-gb-x-gbd", "en-gb-x-gbb")

    /** Best voice for the butler: British male, offline first, then any British, then any English. */
    fun pickVoice(voices: List<VoiceInfo>): VoiceInfo? {
        fun british(v: VoiceInfo) = v.locale.replace('_', '-').lowercase().let { it == "en-gb" || it.startsWith("en-gb-") }
        val byPreference = preferredBritishMale.firstNotNullOfOrNull { p ->
            voices.filter { it.name.lowercase().startsWith(p) }.sortedWith(compareByDescending<VoiceInfo> { it.offline }.thenByDescending { it.quality }).firstOrNull()
        }
        return byPreference
            ?: voices.filter(::british).sortedWith(compareByDescending<VoiceInfo> { it.offline }.thenByDescending { it.quality }).firstOrNull()
            ?: voices.filter { it.locale.lowercase().startsWith("en") }.sortedByDescending { it.offline }.firstOrNull()
    }

    /** Slightly slower and lower than default: calm and unhurried. */
    const val RATE = 0.92f
    const val PITCH = 0.88f

    enum class Address { NAME, SIR, MAAM }

    fun address(mode: Address, name: String): String = when (mode) {
        Address.SIR -> "sir"
        Address.MAAM -> "ma'am"
        Address.NAME -> name.trim().split(' ').firstOrNull()?.takeIf { it.isNotBlank() } ?: ""
    }

    /** ", sir" / ", Hrithik" / "" — for the end of a short phrase. */
    private fun tail(a: String) = if (a.isBlank()) "" else ", $a"

    // Short spoken lines in the voice conversation.
    fun sent(a: String) = "Very good${tail(a)}. It's been sent."
    fun cancelled(a: String) = "As you wish${tail(a)}. I've left it unsent."
    fun done(a: String) = "Done${tail(a)}."
    fun goodbye(a: String) = "Very good${tail(a)}. I'll be here if you need me."
    fun askSend(a: String, chat: String, text: String) = "Your reply to $chat reads: $text. Shall I send it${tail(a)}?"
    fun changed(text: String) = "Certainly. It now reads: $text. Shall I send it?"
    fun which(names: String) = "Which one, if I may ask: $names?"
    fun tooLong(a: String) = "My apologies${tail(a)}, that took rather too long."

    /**
     * Personality for Hy's own answers to the user. Put first in the system prompt: small models
     * follow a clear identity with example phrases far better than a description of a manner.
     */
    fun persona(a: String): String {
        val who = if (a.isBlank()) "" else ", $a"
        return "You are Hy, the user's personal butler: a calm, courteous English butler. Speak politely and a little " +
            "formally, with phrases like \"Certainly$who.\", \"Very good$who.\", \"If I may$who...\"" +
            (if (a.isBlank()) ". " else ". Address the user as \"$a\" now and then. ")
    }

    private val writingTask = Regex(
        """^(please |can you |could you )?(write|draft|compose|prepare|make)\b.*\b(letter|e-?mail|mail|application|message|note|post|caption|essay|speech|invitation|cover letter|resume|cv|reply)\b""",
    )

    /**
     * Writing something for the user to send or use (a leave letter, an email…) must be in their
     * voice: the butler manner is switched off for those requests (tested: a 1.5B model otherwise
     * writes "…for tomorrow, sir" into the letter).
     */
    fun appliesTo(request: String): Boolean = !writingTask.containsMatchIn(request.lowercase().trim())
}
