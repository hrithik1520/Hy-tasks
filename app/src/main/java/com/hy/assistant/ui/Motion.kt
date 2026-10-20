package com.hy.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What the AI orb is showing. */
enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Hy's animated orb (tap to talk). A ring of dots like Nothing's Glyph lights around a glowing
 * red core: it breathes when idle, sends out ripples while listening (bigger with louder speech),
 * chases a dot around the ring while thinking, and pulses the ring like a waveform while speaking.
 */
@Composable
fun HyOrb(mode: OrbMode, modifier: Modifier = Modifier, size: Dp = 168.dp, level: Float = 0f, onClick: (() -> Unit)? = null) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.THINKING) 1400 else 16000, easing = LinearEasing)), label = "spin")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ripple")
    val wave by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "wave")
    val loud by animateFloatAsState(level.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessMediumLow), label = "level")
    val coreScale by animateFloatAsState(
        when (mode) {
            OrbMode.IDLE -> 1f
            OrbMode.LISTENING -> 1.08f + loud * 0.25f
            OrbMode.THINKING -> 0.86f
            OrbMode.SPEAKING -> 1.02f
        },
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "core",
    )
    val dotColor = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")

    Canvas(
        modifier
            .size(size)
            .scale(press)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier),
    ) {
        val c = center
        val r = this.size.minDimension / 2f
        val coreR = r * 0.34f * coreScale * (if (mode == OrbMode.IDLE) breathe else 1f)

        // Listening: ripples travelling outwards.
        if (mode == OrbMode.LISTENING) {
            for (i in 0 until 3) {
                val p = (ripple + i / 3f) % 1f
                drawCircle(NothingRed.copy(alpha = (1f - p) * 0.35f), radius = coreR + (r * 0.95f - coreR) * p, center = c, style = Stroke(width = 2.dp.toPx()))
            }
        }
        // Soft glow behind the core.
        drawCircle(
            Brush.radialGradient(listOf(NothingRed.copy(alpha = 0.45f), NothingRed.copy(alpha = 0.10f), Color.Transparent), center = c, radius = coreR * 2.4f),
            radius = coreR * 2.4f, center = c,
        )
        // Core.
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFF5A5F), NothingRed, Color(0xFF8E0E14)), center = c + Offset(-coreR * 0.25f, -coreR * 0.3f), radius = coreR * 1.3f),
            radius = coreR, center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.9f), radius = coreR * 0.12f, center = c) // the "eye"

        // Dot ring (Glyph-style).
        val n = 36
        val ringR = r * 0.82f
        for (i in 0 until n) {
            val frac = i / n.toFloat()
            val angle = (frac + spin) * 2 * PI
            var dotR = 2.2.dp.toPx()
            var alpha = 0.28f
            when (mode) {
                OrbMode.THINKING -> {
                    // A bright head chasing around with a fading tail.
                    val d = ((frac - (1f - spin) + 1f) % 1f)
                    alpha = 0.15f + 0.85f * (1f - d).let { it * it * it }
                    dotR *= 1f + 0.6f * (1f - d)
                }
                OrbMode.SPEAKING -> {
                    val amp = (sin(wave + i * 0.7f) * 0.5f + 0.5f) * (0.6f + 0.4f * abs(sin(wave * 0.5f + i)))
                    alpha = 0.3f + 0.7f * amp
                    dotR *= 1f + amp
                }
                OrbMode.LISTENING -> alpha = 0.25f + 0.5f * loud
                OrbMode.IDLE -> alpha = 0.18f + 0.22f * ((sin(angle * 3 + breathe * 6) + 1f) / 2f).toFloat()
            }
            val ringOffset = if (mode == OrbMode.SPEAKING) (sin(wave + i * 0.7f) * 3.dp.toPx()).toFloat() else 0f
            val p = Offset(c.x + cos(angle).toFloat() * (ringR + ringOffset), c.y + sin(angle).toFloat() * (ringR + ringOffset))
            drawCircle((if (mode == OrbMode.THINKING || mode == OrbMode.SPEAKING) NothingRed else dotColor).copy(alpha = alpha), radius = dotR, center = p)
        }
        if (mode == OrbMode.THINKING) {
            drawArc(
                NothingRed.copy(alpha = 0.5f), startAngle = spin * 360f, sweepAngle = 70f, useCenter = false,
                topLeft = Offset(c.x - ringR * 0.72f, c.y - ringR * 0.72f),
                size = androidx.compose.ui.geometry.Size(ringR * 1.44f, ringR * 1.44f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Subtle dot-matrix pattern (Nothing OS style) behind headers. */
@Composable
fun DotGrid(modifier: Modifier = Modifier, spacing: Dp = 14.dp, alpha: Float = 0.07f) {
    val color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)
    Canvas(modifier) {
        val step = spacing.toPx()
        val r = 1.1.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                // Fade towards the bottom so the pattern melts into the page.
                drawCircle(color.copy(alpha = color.alpha * (1f - y / size.height)), r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** Two-option pill with a sliding indicator (e.g. MANUAL / AUTO). */
@Composable
fun SlidingPill(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, width: Dp = 200.dp) {
    val segW = (width - 6.dp) / options.size // inside the 3 dp padding
    val offset by animateDpAsState(segW * selected, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "pill")
    Box(
        modifier
            .width(width)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(width = segW, height = 36.dp)
                .clip(CircleShape)
                .background(if (selected == options.lastIndex) NothingRed else MaterialTheme.colorScheme.onSurface),
        )
        Row {
            options.forEachIndexed { i, label ->
                val color by animateColorAsState(
                    when {
                        i != selected -> MaterialTheme.colorScheme.onSurfaceVariant
                        selected == options.lastIndex -> Color.White
                        else -> MaterialTheme.colorScheme.surface
                    },
                    label = "pillText",
                )
                Box(
                    Modifier
                        .size(width = segW, height = 36.dp)
                        .clip(CircleShape)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(label.uppercase(), style = DotLabel, color = color) }
            }
        }
    }
}

/** Small status chip with a (optionally pulsing) dot: "● AI READY". */
@Composable
fun StatusChip(text: String, dot: Color, pulsing: Boolean = false, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "chip")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "chipDot")
    Row(
        modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot.copy(alpha = if (pulsing) a else 1f)))
        Spacer(Modifier.width(7.dp))
        Text(text.uppercase(), style = DotLabel, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** Square quick-action tile with a press animation. */
@Composable
fun QuickTile(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "tile")
    val border by animateColorAsState(if (active) NothingRed else MaterialTheme.colorScheme.outline, label = "tileBorder")
    Column(
        modifier
            .scale(scale)
            .height(116.dp) // equal tiles even when a label wraps
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, border, RoundedCornerShape(22.dp))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (active) NothingRed else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Text(icon, fontSize = 17.sp) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    }
}

/** Circle with a contact's initial in the dot font; red ring when they have unread messages. */
@Composable
fun Avatar(name: String, unread: Boolean, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    // Monochrome shades picked from the name, so each contact keeps the same tone.
    val shades = listOf(Color(0xFF2B2B2B), Color(0xFF3A3A3A), Color(0xFF1F1F1F), Color(0xFF454545), Color(0xFF333333))
    val bg = if (MaterialTheme.colorScheme.background == Color.Black) shades[Math.floorMod(name.hashCode(), shades.size)]
    else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .then(if (unread) Modifier.border(2.dp, NothingRed, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.headlineSmall.copy(fontSize = (size.value * 0.42f).sp), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Three bouncing dots: "Hy is typing". */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "typing")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "typingPhase")
    Row(modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val local = ((p - i * 0.18f) + 1f) % 1f
            val lift = if (local < 0.4f) sin(local / 0.4f * PI).toFloat() else 0f
            Box(Modifier.offset(y = (-5 * lift).dp).size(7.dp).clip(CircleShape).background(color.copy(alpha = 0.4f + 0.6f * lift)))
        }
    }
}

/** Section header: small dot-font caps with a hairline rule. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = DotLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).size(height = 1.dp, width = 1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        trailing?.let {
            Spacer(Modifier.width(6.dp))
            it()
        }
    }
}

/** Caption under the orb, centred. */
@Composable
fun OrbCaption(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title.uppercase(), style = DotLabel, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 3, fontWeight = FontWeight.Normal,
            )
        }
    }
}
