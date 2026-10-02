package com.hy.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hy.assistant.agents.AgentRun
import com.hy.assistant.agents.AgentStepUi
import com.hy.assistant.agents.Approval
import com.hy.assistant.agents.StepStatus
import com.hy.assistant.tools.SavedFile

/** Live view of a multi-step agent run: each step, its specialist, and any approval it's waiting on. */
@Composable
fun AgentCard(
    run: AgentRun,
    onApprove: (String) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenFile: (SavedFile) -> Unit,
    onShareFile: (SavedFile) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agent", style = MaterialTheme.typography.titleMedium)
                Text(run.goal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (run.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.padding(4.dp))
        run.steps.forEach { StepRow(it) }

        run.approval?.let { a -> ApprovalBox(a, onApprove, onSkip) }

        run.files.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📄 ${f.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { onOpenFile(f) }) { Text("Open") }
                TextButton(onClick = { onShareFile(f) }) { Text("Share") }
            }
        }
        run.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!run.running && run.answer != null) {
            Text("Done — answer is in the conversation.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val done = run.steps.count { it.status != StepStatus.PLANNING }
            Text(
                "Step ${done.coerceAtMost(run.maxSteps)} of max ${run.maxSteps}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (run.running) TextButton(onClick = onStop) { Text("Stop") } else TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun StepRow(s: AgentStepUi) {
    var expanded by remember(s.index) { mutableStateOf(false) }
    val (icon, color) = when (s.status) {
        StepStatus.PLANNING -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
        StepStatus.WORKING -> "▶" to MaterialTheme.colorScheme.primary
        StepStatus.WAITING -> "⏸" to MaterialTheme.colorScheme.tertiary
        StepStatus.DONE -> "✓" to MaterialTheme.colorScheme.primary
        StepStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
        StepStatus.SKIPPED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = s.observation.isNotBlank()) { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("$icon ", color = color, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.agent == null) "Step ${s.index}: planning…" else "Step ${s.index} · ${s.agent.label} agent",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (s.thought.isNotBlank()) {
                    Text(s.thought, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.agent != null) Text("→ ${s.task}", style = MaterialTheme.typography.bodySmall)
                if (s.observation.isNotBlank()) {
                    Text(
                        if (expanded) s.observation else s.observation.lineSequence().first().take(90) + "  (tap for more)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (s.agent?.id == "terminal") FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalBox(a: Approval, onApprove: (String) -> Unit, onSkip: () -> Unit) {
    var text by rememberSaveable(a) { mutableStateOf(a.text) }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text(a.title, style = MaterialTheme.typography.titleSmall)
    Text(
        if (a.kind == Approval.Kind.SEND_MESSAGE) "The agent wants to send this message. Edit it if needed."
        else "The agent wants to run this command.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textStyle = if (a.kind == Approval.Kind.RUN_COMMAND) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyMedium,
    )
    if (a.warnings.isNotEmpty()) {
        Text("⚠ This command ${a.warnings.joinToString(", ")}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onSkip) { Text("Skip") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { onApprove(text) },
            enabled = text.isNotBlank(),
            colors = if (a.warnings.isNotEmpty()) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
        ) { Text(if (a.kind == Approval.Kind.SEND_MESSAGE) "Send" else "Run") }
    }
}
