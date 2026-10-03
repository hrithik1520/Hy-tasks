package com.hy.assistant.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.hy.assistant.HyApp

/** Handles buttons on Alfrid's notifications. Not exported: only our own PendingIntents reach it. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatKey = intent.getStringExtra(EXTRA_CHAT) ?: return
        val automation = HyApp.instance.automation
        when (intent.action) {
            ACTION_SEND -> intent.getStringExtra(EXTRA_TEXT)?.let { automation.sendNow(chatKey, it, auto = false) }
            ACTION_TYPED -> {
                val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(HyNotifications.KEY_TYPED_REPLY)
                if (!typed.isNullOrBlank()) automation.sendNow(chatKey, typed.toString(), auto = false)
            }
            ACTION_CANCEL_AUTO -> automation.cancelAuto(chatKey)
            ACTION_SEND_NOW -> automation.sendPendingNow(chatKey)
        }
    }

    companion object {
        const val ACTION_SEND = "com.hy.assistant.SEND"
        const val ACTION_TYPED = "com.hy.assistant.TYPED"
        const val ACTION_CANCEL_AUTO = "com.hy.assistant.CANCEL_AUTO"
        const val ACTION_SEND_NOW = "com.hy.assistant.SEND_NOW"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_TEXT = "text"
    }
}
