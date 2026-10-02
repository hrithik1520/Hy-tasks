package com.hy.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hy.assistant.MainViewModel
import com.hy.assistant.models.TransferState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val models = vm.models
    val installed by models.installed.collectAsState()
    val transfer by models.transfer.collectAsState()
    val settings by vm.settings.data.collectAsState()
    val speed by vm.speed.collectAsState()
    var customUrl by rememberSaveable { mutableStateOf("") }
    val busy = transfer is TransferState.Downloading || transfer is TransferState.Importing

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) models.import(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI models") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Speed test", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = vm::runSpeedTest, enabled = settings.activeModel != null) { Text("Run") }
                    }
                    Text(
                        speed ?: "Times the selected model on this phone: a command, a cached command and a WhatsApp reply.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Text(
                    "Models run fully on your phone with llama.cpp. Download once on Wi-Fi; after that everything works offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val t = transfer) {
                is TransferState.Downloading -> item {
                    SectionCard {
                        Text("Downloading ${t.fileName}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.padding(4.dp))
                        val progress = t.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${t.downloadedMb} MB", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = models::cancelDownload) { Text("Cancel") }
                        }
                    }
                }
                is TransferState.Importing -> item {
                    SectionCard {
                        Text("Importing ${t.fileName}… ${t.copiedMb} MB", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is TransferState.Error -> item {
                    SectionCard {
                        Text(t.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = models::dismissError) { Text("OK") }
                    }
                }
                TransferState.None -> Unit
            }

            if (installed.isNotEmpty()) {
                item { Text("Installed", style = MaterialTheme.typography.titleSmall) }
                items(installed, key = { it.fileName }) { m ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.activeModel == m.fileName, onClick = { models.setActive(m.fileName) })
                            Text(m.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${m.sizeMb} MB", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { models.delete(m.fileName) }) { Text("Delete") }
                        }
                    }
                }
            }

            item { Text("Download", style = MaterialTheme.typography.titleSmall) }
            items(models.catalog, key = { it.id }) { m ->
                val have = installed.any { it.fileName == m.fileName }
                SectionCard {
                    Text(m.title, style = MaterialTheme.typography.titleSmall)
                    Text(m.note, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "~${m.sizeMb} MB · ${m.license}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Button(onClick = { models.download(m) }, enabled = !have && !busy) {
                        Text(if (have) "Installed" else "Download")
                    }
                }
            }

            item {
                SectionCard {
                    Text("Other options", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Import a .gguf you already downloaded, or paste a direct .gguf link (e.g. from Hugging Face).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import .gguf file") }
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…/model.gguf") },
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            enabled = !busy && customUrl.startsWith("https://"),
                            onClick = {
                                val name = customUrl.substringAfterLast('/').substringBefore('?')
                                models.downloadUrl(customUrl.trim(), name)
                            },
                        ) { Text("Download link") }
                    }
                }
            }
            item { Spacer(Modifier.width(1.dp)) }
        }
    }
}
