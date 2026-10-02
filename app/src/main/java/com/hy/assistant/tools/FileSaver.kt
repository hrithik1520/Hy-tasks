package com.hy.assistant.tools

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.hy.assistant.core.Export
import com.hy.assistant.core.ExportFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A file Hy created: saved in Downloads/Hy, plus a private copy for Open/Share. */
data class SavedFile(val name: String, val format: ExportFormat, val shareUri: Uri) {
    val location: String get() = "Downloads/Hy/$name"
}

object FileSaver {
    private const val FOLDER = "Hy"

    /** Builds the file from [text] and saves it. Blocking: call on Dispatchers.IO. */
    fun save(context: Context, text: String, title: String, format: ExportFormat): SavedFile {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = Export.fileName(title, format, stamp)
        val bytes = Export.build(text, format, title.take(80))

        // Public copy in Downloads/Hy (no storage permission needed on Android 10+).
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, format.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Couldn't create the file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        // Private copy served through FileProvider, so Open/Share work with any app.
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 24 * 3600_000L }?.forEach { it.delete() }
        val local = File(dir, name).apply { writeBytes(bytes) }
        val share = FileProvider.getUriForFile(context, context.packageName + ".files", local)
        return SavedFile(name, format, share)
    }

    /** Returns false if no installed app can open this type (e.g. no Word/Docs app). */
    fun open(context: Context, file: SavedFile): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(file.shareUri, file.format.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun share(context: Context, file: SavedFile) {
        val send = Intent(Intent.ACTION_SEND).setType(file.format.mime)
            .putExtra(Intent.EXTRA_STREAM, file.shareUri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
