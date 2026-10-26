package com.mewbox.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFB69CFF),
    onPrimary = Color(0xFF2A0B6B),
    secondary = Color(0xFF7FD6C8),
    background = Color(0xFF0F0E17),
    surface = Color(0xFF0F0E17),
    surfaceContainer = Color(0xFF1A1826),
    surfaceContainerLow = Color(0xFF151320),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    secondary = Color(0xFF00796B),
    background = Color(0xFFFBF9FF),
    surface = Color(0xFFFBF9FF),
)

@Composable
fun MewBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
