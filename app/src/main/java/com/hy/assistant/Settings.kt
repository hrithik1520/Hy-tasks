package com.hy.assistant

import android.content.Context
import com.hy.assistant.core.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsData(
    val userName: String = "",
    val tone: Tone = Tone.CASUAL,
    val threads: Int = 4,
    val contextSize: Int = 2048,
    val includeBusiness: Boolean = true,
    val polishReplies: Boolean = true,
    val activeModel: String? = null,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<SettingsData> = _data.asStateFlow()
    val current: SettingsData get() = _data.value

    private fun load() = SettingsData(
        userName = prefs.getString("userName", "") ?: "",
        tone = runCatching { Tone.valueOf(prefs.getString("tone", Tone.CASUAL.name)!!) }.getOrDefault(Tone.CASUAL),
        // Dimensity 7300: 4 big A78 cores + 4 little A55 cores; 4 threads keeps work on the big cores.
        threads = prefs.getInt("threads", 4),
        contextSize = prefs.getInt("contextSize", 2048),
        includeBusiness = prefs.getBoolean("includeBusiness", true),
        polishReplies = prefs.getBoolean("polishReplies", true),
        activeModel = prefs.getString("activeModel", null),
    )

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
            .apply()
        _data.value = d
    }
}
