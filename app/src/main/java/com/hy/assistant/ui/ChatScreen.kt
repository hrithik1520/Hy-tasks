package com.hy.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hy.assistant.ChatMode
import com.hy.assistant.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, chatKey: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val chats by vm.chats.collectAsState()
    val output by vm.output.collectAsState()
    val proposal by vm.proposal.collectAsState()
    val settings by vm.settings.data.collectAsState()
    val chat = chats.firstOrNull { it.key == chatKey }
    val listState = rememberLazyListState()
    var question by rememberSaveable { mutableStateOf("") }
    val ask = {
        if (question.isNotBlank()) {
            vm.askAboutChat(chatKey, question)
            question = ""
        }
    }

    LaunchedEffect(chat?.messages?.size) {
        val n = chat?.messages?.size ?: 0
        if (n > 0) listState.scrollToItem(n - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat?.name ?: "Chat", maxLines = 1)
                        Text(
                            (chat?.appName ?: "") + " · " + if (chat?.canReply == true) "direct reply available" else "reply via copy & paste",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (chat == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("This chat expired.") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(chat.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier
                                .widthIn(max = 300.dp)
                                .background(
                                    if (m.fromMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (chat.isGroup && !m.fromMe) {
                                Text(m.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(m.text)
                            Text(timeLabel(m.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("This chat:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    ChatMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.modeFor(chatKey) == m,
                            onClick = { vm.setChatMode(chatKey, m) },
                            label = { Text(m.label) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                proposal?.takeIf { it.chatKey == chatKey }?.let { p ->
                    ProposalCard(p, vm::editProposal, vm::confirmSend, vm::copyAndOpenWhatsApp, vm::dismissProposal)
                }
                output?.let { OutputCard(it, vm::dismissOutput) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything about this chat…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask() }),
                    )
                    IconButton(onClick = ask) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask") }
                }
                Row {
                    FilledTonalButton(onClick = { vm.summarize(chatKey) }) { Text("Summarize") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { vm.draftReply(chatKey, null) }) { Text("Suggest reply") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.editOwnReply(chatKey) }) { Text("Write") }
                }
            }
        }
    }
}
