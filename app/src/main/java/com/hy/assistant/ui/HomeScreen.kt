package com.hy.assistant.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.hy.assistant.core.Web
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hy.assistant.MainViewModel
import com.hy.assistant.ReplyMode
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.llm.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    snackbar: SnackbarHostState,
    openChat: (String) -> Unit,
    openModels: () -> Unit,
    openSettings: () -> Unit,
    openBrowser: () -> Unit,
    openTerminal: () -> Unit,
) {
    val context = LocalContext.current
    val chats by vm.chats.collectAsState()
    val feed by vm.feed.collectAsState()
    val activity by vm.activity.collectAsState()
    val listenerEnabled by vm.listenerEnabled.collectAsState()
    val canNotify by vm.canNotify.collectAsState()
    val settings by vm.settings.data.collectAsState()
    val installed by vm.models.installed.collectAsState()
    val engineState by vm.engine.state.collectAsState()
    val output by vm.output.collectAsState()
    val proposal by vm.proposal.collectAsState()
    val disambiguation by vm.disambiguation.collectAsState()
    var command by rememberSaveable { mutableStateOf("") }
    val turns by vm.turns.collectAsState()
    val pendingRequest by vm.pendingRequest.collectAsState()
    val threaded by vm.threadedOutput.collectAsState()
    var showAllTurns by rememberSaveable { mutableStateOf(false) }
    val agentRun by vm.agentRun.collectAsState()
    val agentMode by vm.agentMode.collectAsState()
    val savedFile by vm.savedFile.collectAsState()
    val shared by vm.shared.collectAsState()
    var showAllFeed by rememberSaveable { mutableStateOf(false) }
    var askedNotify by rememberSaveable { mutableStateOf(false) }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshStatus() }
    // Ask once for permission to show reply suggestions (Android 13+).
    LaunchedEffect(canNotify) {
        if (!canNotify && !askedNotify && Build.VERSION.SDK_INT >= 33) {
            askedNotify = true
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                            "Hy reads your notifications on this phone to summarize and reply. Nothing leaves the device.",
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
                            "One-time ~1.1 GB download. Needed for suggestions and auto-replies.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = openModels) { Text("Choose model") }
                    }
                }
            }
            if (!canNotify && Build.VERSION.SDK_INT >= 33) {
                item {
                    SectionCard {
                        Text("Allow Hy's notifications", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Needed to show suggested replies with a Send button, and auto-reply alerts.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                    }
                }
            }

            // ---- Mode switch -----------------------------------------------------------
            item {
                SectionCard {
                    Text("Reply mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(4.dp))
                    val modes = listOf(ReplyMode.MANUAL to "Manual", ReplyMode.AUTO to "Auto")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = settings.replyMode == mode,
                                onClick = { vm.setReplyMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                            ) { Text(label) }
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        if (settings.replyMode == ReplyMode.AUTO) {
                            "Hy replies by itself ${if (settings.autoSendDelaySec > 0) "after ${settings.autoSendDelaySec}s (tap Cancel to stop)" else "immediately"}. " +
                                "Never for OTPs, money, passwords or emergencies" +
                                (if (!settings.autoReplyGroups) ", or group chats" else "") + " — those come to you as suggestions."
                        } else if (settings.proactiveSuggestions) {
                            "Hy drafts a reply for every new message and shows it as a notification. Tap Send — no need to open the app."
                        } else {
                            "Hy only drafts replies when you ask."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasModel) {
                        val label = when (val s = engineState) {
                            EngineState.Idle -> "AI ready · ${settings.activeModel}"
                            is EngineState.Loading -> "Loading ${s.model}…"
                            is EngineState.Ready -> "AI loaded · ${s.model}"
                            is EngineState.Busy -> "Thinking · ${s.model}"
                            is EngineState.Failed -> "AI error: ${s.message}"
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (engineState is EngineState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            shared?.let { sh -> item { SharedCard(sh, vm::askAboutShared, vm::dismissShared) } }

            // ---- Conversation with Hy ---------------------------------------------------
            if (turns.isNotEmpty() || pendingRequest != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Conversation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::newChat) { Text("New chat") }
                    }
                }
                val hidden = if (showAllTurns) 0 else (turns.size - 6).coerceAtLeast(0)
                if (hidden > 0) {
                    item { TextButton(onClick = { showAllTurns = true }) { Text("Show $hidden earlier") } }
                }
                turns.drop(hidden).forEachIndexed { i, t ->
                    val isLast = hidden + i == turns.lastIndex
                    item(key = "turn-${t.timestamp}-$i") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Bubble(t.user, fromUser = true)
                            Bubble(t.assistant, fromUser = false)
                            SaveRow { f -> vm.exportText(t.assistant, t.user, f) }
                            // Sources of the latest web answer stay tappable.
                            val links = if (isLast && threaded != null && threaded === output) output?.links.orEmpty() else emptyList()
                            links.forEach { link ->
                                Text(
                                    "• ${link.title} — ${Web.host(link.url)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    modifier = Modifier.clickable { vm.openInBrowser(link.url) }.padding(start = 8.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
                pendingRequest?.let { req -> item { Bubble(req, fromUser = true) } }
            }

            agentRun?.let { r ->
                item { AgentCard(r, vm.agents::approve, vm.agents::skip, vm::stopAgent, vm::closeAgent, vm::openSaved, vm::shareSaved) }
            }
            savedFile?.let { f ->
                item { SavedFileBar(f, { vm.openSaved(f) }, { vm.shareSaved(f) }, vm::dismissSaved) }
            }
            disambiguation?.let { d -> item { DisambiguationCard(d, vm::chooseCandidate, vm::dismissDisambiguation) } }
            proposal?.let { p ->
                item { ProposalCard(p, vm::editProposal, vm::confirmSend, vm::copyAndOpenWhatsApp, vm::dismissProposal) }
            }
            // A finished answer already appears in the conversation; only show the card while working.
            output?.takeIf { it !== threaded }?.let { o ->
                item { OutputCard(o, vm::dismissOutput, onOpenLink = vm::openInBrowser, onSave = { f -> vm.exportText(o.text, o.title, f) }) }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when {
                                    agentMode -> "Give the agent a multi-step task…"
                                    turns.isEmpty() -> "Ask or tell Hy anything…"
                                    else -> "Ask a follow-up…"
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                    IconButton(onClick = submit) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run") }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = agentMode,
                        onClick = { vm.setAgentMode(!agentMode) },
                        label = { Text(if (agentMode) "Agent mode on" else "Agent mode") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { vm.runCommand("What did I miss?") }) { Text("What did I miss?") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = openBrowser) { Text("Browser") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = openTerminal) { Text("Terminal") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = openModels) { Text("Models") }
                }
            }

            // ---- What Hy did on its own ----------------------------------------------
            if (activity.isNotEmpty()) {
                item { Header("Hy activity") }
                items(activity.take(5)) { e -> ActivityRow(e) }
            }

            // ---- Chats -----------------------------------------------------------------
            item { Header("Chats (last 3 days)") }
            if (chats.isEmpty()) {
                item {
                    Text(
                        if (listenerEnabled) "No messages yet. New chat notifications will show up here automatically."
                        else "Allow notification access to see messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(chats, key = { "chat-" + it.key }) { chat -> ChatRow(chat) { openChat(chat.key) } }

            // ---- Everything else -------------------------------------------------------
            if (feed.isNotEmpty()) {
                item { Header("Other notifications (24 h)") }
                items(if (showAllFeed) feed else feed.take(6), key = { "feed-" + it.id }) { f ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${f.category.label} · ${f.appName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(timeLabel(f.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (f.title.isNotBlank()) Text(f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                        if (f.text.isNotBlank()) Text(f.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                if (feed.size > 6) {
                    item {
                        TextButton(onClick = { showAllFeed = !showAllFeed }) {
                            Text(if (showAllFeed) "Show less" else "Show all ${feed.size}")
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

/** Text or a link shared to Hy from another app, with one-tap tasks. */
@Composable
private fun SharedCard(sh: MainViewModel.Shared, onAsk: (String) -> Unit, onDismiss: () -> Unit) {
    var question by rememberSaveable(sh) { mutableStateOf("") }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shared with Hy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("✕") }
        }
        Text(sh.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (sh.url == null) Text(sh.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            listOf(
                "Summarize" to "Summarize this in 5 short bullet points.",
                "Explain simply" to "Explain this in simple words, like to a friend.",
                "Key facts" to "List the key facts, numbers and dates.",
                "Is it true?" to "Point out claims here that look doubtful or need checking.",
                "Reply ideas" to "Suggest 3 short replies I could send about this.",
            ).forEach { (label, task) ->
                AssistChip(onClick = { onAsk(task) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Ask anything about it…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onAsk(question); question = "" }),
            )
            IconButton(onClick = { onAsk(question); question = "" }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask") }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        if (fromUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun ActivityRow(e: ActivityLog.Entry) {
    val color = when (e.kind) {
        ActivityLog.Kind.AUTO_SENT, ActivityLog.Kind.SENT -> MaterialTheme.colorScheme.primary
        ActivityLog.Kind.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text("${e.kind.label} · ${e.chatName}", style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.weight(1f), maxLines = 1)
            Text(timeLabel(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(e.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        e.note?.let { Text("Reason: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
