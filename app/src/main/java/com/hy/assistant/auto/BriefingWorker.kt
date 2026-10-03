package com.hy.assistant.auto

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hy.assistant.HyApp
import com.hy.assistant.R
import com.hy.assistant.core.NotificationClassifier.Category
import com.hy.assistant.core.Prompts
import com.hy.assistant.core.TextCleanup
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.notifications.NotificationFeed
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Daily briefing at the user's chosen hour: unread chats + important notifications. */
class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = HyApp.instance
        val s = app.settings.current
        if (!s.briefingEnabled) return Result.success()
        val unread = MessageStore.state.value.filter { it.unread.isNotEmpty() }
        val important = NotificationFeed.state.value
            .filter { it.category in setOf(Category.PAYMENT, Category.DELIVERY, Category.CALENDAR) }
            .filter { System.currentTimeMillis() - it.timestamp < 24 * 3600_000L }

        val chatsText = if (unread.isEmpty()) "No unread messages." else {
            // AI summary when a model is installed (bounded so the job can't hang), else a plain list.
            val model = app.models.activeModelFile()
            val ai = model?.let {
                withTimeoutOrNull(120_000) {
                    val p = Prompts.digest(unread.take(8).associate { c -> c.name to c.unread.map { m -> m.toChatLine() } })
                    TextCleanup.stripThinking(app.engine.complete(it, p, s.threads, s.contextSize)).trim()
                }
            }
            ai?.takeIf { it.isNotBlank() } ?: unread.take(8).joinToString("\n") { c ->
                "• ${c.name} (${c.unread.size}): ${c.unread.last().text.take(70)}"
            }
        }
        val extras = important.take(5).joinToString("\n") { "• ${it.category.label}: ${it.appName} — ${listOf(it.title, it.text).filter { t -> t.isNotBlank() }.joinToString(": ").take(70)}" }
        val body = chatsText + if (extras.isNotEmpty()) "\n\n$extras" else ""

        if (HyNotifications.canPost(applicationContext)) {
            val n = NotificationCompat.Builder(applicationContext, HyNotifications.CH_BRIEFING)
                .setSmallIcon(R.drawable.ic_stat_hy)
                .setContentTitle("Your Alfrid briefing · ${unread.size} unread chat${if (unread.size == 1) "" else "s"}")
                .setContentText(body.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(HyNotifications.openAppIntent(applicationContext))
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(BRIEFING_ID, n)
            } catch (e: SecurityException) {
                // Notification permission revoked.
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "daily-briefing"
        private const val BRIEFING_ID = 900

        /** (Re)schedules or cancels the daily job to match settings. */
        fun schedule(context: Context, enabled: Boolean, hour: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            val req = PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
