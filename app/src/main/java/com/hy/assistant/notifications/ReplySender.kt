package com.hy.assistant.notifications

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

object ReplySender {
    sealed interface Result {
        data object Sent : Result
        data class Failed(val reason: String) : Result
    }

    /** Sends [text] through WhatsApp's own notification reply action. Call only after user confirmation. */
    fun send(context: Context, chatKey: String, text: String): Result {
        val handle = MessageStore.replyHandle(chatKey)
            ?: return Result.Failed("This chat's WhatsApp notification is gone, so it can't be replied to from here.")
        val inputs = handle.action.remoteInputs
            ?: return Result.Failed("WhatsApp didn't offer a reply box for this chat.")
        return try {
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val results = Bundle()
            for (input in inputs) results.putCharSequence(input.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            handle.action.actionIntent.send(context, 0, intent)
            MessageStore.addOwnMessage(chatKey, text)
            Result.Sent
        } catch (e: PendingIntent.CanceledException) {
            Result.Failed("WhatsApp cancelled the reply action. Open the chat in WhatsApp instead.")
        } catch (e: Exception) {
            Log.w("ReplySender", "send failed", e)
            Result.Failed("Couldn't send: ${e.message}")
        }
    }

    /** Fallback when no live reply action exists: copy text and open WhatsApp. */
    fun copyAndOpenWhatsApp(context: Context, packageName: String, text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Reply", text))
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
