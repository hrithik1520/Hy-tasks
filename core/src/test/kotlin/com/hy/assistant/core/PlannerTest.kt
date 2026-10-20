package com.hy.assistant.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlannerTest {
    // Tuesday 20 Oct 2026, 10:00
    private val now = LocalDateTime.of(2026, 10, 20, 10, 0)
    private fun at(day: Int, h: Int, m: Int = 0, month: Int = 10, year: Int = 2026) = LocalDateTime.of(year, month, day, h, m)
    private fun parse(s: String) = Planner.parse(s, now)

    @Test
    fun reminders() {
        assertEquals(PlanRequest.Remind("Call mom", at(20, 18)), parse("remind me to call mom at 6"))
        assertEquals(PlanRequest.Remind("Call mom", at(20, 18)), parse("Remind me at 6 to call mom"))
        assertEquals(PlanRequest.Remind("Check the oven", at(20, 10, 20)), parse("remind me in 20 minutes to check the oven"))
        assertEquals(PlanRequest.Remind("Pay rent", at(21, 9)), parse("remind me tomorrow to pay rent"))
        assertEquals(PlanRequest.Remind("Pay rent", at(20, 17)), parse("set a reminder for 5pm to pay rent"))
        assertEquals(PlanRequest.Remind("Take medicine", at(21, 9)), parse("remind me tomorrow morning to take medicine"))
        assertEquals(PlanRequest.Remind("Call Priya", at(20, 18, 15)), parse("remind me to call Priya at quarter past six"))
        assertEquals(PlanRequest.Remind("Submit the report", at(23, 15)), parse("remind me on friday at 3 to submit the report"))
        assertEquals(PlanRequest.Remind("Renew passport", at(25, 9, month = 11)), parse("remind me to renew passport on 25th november"))
        assertEquals(PlanRequest.Remind("Water the plants", at(20, 21)), parse("remind me tonight to water the plants"))
        assertEquals(PlanRequest.Remind("Stretch", at(20, 11)), parse("remind me in an hour to stretch"))
        assertEquals(PlanRequest.Remind("Reminder", at(20, 10, 30)), parse("remind me in half an hour"))
        // 9am today has passed → tomorrow
        assertEquals(PlanRequest.Remind("Gym", at(21, 9)), parse("remind me about gym at 9am"))
        assertEquals(PlanRequest.RemindNeedsTime("Buy milk"), parse("remind me to buy milk"))
        assertEquals(PlanRequest.Remind("Dry the clothes in the sun", at(20, 14)), parse("remind me to dry the clothes in the sun this afternoon"))
        assertEquals(PlanRequest.ListReminders, parse("show my reminders"))
        assertEquals(PlanRequest.ListReminders, parse("what are my reminders?"))
    }

    @Test
    fun calendarQuestions() {
        assertEquals(PlanRequest.Agenda(LocalDate.of(2026, 10, 20)), parse("what's my day"))
        assertEquals(PlanRequest.Agenda(LocalDate.of(2026, 10, 21)), parse("what's on my calendar tomorrow"))
        assertEquals(PlanRequest.Agenda(LocalDate.of(2026, 10, 21)), parse("do I have any meetings tomorrow?"))
        assertEquals(PlanRequest.Agenda(LocalDate.of(2026, 10, 26)), parse("what do i have next monday"))
        assertEquals(PlanRequest.Agenda(LocalDate.of(2026, 10, 20)), parse("how does my day look"))
        assertEquals(PlanRequest.FreeAt(at(20, 17)), parse("am I free at 5"))
        assertEquals(PlanRequest.FreeAt(at(21, 15, 30)), parse("am i free tomorrow at 3:30 pm"))
        assertEquals(PlanRequest.Agenda(LocalDate.of(2026, 10, 24)), parse("am I busy on saturday"))
        assertNull(parse("what do i have to buy for the party"))
        assertNull(parse("what's the weather today"))
    }

    @Test
    fun calendarEvents() {
        assertEquals(PlanRequest.AddEvent("Meeting with Priya", at(21, 16), 60, false), parse("add meeting with Priya tomorrow at 4pm to my calendar"))
        assertEquals(PlanRequest.AddEvent("Call with Rahul", at(23, 11), 30, false), parse("schedule a call with Rahul on friday at 11 for 30 minutes"))
        assertEquals(
            PlanRequest.AddEvent("Dentist appointment", at(25, 10), 60, false),
            parse("put dentist appointment on 25 oct at 10am in my calendar"),
        )
        assertEquals(PlanRequest.AddEvent("Mom's birthday", LocalDate.of(2026, 11, 2).atStartOfDay(), 24 * 60, true), parse("add mom's birthday on 2/11 to my calendar"))
        assertEquals(PlanRequest.EventNeedsTime("Dentist"), parse("add dentist to my calendar"))
        assertNull(parse("add milk to the shopping list"))
        assertNull(parse("plan a trip to goa"))
        assertNull(parse("book a table for 8pm"))
    }

    @Test
    fun describe() {
        assertEquals("today at 6:00 PM", When.describe(at(20, 18), now))
        assertEquals("tomorrow at 9:30 AM", When.describe(at(21, 9, 30), now))
        assertEquals("Fri 23 Oct at 3:00 PM", When.describe(at(23, 15), now))
    }
}
