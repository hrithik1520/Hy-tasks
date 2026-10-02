package com.hy.assistant.tools

import android.util.Log
import com.hy.assistant.core.SearchResult
import com.hy.assistant.core.Web
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Web search without an API key: Bing → DuckDuckGo → Wikipedia. Only the query leaves the phone. */
object WebSearch {
    private const val TAG = "WebSearch"
    private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 600_000

    data class Outcome(val results: List<SearchResult>, val topText: String?, val source: String = "")

    /** Blocking; call from Dispatchers.IO. */
    fun search(query: String, readTopPage: Boolean = true): Outcome {
        var source = "Bing"
        val results = attempt { Web.parseBing(get(Web.bingSearchUrl(query))) }
            ?: attempt { Web.parseDuckDuckGo(get(Web.ddgSearchUrl(query))) }.also { source = "DuckDuckGo" }
            ?: attempt { Web.parseWikipedia(get(Web.wikipediaSearchUrl(query))) }.also { source = "Wikipedia" }
            ?: emptyList()
        return Outcome(results, if (readTopPage) readTopPage(results) else null, source)
    }

    /** Reading the best page gives the model real facts instead of 2-line snippets. Blocking. */
    fun readTopPage(results: List<SearchResult>): String? = results.firstOrNull()?.let { r ->
        runCatching { Web.htmlToText(get(r.url, timeoutMs = 6000), 2500) }.getOrNull()?.takeIf { it.length > 200 }
    }

    /** Downloads a page as text (used by "read this page" outside the WebView too). */
    fun get(url: String, timeoutMs: Int = 8000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            val charset = Regex("charset=([\\w-]+)").find(conn.contentType.orEmpty())?.groupValues?.get(1) ?: "UTF-8"
            return String(out.toByteArray(), runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    private fun attempt(block: () -> List<SearchResult>): List<SearchResult>? =
        try {
            block().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "search source failed: ${e.message}")
            null
        }
}
