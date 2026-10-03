package com.hy.assistant.core

/**
 * The two-step conversation Alfrid runs on its own in Auto mode, in every chat:
 *
 *  1. the first time someone messages, ask whether they have anything for the user;
 *  2. once they answer with an actual message or task, confirm that it will be passed on.
 *
 * Both texts are fixed, so this works in any chat without a model loaded and can never invent
 * facts or make promises. Anything that isn't a real message (a greeting, "no", an emoji) gets
 * no second reply.
 */
object Outreach {
    fun intro(userName: String): String {
        val who = userName.trim()
        return if (who.isEmpty()) {
            "Hi! Do you have any messages to pass on? — I'm Alfrid, an AI assistant."
        } else {
            "Hi! Do you have any messages for $who? — I'm Alfrid, $who's AI assistant."
        }
    }

    fun ack(userName: String): String {
        val who = userName.trim()
        return if (who.isEmpty()) "Got it — I'll make sure it gets passed on." else "Got it — I'll make sure $who gets it."
    }

    /** Long enough to be a real message whatever the words are. */
    private const val SURELY_A_MESSAGE = 40

    /**
     * Words that carry no message on their own: greetings, acknowledgements, yes/no, politeness
     * and the usual glue. A text made of nothing but these is small talk, not something to relay.
     */
    private val filler = setOf(
        "hi", "hii", "hiii", "hello", "helo", "hey", "heyy", "yo", "hola", "namaste", "salam",
        "good", "morning", "afternoon", "evening", "night", "day",
        "ok", "oki", "okay", "okey", "k", "kk", "hmm", "hm", "mm", "oh", "ohh", "ah", "ahh", "uh",
        "cool", "nice", "great", "fine", "perfect", "done", "alright",
        "yes", "yeah", "yep", "yup", "ya", "yaa", "sure", "haan", "han", "ha", "hai", "acha", "accha", "theek", "thik",
        "no", "nope", "nah", "not", "none", "nothing", "nvm", "never", "mind",
        "thanks", "thank", "thanku", "thankyou", "thx", "ty", "welcome", "please", "pls", "plz", "sorry",
        "lol", "lmao", "lmfao", "haha", "hahaha", "hehe", "hihi", "xd",
        "bro", "bruh", "dude", "bhai", "sir", "maam", "madam", "boss", "buddy", "mate",
        "i", "im", "me", "my", "mine", "you", "u", "ur", "your", "yours", "he", "she", "him", "her", "his", "they", "them",
        "it", "its", "this", "that", "these", "those", "there", "here", "the", "a", "an",
        "is", "am", "are", "was", "were", "be", "been", "do", "does", "did", "doing",
        "who", "whos", "whose", "what", "whats", "why", "how",
        "and", "or", "but", "so", "to", "of", "for", "with", "from", "at", "in", "on",
        "just", "really", "very", "too", "also", "right", "much", "any", "all",
    )

    private val words = Regex("""[a-z]+""")
    private val digits = Regex("""\d""")

    /** Is this worth relaying — an actual message or task, rather than small talk? */
    fun looksLikeMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.length >= SURELY_A_MESSAGE) return true
        val lower = t.lowercase()
        if (digits.containsMatchIn(lower)) return true // times, dates, amounts, order numbers…
        return words.findAll(lower).any { it.value !in filler }
    }
}
