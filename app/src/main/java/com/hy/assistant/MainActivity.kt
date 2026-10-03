package com.hy.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.tools.GoogleWebSearcher
import com.hy.assistant.ui.BrowserScreen
import com.hy.assistant.ui.ChatScreen
import com.hy.assistant.ui.TerminalScreen
import com.hy.assistant.ui.HomeScreen
import com.hy.assistant.ui.HyTheme
import com.hy.assistant.ui.ModelsScreen
import com.hy.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { HyTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN_CHAT)?.let { vm.requestOpenChat(it) }
        intent?.getStringExtra(EXTRA_SCREEN_LABEL)?.let { label ->
            vm.onScreen(intent.getStringExtra(EXTRA_SCREEN_TEXT).orEmpty(), label, intent.getStringExtra(EXTRA_SCREEN_ERROR))
            intent.removeExtra(EXTRA_SCREEN_LABEL)
        }
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!text.isNullOrBlank()) vm.onShared(text, subject)
            intent.action = null // don't re-handle on rotation
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_SCREEN_TEXT = "screen_text"
        const val EXTRA_SCREEN_LABEL = "screen_label"
        const val EXTRA_SCREEN_ERROR = "screen_error"
    }
}

/** Routes: "home", "models", "settings", "chat:<key>". */
@Composable
private fun App(vm: MainViewModel) {
    var route by rememberSaveable { mutableStateOf("home") }
    val snackbar = remember { SnackbarHostState() }
    val openChat by vm.openChat.collectAsState()
    val requestedRoute by vm.route.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openChat) {
        openChat?.let {
            route = "chat:$it"
            vm.consumeOpenChat()
        }
    }

    LaunchedEffect(requestedRoute) {
        requestedRoute?.let {
            route = it
            vm.consumeRoute()
        }
    }

    BackHandler(enabled = route != "home" && route != "browser") { route = "home" }
    val back = { route = "home" }

    // Google asked "I'm not a robot": show its page so the user can solve it.
    val challenge by GoogleWebSearcher.challenge.collectAsState()
    challenge?.let { wv -> CaptchaDialog(wv) }

    when {
        route == "models" -> ModelsScreen(vm, snackbar, back)
        route == "settings" -> SettingsScreen(vm, snackbar, back)
        route == "browser" -> BrowserScreen(vm, snackbar, back)
        route == "terminal" -> TerminalScreen(vm, snackbar, back)
        route.startsWith("chat:") -> {
            val key = route.removePrefix("chat:")
            LaunchedEffect(key) { MessageStore.markRead(key) }
            ChatScreen(vm, key, snackbar, back)
        }
        else -> HomeScreen(
            vm = vm,
            snackbar = snackbar,
            openChat = { route = "chat:$it" },
            openModels = { route = "models" },
            openSettings = { route = "settings" },
            openBrowser = { route = "browser" },
            openTerminal = { route = "terminal" },
        )
    }
}

@Composable
private fun CaptchaDialog(webView: WebView) {
    Dialog(
        onDismissRequest = GoogleWebSearcher::giveUp,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f)) {
            Column(Modifier.padding(12.dp)) {
                Text("Google check", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Google wants to make sure you're human. Solve it below — Alfrid continues automatically once it's done.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AndroidView(
                    factory = { (webView.parent as? ViewGroup)?.removeView(webView); webView },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = GoogleWebSearcher::giveUp) { Text("Use Bing instead") }
                }
            }
        }
    }
}
