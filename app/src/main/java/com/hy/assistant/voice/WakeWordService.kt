package com.hy.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hy.assistant.HyApp
import com.hy.assistant.MainActivity
import com.hy.assistant.R
import com.hy.assistant.core.WakeWord
import com.hy.assistant.screen.HyScreenService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Listens offline for "Hey Alfred" / "What's up Alfred" / "Alfred" (Vosk, restricted to those
 * phrases). Runs as a microphone foreground service with an ongoing notification, as Android
 * requires. Pauses while Hy itself is listening and during calls.
 */
class WakeWordService : Service(), RecognitionListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HyApp.instance.settings.update { it.copy(wakeWordEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // The microphone service type exists from Android 11; Android 10 takes no type.
            val type = if (android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            // Android only allows starting a microphone service while Hy is on screen.
            Log.w(TAG, "can't start listening now: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (speech == null) scope.launch { startListening() }
        return START_STICKY
    }

    private suspend fun startListening() {
        if (!WakeModel.ensure(this)) {
            stopSelf()
            return
        }
        try {
            val m = model ?: kotlinx.coroutines.withContext(Dispatchers.IO) { Model(WakeModel.dir(this@WakeWordService).absolutePath) }.also { model = it }
            val rec = Recognizer(m, 16000f, WakeWord.grammarJson()).also { recognizer = it }
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            running.value = true
        } catch (e: Exception) {
            Log.w(TAG, "wake word failed to start", e)
            stopSelf()
        }
    }

    // Only completed phrases count: partial guesses caused false wake-ups ("Alfredo") in tests.
    override fun onPartialResult(hypothesis: String) = Unit
    override fun onResult(hypothesis: String) = check(hypothesis)
    override fun onFinalResult(hypothesis: String) = check(hypothesis)
    override fun onError(exception: Exception) { Log.w(TAG, "wake word error", exception) }
    override fun onTimeout() = Unit

    private fun check(json: String) {
        if (!WakeWord.detected(json)) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3000) return
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode != AudioManager.MODE_NORMAL) return // on a call
        lastTrigger = now
        pause() // hand the microphone to Hy's conversation
        recognizer?.reset()
        openConversation()
    }

    private fun openConversation() {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_VOICE, true)
        // Android lets an app with an active accessibility service open its screen from the
        // background; otherwise a notification asks the user to tap.
        if (HyScreenService.isEnabled(this)) {
            startActivity(open)
        } else {
            val pi = PendingIntent.getActivity(this, 11, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hy)
                .setContentTitle("Alfred is listening")
                .setContentText("Tap to talk")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
            scope.launch {
                kotlinx.coroutines.delay(15_000)
                resume()
            }
        }
    }

    fun pause() {
        speech?.setPause(true)
    }

    fun resume() {
        recognizer?.reset()
        speech?.setPause(false)
    }

    override fun onDestroy() {
        running.value = false
        if (instance === this) instance = null
        speech?.let { runCatching { it.stop(); it.shutdown() } }
        recognizer?.let { runCatching { it.close() } }
        model?.let { runCatching { it.close() } }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 12, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 13, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_hy)
            .setContentTitle("Listening for “Hey Alfred”")
            .setContentText("Offline, on this phone. Tap to talk now.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ONGOING = "wake_word"
        private const val CHANNEL_ALERT = "wake_alert"
        private const val NOTIF_ID = 950
        private const val ALERT_ID = 951
        const val ACTION_STOP = "com.hy.assistant.WAKE_STOP"

        @Volatile
        var instance: WakeWordService? = null
            private set

        val running = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Call while Hy is on screen (Android blocks starting a mic service from the background). */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun pauseIfRunning() = instance?.pause()
        fun resumeIfRunning() = instance?.resume()

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Wake word", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Wake word alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
