package com.hy.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hy.assistant.R

/** Nothing red: the only accent colour. */
val NothingRed = Color(0xFFD71921)

private val Dark = darkColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A0C0E),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFFF2F2F2),
    onSecondary = Color.Black,
    background = Color.Black, // true black: OLED-friendly, like Nothing OS
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF8C8C8C),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
    outline = Color(0xFF2E2E2E),
    outlineVariant = Color(0xFF222222),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3E1),
    onPrimaryContainer = Color(0xFF410004),
    secondary = Color(0xFF111111),
    onSecondary = Color.White,
    background = Color(0xFFF1F1F1),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFF1F1F1),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF6E6E6E),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F7F7),
    surfaceContainerHighest = Color(0xFFEDEDED),
    outline = Color(0xFFDCDCDC),
    outlineVariant = Color(0xFFE8E8E8),
)

/** Dot-matrix display font (Doto, SIL OFL 1.1) for headings and small caps labels. */
@OptIn(ExperimentalTextApi::class)
val DotFont = FontFamily(
    Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.doto, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val Base = Typography()

private val HyTypography = Base.copy(
    displaySmall = TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = 1.sp),
    headlineSmall = TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.5.sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

/** Small all-caps labels ("AUTO", "AI READY", section headers) in the dot font. */
val DotLabel = TextStyle(fontFamily = DotFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp)

private val HyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun HyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = HyTypography,
        shapes = HyShapes,
        content = content,
    )
}
