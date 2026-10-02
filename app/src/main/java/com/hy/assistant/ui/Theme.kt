package com.hy.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFD71921)

private val Dark = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Color(0xFFE6E6E6),
    background = Color(0xFF101214),
    surface = Color(0xFF101214),
    surfaceVariant = Color(0xFF1C1F23),
    onSurfaceVariant = Color(0xFFB8BCC2),
)

private val Light = lightColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Color(0xFF202124),
    background = Color(0xFFF6F6F6),
    surface = Color(0xFFF6F6F6),
    surfaceVariant = Color(0xFFE9E9EB),
)

@Composable
fun HyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
