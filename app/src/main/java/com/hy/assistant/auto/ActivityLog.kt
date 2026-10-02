package com.hy.assistant.auto

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the assistant did on its own, so the user can always see (and audit) it. */
object ActivityLog {
    enum class Kind(val label: String) {
        AUTO_SENT("Auto-replied"),
        SENT("Sent"),
        SUGGESTED("Suggested"),
        HELD("Held for you"),
        CANCELLED("Cancelled"),
        FAILED("Failed"),
    }

    data class Entry(val time: Long, val kind: Kind, val chatName: String, val text: String, val note: String? = null)

    private const val MAX = 100
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "activity.json")
        _entries.value = load()
    }

    @Synchronized
    fun add(kind: Kind, chatName: String, text: String, note: String? = null) {
        val list = (listOf(Entry(System.currentTimeMillis(), kind, chatName, text, note)) + _entries.value).take(MAX)
        _entries.value = list
        save(list)
    }

    fun autoSentToday(): Int {
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        return _entries.value.count { it.kind == Kind.AUTO_SENT && it.time > dayAgo }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save(emptyList())
    }

    private fun save(list: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("t", it.time).put("k", it.kind.name).put("c", it.chatName).put("x", it.text).put("n", it.note))
            }
            file.writeText(arr.toString())
        }
    }

    private fun load(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getLong("t"), Kind.valueOf(o.getString("k")), o.getString("c"), o.getString("x"), o.optString("n").ifBlank { null })
        }
    }.getOrDefault(emptyList())
}
