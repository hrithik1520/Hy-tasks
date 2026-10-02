package com.hy.assistant.notifications

import android.content.Context
import android.util.Log
import com.hy.assistant.core.NotificationClassifier
import com.hy.assistant.core.NotificationClassifier.Category
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

data class FeedItem(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val category: Category,
)

/** Non-chat notifications from all apps (codes, deliveries, payments, …), kept for 24 h on-device. */
object NotificationFeed {
    private const val RETENTION_MS = 24L * 60 * 60 * 1000
    private const val MAX_ITEMS = 300

    private val lock = Any()
    private val items = LinkedHashMap<String, FeedItem>()
    private val _state = MutableStateFlow<List<FeedItem>>(emptyList())
    val state: StateFlow<List<FeedItem>> = _state.asStateFlow()

    /** Items newer than this were not yet included in a catch-up. */
    @Volatile
    var seenAt: Long = 0L
        private set

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private var saveJob: Job? = null

    fun init(context: Context, scope: CoroutineScope) {
        this.scope = scope
        file = File(context.filesDir, "feed.json")
        synchronized(lock) { load() }
        publish()
    }

    fun add(id: String, packageName: String, appName: String, title: String, text: String, timestamp: Long) {
        if (title.isBlank() && text.isBlank()) return
        val item = FeedItem(id, packageName, appName, title, text, timestamp, NotificationClassifier.classify(packageName, title, text))
        synchronized(lock) {
            items.remove(id) // re-insert so updated notifications move to the end
            items[id] = item
        }
        publish()
        scheduleSave()
    }

    fun unseen(): List<FeedItem> = _state.value.filter { it.timestamp > seenAt }

    fun markSeen() {
        seenAt = System.currentTimeMillis()
        scheduleSave()
    }

    fun clear() {
        synchronized(lock) { items.clear() }
        publish()
        scheduleSave()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            items.values.removeAll { it.timestamp < cutoff }
            while (items.size > MAX_ITEMS) items.remove(items.keys.first())
            items.values.toList()
        }
        _state.value = snapshot.sortedByDescending { it.timestamp }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(2000)
            synchronized(lock) { save() }
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            items.values.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("pkg", it.packageName).put("app", it.appName)
                        .put("title", it.title).put("text", it.text).put("ts", it.timestamp),
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("seenAt", seenAt).put("items", arr).toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w("NotificationFeed", "save failed", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            seenAt = o.optLong("seenAt")
            val arr = o.getJSONArray("items")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val pkg = j.getString("pkg")
                val title = j.getString("title")
                val text = j.getString("text")
                items[j.getString("id")] = FeedItem(
                    j.getString("id"), pkg, j.getString("app"), title, text, j.getLong("ts"),
                    NotificationClassifier.classify(pkg, title, text),
                )
            }
        } catch (e: Exception) {
            Log.w("NotificationFeed", "load failed", e)
        }
    }
}
