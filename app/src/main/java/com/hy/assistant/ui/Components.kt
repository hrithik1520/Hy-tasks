package com.hy.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hy.assistant.AssistantOutput
import com.hy.assistant.Disambiguation
import com.hy.assistant.ReplyProposal
import com.hy.assistant.notifications.Chat
import java.text.DateFormat
import java.util.Date

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun OutputCard(output: AssistantOutput, onDismiss: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(output.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (output.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.padding(4.dp))
        if (output.running && output.text.isEmpty()) {
            Text("Thinking on-device…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (output.text.isNotEmpty()) SelectionContainer { Text(output.text) }
        output.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(if (output.running) "Stop" else "Close") }
        }
    }
}

/** The confirmation step: shows exactly who receives what. Sending needs this explicit tap. */
@Composable
fun ProposalCard(
    p: ReplyProposal,
    onEdit: (String) -> Unit,
    onSend: () -> Unit,
    onCopyOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard {
        Text("Reply to ${p.chatName}", style = MaterialTheme.typography.titleMedium)
        Text(
            if (p.generating) "Drafting on-device…" else "Check and edit before sending.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(4.dp))
        OutlinedTextField(
            value = p.text,
            onValueChange = onEdit,
            enabled = !p.generating,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(Modifier.padding(4.dp))
        if (!p.canSend) {
            Text(
                "Direct send isn't available (the WhatsApp notification for this chat is gone). Copy and paste it in WhatsApp instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(4.dp))
            if (p.canSend) {
                OutlinedButton(onClick = onCopyOpen, enabled = !p.generating && p.text.isNotBlank()) { Text("Copy") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, enabled = !p.generating && p.text.isNotBlank()) { Text("Send") }
            } else {
                Button(onClick = onCopyOpen, enabled = !p.generating && p.text.isNotBlank()) { Text("Copy & open WhatsApp") }
            }
        }
    }
}

@Composable
fun DisambiguationCard(d: Disambiguation, onPick: (Chat) -> Unit, onDismiss: () -> Unit) {
    SectionCard {
        Text("Which \"${d.query}\"?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(4.dp))
        d.candidates.forEach { c ->
            AssistChip(onClick = { onPick(c) }, label = { Text(c.name) }, modifier = Modifier.padding(end = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRow(chat: Chat, onClick: () -> Unit) {
    val unread = chat.unread.size
    val last = chat.messages.lastOrNull()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chat.isWhatsApp) chat.name else "${chat.name} · ${chat.appName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(timeLabel(chat.lastTimestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    last?.let { (if (it.fromMe) "You: " else if (chat.isGroup) "${it.sender}: " else "") + it.text } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Text(
                        " $unread new",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

fun timeLabel(ts: Long): String {
    if (ts == 0L) return ""
    val sameDay = System.currentTimeMillis() - ts < 20 * 60 * 60 * 1000
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
}
