package com.hy.assistant.llm

import android.content.Context
import android.util.Log
import com.hy.assistant.core.GpuBench
import com.hy.assistant.core.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val model: String) : EngineState
    data class Ready(val model: String) : EngineState
    data class Busy(val model: String) : EngineState
    data class Failed(val message: String) : EngineState
}

class GenerationException(message: String) : Exception(message)

/** Result of the GPU speed test; [gpu] is null when there is no usable GPU. */
data class SpeedTest(val gpuName: String, val cpu: GpuBench.Run, val gpu: GpuBench.Run?, val verdict: GpuBench.Verdict?)

/**
 * On-device LLM via llama.cpp. All native calls run on one dedicated thread. The model is
 * loaded on first use and unloaded after [IDLE_UNLOAD_MS] so it doesn't hold ~1 GB of RAM
 * while the app sits in the background.
 *
 * [gpuWanted]: the user's GPU setting. A GPU that fails to load or generate falls back to the CPU;
 * one that crashed the app (the native driver can abort the process) is switched off on next start.
 */
class LlamaEngine(
    private val scope: CoroutineScope,
    private val context: Context,
    private val gpuWanted: () -> Boolean,
) {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
    private val dispatcher = thread.asCoroutineDispatcher()

    private var handle = 0L
    private var loadedPath: String? = null
    private var loadedGpu = false
    private var backendReady = false
    private var unloadJob: Job? = null
    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)

    /** The crash guard is set (see [ensureLoaded]). */
    private var gpuInflight = false

    private fun gpuProven() {
        if (!gpuInflight) return
        gpuInflight = false
        prefs.edit().putBoolean(KEY_GPU_INFLIGHT, false).apply()
    }

    /** GPU failed this run (load or generation error): stay on the CPU until the app restarts. */
    @Volatile private var gpuFailed = false

    /** True when the previous run died while using the GPU. Read once by the app to turn the setting off. */
    val gpuCrashedLastRun: Boolean = prefs.getBoolean(KEY_GPU_INFLIGHT, false).also {
        if (it) prefs.edit().putBoolean(KEY_GPU_INFLIGHT, false).apply()
    }

    private val _onGpu = MutableStateFlow(false)
    /** The loaded model runs on the GPU. */
    val onGpu: StateFlow<Boolean> = _onGpu.asStateFlow()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Streams generated text chunks. Cancelling the collector stops generation. */
    fun generate(model: File, prompt: Prompt, nThreads: Int, nCtx: Int): Flow<String> = callbackFlow {
        val cancelled = AtomicBoolean(false)
        val job = launch(dispatcher) {
            try {
                unloadJob?.cancel()
                ensureLoaded(model, gpuWanted() && !gpuFailed)
                _state.value = EngineState.Busy(model.name)
                val started = System.currentTimeMillis()
                var sentAny = false
                val run = {
                    runNative(prompt, nThreads, nCtx) { text ->
                        sentAny = true
                        trySend(text)
                        !cancelled.get()
                    }
                }
                var n = run()
                if (n < 0 && loadedGpu && !sentAny && !cancelled.get()) {
                    // GPU trouble (out of GPU memory, a kernel the driver rejects…): retry on the CPU.
                    Log.w(TAG, "GPU generation failed (${LlamaNative.lastError()}); retrying on the CPU")
                    gpuFailed = true
                    ensureLoaded(model, false)
                    n = run()
                }
                Log.i(TAG, "slot ${prompt.cacheSlot} (${if (loadedGpu) "GPU" else "CPU"}): reused ${LlamaNative.lastReused(handle, prompt.cacheSlot)} cached tokens, generated $n in ${System.currentTimeMillis() - started} ms")
                _state.value = EngineState.Ready(model.name)
                if (n < 0) throw GenerationException(LlamaNative.lastError())
                if (loadedGpu) gpuProven()
                close()
            } catch (e: Throwable) {
                if (_state.value !is EngineState.Failed) _state.value = if (handle != 0L) EngineState.Ready(model.name) else EngineState.Failed(e.message ?: "error")
                close(e)
            } finally {
                scheduleUnload()
            }
        }
        awaitClose {
            cancelled.set(true)
            job.cancel()
        }
    }.buffer(Channel.UNLIMITED) // never drop chunks if the UI collector is slow

    /** Runs a prompt to completion and returns the full text. */
    suspend fun complete(model: File, prompt: Prompt, nThreads: Int, nCtx: Int): String {
        val sb = StringBuilder()
        generate(model, prompt, nThreads, nCtx).collect { sb.append(it) }
        return sb.toString()
    }

    suspend fun unload() = withContext(dispatcher) { unloadNow() }

    /** The GPU llama.cpp can use ("" if none). */
    suspend fun gpuName(): String = withContext(dispatcher) {
        initBackend()
        LlamaNative.gpuName()
    }

    /**
     * Runs [GpuBench.prompt] on the CPU, then on the GPU, and compares speed and answers. Leaves
     * the model unloaded; the next request loads it per the (possibly changed) setting.
     */
    suspend fun speedTest(model: File, nThreads: Int, nCtx: Int): SpeedTest = withContext(dispatcher) {
        unloadJob?.cancel()
        initBackend()
        gpuFailed = false // a fresh chance: the test itself shows whether the GPU works
        val gpuName = LlamaNative.gpuName()
        val prompt = GpuBench.prompt().copy(cacheSlot = 0)
        try {
            unloadNow() // measure from a fresh load: nothing reused from earlier requests
            ensureLoaded(model, false)
            val cpu = benchRun(prompt, nThreads, nCtx)
            var gpu: GpuBench.Run? = null
            if (gpuName.isNotEmpty()) {
                ensureLoaded(model, true)
                if (loadedGpu) {
                    benchRun(prompt.copy(maxTokens = 4), nThreads, nCtx) // warm-up: first run sets up GPU kernels
                    // Measure a fresh prompt, not one already in the KV cache.
                    unloadNow()
                    ensureLoaded(model, true)
                    gpu = benchRun(prompt, nThreads, nCtx)
                    gpuProven()
                }
            }
            SpeedTest(gpuName, cpu, gpu, gpu?.let { GpuBench.verdict(cpu, it) })
        } finally {
            unloadNow()
        }
    }

    private fun benchRun(prompt: Prompt, nThreads: Int, nCtx: Int): GpuBench.Run {
        val sb = StringBuilder()
        val n = runNative(prompt, nThreads, nCtx) { sb.append(it); true }
        if (n < 0) throw GenerationException(LlamaNative.lastError())
        val t = LlamaNative.lastTimings(handle, prompt.cacheSlot)
        return GpuBench.Run(t[0].toInt(), t[1], t[2].toInt(), t[3], sb.toString().trim())
    }

    private fun runNative(prompt: Prompt, nThreads: Int, nCtx: Int, onText: (String) -> Boolean): Int = LlamaNative.generate(
        handle,
        prompt.cacheSlot,
        prompt.system.toByteArray(Charsets.UTF_8),
        prompt.user.toByteArray(Charsets.UTF_8),
        nCtx,
        nThreads,
        prompt.maxTokens,
        prompt.temperature,
        prompt.grammar?.toByteArray(Charsets.UTF_8),
    ) { bytes -> onText(String(bytes, Charsets.UTF_8)) }

    private fun initBackend() {
        if (backendReady) return
        val kernels = File(context.cacheDir, "gpu-kernels").apply { mkdirs() }
        LlamaNative.backendInit(kernels.absolutePath)
        backendReady = true
    }

    private fun ensureLoaded(model: File, gpu: Boolean) {
        initBackend()
        if (handle != 0L && loadedPath == model.absolutePath && loadedGpu == gpu) return
        unloadNow()
        if (!model.exists()) throw GenerationException("Model file missing: ${model.name}")
        _state.value = EngineState.Loading(model.name)
        // Set while the GPU is in use until its first good answer: if the driver kills the process
        // in between, the next start sees it and turns the GPU setting off.
        if (gpu && !gpuInflight) {
            gpuInflight = true
            prefs.edit().putBoolean(KEY_GPU_INFLIGHT, true).commit()
        }
        val h = LlamaNative.loadModel(model.absolutePath, gpu)
        if (h == 0L) {
            val msg = LlamaNative.lastError().ifBlank { "Could not load ${model.name}" }
            _state.value = EngineState.Failed(msg)
            throw GenerationException(msg)
        }
        handle = h
        loadedPath = model.absolutePath
        loadedGpu = gpu && LlamaNative.usesGpu(h)
        if (gpu && !loadedGpu) {
            gpuFailed = true
            gpuProven() // nothing runs on the GPU, so nothing to guard
        }
        _onGpu.value = loadedGpu
        _state.value = EngineState.Ready(model.name)
    }

    private fun unloadNow() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
            loadedPath = null
            loadedGpu = false
            _onGpu.value = false
        }
        _state.value = EngineState.Idle
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            withContext(dispatcher) { unloadNow() }
        }
    }

    companion object {
        private const val TAG = "LlamaEngine"
        private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L
        private const val KEY_GPU_INFLIGHT = "gpuInflight"
    }
}
