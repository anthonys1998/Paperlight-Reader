package com.paperlight.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.RenderedPage
import com.paperlight.reader.data.renderPdfPage
import com.paperlight.reader.data.renderComicPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ImagePageReaderScreen(book: BookEntity, onBack: () -> Unit, onProgress: (Float) -> Unit) {
    var position by rememberSaveable(book.id) { mutableFloatStateOf(book.progress) }
    var page by remember(book.id) { mutableStateOf<RenderedPage?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var zoom by remember(position) { mutableFloatStateOf(1f) }
    var pan by remember(position) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(book.id, position) {
        val result = withContext(Dispatchers.IO) { runCatching {
            if (book.format == "PDF") renderPdfPage(book.localPath, position, 1800)
            else renderComicPage(book, position, 2400)
        } }
        page = result.getOrNull() ?: page
        error = result.exceptionOrNull()?.let { "Couldn’t open this ${book.format} page. " + (it.message ?: "") }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(position) {
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
                error != null -> Text(error.orEmpty(), Modifier.padding(16.dp))
                current == null -> CircularProgressIndicator()
                else -> Image(current.bitmap.asImageBitmap(), "Page ${current.index + 1}",
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    })
            }
        }
        page?.let { current ->
            fun go(index: Int) {
                position = index.toFloat() / (current.count - 1).coerceAtLeast(1)
                onProgress(position)
            }
            val requested = kotlin.math.round((current.count - 1) * position).toInt()
            val ready = error != null || requested == current.index
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = ready && requested > 0, onClick = { go(requested - 1) }) { Text("Previous") }
                Text("${requested + 1} / ${current.count}")
                TextButton(enabled = ready && requested + 1 < current.count, onClick = { go(requested + 1) }) { Text("Next") }
            }
        }
    }
}
