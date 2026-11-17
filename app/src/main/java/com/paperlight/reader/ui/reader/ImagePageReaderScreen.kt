package com.paperlight.reader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.RenderedPage
import com.paperlight.reader.data.renderComicPage
import com.paperlight.reader.data.renderPdfPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private data class ImageReaderPalette(val paper: Color, val ink: Color, val panel: Color, val accent: Color)

private fun imageReaderPalette(dark: Boolean) = if (dark) ImageReaderPalette(
    Color(0xFF1D201F), Color(0xFFE1DFD5), Color(0xFF292D2B), Color(0xFFB4CA9A)
) else ImageReaderPalette(
    Color(0xFFF9F9F5), Color(0xFF2C302E), Color(0xFFE8EBE6), Color(0xFF395F55)
)

@Composable
internal fun ImagePageReaderScreen(book: BookEntity, onBack: () -> Unit, onProgress: (Float) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("reader", 0) }
    var position by rememberSaveable(book.id) { mutableFloatStateOf(book.progress) }
    var page by remember(book.id) { mutableStateOf<RenderedPage?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var zoom by remember(position) { mutableFloatStateOf(1f) }
    var pan by remember(position) { mutableStateOf(Offset.Zero) }
    var dark by remember { mutableStateOf(preferences.getInt("lightDarkPalette", 0) == 1) }
    var texture by remember { mutableStateOf(preferences.getString("texture", "none") ?: "none") }
    var turningDirection by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val palette = imageReaderPalette(dark)

    LaunchedEffect(book.id, position) {
        val result = withContext(Dispatchers.IO) { runCatching {
            if (book.format == "PDF") renderPdfPage(book.localPath, position, 1800)
            else renderComicPage(book, position, 2400)
        } }
        page = result.getOrNull() ?: page
        error = result.exceptionOrNull()?.let { "Couldn’t open this ${book.format} page. " + (it.message ?: "") }
    }
    Column(Modifier.fillMaxSize().background(palette.paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = palette.ink)) {
                Text("Back")
            }
            Text(book.title, Modifier.weight(1f), color = palette.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = {
                dark = !dark
                preferences.edit().putInt("lightDarkPalette", if (dark) 1 else 0).apply()
            }) {
                Icon(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    if (dark) "Light mode" else "Dark mode", tint = palette.ink)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .pointerInput(position, page?.index, zoom) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount ->
                        if (zoom == 1f && page != null && error == null) {
                            dragOffset = (dragOffset + amount).coerceIn(-size.width * .9f, size.width * .9f)
                        }
                    },
                    onDragEnd = {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val fraction = abs(dragOffset) / width
                        if (fraction > .22f) {
                            val current = page
                            if (current != null) {
                                turningDirection = if (dragOffset < 0) 1 else -1
                                position = ((current.index + turningDirection).coerceIn(0, current.count - 1))
                                    .toFloat() / (current.count - 1).coerceAtLeast(1)
                                onProgress(position)
                            }
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f }
                )
            }
            .pointerInput(position) {
                detectTransformGestures { _, delta, factor, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 4f)
                    val limitX = size.width * (zoom - 1f) / 2
                    val limitY = size.height * (zoom - 1f) / 2
                    pan = Offset((pan.x + delta.x).coerceIn(-limitX, limitX),
                        (pan.y + delta.y).coerceIn(-limitY, limitY))
                }
            }, contentAlignment = Alignment.Center) {
            val current = page
            when {
                error != null -> Text(error.orEmpty(), Modifier.padding(16.dp), color = palette.ink)
                current == null -> CircularProgressIndicator(color = palette.accent)
                else -> {
                    AnimatedContent(
                        targetState = current.index,
                        transitionSpec = {
                            if (preferences.getBoolean("pageFlip", true)) {
                                if (turningDirection > 0) {
                                    (slideInHorizontally { it } + fadeIn()).togetherWith(
                                        slideOutHorizontally { -it / 3 } + fadeOut()
                                    )
                                } else {
                                    (slideInHorizontally { -it } + fadeIn()).togetherWith(
                                        slideOutHorizontally { it / 3 } + fadeOut()
                                    )
                                }
                            } else fadeIn() togetherWith fadeOut()
                        },
                        label = "image page turn"
                    ) { shownIndex ->
                        if (shownIndex == current.index) {
                            Image(current.bitmap.asImageBitmap(), "Page ${shownIndex + 1}",
                                Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x + dragOffset
                                    translationY = pan.y
                                })
                        }
                    }
                }
            }
            if (texture != "none") {
                Canvas(Modifier.matchParentSize()) {
                    val step = if (texture == "linen") 5.dp.toPx() else 7.dp.toPx()
                    val alpha = if (texture == "linen") .035f else .022f
                    var y = 0f
                    while (y < size.height) {
                        drawLine(palette.ink.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y))
                        y += step
                    }
                    if (texture == "linen") {
                        var x = 0f
                        while (x < size.width) {
                            drawLine(palette.ink.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height))
                            x += step
                        }
                    }
                }
            }
        }
        page?.let { current ->
            val requested = kotlin.math.round((current.count - 1) * position).toInt()
            val ready = error != null || requested == current.index
            Row(Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = ready && requested > 0, onClick = {
                    turningDirection = -1
                    position = (requested - 1).toFloat() / (current.count - 1).coerceAtLeast(1)
                    onProgress(position)
                }, colors = ButtonDefaults.textButtonColors(contentColor = palette.ink)) { Text("‹ Previous") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${requested + 1} / ${current.count}", color = palette.ink,
                        style = MaterialTheme.typography.labelLarge)
                    Text("${(position * 100).toInt()}%", color = palette.ink,
                        style = MaterialTheme.typography.labelSmall)
                }
                TextButton(enabled = ready && requested + 1 < current.count, onClick = {
                    turningDirection = 1
                    position = (requested + 1).toFloat() / (current.count - 1).coerceAtLeast(1)
                    onProgress(position)
                }, colors = ButtonDefaults.textButtonColors(contentColor = palette.ink)) { Text("Next ›") }
            }
        }
        Row(Modifier.fillMaxWidth().background(palette.panel).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.Center) {
            listOf("none" to "Plain", "grain" to "Grain", "linen" to "Linen").forEach { (value, label) ->
                TextButton(onClick = {
                    texture = value
                    preferences.edit().putString("texture", value).apply()
                }, colors = ButtonDefaults.textButtonColors(contentColor = if (texture == value) palette.accent else palette.ink)) {
                    Text(label)
                }
            }
        }
    }
}
