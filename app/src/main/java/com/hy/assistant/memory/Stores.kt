package com.hy.assistant.memory

import android.content.Context
import android.util.Log
import com.hy.assistant.core.Memory
import com.hy.assistant.core.MemoryFact
import com.hy.assistant.core.Turn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Facts the user asked Hy to remember. Stored only on the phone (app-private file). */
object MemoryStore {
    private val _facts = MutableStateFlow<List<MemoryFact>>(emptyList())
    val facts: StateFlow<List<MemoryFact>> = _facts.asStateFlow()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "memory.json")
        _facts.value = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MemoryFact(o.getLong("id"), o.getString("text"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    /** Returns the stored text, or a reason it was refused. */
    @Synchronized
    fun add(raw: String): Memory.AddResult {
        val r = Memory.prepare(raw)
        if (r is Memory.AddResult.Added) {
            val now = System.currentTimeMillis()
            val list = _facts.value.filterNot { it.text.equals(r.fact, ignoreCase = true) } + MemoryFact(now, r.fact, now)
            save(list.takeLast(Memory.MAX_FACTS))
        }
        return r
    }

    /** Removes facts matching [query]; returns what was removed. */
    @Synchronized
    fun forget(query: String): List<MemoryFact> {
        val gone = Memory.matching(_facts.value, query)
        if (gone.isNotEmpty()) save(_facts.value - gone.toSet())
        return gone
    }

    @Synchronized
    fun remove(id: Long) = save(_facts.value.filterNot { it.id == id })

    @Synchronized
    fun clear() = save(emptyList())

    fun promptBlock(maxChars: Int): String = Memory.block(_facts.value, maxChars)

    private fun save(list: List<MemoryFact>) {
        _facts.value = list
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.createdAt)) }
            file.writeText(arr.toString())
        }.onFailure { Log.w("MemoryStore", "save failed", it) }
    }
}

/** The ongoing conversation with Hy on the home screen. Persisted so it survives restarts. */
object ConversationStore {
    private const val MAX_TURNS = 40
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "conversation.json")
        _turns.value = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Turn(o.getString("u"), o.getString("a"), o.optLong("t"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(user: String, assistant: String) =
        save((_turns.value + Turn(user, assistant, System.currentTimeMillis())).takeLast(MAX_TURNS))

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<Turn>) {
        _turns.value = list
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("u", it.user).put("a", it.assistant).put("t", it.timestamp)) }
            file.writeText(arr.toString())
        }.onFailure { Log.w("ConversationStore", "save failed", it) }
    }
}
