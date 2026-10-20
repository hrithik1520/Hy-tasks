package com.hy.assistant.llm

/** Receives UTF-8 text chunks from native generation. Return false to stop. */
fun interface TokenCallback {
    fun onBytes(bytes: ByteArray): Boolean
}

/** JNI bindings to app/src/main/cpp/jni_bridge.cpp. Not thread-safe: call from one thread. */
object LlamaNative {
    init {
        System.loadLibrary("hyllm")
    }

    /** [cacheDir]: where compiled GPU kernels are kept between runs. */
    external fun backendInit(cacheDir: String)

    /** The GPU llama.cpp can use (an Adreno GPU via OpenCL), or "" for none. Call after [backendInit]. */
    external fun gpuName(): String
    external fun loadModel(path: String, gpu: Boolean): Long
    external fun usesGpu(handle: Long): Boolean
    external fun freeModel(handle: Long)
    external fun generate(
        handle: Long,
        slot: Int,
        system: ByteArray,
        user: ByteArray,
        nCtx: Int,
        nThreads: Int,
        maxTokens: Int,
        temperature: Float,
        grammar: ByteArray?,
        callback: TokenCallback,
    ): Int
    external fun lastReused(handle: Long, slot: Int): Int

    /** [prompt tokens, prompt ms, generated tokens, generation ms] of the slot's last generate(). */
    external fun lastTimings(handle: Long, slot: Int): DoubleArray
    external fun lastError(): String
}
