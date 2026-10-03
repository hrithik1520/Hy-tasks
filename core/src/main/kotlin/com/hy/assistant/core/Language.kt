package com.hy.assistant.core

/**
 * Rough "is this English?" check for chat messages, so Hy doesn't auto-answer messages its
 * English-only model can't understand (e.g. romanized Kannada/Tulu/Hindi like "Savu ninna").
 */
object Language {
    private val common = """
        a about after again all am an and any are as at back be been before bro but by call can come coming cool could
        da did do does doing done dont don't eat for free from fine get go going good got great had has have he hello hey
        hi him his home how i i'm if in is it it's just k know lets let's like lol haha hmm hm later me meet more morning my
        need night no not now of off ok okay on one or our out please plz reach really right see send she should so soon
        sorry still sure tell thank thanks that the them then there they think this time to today tomorrow too u ur up us
        very wait want was we well went were what whats what's when where which who why will with work would ya yeah yes yet
        you your you're yup birthday happy congrats dinner lunch food bye gn gm tc call message msg pls thx ty nice wow omg
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** True for English (or emoji/number-only) text; false when most words aren't English. */
    fun looksEnglish(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true // emoji, numbers, punctuation
        if (words.any { w -> w.any { it.isLetter() && it.code > 0x24F } }) return false // non-Latin script
        val known = words.count { it in common }
        return known * 3 >= words.size // at least a third are everyday English words
    }
}
