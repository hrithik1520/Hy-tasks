package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryTest {
    @Test
    fun parsesMemoryCommands() {
        assertEquals(Command.Remember("my boss is Priya"), CommandParser.parse("Remember that my boss is Priya."))
        assertEquals(Command.Remember("I'm free after 6 on weekdays"), CommandParser.parse("remember: I'm free after 6 on weekdays"))
        assertEquals(Command.Remember("Rahul's birthday is 12 March"), CommandParser.parse("keep in mind Rahul's birthday is 12 March"))
        assertEquals(Command.Forget("my boss"), CommandParser.parse("forget about my boss"))
        assertEquals(Command.ForgetAll, CommandParser.parse("forget everything"))
        assertEquals(Command.ListMemory, CommandParser.parse("What do you remember about me?"))
        assertEquals(Command.NewChat, CommandParser.parse("New chat"))
        // Not memory commands:
        assertIs<Command.Reply>(CommandParser.parse("tell mom that I'll be late"))
    }

    @Test
    fun preparesAndRejectsFacts() {
        assertEquals(Memory.AddResult.Added("My boss is Priya"), Memory.prepare("that my boss is Priya."))
        assertIs<Memory.AddResult.Rejected>(Memory.prepare("my bank PIN is 4321"))
        assertIs<Memory.AddResult.Rejected>(Memory.prepare("the wifi password is hunter2"))
        assertIs<Memory.AddResult.Rejected>(Memory.prepare("ok"))
    }

    @Test
    fun forgetMatching() {
        val facts = listOf(
            MemoryFact(1, "My boss is Priya", 1),
            MemoryFact(2, "I'm vegetarian", 2),
            MemoryFact(3, "Priya's birthday is in June", 3),
        )
        assertEquals(listOf(1L), Memory.matching(facts, "my boss").map { it.id })
        assertEquals(listOf(1L, 3L), Memory.matching(facts, "priya").map { it.id })
        assertEquals(listOf(2L), Memory.matching(facts, "vegetarian").map { it.id })
        assertTrue(Memory.matching(facts, "it").isEmpty())
    }

    @Test
    fun memoryBlockNewestFirstWithinBudget() {
        val facts = (1..100).map { MemoryFact(it.toLong(), "Fact number $it", it.toLong()) }
        val b = Memory.block(facts, maxChars = 60)
        assertTrue(b.startsWith("- Fact number 100"))
        assertTrue(b.length <= 60)
    }

    @Test
    fun historyKeepsRecentTurnsAndShortensAnswers() {
        val turns = (1..20).map { Turn("question $it", "answer $it " + "x".repeat(1000)) }
        val h = Conversation.historyBlock(turns, maxChars = 1000, maxAnswerChars = 50)
        assertTrue(h.contains("User: question 20"))
        assertTrue(!h.contains("question 1\n"))
        assertTrue(h.length <= 1000)
        assertTrue(h.lines().first().startsWith("User:"))
    }

    @Test
    fun promptsCarryHistoryAndMemory() {
        val p = Agent.answerPrompt("and tomorrow?", "", "Hrithik", history = "User: weather today\nHy: Sunny", memory = "- I live in Goa")
        assertTrue(p.user.contains("I live in Goa"))
        assertTrue(p.user.contains("Hy: Sunny"))
        assertTrue(p.user.endsWith("Request: and tomorrow?"))
        val r = Agent.routePrompt("reply to him saying ok", listOf("Rahul"), history = "User: summarize Rahul\nHy: He wants to meet")
        assertTrue(r.user.contains("He wants to meet") && r.user.endsWith("New request: reply to him saying ok"))
        assertTrue(Agent.routePrompt("reply to him", emptyList()).user.endsWith("Request: reply to him"))
        // The system prompt must not depend on chats or history, or the router's KV cache breaks.
        assertEquals(Agent.routePrompt("a", listOf("X")).system, Agent.routePrompt("b", listOf("Y", "Z"), "User: hi\nHy: hello").system)
        assertEquals(AgentAction.Remember("I'm vegetarian"), Agent.parse("""{"action":"remember","fact":"I'm vegetarian"}"""))
        assertTrue(Agent.GRAMMAR.contains("remember ::="))
    }
}
