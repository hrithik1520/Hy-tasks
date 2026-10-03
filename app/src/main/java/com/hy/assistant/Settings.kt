package com.hy.assistant

import android.content.Context
import com.hy.assistant.core.Tone
import com.hy.assistant.tools.SearchEngine
import com.hy.assistant.tools.TerminalBackend
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
    /** 4096 leaves room for conversation history + memory; ~120 MB extra RAM, fine on 8 GB phones. */
    val contextSize: Int = 4096,
    val includeBusiness: Boolean = true,
    val polishReplies: Boolean = true,
    val activeModel: String? = null,
    // Automation
    val replyMode: ReplyMode = ReplyMode.MANUAL,
    /** Draft a reply automatically for every new message (Manual mode: shown as a notification). */
    val proactiveSuggestions: Boolean = true,
    val autoSendDelaySec: Int = 10,
    /** Auto mode in group chats too (Alfrid's opening question is the same everywhere). */
    val autoReplyGroups: Boolean = true,
    /** Also auto-reply in non-WhatsApp messengers (Telegram, Messages, …). */
    val autoReplyOtherApps: Boolean = true,
    val autoCooldownMin: Int = 5,
    val appendSignature: Boolean = false,
    /** Capture notifications from every app, not just WhatsApp. */
    val watchAllApps: Boolean = true,
    val chatModes: Map<String, ChatMode> = emptyMap(),
    // Tools
    /** Let the AI look things up on the web (only the search query leaves the phone). */
    val webSearch: Boolean = true,
    val terminalBackend: TerminalBackend = TerminalBackend.LOCAL,
    /** Most steps a multi-step agent run may take before it must answer. */
    val agentMaxSteps: Int = 10,
    val searchEngine: SearchEngine = SearchEngine.BING,
    /** Make WhatsApp replies read like a person texting (humanizer rules + voice matching). */
    val humanizeReplies: Boolean = true,
    val briefingEnabled: Boolean = false,
    val briefingHour: Int = 8,
    /** Accessibility: add messages from the open WhatsApp chat on screen to Hy's history. */
    val readWhatsAppScreen: Boolean = false,
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
            contextSize = prefs.getInt("contextSize", d.contextSize).let { stored ->
                // One-time bump of the old 2048 default (v0.1.6 added conversation memory).
                if (!prefs.getBoolean("ctxMigrated", false)) {
                    val bumped = if (stored == 2048) 4096 else stored
                    prefs.edit().putBoolean("ctxMigrated", true).putInt("contextSize", bumped).apply()
                    bumped
                } else stored
            },
            includeBusiness = prefs.getBoolean("includeBusiness", d.includeBusiness),
            polishReplies = prefs.getBoolean("polishReplies", d.polishReplies),
            activeModel = prefs.getString("activeModel", null),
            replyMode = runCatching { ReplyMode.valueOf(prefs.getString("replyMode", d.replyMode.name)!!) }.getOrDefault(d.replyMode),
            proactiveSuggestions = prefs.getBoolean("proactiveSuggestions", d.proactiveSuggestions),
            autoSendDelaySec = prefs.getInt("autoSendDelaySec", d.autoSendDelaySec),
            // v1.0.2 made Auto mode cover every chat, so an install that stored the old
            // opt-out defaults is switched over once.
            autoReplyGroups = migratedToAllChats(prefs.getBoolean("autoReplyGroups", d.autoReplyGroups)),
            autoReplyOtherApps = migratedToAllChats(prefs.getBoolean("autoReplyOtherApps", d.autoReplyOtherApps)),
            autoCooldownMin = prefs.getInt("autoCooldownMin", d.autoCooldownMin),
            appendSignature = prefs.getBoolean("appendSignature", d.appendSignature),
            watchAllApps = prefs.getBoolean("watchAllApps", d.watchAllApps),
            chatModes = decodeModes(prefs.getString("chatModes", null)),
            webSearch = prefs.getBoolean("webSearch", d.webSearch),
            terminalBackend = runCatching { TerminalBackend.valueOf(prefs.getString("terminalBackend", d.terminalBackend.name)!!) }
                .getOrDefault(d.terminalBackend),
            agentMaxSteps = prefs.getInt("agentMaxSteps", d.agentMaxSteps),
            humanizeReplies = prefs.getBoolean("humanizeReplies", d.humanizeReplies),
            briefingEnabled = prefs.getBoolean("briefingEnabled", d.briefingEnabled),
            briefingHour = prefs.getInt("briefingHour", d.briefingHour),
            readWhatsAppScreen = prefs.getBoolean("readWhatsAppScreen", d.readWhatsAppScreen),
            searchEngine = runCatching { SearchEngine.valueOf(prefs.getString("searchEngine", d.searchEngine.name)!!) }.getOrDefault(d.searchEngine),
        )
    }

    private fun migratedToAllChats(stored: Boolean): Boolean {
        if (prefs.getBoolean("allChatsMigrated", false)) return stored
        prefs.edit().putBoolean("allChatsMigrated", true).apply()
        return true
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
            .putBoolean("autoReplyGroups", d.autoReplyGroups)
            .putBoolean("autoReplyOtherApps", d.autoReplyOtherApps)
            .putInt("autoCooldownMin", d.autoCooldownMin)
            .putBoolean("appendSignature", d.appendSignature)
            .putBoolean("watchAllApps", d.watchAllApps)
            .putString("chatModes", encodeModes(d.chatModes))
            .putBoolean("webSearch", d.webSearch)
            .putString("terminalBackend", d.terminalBackend.name)
            .putInt("agentMaxSteps", d.agentMaxSteps)
            .putString("searchEngine", d.searchEngine.name)
            .putBoolean("humanizeReplies", d.humanizeReplies)
            .putBoolean("briefingEnabled", d.briefingEnabled)
            .putInt("briefingHour", d.briefingHour)
            .putBoolean("readWhatsAppScreen", d.readWhatsAppScreen)
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
