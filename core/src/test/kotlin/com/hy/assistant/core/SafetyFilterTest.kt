package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyFilterTest {
    @Test
    fun blocksSensitiveMessages() {
        assertEquals(SafetyFilter.Reason.OTP, SafetyFilter.blockReason("Your OTP is 482913"))
        assertEquals(SafetyFilter.Reason.OTP, SafetyFilter.blockReason("can you send me the code 4821 you got"))
        assertEquals(SafetyFilter.Reason.MONEY, SafetyFilter.blockReason("bro send me ₹500 on gpay"))
        assertEquals(SafetyFilter.Reason.MONEY, SafetyFilter.blockReason("Can you transfer the rent today?"))
        assertEquals(SafetyFilter.Reason.MONEY, SafetyFilter.blockReason("pay me back tomorrow"))
        assertEquals(SafetyFilter.Reason.CREDENTIALS, SafetyFilter.blockReason("what's the wifi password"))
        assertEquals(SafetyFilter.Reason.EMERGENCY, SafetyFilter.blockReason("URGENT call me"))
        assertEquals(SafetyFilter.Reason.EMERGENCY, SafetyFilter.blockReason("I'm at the hospital"))
    }

    @Test
    fun allowsNormalChat() {
        for (s in listOf("hey what's up", "are we meeting at 5?", "lol 😂", "did you watch the match", "happy birthday!!", "call me when free")) {
            assertNull(SafetyFilter.blockReason(s), s)
        }
    }

    @Test
    fun classifiesNotifications() {
        val c = NotificationClassifier
        assertEquals(NotificationClassifier.Category.OTP, c.classify("com.google.android.apps.messaging", "AX-HDFCBK", "123456 is your OTP for login"))
        assertEquals(NotificationClassifier.Category.PAYMENT, c.classify("net.one97.paytm", "Payment received", "₹250 credited to your account"))
        assertEquals(NotificationClassifier.Category.DELIVERY, c.classify("in.amazon.mShop.android.shopping", "Amazon", "Your package was delivered"))
        assertEquals(NotificationClassifier.Category.CALENDAR, c.classify("com.google.android.calendar", "Standup", "Meeting starts in 10 minutes"))
        assertEquals(NotificationClassifier.Category.PROMO, c.classify("com.myntra.android", "Big sale", "Flat 50% off today"))
        assertEquals(NotificationClassifier.Category.SOCIAL, c.classify("com.instagram.android", "priya", "liked your photo"))
        assertEquals(NotificationClassifier.Category.OTHER, c.classify("com.some.app", "Hello", "Update available"))
    }

    @Test
    fun autoReplyPromptForbidsCommitments() {
        val p = Prompts.autoReply("Rahul", listOf(ChatLine("Rahul", "can you come tomorrow at 6?", 1, false)), "Hrithik", Tone.CASUAL)
        assertTrue(p.system.contains("Never invent facts"))
        assertTrue(p.system.contains("holding message"))
        assertTrue(p.user.contains("can you come tomorrow at 6?"))
    }
}
