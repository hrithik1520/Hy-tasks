package com.hy.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.hy.assistant.planner.CalendarRepo
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
    val confirm by vm.confirm.collectAsState()
    val ignoringBattery by vm.ignoringBattery.collectAsState()
    var command by rememberSaveable { mutableStateOf("") }
    val turns by vm.turns.collectAsState()
    val pendingRequest by vm.pendingRequest.collectAsState()
    val threaded by vm.threadedOutput.collectAsState()
    var showAllTurns by rememberSaveable { mutableStateOf(false) }
    val agentRun by vm.agentRun.collectAsState()
    val agentMode by vm.agentMode.collectAsState()
    val savedFile by vm.savedFile.collectAsState()
    val shared by vm.shared.collectAsState()
    val voice by vm.voice.collectAsState()
    val voiceRequest by vm.voiceRequest.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startVoice(it) }
    LaunchedEffect(voiceRequest) {
        if (voiceRequest) {
            if (vm.hasMicPermission()) vm.startVoice(true) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val needContacts by vm.needContacts.collectAsState()
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.retryPhone(it) }
    LaunchedEffect(needContacts) { if (needContacts != null) contactsPermission.launch(Manifest.permission.READ_CONTACTS) }
    val needCalendar by vm.needCalendar.collectAsState()
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> vm.retryPlan(r.values.all { it }) }
    LaunchedEffect(needCalendar) { if (needCalendar != null) calendarPermission.launch(CalendarRepo.PERMISSIONS) }
    val reminders by vm.reminders.collectAsState()
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

    val listState = rememberLazyListState()
    val working = pendingRequest != null || output?.running == true || agentRun?.running == true || proposal?.generating == true
    val orbMode = when (voice) {
        is MainViewModel.VoiceState.Listening -> OrbMode.LISTENING
        MainViewModel.VoiceState.Thinking -> OrbMode.THINKING
        is MainViewModel.VoiceState.Speaking -> OrbMode.SPEAKING
        MainViewModel.VoiceState.Idle -> if (working) OrbMode.THINKING else OrbMode.IDLE
    }
    val (orbTitle, orbSubtitle) = when (val v = voice) {
        is MainViewModel.VoiceState.Listening -> "Listening" to v.partial.ifBlank { "Go ahead, or say \"stop\"" }
        MainViewModel.VoiceState.Thinking -> "Thinking" to ""
        is MainViewModel.VoiceState.Speaking -> "Speaking" to v.text
        MainViewModel.VoiceState.Idle -> if (working) "Working on it" to "" else
            "Tap to talk" to (if (settings.wakeWordEnabled) "or say \"Hey Alfred\"" else "or type below")
    }
    val (aiLabel, aiDot, aiPulse) = when {
        !hasModel -> Triple("No AI model", MaterialTheme.colorScheme.onSurfaceVariant, false)
        engineState is EngineState.Failed -> Triple("AI error", MaterialTheme.colorScheme.error, false)
        engineState is EngineState.Loading -> Triple("Loading AI", NothingRed, true)
        engineState is EngineState.Busy -> Triple("Thinking", NothingRed, true)
        else -> Triple("AI ready", MaterialTheme.colorScheme.onSurface, false)
    }
    val modeHint = when {
        settings.replyMode == ReplyMode.AUTO ->
            "Replies by itself" + (if (settings.autoSendDelaySec > 0) " after ${settings.autoSendDelaySec}s" else "") +
                ". Never for codes, money, passwords or emergencies."
        settings.proactiveSuggestions -> "Drafts a reply for each new message. Tap Send in the notification."
        else -> "Drafts replies only when you ask."
    }
    val talk = { if (voice != MainViewModel.VoiceState.Idle) vm.stopVoice() else vm.requestVoice() }
    // A new request: scroll up so the orb and the answer are in view.
    LaunchedEffect(pendingRequest) { if (pendingRequest != null) listState.animateScrollToItem(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // the header handles the status bar
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            InputBar(
                value = command,
                onValueChange = { command = it },
                onSend = submit,
                onMic = talk,
                placeholder = when {
                    agentMode -> "Give the agent a task…"
                    turns.isEmpty() -> "Ask Alfred anything…"
                    else -> "Ask a follow-up…"
                },
                listening = voice is MainViewModel.VoiceState.Listening,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("header") {
                HomeHeader(
                    name = settings.userName,
                    auto = settings.replyMode == ReplyMode.AUTO,
                    onModeChange = { vm.setReplyMode(if (it) ReplyMode.AUTO else ReplyMode.MANUAL) },
                    aiLabel = aiLabel, aiDot = aiDot, aiPulsing = aiPulse,
                    modeHint = modeHint,
                    onSettings = openSettings,
                )
            }
            item("orb") {
                OrbHero(
                    mode = orbMode,
                    level = (voice as? MainViewModel.VoiceState.Listening)?.level ?: 0f,
                    title = orbTitle, subtitle = orbSubtitle, onClick = talk,
                    compact = turns.isNotEmpty() || pendingRequest != null,
                )
            }

            // ---- First-run steps ---------------------------------------------------------
            if (!listenerEnabled) item("setup-listener") {
                Box(Modifier.animateItem()) {
                    SetupCard("Step 1", "Allow notification access", "Hy reads your notifications on this phone to summarize and reply. Nothing leaves the device.", "Open settings") {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            if (!hasModel) item("setup-model") {
                Box(Modifier.animateItem()) {
                    SetupCard("Step 2", "Get the on-device AI model", "One-time ~1.1 GB download. Needed for suggestions and auto-replies.", "Choose model", openModels)
                }
            }
            if (!canNotify && Build.VERSION.SDK_INT >= 33) item("setup-notify") {
                Box(Modifier.animateItem()) {
                    SetupCard("Permission", "Allow Hy's notifications", "Needed to show suggested replies with a Send button, and auto-reply alerts.", "Allow") {
                        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            if (listenerEnabled && !ignoringBattery) item("setup-battery") {
                Box(Modifier.animateItem()) {
                    SetupCard("Background", "Let Hy work while the phone sleeps", "Without this, Android may pause Hy when the screen is off, so replies can come late or not at all.", "Allow") {
                        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(request)
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }

            shared?.let { sh -> item("shared") { Box(Modifier.animateItem()) { SharedCard(sh, vm::askAboutShared, vm::dismissShared) } } }

            // ---- Conversation with Hy -------------------------------------------------------
            if (turns.isNotEmpty() || pendingRequest != null) {
                item("conv-header") {
                    SectionHeader("Conversation") {
                        TextButton(onClick = vm::newChat) { Text("New chat", color = NothingRed) }
                    }
                }
                val hidden = if (showAllTurns) 0 else (turns.size - 6).coerceAtLeast(0)
                if (hidden > 0) {
                    item("conv-more") { TextButton(onClick = { showAllTurns = true }) { Text("Show $hidden earlier") } }
                }
                turns.drop(hidden).forEachIndexed { i, t ->
                    val isLast = hidden + i == turns.lastIndex
                    item(key = "turn-${t.timestamp}-$i") {
                        Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChatBubble(t.user, fromUser = true)
                            ChatBubble(t.assistant, fromUser = false)
                            SaveRow { f -> vm.exportText(t.assistant, t.user, f) }
                            // Sources of the latest web answer stay tappable.
                            val links = if (isLast && threaded != null && threaded === output) output?.links.orEmpty() else emptyList()
                            links.forEach { link ->
                                Text(
                                    "↗ ${link.title} · ${Web.host(link.url)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NothingRed,
                                    maxLines = 1,
                                    modifier = Modifier.clickable { vm.openInBrowser(link.url) }.padding(start = 8.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
                pendingRequest?.let { req ->
                    item("conv-pending") {
                        Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChatBubble(req, fromUser = true)
                            if (output == null || output?.running == true) TypingBubble()
                        }
                    }
                }
            }

            agentRun?.let { r ->
                item("agent") { Box(Modifier.animateItem()) { AgentCard(r, vm.agents::approve, vm.agents::skip, vm::stopAgent, vm::closeAgent, vm::openSaved, vm::shareSaved) } }
            }
            savedFile?.let { f ->
                item("saved") { Box(Modifier.animateItem()) { SavedFileBar(f, { vm.openSaved(f) }, { vm.shareSaved(f) }, vm::dismissSaved) } }
            }
            confirm?.let { c -> item("confirm") { Box(Modifier.animateItem()) { ConfirmCard(c, vm::confirmPending, vm::dismissConfirm) } } }
            disambiguation?.let { d -> item("which") { Box(Modifier.animateItem()) { DisambiguationCard(d, vm::chooseCandidate, vm::dismissDisambiguation) } } }
            proposal?.let { p ->
                item("proposal") { Box(Modifier.animateItem()) { ProposalCard(p, vm::editProposal, vm::confirmSend, vm::copyAndOpenWhatsApp, vm::dismissProposal) } }
            }
            // A finished answer already appears in the conversation; only show the card while working.
            output?.takeIf { it !== threaded }?.let { o ->
                item("output") {
                    Box(Modifier.animateItem()) {
                        OutputCard(o, vm::dismissOutput, onOpenLink = vm::openInBrowser, onSave = { f -> vm.exportText(o.text, o.title, f) })
                    }
                }
            }

            // ---- Quick actions -----------------------------------------------------------------
            item("quick") {
                Column {
                    SectionHeader("Quick actions")
                    Spacer(Modifier.height(8.dp))
                    QuickActions(
                        agentMode = agentMode,
                        onCatchUp = { vm.runCommand("What did I miss?") },
                        onAgent = { vm.setAgentMode(!agentMode) },
                        onBrowser = openBrowser,
                        onTerminal = openTerminal,
                        onModels = openModels,
                    )
                }
            }

            // ---- Reminders --------------------------------------------------------------------------
            if (reminders.isNotEmpty()) {
                item("reminders-header") { SectionHeader("Upcoming") }
                items(reminders.take(5), key = { "rem-${it.id}" }) { r -> Box(Modifier.animateItem()) { ReminderLine(r) { vm.removeReminder(r.id) } } }
            }

            // ---- What Hy did on its own ----------------------------------------------------------
            if (activity.isNotEmpty()) {
                item("activity-header") { SectionHeader("Hy activity") }
                items(activity.take(5), key = { "act-${it.time}-${it.chatName}" }) { e -> Box(Modifier.animateItem()) { ActivityLine(e) } }
            }

            // ---- Chats ---------------------------------------------------------------------------
            item("chats-header") { SectionHeader("Chats · last 3 days") }
            if (chats.isEmpty()) {
                item("chats-empty") {
                    Text(
                        if (listenerEnabled) "No messages yet. New chat notifications will show up here automatically."
                        else "Allow notification access to see messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(chats, key = { "chat-" + it.key }) { chat -> Box(Modifier.animateItem()) { ChatRow(chat) { openChat(chat.key) } } }

            // ---- Everything else -----------------------------------------------------------------
            if (feed.isNotEmpty()) {
                item("feed-header") { SectionHeader("Other notifications · 24 h") }
                items(if (showAllFeed) feed else feed.take(6), key = { "feed-" + it.id }) { f -> Box(Modifier.animateItem()) { FeedLine(f) } }
                if (feed.size > 6) {
                    item("feed-more") {
                        TextButton(onClick = { showAllFeed = !showAllFeed }) {
                            Text(if (showAllFeed) "Show less" else "Show all ${feed.size}", color = NothingRed)
                        }
                    }
                }
            }
        }
    }
}


/** Text or a link shared to Hy from another app, with one-tap tasks. */
@Composable
private fun SharedCard(sh: MainViewModel.Shared, onAsk: (String) -> Unit, onDismiss: () -> Unit) {
    var question by rememberSaveable(sh) { mutableStateOf("") }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (sh.fromScreen) "On your screen" else "Shared with Hy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                "Translate" to "Translate this into simple English.",
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



