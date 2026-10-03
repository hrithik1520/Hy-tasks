package com.hy.assistant.notifications

import android.app.Notification
import android.content.Context
import android.util.Log
import com.hy.assistant.core.ChatLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredMessage(val sender: String, val text: String, val timestamp: Long, val fromMe: Boolean) {
    fun toChatLine() = ChatLine(sender, text, timestamp, fromMe)
}

data class Chat(
    val key: String,
    val name: String,
    val packageName: String,
    val appName: String,
    val isGroup: Boolean,
    val messages: List<StoredMessage>,
    val lastReadAt: Long,
    val canReply: Boolean,
) {
    val lastTimestamp: Long get() = messages.lastOrNull()?.timestamp ?: 0L
    val unread: List<StoredMessage> get() = messages.filter { !it.fromMe && it.timestamp > lastReadAt }
    val isWhatsApp: Boolean get() = packageName.startsWith("com.whatsapp")
}

/** Live notification reply action for a chat. Only valid while WhatsApp's notification is showing. */
class ReplyHandle(val action: Notification.Action, val notificationKey: String)

/**
 * WhatsApp messages captured from notifications. Messages are kept on-device for
 * [RETENTION_MS] in app-private storage; reply actions live only in memory.
 */
object MessageStore {
    private const val TAG = "MessageStore"
    private const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_PER_CHAT = 200

    private class MutableChat(
        val key: String,
        var name: String,
        var packageName: String,
        var appName: String,
        var isGroup: Boolean,
        val messages: MutableList<StoredMessage> = mutableListOf(),
        var lastReadAt: Long = 0L,
    )

    private val lock = Any()
    private val chats = LinkedHashMap<String, MutableChat>()
    private val replyHandles = HashMap<String, ReplyHandle>()
    private val _state = MutableStateFlow<List<Chat>>(emptyList())
    val state: StateFlow<List<Chat>> = _state.asStateFlow()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private var saveJob: Job? = null

    fun init(context: Context, scope: CoroutineScope) {
        this.scope = scope
        file = File(context.filesDir, "messages.json")
        synchronized(lock) { load() }
        publish()
    }

    fun chat(key: String): Chat? = _state.value.firstOrNull { it.key == key }

    fun replyHandle(key: String): ReplyHandle? = synchronized(lock) { replyHandles[key] }

    /** Adds messages from a notification. Returns the incoming messages that were new. */
    fun ingest(
        key: String,
        name: String,
        packageName: String,
        appName: String,
        isGroup: Boolean,
        messages: List<StoredMessage>,
        reply: ReplyHandle?,
    ): List<StoredMessage> {
        val added = mutableListOf<StoredMessage>()
        synchronized(lock) {
            val chat = chats.getOrPut(key) { MutableChat(key, name, packageName, appName, isGroup) }
            chat.name = name
            chat.packageName = packageName
            chat.appName = appName
            chat.isGroup = isGroup
            for (m in messages) {
                if (m.text.isBlank() || isDuplicate(chat, m)) continue
                chat.messages.add(m)
                if (!m.fromMe) added.add(m)
            }
            chat.messages.sortBy { it.timestamp }
            while (chat.messages.size > MAX_PER_CHAT) chat.messages.removeAt(0)
            if (reply != null) replyHandles[key] = reply
        }
        publish()
        scheduleSave()
        return added
    }

    /**
     * Messages read from an open WhatsApp chat on screen (Accessibility). Merged into the chat with
     * the same name (so notification history and screen history combine); new text only, in order.
     * Returns how many messages were added.
     */
    fun mergeFromScreen(name: String, messages: List<StoredMessage>): Int {
        var added = 0
        synchronized(lock) {
            val chat = chats.values.firstOrNull { it.name.equals(name, ignoreCase = true) && it.packageName.startsWith("com.whatsapp") }
                ?: MutableChat("screen:$name", name, "com.whatsapp", "WhatsApp", false).also { chats[it.key] = it }
            for (m in messages) {
                if (m.text.isBlank()) continue
                // Screen rows have only minute-precision times: match on text + side instead.
                if (chat.messages.any { it.text == m.text && it.fromMe == m.fromMe }) continue
                chat.messages.add(m)
                added++
            }
            if (added > 0) {
                chat.messages.sortBy { it.timestamp }
                while (chat.messages.size > MAX_PER_CHAT) chat.messages.removeAt(0)
                // The user is looking at this chat right now: it's read.
                chat.lastReadAt = System.currentTimeMillis()
            }
        }
        if (added > 0) {
            publish()
            scheduleSave()
        }
        return added
    }

    /** Records a reply we sent so it shows immediately (WhatsApp's echo is de-duplicated). */
    fun addOwnMessage(key: String, text: String) {
        synchronized(lock) {
            val chat = chats[key] ?: return
            chat.messages.add(StoredMessage("Me", text, System.currentTimeMillis(), fromMe = true))
            chat.lastReadAt = System.currentTimeMillis()
        }
        publish()
        scheduleSave()
    }

    fun onNotificationRemoved(notificationKey: String) {
        synchronized(lock) {
            val entry = replyHandles.entries.firstOrNull { it.value.notificationKey == notificationKey } ?: return
            replyHandles.remove(entry.key)
            // The user most likely read the chat in WhatsApp.
            chats[entry.key]?.lastReadAt = System.currentTimeMillis()
        }
        publish()
        scheduleSave()
    }

    fun markRead(key: String) {
        synchronized(lock) { chats[key]?.lastReadAt = System.currentTimeMillis() }
        publish()
        scheduleSave()
    }

    fun clearAll() {
        synchronized(lock) {
            chats.clear()
            replyHandles.clear()
        }
        publish()
        scheduleSave()
    }

    private fun isDuplicate(chat: MutableChat, m: StoredMessage): Boolean =
        chat.messages.any {
            it.text == m.text && it.fromMe == m.fromMe && (
                (it.timestamp == m.timestamp && it.sender == m.sender) ||
                    // Our own sent reply echoed back by WhatsApp with a different timestamp.
                    (m.fromMe && kotlin.math.abs(it.timestamp - m.timestamp) < 10 * 60 * 1000)
                )
        }

    private fun publish() {
        val snapshot = synchronized(lock) {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            chats.values.forEach { c -> c.messages.removeAll { it.timestamp < cutoff } }
            chats.values.removeAll { it.messages.isEmpty() && !replyHandles.containsKey(it.key) }
            chats.values.map {
                Chat(it.key, it.name, it.packageName, it.appName, it.isGroup, it.messages.toList(), it.lastReadAt, replyHandles.containsKey(it.key))
            }
        }
        _state.value = snapshot.sortedByDescending { it.lastTimestamp }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            synchronized(lock) { save() }
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (c in chats.values) {
                val msgs = JSONArray()
                c.messages.forEach {
                    msgs.put(JSONObject().put("s", it.sender).put("t", it.text).put("ts", it.timestamp).put("me", it.fromMe))
                }
                arr.put(
                    JSONObject().put("key", c.key).put("name", c.name).put("pkg", c.packageName).put("app", c.appName)
                        .put("group", c.isGroup).put("read", c.lastReadAt).put("messages", msgs),
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = MutableChat(o.getString("key"), o.getString("name"), o.getString("pkg"), o.optString("app", "WhatsApp"), o.optBoolean("group"))
                c.lastReadAt = o.optLong("read")
                val msgs = o.getJSONArray("messages")
                for (j in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(j)
                    c.messages.add(StoredMessage(m.getString("s"), m.getString("t"), m.getLong("ts"), m.optBoolean("me")))
                }
                chats[c.key] = c
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }
}
