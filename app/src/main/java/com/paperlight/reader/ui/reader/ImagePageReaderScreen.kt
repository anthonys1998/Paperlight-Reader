package com.paperlight.reader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.rounded.GridView
import com.paperlight.reader.data.PdfPageSource
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
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private data class ImageReaderPalette(val paper: Color, val ink: Color, val panel: Color, val accent: Color)

private fun imageReaderPalette(dark: Boolean) = if (dark) ImageReaderPalette(
    Color(0xFF1D201F), Color(0xFFE1DFD5), Color(0xFF292D2B), Color(0xFFB4CA9A)
) else ImageReaderPalette(
    Color(0xFFF9F9F5), Color(0xFF2C302E), Color(0xFFE8EBE6), Color(0xFF395F55)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImagePageReaderScreen(book: BookEntity, onBack: () -> Unit, onProgress: (Float) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pdfSource = remember(book.id) { if (book.format == "PDF") PdfPageSource(book.localPath) else null }
    DisposableEffect(pdfSource) { onDispose { pdfSource?.close() } }
    val preferences = remember(context) { context.getSharedPreferences("reader", 0) }
    val reduceMotion = remember(context) { context.getSharedPreferences("accessibility", 0).getBoolean("reduceMotion", false) }
    var position by rememberSaveable(book.id) { mutableFloatStateOf(book.progress) }
    var page by remember(book.id) { mutableStateOf<RenderedPage?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var zoom by remember(position) { mutableFloatStateOf(1f) }
    var pan by remember(position) { mutableStateOf(Offset.Zero) }
    var dark by remember { mutableStateOf(preferences.getInt("lightDarkPalette", 0) == 1) }
    var texture by remember { mutableStateOf(preferences.getString("texture", "none") ?: "none") }
    var turningDirection by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var swipeCommitted by remember { mutableStateOf(false) }
    var turnStartFraction by remember { mutableFloatStateOf(0f) }
    ReaderImmersiveMode(dark)
    val palette = imageReaderPalette(dark)
    var barsVisible by rememberSaveable { mutableStateOf(false) }
    var singleTap by remember { mutableStateOf(preferences.getBoolean("singleTapBars", false)) }
    var fitWidth by remember { mutableStateOf(preferences.getBoolean("imageFitWidth", false)) }
    var thumbnailsVisible by remember { mutableStateOf(false) }
    var scrubPage by remember { mutableStateOf<Float?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
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
    val ready = (continuous && page != null && error == null) || page?.let { kotlin.math.round((it.count - 1) * position).toInt() == it.index } == true && error == null

    val currentReady by rememberUpdatedState(ready && !swipeCommitted)

    fun goTo(index: Int) {
        val current = page ?: return
        val target = index.coerceIn(0, current.count - 1)
        turningDirection = if (target >= current.index) 1 else -1
        if (continuous) scope.launch { scrollState.scrollToItem(target) }
        position = target.toFloat() / (current.count - 1).coerceAtLeast(1)
    }
    fun toggleBookmark() {
        val index = if (continuous) scrollState.firstVisibleItemIndex else page?.index ?: return
        bookmarks = if (bookmarks.any { it.chapter == index }) bookmarks.filterNot { it.chapter == index }
            else bookmarks + ReaderBookmark(index, 0f)
        bookmarkStore.write(book.id, bookmarks)
    }


    LaunchedEffect(book.id, if (continuous) null else position, loadAttempt) {
        error = null
        val result = withContext(Dispatchers.IO) { runCatching {
            if (pdfSource != null) pdfSource.render(position, 1400)
            else renderComicPage(book, position, 2400)
        } }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        page = result.getOrNull() ?: page
        error = result.exceptionOrNull()?.let { "Couldn’t open this ${book.format} page. " + (it.message ?: "") }
        if (result.isSuccess && !continuous) {
            latestProgress(position)
            val current = result.getOrThrow()
            if (pdfSource != null) {
                for (index in listOf(current.index + 1, current.index - 1).filter { it in 0 until current.count }) {
                    try {
                        pdfSource.render(index.toFloat() / (current.count - 1).coerceAtLeast(1), 1400)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { /* A failed prefetch is retried by explicit navigation. */ }
                }
            }
        }
    }
    LaunchedEffect(page?.index) {
        if (swipeCommitted) {
            delay(350)
            dragOffset = 0f
            swipeCommitted = false
            turnStartFraction = 0f
        }
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
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 24.dp).clipToBounds()
            .pointerInput(singleTap) {
                detectTapGestures(onTap = { if (singleTap) barsVisible = !barsVisible },
                    onDoubleTap = { if (!singleTap) barsVisible = !barsVisible })
            }
            .then(if (continuous) Modifier else Modifier.pointerInput(book.id, fitWidth) {
                var settling: kotlinx.coroutines.Job? = null
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    settling?.cancel()
                    if (!currentReady) return@awaitEachGesture
                    var accumulated = Offset.Zero
                    var swiping = false
                    var transformed = false
                    var pressed: Boolean
                    do {
                        val event = awaitPointerEvent()
                        pressed = event.changes.any { it.pressed }
                        if (event.changes.any { it.isConsumed } && !swiping && !transformed) break
                        val delta = event.calculatePan()
                        val factor = event.calculateZoom()
                        val multiple = event.changes.count { it.pressed } > 1
                        if (multiple || zoom > 1.01f || transformed) {
                            transformed = true
                            swiping = false
                            dragOffset = 0f
                            val nextZoom = (zoom * factor).coerceIn(1f, 5f)
                            val ratio = nextZoom / zoom
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focal = event.calculateCentroid(useCurrent = false)
                            val anchored = if (focal != Offset.Unspecified) (pan + center - focal) * ratio + focal - center + delta else pan + delta
                            zoom = nextZoom
                            val limitX = size.width * (zoom - 1f) / 2
                            val limitY = size.height * (zoom - 1f) / 2
                            pan = Offset(anchored.x.coerceIn(-limitX, limitX), anchored.y.coerceIn(-limitY, limitY))
                            event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                        } else {
                            accumulated += delta
                            if (abs(accumulated.x) > viewConfiguration.touchSlop && abs(accumulated.x) > abs(accumulated.y) * 1.4f) swiping = true
                            if (swiping) {
                                dragOffset = accumulated.x.coerceIn(-size.width * .9f, size.width * .9f)
                                event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                            }
                        }
                    } while (pressed)
                    if (swiping && currentReady && abs(dragOffset) > size.width * .20f) {
                        val current = page
                        val direction = if (dragOffset < 0) 1 else -1
                        if (current != null && current.index + direction in 0 until current.count) {
                            swipeCommitted = true
                            turnStartFraction = abs(dragOffset) / size.width
                            goTo(current.index + direction)
                        } else dragOffset = 0f
                    } else {
                        val start = dragOffset
                        if (reduceMotion) dragOffset = 0f
                        else settling = scope.launch {
                            androidx.compose.animation.core.animate(start, 0f, animationSpec = androidx.compose.animation.core.tween(120)) { value, _ -> dragOffset = value }
                        }
                    }
                }
            }), contentAlignment = Alignment.Center) {
            val current = page
            when {
                error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = palette.ink)
                    TextButton(onClick = { loadAttempt++ }) { Text("Retry") }
                    TextButton(onClick = onBack) { Text("Back to library") }
                }
                current == null -> CircularProgressIndicator(color = palette.accent)
                continuous -> {
                    LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.count, key = { it }) { index ->
                            ScrollImagePage(book, index, current.count, pdfSource)
                        }
                    }
                }
                else -> {
                    val transitionStart = turnStartFraction
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            if (reduceMotion) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                            else if (turningDirection > 0) slideInHorizontally { (it * (1f - transitionStart)).toInt() }.togetherWith(slideOutHorizontally { -it })
                            else slideInHorizontally { (-it * (1f - transitionStart)).toInt() }.togetherWith(slideOutHorizontally { it })
                        },
                        contentKey = { it.index },
                        label = "image page turn"
                    ) { shown ->
                        val scroll = rememberScrollState()
                        Box(Modifier.fillMaxSize().background(palette.paper).graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x + if (swipeCommitted && shown.index == current.index) 0f else dragOffset
                            translationY = pan.y
                        }.then(if (fitWidth) Modifier.verticalScroll(scroll, enabled = zoom <= 1.01f) else Modifier)) {
                            Image(shown.bitmap.asImageBitmap(), "Page ${shown.index + 1}",
                                (if (fitWidth) Modifier.fillMaxWidth().aspectRatio(shown.bitmap.width.toFloat() / shown.bitmap.height)
                                else Modifier.fillMaxSize()), contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit)
                        }
                    }
                }
            }
            if (texture != "none") {
                Canvas(Modifier.matchParentSize()) {
                    val step = if (texture == "grid") 20.dp.toPx() else if (texture == "linen") 4.dp.toPx() else 7.dp.toPx()
                    val alpha = if (texture == "linen") .035f else .022f
                    var y = 0f
                    while (y < size.height) {
                        drawLine(palette.ink.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y))
                        y += step
                    }
                    if (texture == "linen" || texture == "grid") {
                        var x = 0f
                        while (x < size.width) {
                            drawLine(palette.ink.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height))
                            x += step
                        }
                    }
                }
            }
        }
        if (!ready && page != null && error == null) CircularProgressIndicator(
            Modifier.align(Alignment.Center).size(32.dp), color = palette.accent)
        if (barsVisible) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().shadow(8.dp).background(palette.panel).statusBarsPadding().displayCutoutPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to library", tint = palette.ink) }
            Text(book.title, Modifier.weight(1f), color = palette.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { settingsVisible = true }) { Icon(Icons.Rounded.MoreVert, "Reader settings", tint = palette.ink) }
        }
        if (zoom > 1.01f && !continuous) FilledTonalButton(
            onClick = { zoom = 1f; pan = Offset.Zero },
            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp)) { Text("${(zoom * 100).toInt()}% · Reset") }
        if (barsVisible) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().shadow(12.dp).background(palette.panel)) {
        page?.let { current ->
            val requested = kotlin.math.round((current.count - 1) * position).toInt()
            Slider(value = scrubPage ?: requested.toFloat(),
                onValueChange = { scrubPage = it },
                onValueChangeFinished = { scrubPage?.let { goTo(it.toInt()) }; scrubPage = null },
                valueRange = 0f..(current.count - 1).coerceAtLeast(1).toFloat(),
                enabled = current.count > 1 && error == null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ready && requested > 0, onClick = { goTo(requested - 1) }) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous page", tint = palette.ink)
                }
                Column(Modifier.clickable { thumbnailsVisible = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(scrubPage?.toInt() ?: requested) + 1} / ${current.count}", color = palette.ink,
                        style = MaterialTheme.typography.labelLarge)
                    Text("${(position * 100).toInt()}%", color = palette.ink,
                        style = MaterialTheme.typography.labelSmall)
                }
                IconButton(enabled = ready && requested + 1 < current.count, onClick = { goTo(requested + 1) }) {
                    Icon(Icons.Rounded.ChevronRight, "Next page", tint = palette.ink)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(palette.panel).navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(enabled = ready, onClick = {
                val index = if (continuous) scrollState.firstVisibleItemIndex else page?.index ?: return@IconButton
                editingNote = ReaderAnnotation(UUID.randomUUID().toString(), index, 0, 0, "Page ${index + 1}", "")
            }) { Icon(Icons.Rounded.NoteAdd, "Add page note") }
            IconButton(onClick = { thumbnailsVisible = true }) { Icon(Icons.Rounded.GridView, "Browse pages") }
            IconButton(onClick = { notesVisible = true }) { Icon(Icons.Rounded.Notes, "Notes and bookmarks") }
            IconButton(enabled = ready, onClick = { toggleBookmark() }) {
                Icon(if (bookmarks.any { it.chapter == (if (continuous) scrollState.firstVisibleItemIndex else page?.index) }) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Toggle bookmark")
            }
            IconButton(onClick = { fitWidth = !fitWidth; zoom = 1f; pan = Offset.Zero; preferences.edit().putBoolean("imageFitWidth", fitWidth).apply() }) {
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
    if (thumbnailsVisible && page != null) ModalBottomSheet(onDismissRequest = { thumbnailsVisible = false }, containerColor = palette.panel) {
        Text("Pages", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        val count = page!!.count
        val initial = kotlin.math.round(position * (count - 1)).toInt()
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = (initial / 3) * 3)
        LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(count) { index ->
                Column(Modifier.clickable { goTo(index); thumbnailsVisible = false }, horizontalAlignment = Alignment.CenterHorizontally) {
                    PageThumbnail(book, index, count, pdfSource, index == initial)
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    if (settingsVisible) AlertDialog(onDismissRequest = { settingsVisible = false }, title = { Text("Reader settings") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Page fit", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !fitWidth, onClick = { fitWidth = false; zoom = 1f; pan = Offset.Zero; preferences.edit().putBoolean("imageFitWidth", false).apply() }, label = { Text("Whole page") })
                    FilterChip(selected = fitWidth, onClick = { fitWidth = true; zoom = 1f; pan = Offset.Zero; preferences.edit().putBoolean("imageFitWidth", true).apply() }, label = { Text("Page width") })
                }
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
                    FilterChip(selected = !continuous, onClick = { continuous = false; zoom = 1f; pan = Offset.Zero; preferences.edit().putBoolean("imageContinuous", false).apply() }, label = { Text("Pages") })
                    FilterChip(selected = continuous, onClick = { continuous = true; zoom = 1f; pan = Offset.Zero; preferences.edit().putBoolean("imageContinuous", true).apply() }, label = { Text("Scroll") })
                }
                TexturePicker(texture, palette.paper, palette.ink) { texture = it; preferences.edit().putString("texture", it).apply() }
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
private fun ScrollImagePage(book: BookEntity, index: Int, count: Int, pdfSource: PdfPageSource?) {
    var page by remember(book.id, index) { mutableStateOf<RenderedPage?>(null) }
    var error by remember(book.id, index) { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id, index) {
        try {
            page = withContext(Dispatchers.IO) {
                val fraction = index.toFloat() / (count - 1).coerceAtLeast(1)
                if (pdfSource != null) pdfSource.render(fraction, 1400)
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

@Composable
private fun PageThumbnail(book: BookEntity, index: Int, count: Int, pdfSource: PdfPageSource?, selected: Boolean) {
    var thumbnail by remember(book.id, index) { mutableStateOf<RenderedPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(book.id, index) {
        try {
            val fraction = index.toFloat() / (count - 1).coerceAtLeast(1)
            thumbnail = withContext(Dispatchers.IO) {
                pdfSource?.render(fraction, 220) ?: renderComicPage(book, fraction, 220)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { failed = true }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(.7f)
        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
        .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        thumbnail?.let { Image(it.bitmap.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) }
            ?: if (failed) Text("Unavailable", style = MaterialTheme.typography.labelSmall) else CircularProgressIndicator(Modifier.size(20.dp))
    }
}
