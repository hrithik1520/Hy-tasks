package com.hy.assistant.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hy.assistant.MainViewModel
import com.hy.assistant.core.Web
import com.hy.assistant.tools.Browser
import org.json.JSONTokener

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val output by vm.output.collectAsState()
    val request by Browser.request.collectAsState()
    var address by rememberSaveable { mutableStateOf(Browser.currentUrl) }
    var question by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    fun load(input: String) {
        val url = Web.browseUrl(input)
        address = url
        webView?.loadUrl(url)
    }

    // Pages the assistant asked to open.
    LaunchedEffect(request, webView) {
        if (webView != null) Browser.consume()?.let { load(it) }
    }

    fun ask() {
        val wv = webView ?: return
        val q = question
        question = ""
        wv.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { raw ->
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            vm.askAboutPage(q, wv.url ?: address, text)
        }
    }

    BackHandler {
        if (canGoBack) webView?.goBack() else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close browser") }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search or type a URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { load(address) }),
                )
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                                val scheme = req.url.scheme.orEmpty()
                                if (scheme == "http" || scheme == "https") return false
                                // intent:, tel:, mailto:, market: … hand off to other apps.
                                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, req.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                Browser.currentUrl = url
                                address = url
                                canGoBack = view.canGoBack()
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(Browser.consume()?.let { Web.browseUrl(it) } ?: Browser.currentUrl)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            output?.let { o ->
                Column(Modifier.padding(horizontal = 8.dp)) {
                    OutputCard(o, vm::dismissOutput, onOpenLink = { load(it) }, onSave = { f -> vm.exportText(o.text, o.title, f) })
                }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Ask about this page (empty = summarize)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ask() }),
                )
                IconButton(onClick = { ask() }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask") }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = {
                    val url = webView?.url ?: address
                    runCatching { webView?.context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open in browser app") }
            }
        }
    }
}
