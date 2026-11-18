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
    onBackground = Color(0xFF302B25), onSurface = Color(0xFF302B25),
    secondary = Color(0xFF635842), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E9DD), onSecondaryContainer = Color(0xFF303C30),
    surfaceVariant = Color(0xFFEAE2D6), onSurfaceVariant = Color(0xFF62594F),
    outline = Color(0xFF82796E), outlineVariant = Color(0xFFD5CBBE),
    surfaceContainerLowest = Color(0xFFFFFBF5), surfaceContainerLow = Color(0xFFF5EFE5),
    surfaceContainer = Color(0xFFF0E9DE), surfaceContainerHigh = Color(0xFFEAE2D6),
    surfaceContainerHighest = Color(0xFFE4DBCE)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ADBC5), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF254F43), onPrimaryContainer = Color(0xFFC7F2E4),
    background = Color(0xFF211913), surface = Color(0xFF211913),
    onBackground = Color(0xFFF4E6CB), onSurface = Color(0xFFF4E6CB),
    secondary = Color(0xFFD1C4A9), onSecondary = Color(0xFF352D21),
    secondaryContainer = Color(0xFF3D483A), onSecondaryContainer = Color(0xFFE0E8D9),
    surfaceVariant = Color(0xFF3E342B), onSurfaceVariant = Color(0xFFD2C4B3),
    outline = Color(0xFF9C8D7A), outlineVariant = Color(0xFF564A3D),
    surfaceContainerLowest = Color(0xFF18130F), surfaceContainerLow = Color(0xFF292119),
    surfaceContainer = Color(0xFF30271F), surfaceContainerHigh = Color(0xFF392F25),
    surfaceContainerHighest = Color(0xFF42372C)
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
