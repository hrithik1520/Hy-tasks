package com.hy.assistant.core

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenTest {
    private val W = 1080

    private fun msg(id: Int, text: String, left: Int, top: Int, time: String) = listOf(
        ScreenNode("com.whatsapp:id/message_text", text, left = left, top = top, right = left + 400, bottom = top + 60),
        ScreenNode("com.whatsapp:id/date", time, left = left + 300, top = top + 62, right = left + 400, bottom = top + 90),
    )

    private val chat = ScreenNode(
        children = listOf(
            ScreenNode("com.whatsapp:id/conversation_contact_name", "Rahul Sharma", left = 150, top = 60, right = 600, bottom = 120),
            ScreenNode(desc = "Back", left = 0, top = 60, right = 120, bottom = 120),
        ) + msg(1, "bro are you coming tonight?", 40, 300, "8:53 pm") +
            msg(2, "yes, at 8", 600, 420, "8:54 pm") +
            msg(3, "cool see you", 40, 540, "8:55 pm") +
            listOf(ScreenNode("com.whatsapp:id/entry", "", isPassword = false, left = 40, top = 1800, right = 900, bottom = 1880)),
    )

    @Test
    fun parsesWhatsAppChatBySide() {
        val c = WhatsAppScreen.parse(chat, W)!!
        assertEquals("Rahul Sharma", c.name)
        assertEquals(
            listOf(
                WhatsAppScreen.Message("bro are you coming tonight?", false, "8:53 pm"),
                WhatsAppScreen.Message("yes, at 8", true, "8:54 pm"),
                WhatsAppScreen.Message("cool see you", false, "8:55 pm"),
            ),
            c.messages,
        )
    }

    @Test
    fun notAChatScreen() {
        assertNull(WhatsAppScreen.parse(ScreenNode(children = listOf(ScreenNode(text = "Chats"))), W))
    }

    @Test
    fun screenTextSkipsPasswordsAndChrome() {
        val root = ScreenNode(
            children = listOf(
                ScreenNode(text = "Sign in", top = 10),
                ScreenNode(desc = "Back", top = 10, left = 0),
                ScreenNode(text = "hunter2", isPassword = true, top = 50),
                ScreenNode(text = "Forgot  password?", top = 90),
                ScreenNode(text = "Forgot password?", top = 91),
            ),
        )
        assertEquals("Sign in\nForgot password?", ScreenText.toText(root))
    }

    @Test
    fun readsTimeLabels() {
        val now = Calendar.getInstance().apply { set(2026, 9, 3, 21, 0) }
        val t = Calendar.getInstance().apply { timeInMillis = WhatsAppScreen.timeToday("8:53 pm", now)!! }
        assertEquals(20, t.get(Calendar.HOUR_OF_DAY))
        assertEquals(53, t.get(Calendar.MINUTE))
        assertEquals(0, Calendar.getInstance().apply { timeInMillis = WhatsAppScreen.timeToday("12:05 am", now)!! }.get(Calendar.HOUR_OF_DAY))
        assertEquals(14, Calendar.getInstance().apply { timeInMillis = WhatsAppScreen.timeToday("14:10", now)!! }.get(Calendar.HOUR_OF_DAY))
        assertNull(WhatsAppScreen.timeToday("yesterday", now))
        assertTrue(WhatsAppScreen.timeToday(null, now) == null)
    }
}
