package com.hy.assistant.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hy.assistant.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CatalogModel(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int,
    val note: String,
    val license: String,
)

data class InstalledModel(val fileName: String, val sizeMb: Long)

sealed interface TransferState {
    data object None : TransferState
    data class Downloading(val fileName: String, val progress: Float?, val downloadedMb: Long) : TransferState
    data class Importing(val fileName: String, val copiedMb: Long) : TransferState
    data class Error(val message: String) : TransferState
}

/**
 * Manages GGUF model files in app-specific storage. Models are downloaded after install
 * (never bundled in the APK) and checked for the GGUF header before use.
 */
class ModelManager(
    private val context: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    val catalog = listOf(
        CatalogModel(
            id = "qwen2.5-1.5b",
            title = "Qwen2.5 1.5B Instruct (recommended)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1120,
            note = "Best balance on Nothing Phone (3a) Pro. Good summaries and replies.",
            license = "Apache 2.0",
        ),
        CatalogModel(
            id = "qwen2.5-0.5b",
            title = "Qwen2.5 0.5B Instruct (fast)",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 400,
            note = "Very fast, small download. Noticeably weaker writing.",
            license = "Apache 2.0",
        ),
        CatalogModel(
            id = "qwen2.5-3b",
            title = "Qwen2.5 3B Instruct (best quality)",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeMb = 2100,
            note = "Better writing but ~2x slower and uses ~2.5 GB RAM.",
            license = "Qwen Research License (personal / non-commercial)",
        ),
    )

    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _transfer = MutableStateFlow<TransferState>(TransferState.None)
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private var pollJob: Job? = null

    init {
        // Resume progress tracking if a download was running when the app was closed.
        val id = prefs.getLong("downloadId", -1L)
        val file = prefs.getString("downloadFile", null)
        if (id >= 0 && file != null) poll(id, file)
        ensureActiveModel()
    }

    fun activeModelFile(): File? =
        settings.current.activeModel?.let { File(modelsDir, it) }?.takeIf { it.exists() }

    fun setActive(fileName: String) = settings.update { it.copy(activeModel = fileName) }

    fun download(model: CatalogModel) = downloadUrl(model.url, model.fileName)

    fun downloadUrl(url: String, fileName: String) {
        if (_transfer.value is TransferState.Downloading || _transfer.value is TransferState.Importing) return
        val name = sanitize(fileName)
        File(modelsDir, "$name.part").delete()
        val id = try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Hy Assistant model: $name")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, "models", "$name.part")
                .setAllowedOverMetered(true)
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            _transfer.value = TransferState.Error("Download failed to start: ${e.message}")
            return
        }
        prefs.edit().putLong("downloadId", id).putString("downloadFile", name).apply()
        poll(id, name)
    }

    fun cancelDownload() {
        val id = prefs.getLong("downloadId", -1L)
        if (id >= 0) downloadManager.remove(id)
        finishDownload()
        _transfer.value = TransferState.None
    }

    fun delete(fileName: String) {
        File(modelsDir, fileName).delete()
        if (settings.current.activeModel == fileName) settings.update { it.copy(activeModel = null) }
        refresh()
    }

    /** Copies a user-picked .gguf (e.g. from Downloads) into app storage. */
    fun import(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val name = sanitize(displayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf")
            val target = File(modelsDir, name)
            val tmp = File(modelsDir, "$name.part")
            try {
                _transfer.value = TransferState.Importing(name, 0)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (32L shl 20) < n) _transfer.value = TransferState.Importing(name, total shr 20)
                        }
                    }
                }
                if (!isGguf(tmp)) {
                    tmp.delete()
                    _transfer.value = TransferState.Error("$name is not a GGUF model file.")
                    return@launch
                }
                tmp.renameTo(target)
                _transfer.value = TransferState.None
                refresh()
            } catch (e: Exception) {
                tmp.delete()
                _transfer.value = TransferState.Error("Import failed: ${e.message}")
            }
        }
    }

    fun dismissError() {
        if (_transfer.value is TransferState.Error) _transfer.value = TransferState.None
    }

    fun refresh() {
        _installed.value = scanInstalled()
        ensureActiveModel()
    }

    private fun ensureActiveModel() {
        val installed = _installed.value
        if (activeModelFile() == null && installed.isNotEmpty()) {
            // Prefer the recommended model if present.
            val pick = installed.firstOrNull { it.fileName == catalog[0].fileName } ?: installed[0]
            setActive(pick.fileName)
        }
    }

    private fun poll(id: Long, fileName: String) {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    finishDownload()
                    _transfer.value = TransferState.None
                    return@launch
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                cursor.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val part = File(modelsDir, "$fileName.part")
                        val target = File(modelsDir, fileName)
                        finishDownload()
                        if (isGguf(part)) {
                            target.delete()
                            part.renameTo(target)
                            _transfer.value = TransferState.None
                            withContext(Dispatchers.Main) { refresh() }
                        } else {
                            part.delete()
                            _transfer.value = TransferState.Error("Downloaded file is not a GGUF model. Check the URL.")
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloadManager.remove(id)
                        finishDownload()
                        _transfer.value = TransferState.Error("Download failed (code $reason). Check your connection and try again.")
                        return@launch
                    }
                    else -> _transfer.value = TransferState.Downloading(
                        fileName,
                        if (total > 0) done.toFloat() / total else null,
                        done shr 20,
                    )
                }
                delay(700)
            }
        }
    }

    private fun finishDownload() {
        prefs.edit().remove("downloadId").remove("downloadFile").apply()
    }

    private fun scanInstalled(): List<InstalledModel> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .map { InstalledModel(it.name, it.length() shr 20) }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (clean.endsWith(".gguf", ignoreCase = true)) clean else "$clean.gguf"
    }

    companion object {
        fun isGguf(file: File): Boolean = try {
            file.inputStream().use { s ->
                val magic = ByteArray(4)
                s.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        } catch (e: Exception) {
            false
        }
    }
}
