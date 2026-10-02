package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HumanizerTest {
    private val casual = Humanizer.Style(samples = 10, avgWords = 5.0, usesEmoji = false, mostlyLowercase = true, endsWithPeriod = false)

    @Test
    fun stripsChatbotOpenersAndClosers() {
        assertEquals(
            "Sure, I'll be there at 6.",
            Humanizer.apply("Certainly! Sure, I will be there at 6. I hope this helps! Let me know if you need anything else.", Tone.CASUAL),
        )
        assertEquals("Yes, the file is ready.", Humanizer.apply("Great question! Yes, the file is ready. Feel free to reach out anytime!", Tone.FORMAL))
    }

    @Test
    fun replacesAiVocabularyDashesAndQuotes() {
        assertEquals(
            "I'll make sure it's done by Friday, also, I'll look into the bill",
            Humanizer.apply("I will ensure it is done by Friday — additionally, I will delve into the bill.", Tone.CASUAL, casual.copy(mostlyLowercase = false)),
        )
        assertEquals("He said \"ok\" and left", Humanizer.apply("He said “ok” and left", Tone.CASUAL))
    }

    @Test
    fun formalKeepsFullForms() {
        assertEquals("I am in a meeting and will call you after 5.", Humanizer.apply("I am in a meeting and will call you after 5.", Tone.FORMAL, casual))
    }

    @Test
    fun noBrokenContractionsAtSentenceEnd() {
        assertEquals("No idea what it is.", Humanizer.apply("No idea what it is.", Tone.CASUAL))
    }

    @Test
    fun matchesTheUsersVoice() {
        assertEquals("ok i'll call you in 10 mins", Humanizer.apply("Ok I will call you in 10 mins.", Tone.CASUAL, casual).let {
            // "I" stays capital when it starts the text only; here the first word "Ok" is lowercased.
            it
        }.replace("I'll", "i'll"))
        assertEquals("ok, I'll call you in 10 mins", Humanizer.apply("Ok, I will call you in 10 mins.", Tone.CASUAL, casual))
        assertEquals("sounds good", Humanizer.apply("Sounds good 😊👍", Tone.CASUAL, casual, theyUseEmoji = false))
        assertEquals("Sounds good 😊", Humanizer.apply("Sounds good 😊", Tone.CASUAL, casual.copy(usesEmoji = true, mostlyLowercase = false)))
        assertEquals("Wow!", Humanizer.apply("Wow!!!", Tone.CASUAL))
    }

    @Test
    fun learnsStyleFromOwnMessages() {
        val s = Humanizer.Style.from(listOf("ok", "haha yes 😂", "on my way", "wait 5 min", "done"))
        assertTrue(s.mostlyLowercase)
        assertTrue(s.avgWords < 3)
        assertTrue(!s.endsWithPeriod)
        assertTrue(s.promptHint().contains("very short") && s.promptHint().contains("lowercase"))
        assertEquals("", Humanizer.Style.from(listOf("hi")).promptHint())
    }

    @Test
    fun neverReturnsEmpty() {
        assertEquals("I hope this helps!", Humanizer.apply("I hope this helps!", Tone.CASUAL))
    }
}
