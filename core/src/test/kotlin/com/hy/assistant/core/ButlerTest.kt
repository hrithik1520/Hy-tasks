package com.hy.assistant.core

import com.hy.assistant.core.Butler.VoiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ButlerTest {
    @Test
    fun picksBritishMaleOfflineVoice() {
        val voices = listOf(
            VoiceInfo("en-us-x-iol-local", "en-US", offline = true),
            VoiceInfo("en-gb-x-gba-local", "en-GB", offline = true),
            VoiceInfo("en-gb-x-rjs-network", "en-GB", offline = false),
            VoiceInfo("en-gb-x-rjs-local", "en-GB", offline = true),
        )
        assertEquals("en-gb-x-rjs-local", Butler.pickVoice(voices)?.name)
    }

    @Test
    fun fallsBackToAnyBritishThenAnyEnglish() {
        assertEquals("en-gb-x-gba-local", Butler.pickVoice(listOf(VoiceInfo("en-us-x-a", "en-US", true), VoiceInfo("en-gb-x-gba-local", "en_GB", true)))?.name)
        assertEquals("en-in-x-a", Butler.pickVoice(listOf(VoiceInfo("hi-in-x-a", "hi-IN", true), VoiceInfo("en-in-x-a", "en-IN", true)))?.name)
        assertNull(Butler.pickVoice(listOf(VoiceInfo("hi-in-x-a", "hi-IN", true))))
    }

    @Test
    fun addressAndLines() {
        assertEquals("sir", Butler.address(Butler.Address.SIR, "Hrithik"))
        assertEquals("Hrithik", Butler.address(Butler.Address.NAME, "Hrithik Menezes"))
        assertEquals("", Butler.address(Butler.Address.NAME, ""))
        assertEquals("Very good, sir. It's been sent.", Butler.sent("sir"))
        assertEquals("Very good. It's been sent.", Butler.sent(""))
        assertTrue(Butler.askSend("ma'am", "Rahul", "On my way").endsWith("Shall I send it, ma'am?"))
        assertTrue(Butler.persona("sir").contains("butler") && Butler.persona("sir").contains("\"sir\""))
        assertTrue(!Butler.appliesTo("write a short leave application to my manager"))
        assertTrue(!Butler.appliesTo("Can you draft an email to the landlord about the leak"))
        assertTrue(Butler.appliesTo("what can you do for me?"))
        assertTrue(Butler.appliesTo("what's the capital of France"))
    }

    @Test
    fun personaOnlyWhenSet() {
        val plain = Agent.answerPrompt("hi", "", "Hrithik")
        val butler = Agent.answerPrompt("hi", "", "Hrithik", persona = Butler.persona("sir"))
        assertTrue(!plain.system.contains("butler"))
        assertTrue(butler.system.startsWith("You are Hy, the user's personal butler"))
        assertTrue(plain.system.contains("sign it with their name") && plain.system.contains("cannot"))
        assertTrue(Agent.searchAnswerPrompt("q", "q", "r", persona = Butler.persona("")).system.contains("butler"))
    }
}
