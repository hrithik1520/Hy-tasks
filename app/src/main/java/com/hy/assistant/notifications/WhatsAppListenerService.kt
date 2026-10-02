package com.hy.assistant.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hy.assistant.HyApp

/**
 * Reads WhatsApp message notifications (the only way to see WhatsApp messages without an
 * official API) and remembers each chat's inline-reply action.
 */
class WhatsAppListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (e: Exception) {
            Log.w(TAG, "initial scan failed", e)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isWatched(sbn.packageName)) MessageStore.onNotificationRemoved(sbn.key)
    }

    private fun isWatched(pkg: String): Boolean =
        pkg == WHATSAPP || (pkg == WHATSAPP_BUSINESS && HyApp.instance.settings.current.includeBusiness)

    private fun handle(sbn: StatusBarNotification) {
        if (!isWatched(sbn.packageName)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Calls, backups, "WhatsApp Web is active", etc. have no MessagingStyle.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return

        val extras = n.extras
        val rawName = style.conversationTitle
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: return
        val name = cleanTitle(rawName.toString())
        val key = n.shortcutId ?: sbn.tag ?: name

        val selfName = style.user.name?.toString()
        val messages = style.messages.mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val person = m.person
            val fromMe = person == null || (selfName != null && person.name?.toString() == selfName)
            val sender = if (fromMe) "Me" else person?.name?.toString() ?: name
            StoredMessage(sender, text, m.timestamp, fromMe)
        }

        MessageStore.ingest(
            key = key,
            name = name,
            packageName = sbn.packageName,
            isGroup = style.isGroupConversation,
            messages = messages,
            reply = findReplyAction(n)?.let { ReplyHandle(it, sbn.key) },
        )
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val withInput = n.actions?.filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }.orEmpty()
        return withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: withInput.firstOrNull()
    }

    companion object {
        private const val TAG = "WaListener"
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        @Volatile
        var connected = false
            private set

        private val countSuffix = Regex("""\s*\(\d+ (?:new )?messages?\)$""", RegexOption.IGNORE_CASE)

        fun cleanTitle(title: String): String = title.replace(countSuffix, "").trim()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, WhatsAppListenerService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
