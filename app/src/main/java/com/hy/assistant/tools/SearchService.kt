package com.hy.assistant.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SearchEngine(val label: String) { BING("Bing"), GOOGLE("Google (via browser)") }

/** Picks the configured engine; anything that fails falls back to Bing → DuckDuckGo → Wikipedia. */
object SearchService {
    suspend fun search(context: Context, query: String, engine: SearchEngine): WebSearch.Outcome {
        if (engine == SearchEngine.GOOGLE) {
            val google = runCatching { GoogleWebSearcher.search(context, query) }.getOrNull()
            if (!google.isNullOrEmpty()) {
                val top = withContext(Dispatchers.IO) { WebSearch.readTopPage(google) }
                return WebSearch.Outcome(google, top, source = "Google")
            }
        }
        return withContext(Dispatchers.IO) { WebSearch.search(query) }
    }
}
