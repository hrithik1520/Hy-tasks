package com.hy.assistant.planner

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class CalendarEvent(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val location: String)

/** The phone's calendars (Google Calendar and others synced to Android), read and written on-device. */
object CalendarRepo {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun hasPermission(context: Context) = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Events overlapping [day], in start order (visible calendars only). */
    fun eventsOn(context: Context, day: LocalDate): List<CalendarEvent> {
        val zone = ZoneId.systemDefault()
        return eventsBetween(context, day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    fun eventsBetween(context: Context, fromMs: Long, toMs: Long): List<CalendarEvent> {
        if (!hasPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<CalendarEvent>()
        val zone = ZoneId.systemDefault()
        context.contentResolver.query(uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                // All-day events are stored at UTC midnight.
                val z = if (allDay) ZoneOffset.UTC else zone
                out += CalendarEvent(
                    title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)",
                    start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), z),
                    end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), z),
                    allDay = allDay,
                    location = c.getString(4).orEmpty(),
                )
            }
        }
        return out
    }

    /** Adds an event to the primary writable calendar. Returns the calendar's name, or null if there is none. */
    fun insert(context: Context, title: String, start: LocalDateTime, minutes: Int, allDay: Boolean): String? {
        if (!hasPermission(context)) return null
        val (calId, calName) = primaryCalendar(context) ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            if (allDay) {
                val utc = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, utc)
                put(CalendarContract.Events.DTEND, utc + 24 * 3_600_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val ms = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, ms)
                put(CalendarContract.Events.DTEND, ms + minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { calName }
    }

    /** The user's main calendar: marked primary, else an owner-level Google one, else any writable one. */
    private fun primaryCalendar(context: Context): Pair<Long, String>? {
        val cols = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        data class Cal(val id: Long, val name: String, val primary: Boolean, val google: Boolean, val owner: Boolean)
        val cals = mutableListOf<Cal>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, cols,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                cals += Cal(
                    c.getLong(0), c.getString(1).orEmpty(), c.getInt(2) == 1, c.getString(3) == "com.google",
                    c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_OWNER,
                )
            }
        }
        val best = cals.firstOrNull { it.primary } ?: cals.firstOrNull { it.google && it.owner } ?: cals.firstOrNull { it.owner } ?: cals.firstOrNull()
        return best?.let { it.id to it.name.ifBlank { "your calendar" } }
    }
}
