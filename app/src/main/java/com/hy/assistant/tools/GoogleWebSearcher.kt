package com.hy.assistant.tools

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hy.assistant.core.SearchResult
import com.hy.assistant.core.Web
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Google results read from a hidden in-app browser, the way a person would see them: load the
 * results page, then run assets/google_extract.js on it (like Inspect). If Google shows its
 * "I'm not a robot" check, the same browser is shown in a popup so the user can solve it.
 */
object GoogleWebSearcher {
    private const val TAG = "GoogleSearch"
    private const val LOAD_TIMEOUT_MS = 15_000L
    private const val HUMAN_TIMEOUT_MS = 3 * 60_000L
    private const val MIN_GAP_MS = 3_000L

    /** Non-null while Google wants a human check; the UI shows this WebView in a popup. */
    private val _challenge = MutableStateFlow<WebView?>(null)
    val challenge: StateFlow<WebView?> = _challenge.asStateFlow()

    private var webView: WebView? = null
    private var script: String? = null
    private var pageLoaded: CompletableDeferred<String>? = null
    private var solved: CompletableDeferred<Boolean>? = null
    private var lastSearchAt = 0L
    private val lock = Mutex()

    /** Returns results, or null to fall back to another engine (blocked, timeout, layout changed). */
    suspend fun search(context: Context, query: String): List<SearchResult>? = lock.withLock {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastSearchAt)
        if (wait > 0) delay(wait) // be gentle: never hammer Google
        lastSearchAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            val wv = ensureWebView(context)
            if (!load(wv, Web.googleSearchUrl(query))) return@withContext null
            var page = extract(context, wv) ?: return@withContext null
            if (page.optBoolean("blocked")) {
                Log.i(TAG, "Google asked for a human check")
                if (!askHuman(wv)) return@withContext null
                delay(1200) // let the results render after the redirect
                page = extract(context, wv) ?: return@withContext null
                if (page.optBoolean("blocked")) return@withContext null
            }
            parse(page).takeIf { it.isNotEmpty() }
        }
    }

    /** User tapped "Use Bing instead" (or closed the popup). */
    fun giveUp() {
        solved?.complete(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context): WebView = webView ?: WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded?.complete(url)
                // After the captcha, Google redirects back to the results page.
                if (solved != null && url.contains("/search") && !url.contains("/sorry/")) solved?.complete(true)
            }
        }
        // Off-screen views have no size; give it a phone-sized layout so the page renders normally.
        val dm = context.resources.displayMetrics
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, dm.widthPixels, dm.heightPixels)
        webView = this
    }

    private suspend fun load(wv: WebView, url: String): Boolean {
        val done = CompletableDeferred<String>()
        pageLoaded = done
        wv.loadUrl(url)
        val ok = withTimeoutOrNull(LOAD_TIMEOUT_MS) { done.await() } != null
        pageLoaded = null
        if (ok) delay(700) // results are rendered by script after load
        return ok
    }

    private suspend fun askHuman(wv: WebView): Boolean {
        val d = CompletableDeferred<Boolean>()
        solved = d
        _challenge.value = wv
        try {
            return withTimeoutOrNull(HUMAN_TIMEOUT_MS) { d.await() } == true
        } finally {
            solved = null
            _challenge.value = null
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
    }

    private suspend fun extract(context: Context, wv: WebView): JSONObject? {
        val js = script ?: context.assets.open("google_extract.js").bufferedReader().use { it.readText() }.also { script = it }
        val raw = CompletableDeferred<String>()
        wv.evaluateJavascript(js) { raw.complete(it ?: "null") }
        val value = withTimeoutOrNull(5_000) { raw.await() } ?: return null
        // evaluateJavascript returns the JSON string as a JS string literal.
        val json = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull() ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private fun parse(page: JSONObject): List<SearchResult> {
        val arr = page.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("t").trim()
            val url = o.optString("u")
            if (title.isEmpty() || !url.startsWith("http")) null
            else SearchResult(title, url, Web.cleanGoogleSnippet(title, o.optString("s")))
        }
    }
}
