package com.hy.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hy.assistant.notifications.MessageStore
import com.hy.assistant.ui.ChatScreen
import com.hy.assistant.ui.HomeScreen
import com.hy.assistant.ui.HyTheme
import com.hy.assistant.ui.ModelsScreen
import com.hy.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HyTheme { App(vm) } }
    }
}

/** Routes: "home", "models", "settings", "chat:<key>". */
@Composable
private fun App(vm: MainViewModel) {
    var route by rememberSaveable { mutableStateOf("home") }
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    BackHandler(enabled = route != "home") { route = "home" }
    val back = { route = "home" }

    when {
        route == "models" -> ModelsScreen(vm, snackbar, back)
        route == "settings" -> SettingsScreen(vm, snackbar, back)
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
        )
    }
}
