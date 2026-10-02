package com.hy.assistant.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hy.assistant.core.CommandSafety
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class TerminalBackend(val label: String) { LOCAL("Phone shell"), TERMUX("Termux") }

data class TermEntry(
    val id: Int,
    val command: String,
    val backend: TerminalBackend,
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = true,
)

/** A command the AI proposed. It never runs until the user taps Run. */
data class ProposedCommand(val command: String, val warnings: List<String>)

/**
 * In-app terminal. Runs commands either in the app's own sandboxed shell (/system/bin/sh,
 * toybox tools) or inside Termux via its RUN_COMMAND intent (full Linux userland, pkg, python…).
 */
object Terminal {
    private const val TIMEOUT_SEC = 60L
    private const val MAX_OUTPUT = 20_000
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    private val ids = AtomicInteger(1)
    private val _entries = MutableStateFlow<List<TermEntry>>(emptyList())
    val entries: StateFlow<List<TermEntry>> = _entries.asStateFlow()
    private val _proposed = MutableStateFlow<ProposedCommand?>(null)
    val proposed: StateFlow<ProposedCommand?> = _proposed.asStateFlow()

    fun propose(command: String) {
        _proposed.value = ProposedCommand(command.trim(), CommandSafety.warnings(command))
    }

    fun dismissProposal() {
        _proposed.value = null
    }

    fun clear() {
        _entries.value = _entries.value.filter { it.running }
    }

    fun isTermuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true }.getOrDefault(false)

    fun hasTermuxPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Called only after an explicit user tap (Run button, typing in the terminal, or approving an
     * agent step). Returns the entry id, or null for an empty command.
     */
    fun run(context: Context, scope: CoroutineScope, command: String, backend: TerminalBackend): Int? {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        _proposed.value = null
        val entry = TermEntry(ids.getAndIncrement(), cmd, backend)
        _entries.update { (it + entry).takeLast(50) }
        when (backend) {
            TerminalBackend.LOCAL -> scope.launch(Dispatchers.IO) { runLocal(context, entry) }
            TerminalBackend.TERMUX -> runTermux(context, entry)
        }
        return entry.id
    }

    private fun runLocal(context: Context, entry: TermEntry) {
        try {
            val home = File(context.filesDir, "home").apply { mkdirs() }
            val p = ProcessBuilder("/system/bin/sh", "-c", entry.command)
                .directory(home)
                .redirectErrorStream(true)
                .apply { environment()["HOME"] = home.absolutePath }
                .start()
            p.outputStream.close()
            val sb = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val readerThread = Thread {
                runCatching {
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        synchronized(sb) { if (sb.length < MAX_OUTPUT) sb.append(buf, 0, n) }
                        finish(entry.id, synchronized(sb) { sb.toString() }, null, running = true)
                    }
                }
            }.apply { start() }
            val done = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            readerThread.join(2000)
            val out = synchronized(sb) { sb.toString() } + if (!done) "\n[stopped after ${TIMEOUT_SEC}s]" else ""
            finish(entry.id, out, if (done) p.exitValue() else -1)
        } catch (e: Exception) {
            finish(entry.id, "Error: ${e.message}", -1)
        }
    }

    private fun runTermux(context: Context, entry: TermEntry) {
        if (!isTermuxInstalled(context)) return finish(entry.id, "Termux is not installed. Install it from F-Droid, or switch to Phone shell.", -1)
        if (!hasTermuxPermission(context)) return finish(entry.id, "Allow \"Run commands in Termux\" for Hy (Terminal → Termux setup).", -1)
        val resultIntent = Intent(context, TermuxResultReceiver::class.java).putExtra(TermuxResultReceiver.EXTRA_ID, entry.id)
        val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, entry.id, resultIntent, flags)
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", entry.command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            finish(entry.id, "Couldn't reach Termux: ${e.message}\n$TERMUX_SETUP", -1)
        }
    }

    internal fun finish(id: Int, output: String, exitCode: Int?, running: Boolean = false) {
        _entries.update { list ->
            list.map { if (it.id == id) it.copy(output = output.take(MAX_OUTPUT), exitCode = exitCode, running = running) else it }
        }
    }

    const val TERMUX_SETUP =
        "Termux setup (one time):\n" +
            "1. Install Termux from F-Droid (the Play Store version is outdated).\n" +
            "2. In Termux run:  echo \"allow-external-apps = true\" >> ~/.termux/termux.properties\n" +
            "3. Optional, for /sdcard access:  termux-setup-storage\n" +
            "4. Fully close Termux (exit) and reopen it once.\n" +
            "5. Tap \"Allow Termux access\" here."
}

/** Receives stdout/stderr/exit code from Termux's RunCommandService. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val result = intent.getBundleExtra("result") ?: return Terminal.finish(id, "No result from Termux.", -1)
        val stdout = result.getString("stdout").orEmpty()
        val stderr = result.getString("stderr").orEmpty()
        val errmsg = result.getString("errmsg").orEmpty()
        val exit = result.getInt("exitCode", -1)
        val text = buildString {
            append(stdout)
            if (stderr.isNotBlank()) append(if (isEmpty()) "" else "\n").append(stderr)
            if (errmsg.isNotBlank()) append("\n[Termux] ").append(errmsg)
        }
        Terminal.finish(id, text.ifEmpty { "(no output)" }, exit)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
