package com.hy.assistant.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hy.assistant.MainViewModel
import com.hy.assistant.llm.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    snackbar: SnackbarHostState,
    openChat: (String) -> Unit,
    openModels: () -> Unit,
    openSettings: () -> Unit,
) {
    val context = LocalContext.current
    val chats by vm.chats.collectAsState()
    val listenerEnabled by vm.listenerEnabled.collectAsState()
    val settings by vm.settings.data.collectAsState()
    val installed by vm.models.installed.collectAsState()
    val engineState by vm.engine.state.collectAsState()
    val output by vm.output.collectAsState()
    val proposal by vm.proposal.collectAsState()
    val disambiguation by vm.disambiguation.collectAsState()
    var command by rememberSaveable { mutableStateOf("") }

    val hasModel = settings.activeModel != null && installed.any { it.fileName == settings.activeModel }
    val submit = {
        if (command.isNotBlank()) {
            vm.runCommand(command)
            command = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hy Assistant") },
                actions = {
                    IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!listenerEnabled) {
                item {
                    SectionCard {
                        Text("Step 1 · Allow notification access", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Hy reads your WhatsApp notifications on this phone to summarize and reply. Nothing leaves the device.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Open settings") }
                    }
                }
            }
            if (!hasModel) {
                item {
                    SectionCard {
                        Text("Step 2 · Get the on-device AI model", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "One-time ~1.1 GB download. Without it you can still see messages and send your own replies.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = openModels) { Text("Choose model") }
                    }
                }
            } else {
                item {
                    val label = when (val s = engineState) {
                        EngineState.Idle -> "AI ready · ${settings.activeModel} (loads when needed)"
                        is EngineState.Loading -> "Loading ${s.model}…"
                        is EngineState.Ready -> "AI loaded · ${s.model}"
                        is EngineState.Busy -> "Thinking · ${s.model}"
                        is EngineState.Failed -> "AI error: ${s.message}"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (engineState is EngineState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g. Reply to Rahul saying I'm late") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                    IconButton(onClick = submit) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run") }
                }
            }
            item {
                Row {
                    FilledTonalButton(onClick = vm::digest) { Text("What did I miss?") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = openModels) { Text("Models") }
                }
            }

            disambiguation?.let { d -> item { DisambiguationCard(d, vm::chooseCandidate, vm::dismissDisambiguation) } }
            proposal?.let { p ->
                item { ProposalCard(p, vm::editProposal, vm::confirmSend, vm::copyAndOpenWhatsApp, vm::dismissProposal) }
            }
            output?.let { o -> item { OutputCard(o, vm::dismissOutput) } }

            item {
                Text(
                    "WhatsApp chats (last 3 days)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (chats.isEmpty()) {
                item {
                    Text(
                        if (listenerEnabled) "No messages yet. New WhatsApp notifications will show up here."
                        else "Allow notification access to see WhatsApp messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(chats, key = { it.key }) { chat -> ChatRow(chat) { openChat(chat.key) } }
        }
    }
}
