package com.hy.assistant.core

import com.hy.assistant.core.PhoneAction.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneActionsTest {
    private fun p(s: String) = PhoneActions.parse(s)

    @Test
    fun whatsApp() {
        assertEquals(PhoneAction(Kind.WHATSAPP, "rahul", "I'm running late"), p("Message Rahul on WhatsApp saying I'm running late"))
        assertEquals(PhoneAction(Kind.WHATSAPP, "mom"), p("whatsapp mom"))
        assertEquals(PhoneAction(Kind.WHATSAPP, "priya"), p("reply to Priya on WhatsApp"))
        assertEquals(PhoneAction(Kind.WHATSAPP, "rahul sharma"), p("open chat with Rahul Sharma"))
        assertEquals(PhoneAction(Kind.WHATSAPP, "dad", "Reached home"), p("whatsapp dad: Reached home"))
        assertEquals(PhoneAction(Kind.WHATSAPP, "college gang"), p("open college gang on whatsapp"))
    }

    @Test
    fun otherActions() {
        assertEquals(PhoneAction(Kind.SMS, "mom", "On my way"), p("send sms to mom saying On my way"))
        assertEquals(PhoneAction(Kind.CALL, "dad"), p("call dad"))
        assertEquals(PhoneAction(Kind.ALARM, "6:30am"), p("set alarm for 6:30am"))
        assertEquals(PhoneAction(Kind.ALARM, "7 am"), p("wake me up at 7 am"))
        assertEquals(PhoneAction(Kind.TIMER, "10 minutes"), p("set a timer for 10 minutes"))
        assertEquals(PhoneAction(Kind.TIMER, "5 min"), p("5 min timer"))
        assertEquals(PhoneAction(Kind.NAVIGATE, "goa airport"), p("navigate to Goa airport"))
        assertEquals(PhoneAction(Kind.YOUTUBE, "lofi music"), p("play lofi music on youtube"))
        assertEquals(PhoneAction(Kind.OPEN_APP, "spotify"), p("open Spotify"))
        assertEquals(PhoneAction(Kind.OPEN_APP, "camera"), p("launch the camera app"))
        assertEquals(PhoneAction(Kind.OPEN_APP, "netflix"), p("can you get netflix up for me"))
        assertEquals(PhoneAction(Kind.OPEN_APP, "instagram"), p("fire up instagram"))
        assertEquals(PhoneAction(Kind.ALARM, "quarter past six"), p("wake me at quarter past six"))
    }

    @Test
    fun notPhoneActions() {
        assertNull(p("what did I miss"))
        assertNull(p("set alarm for later"))
        assertNull(p("timer"))
        assertNull(p("reply to rahul saying ok")) // notification reply, handled by CommandParser
    }

    @Test
    fun clockAndDuration() {
        assertEquals(6 to 30, PhoneActions.parseClock("6:30am"))
        assertEquals(19 to 0, PhoneActions.parseClock("7 pm"))
        assertEquals(19 to 45, PhoneActions.parseClock("19:45"))
        assertEquals(0 to 5, PhoneActions.parseClock("12:05 am"))
        assertEquals(6 to 30, PhoneActions.parseClock("6.30 am tomorrow"))
        assertEquals(6 to 15, PhoneActions.parseClock("quarter past six"))
        assertEquals(19 to 30, PhoneActions.parseClock("half past 7 pm"))
        assertEquals(7 to 45, PhoneActions.parseClock("quarter to eight"))
        assertEquals(18 to 30, PhoneActions.parseClock("six thirty in the evening"))
        assertEquals(7 to 0, PhoneActions.parseClock("seven o'clock"))
        assertNull(PhoneActions.parseClock("45"))
        assertNull(PhoneActions.parseClock("25:00"))
        assertEquals(600, PhoneActions.parseDurationSeconds("10 minutes"))
        assertEquals(5400, PhoneActions.parseDurationSeconds("1 hour 30 min"))
        assertEquals(90, PhoneActions.parseDurationSeconds("90 sec"))
        assertNull(PhoneActions.parseDurationSeconds("a while"))
    }

    @Test
    fun whatsAppNumbers() {
        assertEquals("919876543210", PhoneActions.whatsAppNumber("98765 43210", "91"))
        assertEquals("919876543210", PhoneActions.whatsAppNumber("098765-43210", "91"))
        assertEquals("919876543210", PhoneActions.whatsAppNumber("+91 98765 43210", "91"))
        assertEquals("447700900123", PhoneActions.whatsAppNumber("+44 7700 900123", "91"))
        assertEquals("447700900123", PhoneActions.whatsAppNumber("0044 7700 900123", "91"))
        assertNull(PhoneActions.whatsAppNumber("12345", "91"))
        assertEquals("91", PhoneActions.countryCode("IN"))
        assertEquals("971", PhoneActions.countryCode("ae"))
    }

    @Test
    fun findsWhatsAppControls() {
        val nodes = listOf(
            ScreenNode("com.whatsapp:id/message_text", "hi"),
            ScreenNode("com.whatsapp:id/entry", "", className = "android.widget.EditText"),
            ScreenNode("com.whatsapp:id/send", desc = "Send", className = "android.widget.ImageButton"),
        )
        assertEquals("com.whatsapp:id/entry", WhatsAppControls.inputField(nodes)?.viewId)
        assertEquals("com.whatsapp:id/send", WhatsAppControls.sendButton(nodes)?.viewId)
        val fallback = listOf(ScreenNode(className = "android.widget.EditText"), ScreenNode(desc = "Send"))
        assertEquals("android.widget.EditText", WhatsAppControls.inputField(fallback)?.className)
        assertEquals("Send", WhatsAppControls.sendButton(fallback)?.desc)
    }

    @Test
    fun routerPhoneAction() {
        assertEquals(
            AgentAction.Phone(PhoneAction(Kind.WHATSAPP, "brother", "I'll be late")),
            Agent.parse("""{"action":"phone","do":"whatsapp","target":"brother","text":"I'll be late"}"""),
        )
        assertNull(Agent.parse("""{"action":"phone","do":"hack","target":"x","text":""}"""))
        kotlin.test.assertTrue(Agent.GRAMMAR.contains("pkind ::="))
    }
}
