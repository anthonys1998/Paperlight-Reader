package com.paperlight.reader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.AspectRatio
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.data.ReaderAnnotationStore
import com.paperlight.reader.data.BookmarkStore
import com.paperlight.reader.data.ReaderBookmark
import java.util.UUID

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
import kotlinx.coroutines.launch
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
    ReaderImmersiveMode()
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
    var barsVisible by rememberSaveable { mutableStateOf(false) }
    var singleTap by remember { mutableStateOf(preferences.getBoolean("singleTapBars", false)) }
    var fitWidth by remember { mutableStateOf(preferences.getBoolean("imageFitWidth", false)) }
    var settingsVisible by remember { mutableStateOf(false) }
    var notesVisible by remember { mutableStateOf(false) }
    val annotationStore = remember(context) { ReaderAnnotationStore(context) }
    var annotations by remember(book.id) { mutableStateOf(annotationStore.read(book.id)) }
    val bookmarkStore = remember(context) { BookmarkStore(context) }
    var bookmarks by remember(book.id) { mutableStateOf(bookmarkStore.read(book.id)) }
    var editingNote by remember { mutableStateOf<ReaderAnnotation?>(null) }
    var continuous by remember { mutableStateOf(preferences.getBoolean("imageContinuous", false)) }
    val scrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var scrollReady by remember { mutableStateOf(false) }
    val latestProgress by rememberUpdatedState(onProgress)
    var textPage by remember { mutableStateOf<Int?>(null) }
    var pageInput by remember { mutableStateOf("") }
    val ready = continuous || page?.let { kotlin.math.round((it.count - 1) * position).toInt() == it.index } == true && error == null

    fun goTo(index: Int) {
        val current = page ?: return
        val target = index.coerceIn(0, current.count - 1)
        turningDirection = if (target >= current.index) 1 else -1
        if (continuous) scope.launch { scrollState.scrollToItem(target) }
        position = target.toFloat() / (current.count - 1).coerceAtLeast(1)
        onProgress(position)
    }
    fun toggleBookmark() {
        val index = if (continuous) scrollState.firstVisibleItemIndex else page?.index ?: return
        bookmarks = if (bookmarks.any { it.chapter == index }) bookmarks.filterNot { it.chapter == index }
            else bookmarks + ReaderBookmark(index, 0f)
        bookmarkStore.write(book.id, bookmarks)
    }


    LaunchedEffect(book.id, if (continuous) null else position) {
        val result = withContext(Dispatchers.IO) { runCatching {
            if (book.format == "PDF") renderPdfPage(book.localPath, position, 1800)
            else renderComicPage(book, position, 2400)
        } }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        page = result.getOrNull() ?: page
        error = result.exceptionOrNull()?.let { "Couldn’t open this ${book.format} page. " + (it.message ?: "") }
    }
    LaunchedEffect(continuous, page?.count) {
        if (continuous && page != null) {
            scrollState.scrollToItem(kotlin.math.round((page!!.count - 1) * position).toInt())
            scrollReady = true
        } else scrollReady = false
    }
    LaunchedEffect(continuous, scrollReady, page?.count) {
        if (!continuous || !scrollReady) return@LaunchedEffect
        snapshotFlow { scrollState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            position = index.toFloat() / ((page?.count ?: 1) - 1).coerceAtLeast(1)
            latestProgress(position)
        }
    }
    ReaderTheme(ReaderPalette(palette.paper, palette.ink, palette.panel, palette.accent), dark) {
    Box(Modifier.fillMaxSize().background(palette.paper)) {
        Column(Modifier.fillMaxSize()) {
        if (barsVisible) Row(Modifier.fillMaxWidth().background(palette.panel).statusBarsPadding().displayCutoutPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to library", tint = palette.ink) }
            Text(book.title, Modifier.weight(1f), color = palette.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { settingsVisible = true }) { Icon(Icons.Rounded.MoreVert, "Reader settings", tint = palette.ink) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .pointerInput(singleTap) {
                detectTapGestures(onTap = { if (singleTap) barsVisible = !barsVisible },
                    onDoubleTap = { if (!singleTap) barsVisible = !barsVisible })
            }
            .pointerInput(position, page?.index, zoom, continuous) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount ->
                        if (!continuous && zoom == 1f && ready) {
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
            .then(if (continuous) Modifier else Modifier.pointerInput(position) {
                detectTransformGestures { _, delta, factor, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 4f)
                    val limitX = size.width * (zoom - 1f) / 2
                    val limitY = size.height * (zoom - 1f) / 2
                    pan = Offset((pan.x + delta.x).coerceIn(-limitX, limitX),
                        (pan.y + delta.y).coerceIn(-limitY, limitY))
                }
            }), contentAlignment = Alignment.Center) {
            val current = page
            when {
                error != null -> Text(error.orEmpty(), Modifier.padding(16.dp), color = palette.ink)
                current == null -> CircularProgressIndicator(color = palette.accent)
                continuous -> {
                    LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.count, key = { it }) { index ->
                            ScrollImagePage(book, index, current.count)
                        }
                    }
                }
                else -> {
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            if (turningDirection > 0) slideInHorizontally { it }.togetherWith(slideOutHorizontally { -it })
                            else slideInHorizontally { -it }.togetherWith(slideOutHorizontally { it })
                        },
                        contentKey = { it.index },
                        label = "image page turn"
                    ) { shown ->
                        val scroll = rememberScrollState()
                        Box(Modifier.fillMaxSize().background(palette.paper)
                            .then(if (fitWidth) Modifier.verticalScroll(scroll) else Modifier)) {
                            Image(shown.bitmap.asImageBitmap(), "Page ${shown.index + 1}",
                                (if (fitWidth) Modifier.fillMaxWidth().aspectRatio(shown.bitmap.width.toFloat() / shown.bitmap.height)
                                else Modifier.fillMaxSize()).graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x + dragOffset
                                    translationY = pan.y
                                }, contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit)
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
        if (barsVisible) page?.let { current ->
            val requested = kotlin.math.round((current.count - 1) * position).toInt()
            val ready = error != null || requested == current.index
            Row(Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ready && requested > 0, onClick = { goTo(requested - 1) }) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous page", tint = palette.ink)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${requested + 1} / ${current.count}", color = palette.ink,
                        style = MaterialTheme.typography.labelLarge)
                    Text("${(position * 100).toInt()}%", color = palette.ink,
                        style = MaterialTheme.typography.labelSmall)
                }
                IconButton(enabled = ready && requested + 1 < current.count, onClick = { goTo(requested + 1) }) {
                    Icon(Icons.Rounded.ChevronRight, "Next page", tint = palette.ink)
                }
            }
        }
        if (barsVisible) Row(Modifier.fillMaxWidth().background(palette.panel).navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(enabled = ready, onClick = {
                val index = if (continuous) scrollState.firstVisibleItemIndex else page?.index ?: return@IconButton
                editingNote = ReaderAnnotation(UUID.randomUUID().toString(), index, 0, 0, "Page ${index + 1}", "")
            }) { Icon(Icons.Rounded.NoteAdd, "Add page note") }
            IconButton(onClick = { notesVisible = true }) { Icon(Icons.Rounded.Notes, "Notes and bookmarks") }
            IconButton(enabled = ready, onClick = { toggleBookmark() }) {
                Icon(if (bookmarks.any { it.chapter == (if (continuous) scrollState.firstVisibleItemIndex else page?.index) }) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Toggle bookmark")
            }
            IconButton(onClick = { fitWidth = !fitWidth; preferences.edit().putBoolean("imageFitWidth", fitWidth).apply() }) {
                Icon(Icons.Rounded.AspectRatio, if (fitWidth) "Fit whole page" else "Fit page width")
            }
            IconButton(onClick = { dark = !dark; preferences.edit().putInt("lightDarkPalette", if (dark) 1 else 0).apply() }) {
                Icon(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (dark) "Light mode" else "Dark mode")
            }
        }
        }
        if (!barsVisible) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(palette.paper).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(page?.let { "${(if (continuous) scrollState.firstVisibleItemIndex else it.index) + 1} / ${it.count}" }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = palette.ink)
            val currentIndex = if (continuous) scrollState.firstVisibleItemIndex else page?.index
            if (annotations.any { it.chapter == currentIndex }) Icon(Icons.Rounded.Notes,
                "Open notes on this page", Modifier.size(18.dp).clickable { notesVisible = true }, tint = palette.accent)
            Text("${(position * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = palette.ink)
        }
    }
    if (settingsVisible) AlertDialog(onDismissRequest = { settingsVisible = false }, title = { Text("Reader settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (book.format == "PDF" && android.os.Build.VERSION.SDK_INT >= 35) TextButton(onClick = {
                    textPage = if (continuous) scrollState.firstVisibleItemIndex else page?.index
                    settingsVisible = false
                }) { Text("Select page text") }
                Text("Show reading controls", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !singleTap, onClick = { singleTap = false; preferences.edit().putBoolean("singleTapBars", false).apply() }, label = { Text("Double tap") })
                    FilterChip(selected = singleTap, onClick = { singleTap = true; preferences.edit().putBoolean("singleTapBars", true).apply() }, label = { Text("Single tap") })
                }
                Text("Reading mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !continuous, onClick = { continuous = false; preferences.edit().putBoolean("imageContinuous", false).apply() }, label = { Text("Pages") })
                    FilterChip(selected = continuous, onClick = { continuous = true; preferences.edit().putBoolean("imageContinuous", true).apply() }, label = { Text("Scroll") })
                }
                Text("Paper texture", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "Plain", "grain" to "Grain", "linen" to "Linen").forEach { (value, label) ->
                        FilterChip(selected = texture == value, onClick = { texture = value; preferences.edit().putString("texture", value).apply() }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = pageInput, onValueChange = { pageInput = it.filter(Char::isDigit).take(6) },
                    label = { Text("Go to page") }, singleLine = true)
                TextButton(enabled = pageInput.toIntOrNull()?.let { it in 1..(page?.count ?: 0) } == true,
                    onClick = { goTo(pageInput.toInt() - 1); settingsVisible = false }) { Text("Go") }
            }
        }, confirmButton = { TextButton(onClick = { settingsVisible = false }) { Text("Done") } })
    if (notesVisible) AlertDialog(onDismissRequest = { notesVisible = false }, title = { Text("Notes & bookmarks") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (annotations.isEmpty() && bookmarks.isEmpty()) item { Text("Save a page note or bookmark from the reading controls.") }
                items(annotations.sortedBy { it.chapter }, key = { it.id }) { note ->
                    ListItem(headlineContent = { Text("Page ${note.chapter + 1}") }, supportingContent = { Text(listOf(note.quote, note.note).filter { it.isNotBlank() }.joinToString("\n")) },
                        trailingContent = { TextButton(onClick = { editingNote = note }) { Text("Edit") } },
                        modifier = Modifier.clickable { goTo(note.chapter); notesVisible = false })
                }
                items(bookmarks, key = { "bookmark:${it.chapter}" }) { bookmark ->
                    ListItem(headlineContent = { Text("Page ${bookmark.chapter + 1}") },
                        leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
                        trailingContent = { TextButton(onClick = {
                            bookmarks = bookmarks.filterNot { it == bookmark }; bookmarkStore.write(book.id, bookmarks)
                        }) { Text("Remove") } },
                        modifier = Modifier.clickable { goTo(bookmark.chapter); notesVisible = false })
                }
            }
        }, confirmButton = { TextButton(onClick = { notesVisible = false }) { Text("Done") } })
    if (android.os.Build.VERSION.SDK_INT >= 35) textPage?.let { index ->
        PdfTextDialog(book, index, onSave = { annotation, withNote ->
            textPage = null
            if (withNote) editingNote = annotation
            else {
                annotations = annotations + annotation
                annotationStore.write(book.id, annotations)
            }
        }, onDismiss = { textPage = null })
    }
    editingNote?.let { note ->
        ReaderNoteEditor(note, annotations.any { it.id == note.id }, onSave = { text ->
            annotations = annotations.filterNot { it.id == note.id } + note.copy(note = text)
            annotationStore.write(book.id, annotations); editingNote = null
        }, onDelete = {
            annotations = annotations.filterNot { it.id == note.id }; annotationStore.write(book.id, annotations); editingNote = null
        }, onDismiss = { editingNote = null })
    }
    }
}

@Composable
private fun ScrollImagePage(book: BookEntity, index: Int, count: Int) {
    var page by remember(book.id, index) { mutableStateOf<RenderedPage?>(null) }
    var error by remember(book.id, index) { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id, index) {
        try {
            page = withContext(Dispatchers.IO) {
                val fraction = index.toFloat() / (count - 1).coerceAtLeast(1)
                if (book.format == "PDF") renderPdfPage(book.localPath, fraction, 1200)
                else renderComicPage(book, fraction, 1600)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) { error = failure.message ?: "Could not load this page." }
    }
    val rendered = page
    if (rendered != null) Image(rendered.bitmap.asImageBitmap(), "Page ${index + 1}",
        Modifier.fillMaxWidth().aspectRatio(rendered.bitmap.width.toFloat() / rendered.bitmap.height), contentScale = ContentScale.FillWidth)
    else Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        if (error == null) CircularProgressIndicator() else Text(error.orEmpty(), Modifier.padding(16.dp))
    }
}
