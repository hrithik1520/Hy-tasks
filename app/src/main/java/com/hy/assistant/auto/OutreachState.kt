package com.hy.assistant.auto

import android.content.Context

/**
 * Remembers, per chat, how far Alfrid's scripted outreach has got. Kept in SharedPreferences so a
 * restart can't make it introduce itself to the same person twice.
 */
class OutreachState(context: Context) {
    private val prefs = context.getSharedPreferences("outreach", Context.MODE_PRIVATE)

    /** Has Alfrid already asked this chat whether they have anything for the user? */
    fun introduced(chatKey: String): Boolean = prefs.getLong(intro(chatKey), 0L) > 0L

    fun markIntroduced(chatKey: String) =
        prefs.edit().putLong(intro(chatKey), System.currentTimeMillis()).apply()

    /** When Alfrid last confirmed a message here (0 = never). */
    fun lastAckAt(chatKey: String): Long = prefs.getLong(ack(chatKey), 0L)

    fun markAcked(chatKey: String) =
        prefs.edit().putLong(ack(chatKey), System.currentTimeMillis()).apply()

    fun clear() = prefs.edit().clear().apply()

    private fun intro(chatKey: String) = "intro:$chatKey"
    private fun ack(chatKey: String) = "ack:$chatKey"
}
