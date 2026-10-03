package com.hy.assistant

import android.content.Context
import com.hy.assistant.core.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Global reply mode. */
enum class ReplyMode { MANUAL, AUTO }

/** Per-chat override of the global mode. */
enum class ChatMode(val label: String) { DEFAULT("Default"), AUTO("Auto"), MANUAL("Manual"), OFF("Off") }

data class SettingsData(
    val userName: String = "",
    val tone: Tone = Tone.CASUAL,
    val threads: Int = 4,
    val contextSize: Int = 2048,
    val includeBusiness: Boolean = true,
    val polishReplies: Boolean = true,
    val activeModel: String? = null,
    // Automation
    val replyMode: ReplyMode = ReplyMode.MANUAL,
    /** Draft a reply automatically for every new message (Manual mode: shown as a notification). */
    val proactiveSuggestions: Boolean = true,
    val autoSendDelaySec: Int = 10,
    val autoCooldownMin: Int = 5,
    val appendSignature: Boolean = false,
    /** Capture notifications from every app, not just WhatsApp. */
    val watchAllApps: Boolean = true,
    val chatModes: Map<String, ChatMode> = emptyMap(),
) {
    fun modeFor(chatKey: String): ChatMode = chatModes[chatKey] ?: ChatMode.DEFAULT
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<SettingsData> = _data.asStateFlow()
    val current: SettingsData get() = _data.value

    private fun load(): SettingsData {
        val d = SettingsData()
        return SettingsData(
            userName = prefs.getString("userName", d.userName) ?: "",
            tone = runCatching { Tone.valueOf(prefs.getString("tone", d.tone.name)!!) }.getOrDefault(d.tone),
            // Dimensity 7300: 4 big A78 cores + 4 little A55 cores; 4 threads keeps work on the big cores.
            threads = prefs.getInt("threads", d.threads),
            contextSize = prefs.getInt("contextSize", d.contextSize),
            includeBusiness = prefs.getBoolean("includeBusiness", d.includeBusiness),
            polishReplies = prefs.getBoolean("polishReplies", d.polishReplies),
            activeModel = prefs.getString("activeModel", null),
            replyMode = runCatching { ReplyMode.valueOf(prefs.getString("replyMode", d.replyMode.name)!!) }.getOrDefault(d.replyMode),
            proactiveSuggestions = prefs.getBoolean("proactiveSuggestions", d.proactiveSuggestions),
            autoSendDelaySec = prefs.getInt("autoSendDelaySec", d.autoSendDelaySec),
            autoCooldownMin = prefs.getInt("autoCooldownMin", d.autoCooldownMin),
            appendSignature = prefs.getBoolean("appendSignature", d.appendSignature),
            watchAllApps = prefs.getBoolean("watchAllApps", d.watchAllApps),
            chatModes = decodeModes(prefs.getString("chatModes", null)),
        )
    }

    fun update(transform: (SettingsData) -> SettingsData) {
        val d = transform(_data.value)
        prefs.edit()
            .putString("userName", d.userName)
            .putString("tone", d.tone.name)
            .putInt("threads", d.threads)
            .putInt("contextSize", d.contextSize)
            .putBoolean("includeBusiness", d.includeBusiness)
            .putBoolean("polishReplies", d.polishReplies)
            .putString("activeModel", d.activeModel)
            .putString("replyMode", d.replyMode.name)
            .putBoolean("proactiveSuggestions", d.proactiveSuggestions)
            .putInt("autoSendDelaySec", d.autoSendDelaySec)
            .putInt("autoCooldownMin", d.autoCooldownMin)
            .putBoolean("appendSignature", d.appendSignature)
            .putBoolean("watchAllApps", d.watchAllApps)
            .putString("chatModes", encodeModes(d.chatModes))
            .apply()
        _data.value = d
    }

    fun setChatMode(chatKey: String, mode: ChatMode) = update {
        it.copy(chatModes = if (mode == ChatMode.DEFAULT) it.chatModes - chatKey else it.chatModes + (chatKey to mode))
    }

    private fun encodeModes(m: Map<String, ChatMode>): String =
        JSONObject().apply { m.forEach { (k, v) -> put(k, v.name) } }.toString()

    private fun decodeModes(s: String?): Map<String, ChatMode> {
        if (s.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().mapNotNull { k ->
                runCatching { k to ChatMode.valueOf(o.getString(k)) }.getOrNull()
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
