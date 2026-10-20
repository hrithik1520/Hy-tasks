package com.hy.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One utterance of speech → text with Android's recognizer. Uses the fully on-device recognizer
 * when the phone has one (Android 12+ with the offline English pack), otherwise asks the default
 * recognizer to prefer offline. Call on the main thread.
 */
class Listener(private val context: Context) {
    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    private var active: SpeechRecognizer? = null

    /** Returns the transcript, or null on silence / no match / error. [onPartial] gets live text. */
    suspend fun listen(onPartial: (String) -> Unit): String? = suspendCancellableCoroutine { cont ->
        val rec = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        active = rec
        fun finish(result: String?) {
            if (!cont.isActive) return
            rec.destroy()
            if (active === rec) active = null
            cont.resume(result)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() })
            }
            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Log.w("HyListener", "recognizer error $error")
                finish(null)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        rec.startListening(intent)
        cont.invokeOnCancellation { runCatching { rec.cancel(); rec.destroy() } }
    }

    fun cancel() {
        active?.let { runCatching { it.cancel(); it.destroy() } }
        active = null
    }
}
