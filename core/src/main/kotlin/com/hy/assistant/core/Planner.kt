package com.hy.assistant.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Reminders and calendar requests, understood by rules (instant, no AI). */
sealed interface PlanRequest {
    data class Remind(val text: String, val at: LocalDateTime) : PlanRequest

    /** "remind me to call mom" with no time: Hy asks when. */
    data class RemindNeedsTime(val text: String) : PlanRequest
    data object ListReminders : PlanRequest
    data class Agenda(val day: LocalDate) : PlanRequest
    data class FreeAt(val at: LocalDateTime) : PlanRequest

    /** [start] has no time of day when [allDay]. */
    data class AddEvent(val title: String, val start: LocalDateTime, val minutes: Int, val allDay: Boolean) : PlanRequest

    /** "add dentist to my calendar" with no day or time: Hy asks when. */
    data class EventNeedsTime(val title: String) : PlanRequest
}

/** A date/time phrase found in a request, and the request with that phrase removed. */
data class TimeMatch(val date: LocalDate?, val time: LocalTime?, val absolute: LocalDateTime?, val rest: String) {
    val found: Boolean get() = date != null || time != null || absolute != null
}

object When {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*"""
    private val days = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private const val WEEKDAY = """(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"""
    // Short forms only after "on/this/next": "dry the clothes in the sun" is not about Sunday.
    private const val SHORT_WEEKDAY = """(mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)"""
    private const val NUM_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty[- ]five"
    private val wordValues = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty-five" to 45, "forty five" to 45,
    )

    private val relative = Regex("""\bin (?:(half an?|\d+|a|an|$NUM_WORDS)\s*(minutes?|mins?|hours?|hrs?|days?|weeks?)|half an hour)\b""")
    private val namedDay = Regex("""\b(?:on )?(day after tomorrow|tomorrow|tmrw|tmr|today|tonight)\b""")
    private val weekday = Regex("""\b(?:(on|this|next|coming) )?$WEEKDAY\b|\b(on|this|next|coming) $SHORT_WEEKDAY\b""")
    private val dayMonth = Regex("""\b(?:on )?(?:the )?(\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH\b""")
    private val monthDay = Regex("""\b(?:on )?$MONTH (\d{1,2})(?:st|nd|rd|th)?\b""")
    private val numericDate = Regex("""\b(?:on )?(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val clockWithMarker = Regex("""(?:\b(?:at|by|around|from) )?\b(\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm|a\.m\.|p\.m\.))(?=\W|$)""")
    private val clockAfterAt = Regex("""\b(?:at|by|around|from) (\d{1,2}(?:[:.]\d{2})?|$NUM_WORDS)\b(?:\s*(am|pm|o'?clock))?""")
    private val spokenClock = Regex(
        """\b(?:at |by |around )?((?:quarter|half) (?:past|to) (?:\d{1,2}|$NUM_WORDS)|(?:\d{1,2}|$NUM_WORDS) (?:o'?clock|thirty|fifteen|forty[- ]five))(?:\s*(am|pm))?\b""",
    )
    private val partOfDay = Regex("""\b(?:in the |this |at )?(morning|afternoon|evening|night|noon|midday|midnight)\b""")

    /** Finds the date/time phrase in [input] (lowercase) relative to [now]. */
    fun extract(input: String, now: LocalDateTime): TimeMatch {
        var s = " " + input.lowercase(Locale.ROOT).trim() + " "
        fun cut(m: MatchResult) { s = s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1) }

        relative.find(s)?.let { m ->
            cut(m)
            val amountWord = m.groupValues[1]
            val unit = m.groupValues[2]
            val d = if (unit.isEmpty() || amountWord.startsWith("half")) {
                Duration.ofMinutes(30)
            } else {
                val n = (amountWord.toIntOrNull() ?: wordValues[amountWord.replace('-', ' ').let { if (it == "forty five") "forty-five" else it }] ?: 1).toLong()
                when (unit.first()) {
                    'm' -> Duration.ofMinutes(n)
                    'h' -> Duration.ofHours(n)
                    'd' -> Duration.ofDays(n)
                    else -> Duration.ofDays(7 * n)
                }
            }
            return TimeMatch(null, null, now.plus(d).truncatedTo(ChronoUnit.MINUTES), tidy(s))
        }

        var date: LocalDate? = null
        var evening = false
        namedDay.find(s)?.let { m ->
            cut(m)
            date = when (m.groupValues[1]) {
                "day after tomorrow" -> now.toLocalDate().plusDays(2)
                "tomorrow", "tmrw", "tmr" -> now.toLocalDate().plusDays(1)
                "tonight" -> { evening = true; now.toLocalDate() }
                else -> now.toLocalDate()
            }
        }
        if (date == null) dayMonth.find(s)?.let { m ->
            date = dateOf(m.groupValues[1].toInt(), months.indexOf(m.groupValues[2].take(3)) + 1, null, now)?.also { cut(m) }
        }
        if (date == null) monthDay.find(s)?.let { m ->
            date = dateOf(m.groupValues[2].toInt(), months.indexOf(m.groupValues[1].take(3)) + 1, null, now)?.also { cut(m) }
        }
        if (date == null) numericDate.find(s)?.let { m ->
            // Day first (India, UK).
            date = dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), now)?.also { cut(m) }
        }
        if (date == null) weekday.find(s)?.let { m ->
            cut(m)
            val full = m.groupValues[2].isNotEmpty()
            val dow = days.getValue((if (full) m.groupValues[2] else m.groupValues[4]).take(3))
            var d = now.toLocalDate()
            val strictlyAfter = (if (full) m.groupValues[1] else m.groupValues[3]) == "next"
            if (strictlyAfter || d.dayOfWeek != dow) d = d.plusDays(1)
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            date = d
        }

        var time: LocalTime? = null
        var ambiguous = false
        val clock = clockWithMarker.find(s) ?: spokenClock.find(s)
        if (clock != null) {
            val phrase = clock.groupValues[1] + (clock.groupValues.getOrNull(2)?.let { if (it.isBlank()) "" else " $it" } ?: "")
            PhoneActions.parseClock(phrase.trim())?.let { (h, m) ->
                time = LocalTime.of(h, m)
                ambiguous = !Regex("""am|pm|a\.m\.|p\.m\.""").containsMatchIn(phrase) && h in 1..11
                cut(clock)
            }
        }
        if (time == null) clockAfterAt.find(s)?.let { m ->
            val word = m.groupValues[1]
            val marker = m.groupValues[2]
            val (h, min) = when {
                word.contains(':') || word.contains('.') -> PhoneActions.parseClock(word) ?: return@let
                word.toIntOrNull() != null -> (word.toInt() to 0)
                else -> (wordValues[word] ?: return@let) to 0
            }
            if (h !in 0..23 || min !in 0..59) return@let
            var hour = h
            if (marker == "pm" && hour < 12) hour += 12
            if (marker == "am" && hour == 12) hour = 0
            time = LocalTime.of(hour, min)
            ambiguous = marker != "am" && marker != "pm" && h in 1..11
            cut(m)
        }
        partOfDay.find(s)?.let { m ->
            val part = m.groupValues[1]
            if (time == null) {
                time = when (part) {
                    "morning" -> LocalTime.of(9, 0)
                    "afternoon" -> LocalTime.of(14, 0)
                    "evening" -> LocalTime.of(18, 0)
                    "night" -> LocalTime.of(21, 0)
                    "midnight" -> LocalTime.MIDNIGHT
                    else -> LocalTime.NOON
                }
            } else if (ambiguous && part in setOf("afternoon", "evening", "night")) {
                time = time!!.plusHours(12)
                ambiguous = false
            } else if (ambiguous && part == "morning") {
                ambiguous = false
            }
            cut(m)
        }
        if (time == null && evening) time = LocalTime.of(21, 0)
        if (time != null && ambiguous) time = resolveAmbiguous(time!!, date, now, evening)
        return TimeMatch(date, time, null, tidy(s))
    }

    /**
     * "at 5" without am/pm: tonight / later today → the next one still ahead; another day → the
     * usual waking-hours reading (1–6 is afternoon, 7–11 is morning).
     */
    private fun resolveAmbiguous(t: LocalTime, date: LocalDate?, now: LocalDateTime, evening: Boolean): LocalTime {
        if (evening) return t.plusHours(12)
        val today = date == null || date == now.toLocalDate()
        if (today) {
            val am = now.toLocalDate().atTime(t)
            if (am.isAfter(now)) return if (t.hour < 7 && now.toLocalTime().isAfter(LocalTime.of(6, 0))) t.plusHours(12) else t
            return t.plusHours(12)
        }
        return if (t.hour in 1..6) t.plusHours(12) else t
    }

    private fun dateOf(day: Int, month: Int, year: Int?, now: LocalDateTime): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val y = when {
            year == null -> now.year
            year < 100 -> 2000 + year
            else -> year
        }
        val d = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: return null
        // "25 oct" in November means next year's.
        return if (year == null && d.isBefore(now.toLocalDate())) d.plusYears(1) else d
    }

    private fun tidy(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    /** The moment a reminder fires: a day without a time is 9 AM; a time already passed today is tomorrow. */
    fun resolve(m: TimeMatch, now: LocalDateTime): LocalDateTime? {
        m.absolute?.let { return it }
        if (!m.found) return null
        val t = m.time ?: LocalTime.of(9, 0)
        val at = (m.date ?: now.toLocalDate()).atTime(t)
        return if (m.date == null && !at.isAfter(now)) at.plusDays(1) else at
    }

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "today at 6:00 PM", "tomorrow at 9:00 AM", "Fri 24 Oct at 5:00 PM". */
    fun describe(at: LocalDateTime, now: LocalDateTime): String = "${describeDay(at.toLocalDate(), now)} at ${at.format(timeFmt)}"

    fun describeTime(t: LocalTime): String = t.format(timeFmt)

    fun describeDay(d: LocalDate, now: LocalDateTime): String = when (d) {
        now.toLocalDate() -> "today"
        now.toLocalDate().plusDays(1) -> "tomorrow"
        else -> d.format(dayFmt)
    }
}

object Planner {
    private val remind = Regex("""^(?:please |hey |can you |could you |pls )*(?:remind me|set (?:a |an )?reminder|create (?:a )?reminder|add (?:a )?reminder|new reminder|reminder)\b[:,]?(.*)$""")
    private val listReminders = Regex("""^(?:show|list|what are|what're|check)? ?(?:me )?(?:all )?(?:my |the )?(?:upcoming )?reminders\??$|^(?:do i have )?any reminders\??$""")
    private val agenda = Regex(
        """^(?:what'?s|what is|whats|show|check|read|tell me|how does|how's|hows)(?: me)? (?:on )?(?:my )?(?:calendar|schedule|agenda|day|plans?|meetings|events)(?: look(?: like)?)?(.*)$|""" +
            """^what do i have(.*)$|^do i have (?:any )?(?:meetings?|events?|plans|appointments?|anything)(.*)$|^what'?s (?:happening|planned)(.*)$""",
    )
    private val free = Regex("""^(?:am i|will i be) (?:free|busy|available)(.*)$""")
    private val calendarWord = Regex("""\b(?:to|in|on|into) (?:my |the )?(?:calendar|schedule|agenda)\b""")
    private val addVerb = Regex(
        """^(?:please |can you |could you )*(add|put|create|schedule|book|set up|plan|block)(?: an?)?(?: (?:event|appointment|entry)(?: (?:for|called|named|titled))?)? (.+)$""",
    )
    private val duration = Regex("""\bfor (?:(an?|one|half an?|\d+)\s*)(hours?|hrs?|minutes?|mins?)\b|\bfor half an hour\b""")

    fun parse(input: String, now: LocalDateTime): PlanRequest? {
        val original = input.trim().trimEnd('.', '!', '?')
        val text = original.lowercase(Locale.ROOT)
        if (listReminders.matches(text)) return PlanRequest.ListReminders

        remind.matchEntire(text)?.let { m ->
            val body = m.groupValues[1]
            val t = When.extract(body, now)
            val title = title(t.rest, original)
            val at = When.resolve(t, now) ?: return PlanRequest.RemindNeedsTime(title.ifBlank { "Reminder" })
            return PlanRequest.Remind(title.ifBlank { "Reminder" }, at)
        }

        free.matchEntire(text)?.let { m ->
            val t = When.extract(m.groupValues[1], now)
            if (t.time == null && t.absolute == null) return PlanRequest.Agenda(t.date ?: now.toLocalDate())
            return PlanRequest.FreeAt(When.resolve(t, now) ?: return null)
        }

        agenda.matchEntire(text)?.let { m ->
            val rest = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: ""
            val t = When.extract(rest, now)
            // "what do I have to buy" isn't a calendar question: only plain day words may remain.
            if (t.rest.replace(Regex("""\b(for|on|planned|scheduled|today|coming up|up|lined up|the|this)\b"""), "").isNotBlank()) return null
            return PlanRequest.Agenda(t.date ?: now.toLocalDate())
        }

        addVerb.matchEntire(text)?.let { m ->
            val verb = m.groupValues[1]
            val toCalendar = calendarWord.containsMatchIn(text)
            var body = calendarWord.replace(m.groupValues[2], " ")
            var minutes = 60
            duration.find(body)?.let { d ->
                val amount = d.groupValues[1]
                val unit = d.groupValues[2]
                minutes = when {
                    unit.isEmpty() || amount.startsWith("half") -> 30
                    unit.startsWith("h") -> 60 * (amount.toIntOrNull() ?: 1)
                    else -> amount.toIntOrNull() ?: 30
                }.coerceIn(5, 24 * 60)
                body = body.removeRange(d.range)
            }
            val t = When.extract(body, now)
            // "add milk to the list" / "plan a trip" are not calendar events: need the calendar or a time.
            if (!toCalendar && (verb !in setOf("schedule", "set up", "block") || !t.found)) return null
            val title = title(t.rest, original).ifBlank { "Event" }
            if (t.absolute != null) return PlanRequest.AddEvent(title, t.absolute, minutes, allDay = false)
            if (t.time == null) {
                val day = t.date ?: return PlanRequest.EventNeedsTime(title)
                return PlanRequest.AddEvent(title, day.atStartOfDay(), 24 * 60, allDay = true)
            }
            return PlanRequest.AddEvent(title, When.resolve(t, now)!!, minutes, allDay = false)
        }
        return null
    }

    private val leading = Regex("""^(?:(?:to|about|that|for|of|me|i need to|i have to|i should|i must|at|on|by|in)\s+)+""")
    private val trailing = Regex("""(?:\s+(?:at|on|by|for|in|please|pls|and))+$""")

    /** The words left after removing the time phrase, with the user's own casing. */
    private fun title(rest: String, original: String): String {
        val core = rest.replace(leading, "").replace(trailing, "").trim()
        if (core.isEmpty()) return ""
        val idx = original.lowercase(Locale.ROOT).indexOf(core)
        val cased = if (idx >= 0) original.substring(idx, idx + core.length) else core
        return cased.replaceFirstChar { it.uppercase() }
    }
}
