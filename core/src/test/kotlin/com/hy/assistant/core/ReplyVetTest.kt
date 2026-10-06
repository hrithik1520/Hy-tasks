package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReplyVetTest {
    @Test
    fun acceptsHarmlessReplies() {
        assertNotNull(ReplyVet.vet("Good morning! Hope you have a great day."))
        assertNotNull(ReplyVet.vet("Haha that's funny 😂"))
        assertNotNull(ReplyVet.vet("Hey, I'm busy right now, I'll get back to you soon."))
        assertEquals("Thanks a lot!", ReplyVet.vet("  Thanks   a\nlot!  "))
    }

    @Test
    fun rejectsRiskyReplies() {
        for (s in listOf(
            "Sure, I'll be there at 6", "Yes, I will come", "I promise I'll pay you back", "Sounds good!", "Of course",
            "Check https://example.com", "Mail me at a@b.co", "Send ₹500 to me", "Call 9876543210",
            "As an AI language model I cannot", "My password is hunter2", "x".repeat(250), "",
        )) assertNull(ReplyVet.vet(s), s)
    }

    @Test
    fun theModelsChosenReplyIsVettedAndAFailureMeansHold() {
        assertEquals("Thanks!", AutoReply.replyText("""{"kind":"reply","text":"Thanks!"}"""))
        assertNull(AutoReply.replyText("""{"kind":"reply","text":"Sure, I'll be there at 6"}"""))
        assertNull(AutoReply.replyText("""{"kind":"reply","text":"Send me ₹500"}"""))
        assertNull(AutoReply.replyText("""{"kind":"hold"}"""))
    }

    @Test
    fun theirFixedRepliesAndHoldingMessagesAreThemselvesSafe() {
        for (i in 0..3) assertNotNull(ReplyVet.vet(AutoReply.holdingText(i)), AutoReply.holdingText(i))
        for (msg in listOf("happy birthday", "running late", "thanks", "good morning", "good night")) {
            assertNotNull(AutoReply.quickReply(msg)?.let { ReplyVet.vet(it) }, msg)
        }
    }

    @Test
    fun redactorMasksCodesButKeepsContext() {
        assertEquals("•••• is your OTP for HDFC login", Redactor.maskCodes("482913 is your OTP for HDFC login"))
        assertEquals("Code: •••• (valid 10 min)", Redactor.maskCodes("Code: 1234 (valid 10 min)"))
        assertEquals("G-•••• is your Google verification code", Redactor.maskCodes("G-123456 is your Google verification code"))
        assertEquals("Your code is ••••", Redactor.maskCodes("Your code is 123 456"))
        assertEquals("Meeting in 10 minutes", Redactor.maskCodes("Meeting in 10 minutes"))
    }
}
