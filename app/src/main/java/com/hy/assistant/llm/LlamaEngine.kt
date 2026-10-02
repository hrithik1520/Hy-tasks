package com.hy.assistant.llm

import android.util.Log
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

/**
 * On-device LLM via llama.cpp. All native calls run on one dedicated thread. The model is
 * loaded on first use and unloaded after [IDLE_UNLOAD_MS] so it doesn't hold ~1 GB of RAM
 * while the app sits in the background.
 */
class LlamaEngine(private val scope: CoroutineScope) {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
    private val dispatcher = thread.asCoroutineDispatcher()

    private var handle = 0L
    private var loadedPath: String? = null
    private var backendReady = false
    private var unloadJob: Job? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Streams generated text chunks. Cancelling the collector stops generation. */
    fun generate(model: File, prompt: Prompt, nThreads: Int, nCtx: Int): Flow<String> = callbackFlow {
        val cancelled = AtomicBoolean(false)
        val job = launch(dispatcher) {
            try {
                unloadJob?.cancel()
                ensureLoaded(model)
                _state.value = EngineState.Busy(model.name)
                val started = System.currentTimeMillis()
                val n = LlamaNative.generate(
                    handle,
                    prompt.system.toByteArray(Charsets.UTF_8),
                    prompt.user.toByteArray(Charsets.UTF_8),
                    nCtx,
                    nThreads,
                    prompt.maxTokens,
                    prompt.temperature,
                    prompt.grammar?.toByteArray(Charsets.UTF_8),
                ) { bytes ->
                    trySend(String(bytes, Charsets.UTF_8))
                    !cancelled.get()
                }
                Log.i(TAG, "generated $n tokens in ${System.currentTimeMillis() - started} ms")
                _state.value = EngineState.Ready(model.name)
                if (n < 0) throw GenerationException(LlamaNative.lastError())
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

    private fun ensureLoaded(model: File) {
        if (!backendReady) {
            LlamaNative.backendInit()
            backendReady = true
        }
        if (handle != 0L && loadedPath == model.absolutePath) return
        unloadNow()
        if (!model.exists()) throw GenerationException("Model file missing: ${model.name}")
        _state.value = EngineState.Loading(model.name)
        val h = LlamaNative.loadModel(model.absolutePath)
        if (h == 0L) {
            val msg = LlamaNative.lastError().ifBlank { "Could not load ${model.name}" }
            _state.value = EngineState.Failed(msg)
            throw GenerationException(msg)
        }
        handle = h
        loadedPath = model.absolutePath
        _state.value = EngineState.Ready(model.name)
    }

    private fun unloadNow() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
            loadedPath = null
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
    }
}
