package com.hy.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
import com.hy.assistant.Confirmation
import com.hy.assistant.core.ExportFormat
import com.hy.assistant.tools.SavedFile
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hy.assistant.core.Web
import androidx.compose.foundation.clickable
import com.hy.assistant.Disambiguation
import com.hy.assistant.ReplyProposal
import com.hy.assistant.notifications.Chat
import java.text.DateFormat
import java.util.Date

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Nothing-style card: flat surface, hairline outline, generous radius; grows/shrinks smoothly.
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
fun OutputCard(
    output: AssistantOutput,
    onDismiss: () -> Unit,
    onOpenLink: ((String) -> Unit)? = null,
    onSave: ((ExportFormat) -> Unit)? = null,
) {
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
        if (output.links.isNotEmpty()) {
            Spacer(Modifier.padding(2.dp))
            Text("Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            output.links.forEach { link ->
                Text(
                    "• ${link.title} — ${Web.host(link.url)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onOpenLink != null) Modifier.clickable { onOpenLink(link.url) } else Modifier)
                        .padding(vertical = 3.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (onSave != null && !output.running && output.text.isNotBlank()) {
                Box(Modifier.weight(1f)) { SaveRow(onSave) }
            }
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

/** Asks for an explicit tap before a risky change (e.g. turning on unattended replies). */
@Composable
fun ConfirmCard(c: Confirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    SectionCard {
        Text(c.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(4.dp))
        Text(c.text, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onConfirm) { Text(c.confirmLabel) }
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

/** "Save as…" → .md / .csv / .docx / .txt */
@Composable
fun SaveRow(onSave: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Save as…", style = MaterialTheme.typography.labelMedium) }
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            ExportFormat.entries.forEach { f ->
                AssistChip(
                    onClick = { open = false; onSave(f) },
                    label = { Text(".${f.ext}") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

/** The file just saved, with Open / Share. */
@Composable
fun SavedFileBar(file: SavedFile, onOpen: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📄 ${file.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("Saved in Downloads/Hy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onDismiss) { Text("✕") }
        }
    }
}

/** A chat in the list: avatar, name, last message, time and an animated unread badge. */
@Composable
fun ChatRow(chat: Chat, onClick: () -> Unit) {
    val unread = chat.unread.size
    val last = chat.messages.lastOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, unread = unread > 0)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                    )
                    if (!chat.isWhatsApp) {
                        Spacer(Modifier.width(6.dp))
                        Text(chat.appName.uppercase(), style = DotLabel.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(timeLabel(chat.lastTimestamp), style = MaterialTheme.typography.labelSmall, color = if (unread > 0) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    last?.let { (if (it.fromMe) "You: " else if (chat.isGroup) "${it.sender}: " else "") + it.text } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(unread > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(
                        Modifier.padding(start = 8.dp).height(20.dp).widthIn(min = 20.dp).clip(CircleShape).background(NothingRed).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("$unread", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold) }
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
