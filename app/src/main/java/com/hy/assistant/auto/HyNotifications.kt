package com.hy.assistant.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.hy.assistant.MainActivity
import com.hy.assistant.R

/** Alfrid's own notifications: reply suggestions (with Send) and auto-reply countdowns (with Cancel). */
object HyNotifications {
    private const val CH_SUGGEST = "suggestions"
    private const val CH_AUTO = "auto_replies"
    const val KEY_TYPED_REPLY = "typed_reply"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SUGGEST, "Reply suggestions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Suggested replies you can send with one tap"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AUTO, "Auto-replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Replies Alfrid sends for you in Auto mode"
            },
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun idFor(chatKey: String) = 1000 + (chatKey.hashCode() and 0xFFFF)

    /** Manual mode: suggested reply with Send / Reply / Open. */
    fun suggestion(context: Context, chatKey: String, chatName: String, incoming: String, text: String, heldReason: String?) {
        val title = if (heldReason != null) "$chatName · needs you ($heldReason)" else "$chatName · suggested reply"
        val b = base(context, CH_SUGGEST, chatKey)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("“${incoming.take(200)}”\n\n➜ $text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Send", action(context, ActionReceiver.ACTION_SEND, chatKey, text, 1))
            .addAction(typedReplyAction(context, chatKey))
            .addAction(0, "Open", openApp(context, chatKey))
        post(context, chatKey, b)
    }

    /** Auto mode: countdown before sending, with Cancel. */
    fun autoPending(context: Context, chatKey: String, chatName: String, text: String, delaySec: Int) {
        val b = base(context, CH_AUTO, chatKey)
            .setContentTitle("Auto-replying to $chatName in ${delaySec}s")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", action(context, ActionReceiver.ACTION_CANCEL_AUTO, chatKey, null, 2))
            .addAction(0, "Send now", action(context, ActionReceiver.ACTION_SEND_NOW, chatKey, null, 3))
            .addAction(0, "Edit", openApp(context, chatKey))
        post(context, chatKey, b)
    }

    fun sent(context: Context, chatKey: String, chatName: String, text: String, auto: Boolean) {
        val b = base(context, CH_AUTO, chatKey)
            .setContentTitle(if (auto) "Auto-replied to $chatName" else "Sent to $chatName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setTimeoutAfter(if (auto) 0L else 4000L)
        post(context, chatKey, b)
    }

    fun failed(context: Context, chatKey: String, chatName: String, reason: String) {
        val b = base(context, CH_SUGGEST, chatKey)
            .setContentTitle("Couldn't reply to $chatName")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
        post(context, chatKey, b)
    }

    fun cancel(context: Context, chatKey: String) = NotificationManagerCompat.from(context).cancel(idFor(chatKey))

    private fun base(context: Context, channel: String, chatKey: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hy)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context, chatKey))

    private fun post(context: Context, chatKey: String, b: NotificationCompat.Builder) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(idFor(chatKey), b.build())
        } catch (e: SecurityException) {
            // Permission revoked between check and post.
        }
    }

    private fun action(context: Context, action: String, chatKey: String, text: String?, slot: Int): PendingIntent {
        val i = Intent(context, ActionReceiver::class.java).setAction(action)
            .putExtra(ActionReceiver.EXTRA_CHAT, chatKey)
            .putExtra(ActionReceiver.EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context, idFor(chatKey) * 8 + slot, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun typedReplyAction(context: Context, chatKey: String): NotificationCompat.Action {
        val i = Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_TYPED)
            .putExtra(ActionReceiver.EXTRA_CHAT, chatKey)
        // Must be mutable so the system can attach the typed text.
        val pi = PendingIntent.getBroadcast(
            context, idFor(chatKey) * 8 + 4, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_TYPED_REPLY).setLabel("Type your reply").build()
        return NotificationCompat.Action.Builder(0, "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    private fun openApp(context: Context, chatKey: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, chatKey)
        return PendingIntent.getActivity(
            context, idFor(chatKey) * 8 + 5, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
