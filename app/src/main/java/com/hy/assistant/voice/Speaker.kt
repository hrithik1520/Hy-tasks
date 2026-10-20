package com.hy.assistant.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.hy.assistant.SettingsData
import com.hy.assistant.VoiceStyle
import com.hy.assistant.core.Butler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/** Text-to-speech with the phone's installed voices (offline when the voice data is installed). */
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private var current: CompletableDeferred<Unit>? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { current?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { current?.complete(Unit) }
            override fun onError(id: String?, errorCode: Int) { current?.complete(Unit) }
            override fun onStop(id: String?, interrupted: Boolean) { current?.complete(Unit) }
        })
    }

    private suspend fun isReady() = withTimeoutOrNull(5_000) { ready.await() } == true

    /** English voices installed on the phone, for the Settings picker. */
    suspend fun englishVoices(): List<Butler.VoiceInfo> {
        if (!isReady()) return emptyList()
        return runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .map { Butler.VoiceInfo(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired, it.quality) }
            .sortedWith(compareBy<Butler.VoiceInfo> { it.locale }.thenBy { it.name })
    }

    /** Applies the voice style from settings (voice, speed, pitch). Returns the voice used, if any. */
    private fun configure(s: SettingsData): String? {
        val all: Set<Voice> = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        fun use(name: String?): Voice? = name?.let { n -> all.firstOrNull { it.name == n } }?.also { tts.voice = it }
        return when (s.voiceStyle) {
            VoiceStyle.BUTLER -> {
                tts.setSpeechRate(Butler.RATE)
                tts.setPitch(Butler.PITCH)
                val infos = all.map { Butler.VoiceInfo(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired, it.quality) }
                use(Butler.pickVoice(infos)?.name)?.name ?: run { tts.setLanguage(Locale.UK); null }
            }
            VoiceStyle.CUSTOM -> {
                tts.setSpeechRate(1f)
                tts.setPitch(1f)
                use(s.ttsVoice)?.name ?: run { tts.setLanguage(Locale.forLanguageTag("en-IN")); null }
            }
            VoiceStyle.STANDARD -> {
                tts.setSpeechRate(1f)
                tts.setPitch(1f)
                // Indian English first (the user's accent and names), then any English voice.
                if (tts.setLanguage(Locale.forLanguageTag("en-IN")) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
                null
            }
        }
    }

    /** Speaks [text] with the voice from [settings] and returns when it's finished (or stopped). */
    suspend fun speak(text: String, settings: SettingsData) {
        if (text.isBlank() || !isReady()) return
        configure(settings)
        val done = CompletableDeferred<Unit>()
        current = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        withTimeoutOrNull(60_000) { done.await() }
    }

    fun stop() {
        tts.stop()
        current?.complete(Unit)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        /** Opens the phone's "install voice data" screen (more voices, e.g. British English). */
        fun installVoicesIntent() = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
