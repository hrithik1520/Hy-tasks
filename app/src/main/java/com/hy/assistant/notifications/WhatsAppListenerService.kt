package com.hy.assistant.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hy.assistant.HyApp

/**
 * Watches notifications. Chat notifications (MessagingStyle) become chats with an inline-reply
 * action; everything else goes to [NotificationFeed]. New incoming messages are handed to the
 * automation layer. (Class name kept for compatibility: renaming would revoke granted access.)
 */
class WhatsAppListenerService : NotificationListenerService() {
    private val appNames = HashMap<String, String>()

    override fun onListenerConnected() {
        connected = true
        try {
            // Initial scan: remember what's already showing, but don't auto-reply to it.
            activeNotifications?.forEach { handle(it, initialScan = true) }
        } catch (e: Exception) {
            Log.w(TAG, "initial scan failed", e)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn, initialScan = false)
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        MessageStore.onNotificationRemoved(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification, initialScan: Boolean) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return
        val settings = HyApp.instance.settings.current
        if (pkg == WHATSAPP_BUSINESS && !settings.includeBusiness) return
        val isWhatsApp = pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS
        if (!isWhatsApp && !settings.watchAllApps) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) handleChat(sbn, n, style, isWhatsApp, initialScan)
        else if (!isWhatsApp) handleOther(sbn, n) // WhatsApp's non-chat notices (backups, calls…) are noise
    }

    private fun handleChat(
        sbn: StatusBarNotification,
        n: Notification,
        style: NotificationCompat.MessagingStyle,
        isWhatsApp: Boolean,
        initialScan: Boolean,
    ) {
        val extras = n.extras
        val rawName = style.conversationTitle
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: return
        val name = cleanTitle(rawName.toString())
        val id = n.shortcutId ?: sbn.tag ?: name
        // WhatsApp keys stay un-prefixed so data from earlier versions keeps working.
        val key = if (isWhatsApp) id else "${sbn.packageName}:$id"

        val selfName = style.user.name?.toString()
        val messages = style.messages.mapNotNull { m ->
            val text = m.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val person = m.person
            val fromMe = person == null || (selfName != null && person.name?.toString() == selfName)
            val sender = if (fromMe) "Me" else person?.name?.toString() ?: name
            StoredMessage(sender, text, m.timestamp, fromMe)
        }

        val added = MessageStore.ingest(
            key = key,
            name = name,
            packageName = sbn.packageName,
            appName = appName(sbn),
            isGroup = style.isGroupConversation,
            messages = messages,
            reply = findReplyAction(n)?.let { ReplyHandle(it, sbn.key) },
        )
        if (!initialScan && added.isNotEmpty()) {
            // Ignore stale history that WhatsApp re-posts (e.g. after reboot).
            val fresh = added.filter { System.currentTimeMillis() - it.timestamp < FRESH_MS }
            if (fresh.isNotEmpty()) HyApp.instance.automation.onIncoming(key)
        }
    }

    private fun handleOther(sbn: StatusBarNotification, n: Notification) {
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        NotificationFeed.add(sbn.key, sbn.packageName, appName(sbn), title.trim(), text.trim(), sbn.postTime)
    }

    /** App label from the notification itself (avoids needing QUERY_ALL_PACKAGES). */
    private fun appName(sbn: StatusBarNotification): String = appNames.getOrPut(sbn.packageName) {
        val info: ApplicationInfo? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                sbn.notification.extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
            } else {
                @Suppress("DEPRECATION")
                sbn.notification.extras.getParcelable("android.appInfo")
            }
        } catch (e: Exception) {
            null
        }
        val label = info?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
        label ?: KNOWN_APPS[sbn.packageName] ?: sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val withInput = n.actions?.filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }.orEmpty()
        return withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: withInput.firstOrNull()
    }

    companion object {
        private const val TAG = "HyListener"
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val FRESH_MS = 2 * 60 * 1000L

        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui", "com.android.providers.downloads")
        private val KNOWN_APPS = mapOf(
            WHATSAPP to "WhatsApp",
            WHATSAPP_BUSINESS to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.instagram.android" to "Instagram",
        )

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
