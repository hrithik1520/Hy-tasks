package com.hy.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hy.assistant.MainViewModel
import com.hy.assistant.core.Tone
import com.hy.assistant.notifications.MessageStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val s by vm.settings.data.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text("About you", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = s.userName,
                    onValueChange = { v -> vm.settings.update { it.copy(userName = v) } },
                    label = { Text("Your name (used when drafting replies)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(4.dp))
                Text("Reply tone", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tone.entries.forEach { t ->
                        FilterChip(
                            selected = s.tone == t,
                            onClick = { vm.settings.update { it.copy(tone = t) } },
                            label = { Text(t.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }
                ToggleRow("Polish my replies with AI", "\"Reply to X saying …\" gets rewritten naturally. Off = sends your exact words.", s.polishReplies) { v ->
                    vm.settings.update { it.copy(polishReplies = v) }
                }
            }

            SectionCard {
                Text("WhatsApp", style = MaterialTheme.typography.titleSmall)
                ToggleRow("Include WhatsApp Business", null, s.includeBusiness) { v ->
                    vm.settings.update { it.copy(includeBusiness = v) }
                }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { MessageStore.clearAll() }) { Text("Clear stored messages") }
                Text(
                    "Messages are kept only on this phone for 3 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Text("Performance", style = MaterialTheme.typography.titleSmall)
                Text("CPU threads: ${s.threads}", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4, 6).forEach { n ->
                        FilterChip(selected = s.threads == n, onClick = { vm.settings.update { it.copy(threads = n) } }, label = { Text("$n") })
                    }
                }
                Text(
                    "4 is best on Nothing Phone (3a) Pro (uses the fast cores).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Text("Context size: ${s.contextSize} tokens", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096).forEach { n ->
                        FilterChip(selected = s.contextSize == n, onClick = { vm.settings.update { it.copy(contextSize = n) } }, label = { Text("$n") })
                    }
                }
            }

            Text(
                "Hy Assistant · all AI runs on-device · no account, no cloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
