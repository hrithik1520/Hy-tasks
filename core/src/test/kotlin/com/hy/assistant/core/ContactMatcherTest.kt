package com.hy.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContactMatcherTest {
    private val names = listOf("Rahul Sharma", "Rahul Verma", "Mom ❤️", "Priya", "College Gang 🎓", "Office Team")

    @Test
    fun exactAndPrefix() {
        assertEquals(ContactMatcher.Result.Unique("Priya"), ContactMatcher.resolve("priya", names))
        assertEquals(ContactMatcher.Result.Unique("Mom ❤️"), ContactMatcher.resolve("mom", names))
        assertEquals(ContactMatcher.Result.Unique("College Gang 🎓"), ContactMatcher.resolve("college gang", names))
        assertEquals(ContactMatcher.Result.Unique("Office Team"), ContactMatcher.resolve("office", names))
    }

    @Test
    fun ambiguousFirstName() {
        val r = ContactMatcher.resolve("rahul", names)
        assertIs<ContactMatcher.Result.Ambiguous>(r)
        assertEquals(setOf("Rahul Sharma", "Rahul Verma"), r.candidates.toSet())
    }

    @Test
    fun fullNameWins() {
        assertEquals(ContactMatcher.Result.Unique("Rahul Verma"), ContactMatcher.resolve("rahul verma", names))
    }

    @Test
    fun typo() {
        assertEquals(ContactMatcher.Result.Unique("Priya"), ContactMatcher.resolve("pria", names))
    }

    @Test
    fun notFound() {
        assertEquals(ContactMatcher.Result.NotFound, ContactMatcher.resolve("zebra", names))
        assertTrue(ContactMatcher.rank("", names).isEmpty())
    }
}
