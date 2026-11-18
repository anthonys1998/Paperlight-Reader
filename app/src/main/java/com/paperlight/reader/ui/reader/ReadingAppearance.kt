package com.paperlight.reader.ui.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject

internal data class ReadingAppearance(
    val size: Float = 1f,
    val lineHeight: Float = 1.65f,
    val margins: Int = 24,
    val font: String = "serif",
    val alignment: String = "original",
    val texture: String = "none",
    val letterSpacing: Float = 0f,
    val paragraphSpacing: Float = 1f,
) {
    fun json() = JSONObject().put("size", size.toDouble()).put("lineHeight", lineHeight.toDouble())
        .put("margins", margins).put("font", font).put("alignment", alignment).put("texture", texture)
        .put("letterSpacing", letterSpacing.toDouble()).put("paragraphSpacing", paragraphSpacing.toDouble())
}

internal data class ReaderPalette(val paper: Color, val ink: Color, val panel: Color, val accent: Color)

internal val readerPalettes = listOf(
    ReaderPalette(Color(0xFFF9F9F5), Color(0xFF2C302E), Color(0xFFE8EBE6), Color(0xFF395F55)),
    ReaderPalette(Color(0xFF1D201F), Color(0xFFE1DFD5), Color(0xFF292D2B), Color(0xFFB4CA9A)),
)

internal val eInkPalette = ReaderPalette(Color.White, Color.Black, Color.White, Color.Black)

@Composable
internal fun ReaderTheme(palette: ReaderPalette, dark: Boolean, content: @Composable () -> Unit) {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val muted = androidx.compose.ui.graphics.lerp(palette.ink, palette.panel, .24f)
    val tint = androidx.compose.ui.graphics.lerp(palette.panel, palette.accent, .15f)
    MaterialTheme(
        colorScheme = base.copy(
            primary = palette.accent, onPrimary = palette.paper,
            primaryContainer = tint, onPrimaryContainer = palette.ink,
            secondaryContainer = tint, onSecondaryContainer = palette.ink,
            background = palette.paper, onBackground = palette.ink,
            surface = palette.panel, onSurface = palette.ink,
            surfaceVariant = tint, onSurfaceVariant = muted,
            surfaceTint = palette.accent, outline = muted,
            outlineVariant = androidx.compose.ui.graphics.lerp(palette.ink, palette.panel, .79f),
            surfaceContainerLowest = palette.panel, surfaceContainerLow = palette.panel,
            surfaceContainer = palette.panel, surfaceContainerHigh = palette.panel,
            surfaceContainerHighest = tint,
        ),
        content = content,
    )
}

private const val MaxFontSize = 6 * 1024 * 1024

internal fun customFontFile(context: Context) = File(context.filesDir, "reading-font")

internal fun fontData(file: File): String? {
    if (file.length() !in 4L..MaxFontSize.toLong()) return null
    return fontData(file.readBytes())
}

private fun fontData(bytes: ByteArray): String? {
    if (bytes.size !in 4..MaxFontSize) return null
    val header = bytes.take(4).map { it.toInt() and 0xff }
    val type = when {
        header == listOf(0, 1, 0, 0) -> "ttf"
        String(bytes, 0, 4, Charsets.US_ASCII) == "OTTO" -> "otf"
        else -> return null
    }
    return "data:font/$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}

internal fun importReadingFont(context: Context, uri: Uri): String? {
    val bytes = context.contentResolver.openInputStream(uri)?.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (output.size() + count > MaxFontSize) return null
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: return null
    return fontData(bytes)?.also { customFontFile(context).writeBytes(bytes) }
}

@Composable
internal fun ReadingAppearanceDialog(
    value: ReadingAppearance,
    theme: Int,
    palette: ReaderPalette,
    hasFont: Boolean,
    eInkMode: Boolean,
    onEInkMode: (Boolean) -> Unit,
    onChange: (ReadingAppearance) -> Unit,
    onTheme: (Int) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.panel,
        titleContentColor = palette.ink,
        textContentColor = palette.ink,
        title = { Text("Appearance") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Page color", style = MaterialTheme.typography.labelLarge)
                if (eInkMode) {
                    Text("E-Ink mode uses black text on a white page.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Light", "Dark").forEachIndexed { index, label ->
                            FilterChip(selected = theme == index, onClick = { onTheme(index) }, label = { Text(label) })
                        }
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("E-Ink display mode", style = MaterialTheme.typography.labelLarge)
                        Text("Black text on white paper, no texture or page animation. Panel refresh is controlled by your device.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = eInkMode, onCheckedChange = onEInkMode)
                }
                if (eInkMode) {
                    Text("Paper textures and animated page turns are disabled while E-Ink mode is on.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    TexturePicker(value.texture, palette.paper, palette.ink) { onChange(value.copy(texture = it)) }
                }
                Text("Typeface", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("serif" to "Serif", "sans-serif" to "Sans", "monospace" to "Mono").forEach { (font, label) ->
                        FilterChip(selected = value.font == font, onClick = { onChange(value.copy(font = font)) }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasFont) FilterChip(selected = value.font == "custom",
                        onClick = { onChange(value.copy(font = "custom")) }, label = { Text("Imported font") })
                    TextButton(onClick = onImport) { Text("Import font", color = palette.accent) }
                }
                AppearanceSlider("Text size", "${(value.size * 100).roundToInt()}%", value.size, .6f..2.4f) {
                    onChange(value.copy(size = it))
                }
                AppearanceSlider("Line spacing", "${(value.lineHeight * 100).roundToInt()}%", value.lineHeight, 1.1f..2.6f) {
                    onChange(value.copy(lineHeight = it))
                }
                AppearanceSlider("Margins", "${value.margins}", value.margins.toFloat(), 8f..64f) {
                    onChange(value.copy(margins = it.roundToInt()))
                }
                AppearanceSlider("Letter spacing", "${value.letterSpacing.roundToInt()}%", value.letterSpacing, -2f..12f) {
                    onChange(value.copy(letterSpacing = it))
                }
                AppearanceSlider("Paragraph spacing", "${(value.paragraphSpacing * 100).roundToInt()}%", value.paragraphSpacing, 0f..2f) {
                    onChange(value.copy(paragraphSpacing = it))
                }
                Text("Alignment", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("original" to "Book", "start" to "Start", "justify" to "Justify").forEach { (alignment, label) ->
                        FilterChip(selected = value.alignment == alignment, onClick = { onChange(value.copy(alignment = alignment)) },
                            label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = palette.accent) } },
        dismissButton = { TextButton(onClick = { onChange(ReadingAppearance()) }) { Text("Reset type", color = palette.accent) } },
    )
}

@Composable
private fun AppearanceSlider(label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(display, style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}
