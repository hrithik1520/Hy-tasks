package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandParserTest {
    @Test
    fun digest() {
        for (s in listOf("What did I miss?", "catch me up", "Any new messages", "summarize everything")) {
            assertEquals(Command.Digest, CommandParser.parse(s), s)
        }
    }

    @Test
    fun replyWithSeparator() {
        assertEquals(Command.Reply("rahul", "I'm in a meeting, call you later"),
            CommandParser.parse("Reply to Rahul saying I'm in a meeting, call you later"))
        assertEquals(Command.Reply("mom", "I'll be home by 8"),
            CommandParser.parse("tell mom that I'll be home by 8."))
        // Trailing '.'/'!' on the whole command is trimmed.
        assertEquals(Command.Reply("priya sharma", "Sounds good"),
            CommandParser.parse("reply to Priya Sharma: Sounds good!"))
    }

    @Test
    fun replyWithKnownName() {
        val names = listOf("Rahul", "Rahul Office", "Mom")
        assertEquals(Command.Reply("Rahul Office", "running late"),
            CommandParser.parse("text Rahul Office running late", names))
        assertEquals(Command.Reply("Mom", "on my way"), CommandParser.parse("message mom on my way", names))
        assertIs<Command.Unknown>(CommandParser.parse("text Someone hello", names))
    }

    @Test
    fun draft() {
        assertEquals(Command.DraftReply("rahul"), CommandParser.parse("Suggest a reply to Rahul"))
        assertEquals(Command.DraftReply("work group"), CommandParser.parse("draft reply for work group"))
    }

    @Test
    fun summarize() {
        assertEquals(Command.Summarize("rahul"), CommandParser.parse("Summarize Rahul"))
        assertEquals(Command.Summarize("rahul"), CommandParser.parse("what did Rahul say?"))
        assertEquals(Command.Summarize("family"), CommandParser.parse("summarize my chat with family"))
        assertEquals(Command.Summarize("college group"), CommandParser.parse("catch me up on college group"))
    }

    @Test
    fun unknown() {
        assertIs<Command.Unknown>(CommandParser.parse("open the camera"))
        assertIs<Command.Unknown>(CommandParser.parse("   "))
    }
}
