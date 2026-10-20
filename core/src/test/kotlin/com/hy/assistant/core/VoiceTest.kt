package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTest {
    @Test
    fun wakeWord() {
        assertTrue(WakeWord.detected("""{"text" : "hey alfred"}"""))
        assertTrue(WakeWord.detected("""{"text" : "[unk] whats up alfred"}"""))
        assertTrue(WakeWord.detected("""{"text" : "alfred [unk] [unk]"}"""))
        assertTrue(WakeWord.detected("""{"text" : "alfred"}"""))
        assertFalse(WakeWord.detected("""{"partial" : "hey alfred"}""")) // only completed phrases
        assertFalse(WakeWord.detected("""{"text" : "[unk] up alfred"}""")) // "Alfredo is a pasta"
        assertFalse(WakeWord.detected("""{"text" : "alfred hey alfred"}"""))
        assertFalse(WakeWord.detected("""{"text" : "[unk] [unk]"}"""))
        assertFalse(WakeWord.detected("""{"text" : ""}"""))
        assertFalse(WakeWord.detected("""{"text" : "alfredo"}"""))
        assertEquals("""["hey alfred","what's up alfred","whats up alfred","alfred","[unk]"]""", WakeWord.grammarJson())
    }

    @Test
    fun spokenConfirmations() {
        assertEquals(VoiceReply.Confirm, VoiceCommands.interpret("Yes send it", awaitingConfirm = true))
        assertEquals(VoiceReply.Confirm, VoiceCommands.interpret("go ahead.", awaitingConfirm = true))
        assertEquals(VoiceReply.Cancel, VoiceCommands.interpret("don't send", awaitingConfirm = true))
        assertEquals(VoiceReply.Edit("I'll be there by 8"), VoiceCommands.interpret("change it to I'll be there by 8", awaitingConfirm = true))
        assertEquals(VoiceReply.EndConversation, VoiceCommands.interpret("thanks Alfred", awaitingConfirm = false))
        assertEquals(VoiceReply.EndConversation, VoiceCommands.interpret("stop", awaitingConfirm = true))
        // "yes" with nothing to confirm is just a request.
        assertEquals(VoiceReply.Request("yes"), VoiceCommands.interpret("yes", awaitingConfirm = false))
        assertEquals(VoiceReply.Request("what did I miss"), VoiceCommands.interpret("what did I miss", awaitingConfirm = false))
        assertEquals(VoiceReply.Confirm, VoiceCommands.interpret("Alfred, send it", awaitingConfirm = true))
    }

    @Test
    fun speechText() {
        val md = "## Weather\n- **Sunny** today 😊\n- High of 31°C\nSee https://example.com/x for more"
        assertEquals("Weather. Sunny today. High of 31°C. See a link for more.", SpeakText.forSpeech(md))
        val long = "This is a sentence. ".repeat(60)
        val s = SpeakText.forSpeech(long)
        assertTrue(s.length <= SpeakText.MAX_SPOKEN + 30 && s.endsWith("The rest is on screen."))
        assertEquals(listOf("Hi there.", "How are you?", "Fine"), SpeakText.sentences("Hi there. How are you? Fine"))
    }
}
