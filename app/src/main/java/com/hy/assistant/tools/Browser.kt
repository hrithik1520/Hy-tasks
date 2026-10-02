package com.hy.assistant.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the in-app browser, so the assistant can open pages and the screen can be re-entered. */
object Browser {
    const val HOME = "https://duckduckgo.com/"

    /** URL the assistant (or user) asked to load; consumed by the WebView. */
    private val _request = MutableStateFlow<String?>(null)
    val request: StateFlow<String?> = _request.asStateFlow()

    /** Last page the WebView finished loading. */
    @Volatile
    var currentUrl: String = HOME

    fun open(url: String) {
        _request.value = url
    }

    fun consume(): String? = _request.value.also { _request.value = null }
}
