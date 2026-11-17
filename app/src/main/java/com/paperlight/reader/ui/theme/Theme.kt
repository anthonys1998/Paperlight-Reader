package com.paperlight.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006D5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E8D9), onPrimaryContainer = Color(0xFF00382F),
    background = Color(0xFFFCF5EB), surface = Color(0xFFFCF5EB),
    onBackground = Color(0xFF142748), onSurface = Color(0xFF142748)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ADBC5), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF254F43), onPrimaryContainer = Color(0xFFC7F2E4),
    background = Color(0xFF211913), surface = Color(0xFF211913),
    onBackground = Color(0xFFF4E6CB), onSurface = Color(0xFFF4E6CB)
)

@Composable
fun PaperlightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
