package com.hy.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** The small offline English model Vosk uses to hear "Hey Alfred" (~40 MB download, once). */
object WakeModel {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val DIR = "vosk-model-small-en-us-0.15"

    sealed interface State {
        data object Missing : State
        data class Downloading(val mb: Long, val totalMb: Long?) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun dir(context: Context) = File(context.filesDir, "vosk/$DIR")

    fun isReady(context: Context) = File(dir(context), "am/final.mdl").exists()

    fun refresh(context: Context) {
        if (_state.value !is State.Downloading) _state.value = if (isReady(context)) State.Ready else State.Missing
    }

    /** Downloads and unpacks the model if needed. Returns true when it's ready. */
    suspend fun ensure(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isReady(context)) return@withContext true.also { _state.value = State.Ready }
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done % (2L shl 20) < n) _state.value = State.Downloading(done shr 20, total?.shr(20))
                    }
                }
            }
            unzip(zip, root)
            zip.delete()
            val ok = isReady(context)
            _state.value = if (ok) State.Ready else State.Failed("The voice model looks incomplete. Try again.")
            ok
        } catch (e: Exception) {
            zip.delete()
            _state.value = State.Failed("Voice model download failed: ${e.message}")
            false
        }
    }

    /** Unzips into [root], refusing entries that would escape it ("../" paths). */
    private fun unzip(zip: File, root: File) {
        val base = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(root, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("Bad path in model zip: ${e.name}")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
    }
}
