package com.hy.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hy.assistant.auto.ActivityLog
import com.hy.assistant.notifications.FeedItem
import java.util.Calendar

/** "GOOD MORNING" / "GOOD AFTERNOON" / "GOOD EVENING" / "LATE NIGHT". */
fun greetingFor(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..22 -> "Good evening"
    else -> "Late night"
}

/** Top of the home screen: dot-matrix greeting, Manual/Auto pill, AI status, settings. */
@Composable
fun HomeHeader(
    name: String,
    auto: Boolean,
    onModeChange: (Boolean) -> Unit,
    aiLabel: String,
    aiDot: Color,
    aiPulsing: Boolean,
    modeHint: String,
    onSettings: () -> Unit,
    greeting: String = greetingFor(),
) {
    Box(Modifier.fillMaxWidth()) {
        DotGrid(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting.uppercase(), style = DotLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(name.ifBlank { "there" }, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                ) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface) }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SlidingPill(listOf("Manual", "Auto"), if (auto) 1 else 0, { onModeChange(it == 1) }, width = 180.dp)
                StatusChip(aiLabel, aiDot, aiPulsing, Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.height(8.dp))
            Text(modeHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** The orb with its caption; tap to talk (or to stop while talking). */
@Composable
fun OrbHero(mode: OrbMode, level: Float, title: String, subtitle: String, onClick: () -> Unit, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HyOrb(mode, size = if (compact) 120.dp else 176.dp, level = level, onClick = onClick)
        Spacer(Modifier.height(6.dp))
        OrbCaption(title, subtitle, Modifier.padding(horizontal = 24.dp).animateContentSize())
    }
}

/** Horizontally scrolling quick actions. */
@Composable
fun QuickActions(
    agentMode: Boolean,
    onCatchUp: () -> Unit,
    onAgent: () -> Unit,
    onBrowser: () -> Unit,
    onTerminal: () -> Unit,
    onModels: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val w = Modifier.width(118.dp)
        QuickTile("✦", "What did I miss?", onCatchUp, w)
        QuickTile("◈", if (agentMode) "Agent mode on" else "Agent mode", onAgent, w, active = agentMode)
        QuickTile("◎", "Browser", onBrowser, w)
        QuickTile(">_", "Terminal", onTerminal, w)
        QuickTile("▣", "Models", onModels, w)
    }
}

/** A chat bubble that pops in when it first appears. */
@Composable
fun ChatBubble(text: String, fromUser: Boolean, modifier: Modifier = Modifier) {
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.9f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "bubble")
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "bubbleAlpha")
    val shape = if (fromUser) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = if (fromUser) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .scale(scale)
                    .widthIn(max = 310.dp)
                    .background(if (fromUser) NothingRed.copy(alpha = alpha) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha), shape)
                    .then(if (!fromUser) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Hy is working on an answer. */
@Composable
fun TypingBubble() {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { TypingDots() }
    }
}

/** Pinned input: pill text field, mic (pulses while listening) and send (appears with text). */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    placeholder: String,
    listening: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                .padding(start = 18.dp, end = 6.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(NothingRed),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(value.isNotBlank(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                IconButton(onClick = onSend) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(NothingRed), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // Mic: a small orb that pulses while Hy is listening.
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (listening) HyOrb(OrbMode.LISTENING, size = 52.dp, level = 0.5f, onClick = onMic)
            else Box(
                Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface).clickable(onClick = onMic),
                contentAlignment = Alignment.Center,
            ) { MicGlyph(MaterialTheme.colorScheme.surface) }
        }
    }
}

/** A simple microphone drawn with shapes (the core icon set has no mic). */
@Composable
private fun MicGlyph(color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 10.dp, height = 16.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Box(Modifier.padding(top = 2.dp).size(width = 16.dp, height = 2.dp).background(color))
        Box(Modifier.size(width = 2.dp, height = 3.dp).background(color))
    }
}

/** First-run step (permissions, model…) in a card with a red accent rule. */
@Composable
fun SetupCard(step: String, title: String, body: String, button: String, onClick: () -> Unit) {
    SectionCard {
        Text(step.uppercase(), style = DotLabel, color = NothingRed)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Button(onClick = onClick, shape = CircleShape) { Text(button) }
    }
}

/** One thing Hy did on its own. */
@Composable
fun ActivityLine(e: ActivityLog.Entry) {
    val color = when (e.kind) {
        ActivityLog.Kind.AUTO_SENT, ActivityLog.Kind.SENT -> NothingRed
        ActivityLog.Kind.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("${e.kind.label} · ${e.chatName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                Text(timeLabel(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            e.note?.let { Text("Reason: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A notification from another app (codes, deliveries, payments…). */
@Composable
fun FeedLine(f: FeedItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Text(f.appName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("${f.category.label.uppercase()} · ${f.appName}", style = DotLabel.copy(fontSize = 10.sp), color = NothingRed, modifier = Modifier.weight(1f), maxLines = 1)
                Text(timeLabel(f.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (f.title.isNotBlank()) Text(f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
            if (f.text.isNotBlank()) Text(f.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
