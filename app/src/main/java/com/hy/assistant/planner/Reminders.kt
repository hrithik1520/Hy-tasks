package com.hy.assistant.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Reminder(val id: Long, val text: String, val at: Long)

/**
 * Hy's own reminders: kept in a small file and fired by AlarmManager at the exact minute (also in
 * Doze), then shown as a notification with Done / Snooze. Rescheduled after a reboot or update.
 */
object Reminders {
    private lateinit var file: File
    private lateinit var appContext: Context
    private val _items = MutableStateFlow<List<Reminder>>(emptyList())

    /** Pending reminders, soonest first. */
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, "reminders.json")
        _items.value = load()
    }

    @Synchronized
    fun add(text: String, at: Long): Reminder {
        val r = Reminder(System.currentTimeMillis(), text.trim().take(200), at)
        save((_items.value + r).sortedBy { it.at })
        schedule(r)
        return r
    }

    @Synchronized
    fun remove(id: Long) {
        cancelAlarm(id)
        save(_items.value.filterNot { it.id == id })
    }

    @Synchronized
    fun snooze(id: Long, minutes: Int) {
        val r = _items.value.firstOrNull { it.id == id } ?: return
        val moved = r.copy(at = System.currentTimeMillis() + minutes * 60_000L)
        save((_items.value.filterNot { it.id == id } + moved).sortedBy { it.at })
        schedule(moved)
    }

    fun get(id: Long): Reminder? = _items.value.firstOrNull { it.id == id }

    /** After boot / app update: alarms are gone, set them again (overdue ones fire right away). */
    fun rescheduleAll() = _items.value.forEach(::schedule)

    private fun schedule(r: Reminder) {
        val am = appContext.getSystemService(AlarmManager::class.java)
        val pi = firePending(r.id)
        val at = r.at.coerceAtLeast(System.currentTimeMillis() + 1000)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // may be a few minutes late
        }
    }

    private fun cancelAlarm(id: Long) = appContext.getSystemService(AlarmManager::class.java).cancel(firePending(id))

    private fun firePending(id: Long): PendingIntent = PendingIntent.getBroadcast(
        appContext, requestCode(id),
        Intent(appContext, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun requestCode(id: Long) = 50_000 + (id % 100_000).toInt()

    /** Outside the range of Hy's chat notifications (see HyNotifications.idFor). */
    fun notificationId(id: Long) = 200_000 + (id % 100_000).toInt()

    private fun save(list: List<Reminder>) {
        _items.value = list
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
            file.writeText(arr.toString())
        }
    }

    private fun load(): List<Reminder> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Reminder(o.getLong("id"), o.getString("text"), o.getLong("at"))
        }.sortedBy { it.at }
    }.getOrDefault(emptyList())
}
