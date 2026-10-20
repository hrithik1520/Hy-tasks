package com.hy.assistant.planner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hy.assistant.R
import com.hy.assistant.auto.HyNotifications

/** Fires reminders (alarm) and handles their Done / Snooze buttons. Not exported. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        when (intent.action) {
            ACTION_FIRE -> Reminders.get(id)?.let { show(context, it) }
            ACTION_DONE -> {
                Reminders.remove(id)
                NotificationManagerCompat.from(context).cancel(Reminders.notificationId(id))
            }
            ACTION_SNOOZE -> {
                Reminders.snooze(id, SNOOZE_MIN)
                NotificationManagerCompat.from(context).cancel(Reminders.notificationId(id))
            }
        }
    }

    private fun show(context: Context, r: Reminder) {
        if (!HyNotifications.canPost(context)) return
        val b = NotificationCompat.Builder(context, HyNotifications.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_hy)
            .setContentTitle("Reminder")
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(HyNotifications.openAppIntent(context))
            .setDeleteIntent(button(context, ACTION_DONE, r.id, 1)) // swiping it away counts as done
            .addAction(0, "Done", button(context, ACTION_DONE, r.id, 2))
            .addAction(0, "Snooze $SNOOZE_MIN min", button(context, ACTION_SNOOZE, r.id, 3))
        try {
            NotificationManagerCompat.from(context).notify(Reminders.notificationId(r.id), b.build())
        } catch (e: SecurityException) {
            // Notification permission revoked.
        }
    }

    private fun button(context: Context, action: String, id: Long, slot: Int): PendingIntent = PendingIntent.getBroadcast(
        context, Reminders.requestCode(id) * 4 + slot,
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_FIRE = "com.hy.assistant.REMINDER_FIRE"
        const val ACTION_DONE = "com.hy.assistant.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.hy.assistant.REMINDER_SNOOZE"
        const val EXTRA_ID = "id"
        private const val SNOOZE_MIN = 10
    }
}

/** Alarms don't survive a reboot or an app update: set the pending reminders again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        ) {
            Reminders.rescheduleAll()
        }
    }
}
