package com.samplesnatcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC9FF),
    onPrimary = Color(0xFF001018),
    secondary = Color(0xFFB8D4FF),
    background = Color(0xFF0B1116),
    surface = Color(0xFF12181F),
    onBackground = Color(0xFFE8F1FF),
    onSurface = Color(0xFFE8F1FF),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF5C92D8),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
)

@Composable
fun SampleSnatcherTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
