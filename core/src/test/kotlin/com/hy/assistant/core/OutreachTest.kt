package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutreachTest {
    @Test
    fun introAsksForMessagesAndNamesAlfrid() {
        val intro = Outreach.intro("Hrithik")
        assertTrue(intro.contains("messages for Hrithik"), intro)
        assertTrue(intro.contains("Alfrid"), intro)
        assertTrue(Outreach.intro("").contains("Alfrid"))
    }

    @Test
    fun ackPromisesToPassItOn() {
        assertTrue(Outreach.ack("Hrithik").contains("Hrithik gets it"))
        assertTrue(Outreach.ack("").contains("passed on"))
    }

    @Test
    fun realMessagesAreRelayed() {
        for (s in listOf(
            "tell him to call me",
            "can he send the invoice today?",
            "meeting moved to 5",
            "ask him about the delivery",
            "I need the files for the client presentation tomorrow morning",
            "need help with the website",
        )) {
            assertTrue(Outreach.looksLikeMessage(s), s)
        }
    }

    @Test
    fun smallTalkIsNotRelayed() {
        for (s in listOf("", "   ", "no", "nope", "nothing", "no thanks", "ok", "ok thanks", "hey", "hello bro",
            "yes", "sure", "lol", "haha", "hmm", "who is this", "what is this", "good morning", "nvm")) {
            assertFalse(Outreach.looksLikeMessage(s), s)
        }
    }
}
