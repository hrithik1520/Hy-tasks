package com.hy.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/** Text-to-speech with Android's built-in voices (offline when the voice data is installed). */
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

    /** Speaks [text] and returns when it's finished (or stopped). */
    suspend fun speak(text: String) {
        if (text.isBlank() || withTimeoutOrNull(5_000) { ready.await() } != true) return
        // Indian English first (the user's accent and names), then any English voice.
        if (tts.setLanguage(Locale.forLanguageTag("en-IN")) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
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
}
