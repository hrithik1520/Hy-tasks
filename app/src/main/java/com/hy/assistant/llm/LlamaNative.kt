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

    external fun backendInit()
    external fun loadModel(path: String): Long
    external fun freeModel(handle: Long)
    external fun generate(
        handle: Long,
        system: ByteArray,
        user: ByteArray,
        nCtx: Int,
        nThreads: Int,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback,
    ): Int
    external fun lastError(): String
}
