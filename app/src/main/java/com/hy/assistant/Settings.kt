package com.hy.assistant

import android.content.Context
import com.hy.assistant.core.Butler
import com.hy.assistant.core.Humanizer
import com.hy.assistant.core.Tone
import com.hy.assistant.tools.SearchEngine
import com.hy.assistant.tools.TerminalBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class VoiceStyle(val label: String) { BUTLER("Butler (British)"), STANDARD("Standard"), CUSTOM("Chosen voice") }

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
    val autoReplyGroups: Boolean = false,
    /** Also auto-reply in non-WhatsApp messengers (Telegram, Messages, …). */
    val autoReplyOtherApps: Boolean = false,
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
    /** Emojis in drafted replies: add one when it fits (default), match the chat, or none. */
    val emojiMode: Humanizer.EmojiMode = Humanizer.EmojiMode.FITS,
    /** Read answers aloud when a request was spoken. */
    val speakAnswers: Boolean = true,
    /** Offline "Hey Alfred" listener (foreground service with a notification). */
    val wakeWordEnabled: Boolean = false,
    /** Spoken voice: the British "Butler" voice, the phone's standard voice, or a specific installed voice. */
    val voiceStyle: VoiceStyle = VoiceStyle.BUTLER,
    /** Exact TTS voice name when [voiceStyle] is CUSTOM. */
    val ttsVoice: String? = null,
    /** Butler manner for Hy's own answers (never in messages sent to others). */
    val butlerPersona: Boolean = true,
    val addressAs: Butler.Address = Butler.Address.NAME,
    /** Run the model on the phone's GPU (Adreno, via OpenCL). Off until the speed test shows it helps. */
    val useGpu: Boolean = false,
    /** Manual mode: offer 3 reply choices as tap-to-send chips on the notification. */
    val replyChoices: Boolean = true,
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
            // Nothing Phone (3a) Pro (Snapdragon 7s Gen 3): 4 performance cores (A720) + 4 efficiency cores (A520).
            // 4 threads matches the performance cores; Android may still schedule them anywhere.
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
            autoReplyGroups = prefs.getBoolean("autoReplyGroups", d.autoReplyGroups),
            autoReplyOtherApps = prefs.getBoolean("autoReplyOtherApps", d.autoReplyOtherApps),
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
            speakAnswers = prefs.getBoolean("speakAnswers", d.speakAnswers),
            wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", d.wakeWordEnabled),
            voiceStyle = runCatching { VoiceStyle.valueOf(prefs.getString("voiceStyle", d.voiceStyle.name)!!) }.getOrDefault(d.voiceStyle),
            ttsVoice = prefs.getString("ttsVoice", null),
            butlerPersona = prefs.getBoolean("butlerPersona", d.butlerPersona),
            addressAs = runCatching { Butler.Address.valueOf(prefs.getString("addressAs", d.addressAs.name)!!) }.getOrDefault(d.addressAs),
            useGpu = prefs.getBoolean("useGpu", d.useGpu),
            replyChoices = prefs.getBoolean("replyChoices", d.replyChoices),
            emojiMode = runCatching { Humanizer.EmojiMode.valueOf(prefs.getString("emojiMode", d.emojiMode.name)!!) }.getOrDefault(d.emojiMode),
            searchEngine = runCatching { SearchEngine.valueOf(prefs.getString("searchEngine", d.searchEngine.name)!!) }.getOrDefault(d.searchEngine),
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
            .putString("emojiMode", d.emojiMode.name)
            .putBoolean("speakAnswers", d.speakAnswers)
            .putBoolean("wakeWordEnabled", d.wakeWordEnabled)
            .putString("voiceStyle", d.voiceStyle.name)
            .putString("ttsVoice", d.ttsVoice)
            .putBoolean("butlerPersona", d.butlerPersona)
            .putString("addressAs", d.addressAs.name)
            .putBoolean("useGpu", d.useGpu)
            .putBoolean("replyChoices", d.replyChoices)
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
