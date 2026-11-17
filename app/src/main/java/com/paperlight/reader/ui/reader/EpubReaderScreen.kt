package com.paperlight.reader.ui.reader

import android.app.Activity
import android.os.BatteryManager
import android.text.format.DateFormat
import kotlinx.coroutines.delay
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.openLocalText
import com.paperlight.reader.data.BookmarkStore
import com.paperlight.reader.data.ReaderBookmark
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.data.ReaderAnnotationStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import com.paperlight.reader.epub.EpubBook
import com.paperlight.reader.epub.EpubBookParser
import com.paperlight.reader.epub.EpubChapter
import com.paperlight.reader.epub.EpubTocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReaderScreen(
    book: BookEntity,
    libraryBooks: List<BookEntity>,
    onBack: () -> Unit,
    onProgress: (Float) -> Unit
) {
    val context = LocalContext.current
    val mainView = LocalView.current
    val readerLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mainView, readerLifecycle) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, mainView) }
        val oldBehavior = controller?.systemBarsBehavior
        val oldLightStatus = controller?.isAppearanceLightStatusBars
        val window = activity?.window
        val oldContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isStatusBarContrastEnforced else null
        val oldNavContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        val oldStatusColor = window?.statusBarColor
        val oldNavigationColor = window?.navigationBarColor
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isStatusBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        val oldCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 28)
            window?.attributes?.layoutInDisplayCutoutMode else null
        if (android.os.Build.VERSION.SDK_INT >= 28 && window != null) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        fun immerse() {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) immerse() }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) immerse()
        }
        mainView.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        readerLifecycle.addObserver(observer)
        immerse()
        onDispose {
            mainView.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            readerLifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (oldBehavior != null) controller?.systemBarsBehavior = oldBehavior
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldContrast != null)
                window?.isStatusBarContrastEnforced = oldContrast
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldNavContrast != null)
                window?.isNavigationBarContrastEnforced = oldNavContrast
            if (oldStatusColor != null) window?.statusBarColor = oldStatusColor
            if (oldNavigationColor != null) window?.navigationBarColor = oldNavigationColor
            if (oldLightStatus != null) controller?.isAppearanceLightStatusBars = oldLightStatus
            if (android.os.Build.VERSION.SDK_INT >= 28 && window != null && oldCutoutMode != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = oldCutoutMode }
            }
        }
    }
    val latestProgress by rememberUpdatedState(onProgress)
    val preferences = remember { context.getSharedPreferences("reader", 0) }
    val bookmarkStore = remember(context) { BookmarkStore(context) }
    val annotationStore = remember(context) { ReaderAnnotationStore(context) }
    var annotations by remember(book.id) { mutableStateOf(annotationStore.read(book.id)) }
    var selection by remember(book.id) { mutableStateOf<ReaderAnnotation?>(null) }
    var openNote by remember(book.id) { mutableStateOf<ReaderAnnotation?>(null) }
    var colorPicker by remember(book.id) { mutableStateOf<ReaderAnnotation?>(null) }
    var editingNote by remember(book.id) { mutableStateOf<Pair<BookEntity, ReaderAnnotation>?>(null) }
    var showNotes by remember { mutableStateOf(false) }
    var otherNotesBook by remember(book.id) { mutableStateOf<BookEntity?>(null) }
    var dictionaryTerm by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var toolMenu by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var barsVisible by remember(book.id) { mutableStateOf(false) }
    var singleTap by remember { mutableStateOf(preferences.getBoolean("singleTapBars", false)) }
    var toolMessage by remember { mutableStateOf<String?>(null) }
    var pendingScript by remember(book.id) { mutableStateOf<Pair<Int, String>?>(null) }
    val speech = remember(context, book.id) { ReaderSpeech(context) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(speech, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) speech.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.close() }
    }
    var bookmarks by remember(book.id) { mutableStateOf(bookmarkStore.read(book.id)) }
    var chapterTurnDirection by remember(book.id) { mutableIntStateOf(0) }
    var chapterSnapshot by remember(book.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var snapshotReady by remember(book.id) { mutableStateOf(false) }
    val snapshotProgress = remember(book.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(snapshotReady, chapterSnapshot) {
        if (snapshotReady && chapterSnapshot != null) {
            snapshotProgress.animateTo(1f, androidx.compose.animation.core.tween(180))
            chapterSnapshot = null
            snapshotReady = false
            snapshotProgress.snapTo(0f)
        }
    }
    var scrollMode by remember { mutableStateOf(preferences.getBoolean("scrollMode", false)) }
    var flip by remember { mutableStateOf(preferences.getBoolean("pageFlip", true)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var reportedChapter by remember(book.id) { mutableIntStateOf(-1) }
    var fraction by remember(book.id) { mutableFloatStateOf(0f) }
    var parsed by remember(book.id) { mutableStateOf<EpubBook?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var chapterIndex by remember(book.id) { mutableIntStateOf(0) }
    var page by remember(book.id) { mutableIntStateOf(0) }
    var pageCount by remember(book.id) { mutableIntStateOf(1) }
    var initialChapterFraction by remember(book.id) { mutableFloatStateOf(0f) }
    var isRestoringPosition by remember(book.id) { mutableStateOf(true) }
    var themeMode by remember { mutableIntStateOf(preferences.getInt("lightDarkPalette", if (preferences.getInt("palette", 0) == 2) 1 else 0).coerceIn(0, 1)) }
    var showContents by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var appearance by remember {
        mutableStateOf(ReadingAppearance(
            preferences.getFloat("textSize", 1f), preferences.getFloat("lineHeight", 1.65f),
            preferences.getInt("margins", 24), preferences.getString("font", "serif") ?: "serif",
            preferences.getString("alignment", "original") ?: "original",
            preferences.getString("texture", "none") ?: "none",
        ))
    }
    var customFont by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            val imported = withContext(Dispatchers.IO) { runCatching { importReadingFont(context, uri) }.getOrNull() }
            if (imported == null) toolMessage = "Choose a TTF or OTF font under 6 MB."
            else {
                customFont = imported
                appearance = appearance.copy(font = "custom")
            }
        }
    }
    LaunchedEffect(context) {
        customFont = withContext(Dispatchers.IO) { runCatching { fontData(customFontFile(context)) }.getOrNull() }
    }
    LaunchedEffect(appearance, themeMode) {
        preferences.edit().putInt("lightDarkPalette", themeMode).putFloat("textSize", appearance.size)
            .putFloat("lineHeight", appearance.lineHeight).putInt("margins", appearance.margins)
            .putString("font", appearance.font).putString("alignment", appearance.alignment).putString("texture", appearance.texture).apply()
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var sentFont: String? by remember { mutableStateOf(null) }
    var loadedChapterIndex by remember(book.id) { mutableIntStateOf(-1) }

    LaunchedEffect(book.id) {
        runCatching {
            withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, "epub/${book.id}")
                if (book.format == "EPUB") EpubBookParser.open(File(book.localPath), directory)
                else {
                    directory.parentFile?.mkdirs()
                    openLocalText(book, directory)
                }
            }
        }.onSuccess { publication ->
            if (book.progress > 0f) {
                val scaled = book.progress * publication.chapters.size
                chapterIndex = scaled.toInt().coerceIn(0, publication.chapters.lastIndex)
                initialChapterFraction = (scaled - chapterIndex).coerceIn(0f, 1f)
            }
            parsed = publication
        }.onFailure { error = it.message ?: "Could not open this ${book.format} book" }
    }

    val publication = parsed
    if (publication == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) CircularProgressIndicator() else Text(error ?: "Unable to open book")
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("Back to library") }
        }
        return
    }

    val savedPosition = if (chapterIndex == publication.chapters.lastIndex && fraction >= 1f) 1f
        else (chapterIndex + fraction * .9999f) / publication.chapters.size
    LaunchedEffect(reportedChapter, chapterIndex, savedPosition) {
        if (reportedChapter == chapterIndex) latestProgress(savedPosition)
    }
    val displayProgress = if (reportedChapter == chapterIndex) savedPosition else book.progress
    val chapterTitles = remember(publication) {
        val titles = publication.tableOfContents.groupBy { it.chapterIndex }.mapValues { (_, entries) -> entries.first().title }
        publication.chapters.mapIndexed { index, entry -> titles[index] ?: entry.title }
    }
    val chapter = publication.chapters[chapterIndex]
    val palette = readerPalettes[themeMode]
    val background = palette.paper
    SideEffect {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, mainView).isAppearanceLightStatusBars = themeMode == 0
        }
    }
    val foreground = palette.ink
    val toc = publication.tableOfContents


    fun saveAnnotation(target: BookEntity, item: ReaderAnnotation, delete: Boolean = false) {
        val existing = if (target.id == book.id) annotations else annotationStore.read(target.id)
        val updated = existing.filterNot { it.id == item.id } + if (delete) emptyList() else listOf(item)
        annotationStore.write(target.id, updated)
        if (target.id == book.id) annotations = updated
    }

    fun openLocation(target: Int, script: String) {
        if (target == chapterIndex && reportedChapter == chapterIndex) {
            webView?.evaluateJavascript(script, null)
        } else {
            pendingScript = target to script
            chapterTurnDirection = 0
            initialChapterFraction = 0f
            isRestoringPosition = true
            reportedChapter = -1
            chapterIndex = target
        }
    }

    fun lookup(text: String) {
        if (!openDictionary(context, text)) toolMessage = "Open Wiktionary in a web browser to look up this text."
    }

    fun readChapter(startOffset: Int? = null) {
        if (!speech.ready) {
            toolMessage = "Android text-to-speech is unavailable. Check your installed speech engine."
            return
        }
        if (speech.speaking) {
            speech.stop()
            return
        }
        val sourceChapter = chapterIndex
        webView?.evaluateJavascript("window.paperlightChapterText?.($startOffset)") { result ->
            val text = runCatching { JSONTokener(result ?: "null").nextValue() as? String }.getOrNull()
            if (chapterIndex == sourceChapter) {
                if (text.isNullOrBlank()) toolMessage = "No readable text at this position."
                else speech.play(text, publication.language)
            }
        }
    }

    LaunchedEffect(annotations, reportedChapter, chapterIndex, webView) {
        if (reportedChapter == chapterIndex) {
            val ranges = JSONArray()
            annotations.filter { it.chapter == chapterIndex }.forEach {
                ranges.put(JSONArray().put(it.start).put(it.end).put(it.color).put(it.id).put(it.note.isNotBlank()))
            }
            webView?.evaluateJavascript("window.paperlightHighlights?.($ranges)", null)
        }
    }
    LaunchedEffect(webView, reportedChapter, chapterIndex, appearance, customFont) {
        if (reportedChapter != chapterIndex) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        val settings = appearance.json()
        val font = customFont
        if (appearance.font == "custom" && font != null && sentFont !== font) {
            sentFont = font
            view.evaluateJavascript("window.paperlightFontParts = []", null)
            font.chunked(48000).forEach { chunk ->
                view.evaluateJavascript("window.paperlightFontParts.push(${JSONObject.quote(chunk)})", null)
            }
            view.evaluateJavascript("window.paperlightSetAppearance?.($settings,window.paperlightFontParts.join('')); delete window.paperlightFontParts", null)
        } else {
            view.evaluateJavascript("window.paperlightSetAppearance?.($settings)", null)
        }
    }
    LaunchedEffect(themeMode, webView, reportedChapter, chapterIndex) {
        if (reportedChapter == chapterIndex) {
            val theme = listOf("white", "dark")[themeMode]
            webView?.evaluateJavascript("window.paperlightTheme?.('$theme')", null)
        }
    }
    LaunchedEffect(webView, chapterIndex, viewport.width > 0 && viewport.height > 0) {
        val view = webView ?: return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0 || loadedChapterIndex == chapterIndex)
            return@LaunchedEffect
        val requestedChapter = chapterIndex
        val html = withContext(Dispatchers.IO) { runCatching { chapterHtml(chapter) } }.getOrElse {
            chapterSnapshot = null
            toolMessage = it.message ?: "Could not load this section."
            return@LaunchedEffect
        }
        loadedChapterIndex = requestedChapter
        loadChapter(view, chapter, html)
    }

    LaunchedEffect(chapterIndex) {
        speech.stop()
        selection = null
    }

    ReaderTheme(palette, dark = themeMode == 1) {
        Box(Modifier.fillMaxSize().background(background)) {
            Box(Modifier.matchParentSize().padding(bottom = 23.dp)
                .onSizeChanged { viewport = it }) {
                AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = false
                            settings.allowFileAccess = true // EPUB-relative images and stylesheets are local files.
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.domStorageEnabled = false
                            settings.blockNetworkLoads = true
                            isHorizontalScrollBarEnabled = false
                            isVerticalScrollBarEnabled = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webChromeClient = WebChromeClient()
                            addJavascriptInterface(object {
                                @JavascriptInterface fun onNoteTap(sourceChapter: Int, id: String) {
                                    mainView.post {
                                        if (sourceChapter == chapterIndex) {
                                            openNote = annotations.firstOrNull {
                                                it.id == id && it.chapter == sourceChapter && it.note.isNotBlank()
                                            }
                                        }
                                    }
                                }

                                @JavascriptInterface fun onReaderTap(sourceChapter: Int, tapCount: Int) {
                                    mainView.post {
                                        if (sourceChapter == chapterIndex && (singleTap || tapCount == 2)) {
                                            barsVisible = !barsVisible
                                        }
                                    }
                                }

                                @JavascriptInterface fun onSelection(sourceChapter: Int, start: Int, end: Int, quote: String) {
                                    mainView.post {
                                        if (sourceChapter != chapterIndex) return@post
                                        selection = if (start >= 0 && end > start && quote.isNotBlank()) {
                                            annotations.firstOrNull { it.chapter == sourceChapter && it.start == start && it.end == end }
                                                ?: ReaderAnnotation(UUID.randomUUID().toString(), sourceChapter, start, end, quote, "")
                                        } else null
                                    }
                                }

                                @JavascriptInterface fun onSearchMiss(sourceChapter: Int) {
                                    mainView.post {
                                        if (sourceChapter == chapterIndex) toolMessage = "Could not locate this match in the displayed text."
                                    }
                                }

                                @JavascriptInterface fun onPage(current: Int, total: Int, offset: Float, sourceChapter: Int, scrolling: Boolean) {
                                    mainView.post {
                                        if (sourceChapter != chapterIndex || scrolling != scrollMode) return@post
                                        page = current.coerceAtLeast(0)
                                        pageCount = total.coerceAtLeast(1)
                                        fraction = offset.coerceIn(0f, 1f)
                                        reportedChapter = sourceChapter
                                    }
                                }
                                @JavascriptInterface fun onChapterTurn(sourceChapter: Int, direction: Int) {
                                    mainView.post {
                                        if (sourceChapter != chapterIndex || chapterSnapshot != null || direction !in listOf(-1, 1)) return@post
                                        val target = chapterIndex + direction
                                        if (target !in publication.chapters.indices) return@post
                                        val view = webView
                                        if (!scrollMode && view != null && view.width > 0 && view.height > 0 &&
                                            android.provider.Settings.Global.getFloat(context.contentResolver,
                                                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
                                            chapterSnapshot = runCatching {
                                                android.graphics.Bitmap.createBitmap(view.width, view.height,
                                                    android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                                                    view.draw(android.graphics.Canvas(bitmap))
                                                }
                                            }.getOrNull()
                                        }
                                        chapterTurnDirection = direction
                                        initialChapterFraction = if (direction < 0) 1f else 0f
                                        isRestoringPosition = true
                                        reportedChapter = -1
                                        chapterIndex = target
                                    }
                                }
                            }, "Paperlight")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    val fraction = if (isRestoringPosition) initialChapterFraction else 0f
                                    sentFont = null
                                    val loadingChapter = chapterIndex
                                    view.evaluateJavascript(buildReaderScript(
                                        themeMode, appearance.size, fraction, scrollMode, loadingChapter,
                                        view.width, view.height, flip,
                                        lastChapterIndex = publication.chapters.lastIndex,
                                    )) {
                                        if (chapterIndex == loadingChapter) {
                                            pendingScript?.takeIf { it.first == loadingChapter }?.let {
                                                pendingScript = null
                                                view.evaluateJavascript(it.second, null)
                                            }
                                            isRestoringPosition = false
                                            initialChapterFraction = 0f
                                            if (chapterSnapshot != null) {
                                                view.postVisualStateCallback(loadingChapter.toLong(),
                                                    object : WebView.VisualStateCallback() {
                                                        override fun onComplete(requestId: Long) {
                                                            if (chapterIndex == loadingChapter) snapshotReady = true
                                                        }
                                                    })
                                            }
                                        }
                                    }
                                }
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                    if (url.startsWith("file:", ignoreCase = true)) {
                                        val uri = Uri.parse(url)
                                        val targetPath = runCatching { uri.path?.let { File(it).canonicalPath } }.getOrNull()
                                        val targetChapter = publication.chapters.indexOfFirst { it.file.canonicalPath == targetPath }
                                        if (targetChapter >= 0) {
                                            val script = uri.fragment?.let { "window.paperlightAnchor?.(${JSONObject.quote(it)})" }
                                                ?: "window.paperlightRestore?.(0)"
                                            openLocation(targetChapter, script)
                                        }
                                        return true
                                    }
                                    return true
                                }
                            }
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        view.removeJavascriptInterface("Paperlight")
                        view.destroy()
                    },
                    update = { view ->
                        webView = view

                    }
                )
                chapterSnapshot?.let { bitmap ->
                    androidx.compose.foundation.Image(bitmap.asImageBitmap(), null,
                        Modifier.matchParentSize().graphicsLayer {
                            val progress = snapshotProgress.value
                            if (flip) {
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                    if (chapterTurnDirection > 0) 0f else 1f, .5f)
                                cameraDistance = size.width * 2f
                                rotationY = -chapterTurnDirection * 89f * progress
                                alpha = 1f - progress * progress
                            } else {
                                translationX = -chapterTurnDirection * size.width * progress
                            }
                        })
                }
            }
            ReaderFooter(Modifier.align(Alignment.BottomCenter), palette, chapterTitles[chapterIndex],
                if (!scrollMode && reportedChapter == chapterIndex) "${page + 1}/$pageCount" else null,
                displayProgress)
            if (barsVisible) {
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().readerEdgeShadow(topBar = true),
                    color = palette.panel, contentColor = foreground) {
                    Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                        .heightIn(min = 52.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                        Text(publication.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            IconButton(onClick = { toolMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Reader settings")
                            }
                            DropdownMenu(expanded = toolMenu, onDismissRequest = { toolMenu = false }) {
                                DropdownMenuItem(text = { Text("Reader settings") },
                                    onClick = { toolMenu = false; settingsOpen = true })
                                DropdownMenuItem(text = { Text("Appearance") },
                                    onClick = { toolMenu = false; showAppearance = true })
                            }
                        }
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().readerEdgeShadow(topBar = false),
                    color = palette.panel, contentColor = foreground) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewNext(webView, -1) }) {
                                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous")
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(chapterTitles[chapterIndex], style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val position = if (!scrollMode && reportedChapter == chapterIndex)
                                    "${page + 1} / $pageCount  ·  " else ""
                                Text("$position${(displayProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { viewNext(webView, 1) }) {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next")
                            }
                        }
                        LinearProgressIndicator(progress = { displayProgress }, modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = palette.accent, trackColor = palette.accent.copy(alpha = .2f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ReaderBottomAction(Icons.Rounded.List, "Contents") { showContents = true }
                            ReaderBottomAction(Icons.Rounded.TextFields, "Type") { showAppearance = true }
                            ReaderBottomAction(if (themeMode == 0) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                                if (themeMode == 0) "Dark" else "Light") { themeMode = 1 - themeMode }
                            ReaderBottomAction(Icons.Rounded.Search, "Search") { showSearch = true }
                            ReaderBottomAction(Icons.Rounded.Edit, "Notes") { showNotes = true }
                            ReaderBottomAction(Icons.Rounded.PlayArrow, if (speech.speaking) "Stop" else "Listen",
                                enabled = reportedChapter == chapterIndex) { readChapter() }
                        }
                    }
                }
            }
            selection?.takeIf { it.chapter == chapterIndex }?.let { selected ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = if (barsVisible) 139.dp else 31.dp),
                    tonalElevation = 5.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row {
                        TextButton(onClick = {
                            colorPicker = selected
                            selection = null
                            webView?.evaluateJavascript("window.getSelection()?.removeAllRanges()", null)
                        }) { Text("Highlight") }
                        TextButton(onClick = {
                            editingNote = book to selected
                            selection = null
                            webView?.evaluateJavascript("window.getSelection()?.removeAllRanges()", null)
                        }) { Text("Note") }
                        TextButton(onClick = {
                            dictionaryTerm = selected.quote.take(100)
                            selection = null
                            webView?.evaluateJavascript("window.getSelection()?.removeAllRanges()", null)
                        }) { Text("Dictionary") }
                        TextButton(onClick = {
                            readChapter(selected.start)
                            webView?.evaluateJavascript("window.getSelection()?.removeAllRanges()", null)
                        }) { Text("Listen") }
                    }
                }
            }
        }
        if (showContents) {
            ChapterList(
                titles = chapterTitles,
                toc = toc,
                currentChapter = chapterIndex,
                background = palette.panel,
                foreground = foreground,
                bookmarks = bookmarks.filter { it.chapter in chapterTitles.indices },
                canBookmark = reportedChapter == chapterIndex &&
                    ReaderBookmark(chapterIndex, fraction) !in bookmarks,
                onBookmark = {
                    bookmarks = (bookmarks + ReaderBookmark(chapterIndex, fraction)).distinct()
                    bookmarkStore.write(book.id, bookmarks)
                },
                onRemoveBookmark = {
                    bookmarks = bookmarks - it
                    bookmarkStore.write(book.id, bookmarks)
                },
                onOpenBookmark = { bookmark ->
                    showContents = false
                    if (bookmark.chapter == chapterIndex && reportedChapter == chapterIndex) {
                        webView?.evaluateJavascript("window.paperlightRestore?.(${bookmark.fraction})", null)
                    } else if (bookmark.chapter != chapterIndex) {
                        chapterTurnDirection = 0
                        reportedChapter = -1
                        initialChapterFraction = bookmark.fraction
                        isRestoringPosition = true
                        chapterIndex = bookmark.chapter
                    }
                },
                onDismiss = { showContents = false },
                onSelect = { entry ->
                    showContents = false
                    val fragment = entry.fragment
                    if (fragment != null) {
                        openLocation(entry.chapterIndex, "window.paperlightAnchor?.(${JSONObject.quote(fragment)})")
                    } else if (entry.chapterIndex != chapterIndex) {
                        openLocation(entry.chapterIndex, "window.paperlightRestore?.(0)")
                    }
                }
            )
        }
        if (showSearch) {
            ReaderSearchDialog(publication.chapters, chapterTitles, onOpen = { hit ->
                showSearch = false
                barsVisible = false
                openLocation(hit.chapter, "window.paperlightFind?.(${JSONObject.quote(hit.query)},${hit.occurrence})")
            }, onDismiss = { showSearch = false })
        }
        if (showNotes && editingNote == null) {
            ReaderNotesDialog(
                book = book,
                chapterTitles = chapterTitles,
                palette = palette,
                otherBooks = libraryBooks.filter { it.id != book.id && it.format == "EPUB" },
                annotations = annotations.filter { it.chapter in chapterTitles.indices },
                otherBook = otherNotesBook,
                onOtherBook = { otherNotesBook = it },
                readOther = { id -> annotationStore.read(id) },
                onOpen = { item ->
                    showNotes = false
                    barsVisible = false
                    openLocation(item.chapter, "window.paperlightShowRange?.(${item.start},${item.end})")
                },
                onEdit = { target, item -> editingNote = target to item },
                onDismiss = { showNotes = false },
            )
        }
        colorPicker?.let { item ->
            AlertDialog(onDismissRequest = { colorPicker = null }, title = { Text("Highlight color") },
                text = {
                    Column {
                        listOf("yellow" to 0xFFFFD664, "green" to 0xFFACE4AE,
                            "blue" to 0xFF9BD9FA, "pink" to 0xFFFFB9D0).forEach { (name, swatch) ->
                            TextButton(onClick = {
                                saveAnnotation(book, item.copy(color = name))
                                colorPicker = null
                            }) {
                                Box(Modifier.size(16.dp).background(Color(swatch), MaterialTheme.shapes.small))
                                Spacer(Modifier.width(10.dp))
                                Text(name.replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { colorPicker = null }) { Text("Cancel") } })
        }
        openNote?.let { item ->
            AlertDialog(onDismissRequest = { openNote = null }, title = { Text("Note") },
                text = { Text(item.note, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { openNote = null }) { Text("Close") } },
                dismissButton = { TextButton(onClick = { openNote = null; editingNote = book to item }) { Text("Edit") } })
        }
        editingNote?.let { (target, item) ->
            val existing = if (target.id == book.id) annotations else annotationStore.read(target.id)
            ReaderNoteEditor(
                annotation = item,
                existing = existing.any { it.id == item.id },
                onSave = { note -> saveAnnotation(target, item.copy(note = note)); editingNote = null },
                onDelete = { saveAnnotation(target, item, delete = true); editingNote = null },
                onDismiss = { editingNote = null },
            )
        }
        dictionaryTerm?.let { term ->
            AlertDialog(
                onDismissRequest = { dictionaryTerm = null },
                title = { Text("Dictionary lookup") },
                text = {
                    OutlinedTextField(term, { dictionaryTerm = it.take(100) }, label = { Text("Word or phrase") }, singleLine = true)
                },
                confirmButton = {
                    TextButton(onClick = { dictionaryTerm = null; lookup(term) }, enabled = term.isNotBlank()) {
                        Text("Open Wiktionary")
                    }
                },
                dismissButton = { TextButton(onClick = { dictionaryTerm = null }) { Text("Cancel") } },
            )
        }
        if (showAppearance) {
            ReadingAppearanceDialog(
                value = appearance, theme = themeMode, palette = palette, hasFont = customFont != null,
                onChange = { appearance = it }, onTheme = { themeMode = it },
                onImport = { fontPicker.launch(arrayOf("*/*")) },
                onDismiss = { showAppearance = false },
            )
        }
        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                title = { Text("Reader settings") },
                text = {
                    Column {
                        Text("Show or hide reading controls", style = MaterialTheme.typography.labelLarge)
                        listOf(false to "Double tap", true to "Single tap").forEach { (single, label) ->
                            val choose = {
                                singleTap = single
                                preferences.edit().putBoolean("singleTapBars", single).apply()
                            }
                            Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = singleTap == single, onClick = choose)
                                Text(label)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Reading mode", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to "Pages", true to "Scroll").forEach { (scroll, label) ->
                                FilterChip(selected = scrollMode == scroll, onClick = {
                                    if (scrollMode != scroll) {
                                        scrollMode = scroll
                                        preferences.edit().putBoolean("scrollMode", scroll).apply()
                                        webView?.evaluateJavascript("window.paperlightSetScroll?.($scroll)", null)
                                    }
                                }, label = { Text(label) })
                            }
                        }
                        if (!scrollMode) {
                            Text("Page transition", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(true to "Page flip", false to "Slide").forEach { (useFlip, label) ->
                                    FilterChip(selected = flip == useFlip, onClick = {
                                        flip = useFlip
                                        preferences.edit().putBoolean("pageFlip", flip).apply()
                                        webView?.evaluateJavascript("window.paperlightSetFlip?.($flip)", null)
                                    }, label = { Text(label) })
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
            )
        }
        val message = toolMessage ?: speech.error
        if (message != null) {
            val dismiss = { toolMessage = null; speech.clearError() }
            AlertDialog(onDismissRequest = dismiss, title = { Text("Reading tools") },
                text = { Text(message) }, confirmButton = { TextButton(onClick = dismiss) { Text("OK") } })
        }

    }
}

private fun Modifier.readerEdgeShadow(topBar: Boolean): Modifier = drawBehind {
    val depth = 9.dp.toPx()
    val edge = if (topBar) size.height else 0f
    val top = if (topBar) edge else -depth
    val shade = Color.Black.copy(alpha = .22f)
    val colors = if (topBar) listOf(shade, Color.Transparent) else listOf(Color.Transparent, shade)
    drawRect(
        brush = Brush.verticalGradient(colors, startY = top, endY = top + depth),
        topLeft = Offset(0f, top),
        size = Size(size.width, depth),
    )
}

@Composable
private fun RowScope.ReaderBottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChapterList(
    titles: List<String>,
    toc: List<EpubTocEntry>,
    currentChapter: Int,
    background: Color,
    foreground: Color,
    bookmarks: List<ReaderBookmark>,
    canBookmark: Boolean,
    onBookmark: () -> Unit,
    onRemoveBookmark: (ReaderBookmark) -> Unit,
    onOpenBookmark: (ReaderBookmark) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (EpubTocEntry) -> Unit,
) {
    var showBookmarks by remember { mutableStateOf(false) }
    val activeEntry = toc.indexOfFirst { it.chapterIndex == currentChapter }
    val nearbyChapter = (activeEntry - 3).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = nearbyChapter)
    val scope = rememberCoroutineScope()
    val divider = foreground.copy(alpha = .16f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val drawerWidth = minOf(maxWidth * .85f, 440.dp)
            Box(
                Modifier.matchParentSize().clickable(
                    onClickLabel = "Close chapters",
                    onClick = onDismiss,
                )
            )
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).width(drawerWidth).fillMaxHeight(),
                color = background,
                contentColor = foreground,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Close chapters")
                        }
                        TextButton(onClick = { showBookmarks = false }, modifier = Modifier.semantics { selected = !showBookmarks }) {
                            Text("Chapters", color = if (showBookmarks) foreground.copy(alpha = .6f) else foreground)
                        }
                        TextButton(onClick = { showBookmarks = true }, modifier = Modifier.semantics { selected = showBookmarks }) {
                            Text("Bookmarks", color = if (showBookmarks) foreground else foreground.copy(alpha = .6f))
                        }
                    }
                    HorizontalDivider(color = divider)
                    if (showBookmarks) {
                        TextButton(onClick = onBookmark, enabled = canBookmark, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Bookmark this spot")
                        }
                        if (bookmarks.isEmpty()) {
                            Text("No bookmarks yet", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(bookmarks) { _, bookmark ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        Modifier.weight(1f)
                                            .clickable(role = Role.Button) { onOpenBookmark(bookmark) }
                                            .padding(horizontal = 20.dp, vertical = 10.dp)
                                    ) {
                                        Text(titles[bookmark.chapter], style = MaterialTheme.typography.bodyMedium)
                                        Text("${(bookmark.fraction * 100).toInt()}% through chapter", style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { onRemoveBookmark(bookmark) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Remove bookmark")
                                    }
                                }
                                HorizontalDivider(color = divider)
                            }
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                            itemsIndexed(toc, key = { index, _ -> index }) { index, entry ->
                                val current = index == activeEntry
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (current) foreground.copy(alpha = .08f) else background)
                                        .semantics { selected = current }
                                        .clickable(role = Role.Button) { onSelect(entry) }
                                        .heightIn(min = 48.dp)
                                        .padding(start = (20 + 14 * entry.depth.coerceIn(0, 5)).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(entry.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (current) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Current chapter", modifier = Modifier.size(18.dp))
                                    }
                                }
                                HorizontalDivider(color = divider)
                            }
                        }
                        HorizontalDivider(color = divider)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = "Show current chapter in list") {
                                    scope.launch { listState.scrollToItem(nearbyChapter) }
                                }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                titles[currentChapter],
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("${currentChapter + 1}/${titles.size}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderFooter(modifier: Modifier, palette: ReaderPalette, title: String, page: String?, progress: Float) {
    val context = LocalContext.current
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableIntStateOf(-1) }
    LaunchedEffect(context) {
        val manager = context.getSystemService(BatteryManager::class.java)
        while (true) {
            time = System.currentTimeMillis()
            battery = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            delay(60_000L - time % 60_000L)
        }
    }
    val label = MaterialTheme.typography.labelSmall
    Row(modifier.fillMaxWidth().background(palette.panel).height(23.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(listOfNotNull(battery.takeIf { it in 0..100 }?.let { "$it%" },
            DateFormat.getTimeFormat(context).format(java.util.Date(time))).joinToString("  "),
            color = palette.ink, style = label, maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            Text(title, Modifier.weight(1f, fill = false), color = palette.ink, style = label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (page != null) Text(" ($page)", color = palette.ink, style = label, maxLines = 1)
        }
        Text(String.format(java.util.Locale.getDefault(), "%.1f%%", progress * 100),
            color = palette.ink, style = label, maxLines = 1)
    }
}

private fun loadChapter(webView: WebView, chapter: EpubChapter, html: String = chapterHtml(chapter)) {
    val base = Uri.fromFile(chapter.file).toString()
    webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
}

internal fun chapterHtml(chapter: EpubChapter): String {
    // Publication scripting is not supported in this milestone. Strip scripts and inline
    // event handlers before loading so an imported book cannot execute arbitrary JS.
    val bytes = chapter.file.readBytes()
    fun byte(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xff)
    val charset = when {
        byte(0) == 0xfe && byte(1) == 0xff -> Charsets.UTF_16BE
        byte(0) == 0xff && byte(1) == 0xfe -> Charsets.UTF_16LE
        byte(0) == 0 && byte(1) == 0x3c -> Charsets.UTF_16BE
        byte(0) == 0x3c && byte(1) == 0 -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
    val html = String(bytes, charset)
        .trimStart('﻿', ' ', '\t', '\r', '\n')
        .replaceFirst(Regex("""^<\?xml[^>]*>\s*""", RegexOption.IGNORE_CASE), "")
        .replaceFirst(Regex("""^<!doctype[^>]*>\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<meta\b(?=[^>]*\bname\s*=\s*(?:"viewport"|'viewport'|viewport\b))[^>]*>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<(iframe|object|embed)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2", RegexOption.IGNORE_CASE), "$1=\"#\"")
    val viewport = """<meta name="viewport" content="width=device-width, initial-scale=1">"""
    val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
    val document = if (head == null) viewport + html else
        html.substring(0, head.range.last + 1) + viewport + html.substring(head.range.last + 1)
    return "<!doctype html>" + document
}

private fun viewNext(view: WebView?, direction: Int) {
    view?.evaluateJavascript("window.paperlightMove($direction)", null)
}

private fun buildReaderScript(themeMode: Int, fontScale: Float, initialFraction: Float, scrollMode: Boolean, chapterIndex: Int, widthPx: Int, heightPx: Int, flip: Boolean, lastChapterIndex: Int): String = """
(function(){
 const palettes={white:{bg:'#F9F9F5',fg:'#2C302E',link:'#395F55'},dark:{bg:'#1D201F',fg:'#E1DFD5',link:'#B4CA9A'}};
 let mode=${when (themeMode) { 1 -> "'dark'"; else -> "'white'" }};
 if(document.getElementById('paperlight-style')) return null;
 const style=document.createElement('style'); style.id='paperlight-style';
 style.textContent=`html{margin:0!important;padding:0!important;overflow:hidden!important;background:${'$'}{palettes[mode].bg}!important;}
 body{box-sizing:border-box!important;margin:24px!important;padding:0!important;overflow:visible!important;color:${'$'}{palettes[mode].fg}!important;background:transparent!important;font-size:${fontScale}em!important;line-height:1.65!important;font-family:serif!important;}
 ::highlight(paperlight-note){background:#ffd664;color:#28231a;}
 ::highlight(paperlight-search){background:#8de7f2;color:#28231a;}
 ::highlight(paperlight-green){background:#ace4ae;color:#28231a;}
 ::highlight(paperlight-blue){background:#9bd9fa;color:#28231a;}
 ::highlight(paperlight-pink){background:#ffb9d0;color:#28231a;}
 mark[data-paperlight-note]{color:#28231a!important;}
 .paperlight-note-marker{position:fixed;z-index:5;pointer-events:none;box-sizing:border-box;border-bottom:2px dotted #52633d;}
 p,li{orphans:2;widows:2;} img,svg,video{max-width:100%!important;object-fit:contain!important;break-inside:avoid!important;} h1,h2,h3,h4{break-after:avoid!important;} a{color:${'$'}{palettes[mode].link}!important;}`;
 document.head.appendChild(style);
 const darkText=document.createElement('style');
 document.head.appendChild(darkText);
 function applyDarkText(){
   darkText.textContent=mode==='dark'
     ? ".paperlight-content :is(p,li,blockquote,h1,h2,h3,h4,h5,h6,dt,dd,td,th,figcaption){color:inherit!important;background-color:transparent!important;} .paperlight-content :is(span,em,strong):not(a *){color:inherit!important;}"
     : "";
 }
 applyDarkText();
 let scrolling=$scrollMode;
 let type={size:$fontScale,lineHeight:1.65,margins:24,font:'serif',alignment:'original',texture:'none'};
 let customFontReady=false;
 let drag=0;
 let dragDirection=0;
 let dragStyle='';
 let dragScroll=false;
 let underPage=null, backPage=null;
 const fold=document.createElement('div');
 fold.setAttribute('aria-hidden','true');
 fold.style.cssText='display:none;position:fixed;inset:0;pointer-events:none;z-index:1;background-size:100% 100%';
 const fontStyle=document.createElement('style');
 document.head.appendChild(fontStyle);
 // Navigation owns the page index; animated scroll geometry must never own it.
 let currentPage=0;
 let pageTotal=1;
 let pageStride=1;
 let turning=false;
 let layoutWidth=0,layoutHeight=0;
 let motionGeneration=0;
 function cancelMotion(){
   resetDrag();
   motionGeneration++;
   turning=false;
 }
 // Give the viewport and text columns separate boxes so publisher body styles
 // cannot change the size of either the scroll area or the page fragments.
 const viewport=document.createElement('div');
 const content=document.createElement('div');
 const endSpacer=document.createElement('div');
 endSpacer.setAttribute('aria-hidden','true');
 endSpacer.style.cssText='display:none;position:absolute;top:0;width:1px;height:1px;pointer-events:none';
 viewport.id='paperlight-viewport';
 content.id='paperlight-content';
 content.className='paperlight-content';
 while(document.body.firstChild) content.appendChild(document.body.firstChild);
 viewport.appendChild(content);
 viewport.appendChild(endSpacer);
 document.body.appendChild(viewport);
 document.body.appendChild(fold);
 for(const image of content.querySelectorAll?.('img[loading="lazy"]')||[]) image.loading='eager';
 const coverMedia=content.querySelector?.('img,svg');
 const imageOnly=coverMedia&&!content.textContent?.trim()&&content.querySelectorAll('img,svg').length===1;
 if(coverMedia&&(document.querySelector?.('meta[name="calibre:cover"]')||imageOnly)){
   content.classList.add('paperlight-cover');
   coverMedia.classList.add('paperlight-cover-media');
   if(coverMedia.localName==='img'){
     function showMissingIllustration(){
       if(coverMedia.hidden) return;
       coverMedia.hidden=true;
       coverMedia.style.setProperty('display','none','important');
       const message=document.createElement('div');
       message.className='paperlight-missing-image';
       message.textContent='Illustration unavailable in this EPUB';
       content.appendChild(message);
     }
     coverMedia.addEventListener('error',showMissingIllustration);
     if(coverMedia.complete&&!coverMedia.naturalWidth) showMissingIllustration();
   }
   for(let wrapper=coverMedia.parentElement;wrapper&&wrapper!==content;wrapper=wrapper.parentElement){
     wrapper.classList.add('paperlight-cover-wrapper');
   }
 }
 const layout=document.createElement('style');
 document.head.appendChild(layout);
 function configureMode(){
   const ratio=window.devicePixelRatio||1;
   const width=window.innerWidth||Math.round($widthPx/ratio);
   const height=window.innerHeight>48?window.innerHeight:Math.round($heightPx/ratio);
   layoutWidth=width; layoutHeight=height;
   const margin=Math.min(type.margins,Math.floor(Math.min(width,height)/4));
   const contentWidth=Math.max(1,width-2*margin);
   const contentHeight=Math.max(1,height-2*margin);
   const family=type.font==='custom'?(customFontReady?'PaperlightCustom, serif':'serif'):type.font;
   const texture=type.texture==='linen'
     ? 'repeating-linear-gradient(0deg,transparent 0 3px,rgba(128,128,128,.065) 3px 4px),repeating-linear-gradient(90deg,transparent 0 3px,rgba(128,128,128,.065) 3px 4px)'
     : type.texture==='grain'
       ? `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Cpath fill='%23808080' opacity='.10' filter='url(%23n)' d='M0 0h160v160H0z'/%3E%3C/svg%3E")`
       : 'none';
   const alignment=type.alignment==='original'?'':`text-align:${'$'}{type.alignment}!important;`;
   layout.textContent=`
     html > body{margin:0!important;padding:0!important;display:block!important;}
     #paperlight-viewport{
       position:fixed!important;inset:0 auto auto 0!important;
       width:${'$'}{width}px!important;height:${'$'}{height}px!important;
       overflow-x:hidden!important;overflow-y:${'$'}{scrolling?'auto':'hidden'}!important;
       scrollbar-width:none;overscroll-behavior:none;
       overflow-anchor:none!important;scroll-behavior:auto!important;touch-action:${'$'}{scrolling?'manipulation':'none'};
       background-color:${'$'}{palettes[mode].bg};background-image:${'$'}{texture};
     }
     .paperlight-content{
       box-sizing:content-box!important;
       width:${'$'}{contentWidth}px!important;
       height:${'$'}{scrolling?'auto':contentHeight+'px'}!important;
       margin:${'$'}{margin}px!important;
       columns:${'$'}{scrolling?'auto':contentWidth+'px 1'}!important;
       column-gap:${'$'}{2*margin}px!important;
       column-fill:auto!important;overflow-wrap:anywhere!important;
       font-family:${'$'}{family}!important;line-height:${'$'}{type.lineHeight}!important;
     }
     .paperlight-content :is(p,li,dd,blockquote){
       font-family:${'$'}{family}!important;line-height:${'$'}{type.lineHeight}!important;${'$'}{alignment}
     }
     .paperlight-content.paperlight-cover{
       position:relative!important;display:block!important;
       height:${'$'}{contentHeight}px!important;columns:auto!important;overflow:hidden!important;
     }
     .paperlight-missing-image{position:absolute;inset:0;display:grid;place-items:center;text-align:center;}
     .paperlight-content .paperlight-cover-wrapper{display:contents!important;}
     .paperlight-content .paperlight-cover-media{
       position:absolute!important;inset:0!important;display:block!important;box-sizing:border-box!important;margin:auto!important;padding:0!important;
       max-width:100%!important;max-height:${'$'}{contentHeight}px!important;
     }
     .paperlight-content img.paperlight-cover-media{width:auto!important;height:auto!important;object-fit:contain!important;}
     .paperlight-content svg.paperlight-cover-media{width:100%!important;height:${'$'}{contentHeight}px!important;}
     .paperlight-content pre{white-space:pre-wrap!important;}
     .paperlight-content table{max-width:100%!important;overflow-wrap:anywhere!important;}
     .paperlight-content img,.paperlight-content svg,.paperlight-content video{
       max-height:${'$'}{contentHeight}px!important;
     }
   `;
   measurePages();
 }
 function measurePages(){
   endSpacer.style.display='none';
   if(scrolling) return;
   pageStride=Math.max(1,viewport.clientWidth);
   const overflow=Math.max(0,viewport.scrollWidth-viewport.clientWidth);
   pageTotal=Math.round(overflow/pageStride)+1;
   // Column overflow can end inside the final page's right margin.
   // Extend scrollable space to the page boundary without changing text layout.
   if(pageTotal>1){
     endSpacer.style.left=(pageTotal*pageStride-1)+'px';
     endSpacer.style.display='block';
   }
   currentPage=Math.min(currentPage,pageTotal-1);
 }
 function step(){return Math.max(1,scrolling?viewport.clientHeight*.9:pageStride);}
 function maxOffset(){
   return Math.max(0,scrolling?viewport.scrollHeight-viewport.clientHeight:(pageTotal-1)*pageStride);
 }
 function position(){return scrolling?viewport.scrollTop:viewport.scrollLeft;}
 function fraction(){
   if(!scrolling) return page()/Math.max(1,total()-1);
   return Math.min(1,Math.max(0,position()/Math.max(1,maxOffset())));
 }
 function total(){return scrolling?Math.round(maxOffset()/step())+1:pageTotal;}
 function page(){return scrolling?Math.min(total()-1,Math.round(position()/step())):currentPage;}
 function moveTo(offset,behavior){
   viewport.scrollTo({left:scrolling?0:offset,top:scrolling?offset:0,behavior});
 }
 function report(){if(turning||drag) return; drawNoteMarkers(); Paperlight.onPage(page(),total(),fraction(),$chapterIndex,scrolling);}
 function restore(progress){
   currentPage=Math.max(0,Math.min(total()-1,Math.round(progress*(total()-1))));
   moveTo(scrolling?progress*maxOffset():currentPage*step(),'instant');
   report();
 }
 window.paperlightRestore=function(progress){
   cancelMotion();
   restore(progress);
 };
 window.paperlightSetScroll=function(next){
   reflow(()=>{scrolling=next;});
 };
 configureMode();
 let flip=$flip;
 window.paperlightSetFlip=function(enabled){flip=enabled;};
 const reducedMotion=()=>window.matchMedia('(prefers-reduced-motion: reduce)').matches;
 // Keep a real adjacent page beneath the turning sheet. Both directions use
 // the SAME right-to-left fold, with time reversed for a backward turn.
 function copyPage(offset){
   const copy=viewport.cloneNode(true);
   copy.removeAttribute('id');
   copy.querySelectorAll('[id]').forEach(node=>node.removeAttribute('id'));
   copy.style.cssText=window.getComputedStyle(viewport).cssText || '';
   copy.style.cssText+=';position:fixed;inset:0;width:'+layoutWidth+'px;height:'+layoutHeight+'px;overflow:hidden;pointer-events:none;background-color:'+palettes[mode].bg;
   copy.style.backgroundImage=window.getComputedStyle(viewport).backgroundImage;
   copy.setAttribute('aria-hidden','true');
   document.body.appendChild(copy);
   copy.scrollLeft=offset;
   return copy;
 }
 function prepareCurl(direction){
   if(underPage) return;
   const adjacent=page()+direction;
   // Current chapter pages retain their exact rendered columns and images.
   underPage=copyPage((direction>0?Math.min(total()-1,adjacent):page())*step());
   underPage.style.zIndex='0';
   viewport.style.zIndex='1';
   if(direction<0 && adjacent>=0){dragScroll=true;moveTo(adjacent*step(),'instant');}
   // Model the reverse side as paper without cloning the chapter a second time.
   backPage=document.createElement('div');
   backPage.style.cssText='position:fixed;inset:0;pointer-events:none;background:'+palettes[mode].bg;
   document.body.appendChild(backPage);
   backPage.style.zIndex='2';
   backPage.style.opacity='.86';
   fold.style.zIndex='3';
 }
 function curlFrame(direction,progress){
   prepareCurl(direction);
   const p=direction>0?progress:1-progress;
   const w=layoutWidth,h=layoutHeight;
   // The crease is the perpendicular bisector between the lifted corner and
   // its projected position. Clip both sides of the sheet against that line.
   const edge=w*(1-p);
   const slope=h>w ? w/h*0.76*Math.sin(Math.PI*p) : 0.62*Math.sin(Math.PI*p);
   const scale=Math.hypot(1,slope);
   const nx=1/scale,ny=slope/scale;
   const distance=nx*edge+ny*h*.66;
   const corners=[[0,0],[w,0],[w,h],[0,h]];
   const signed=([x,y])=>nx*x+ny*y-distance;
   function half(sign){
     const result=[];
     for(let i=0;i<corners.length;i++){
       const a=corners[i],b=corners[(i+1)%corners.length];
       const da=signed(a),db=signed(b);
       if(sign*da>=0) result.push(a);
       if(da*db<0){
         const t=da/(da-db);
         result.push([a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t]);
       }
     }
     return result;
   }
   const polygon=points=>'polygon('+(points.length?points:[[0,0],[0,0],[0,0]])
     .map(([x,y])=>x+'px '+y+'px').join(',')+')';
   const lifted=half(1);
   viewport.style.setProperty('clip-path',polygon(half(-1)));
   backPage.style.transformOrigin='0 0';
   backPage.style.transform='matrix('+[1-2*nx*nx,-2*nx*ny,-2*nx*ny,1-2*ny*ny,2*distance*nx,2*distance*ny].join(',')+')';
   backPage.style.clipPath=polygon(lifted);
   const reflected=lifted.map(([x,y])=>{
     const d=signed([x,y]);
     return [x-2*d*nx,y-2*d*ny];
   });
   fold.style.display='block';
   fold.style.clipPath=polygon(reflected);
   fold.style.background='linear-gradient(105deg, transparent 25%, #0003)';
 }
 function preview(direction,p,style){
   if(!dragStyle) clearNoteMarkers();
   dragDirection=direction;drag=p;dragStyle=style;
   if(style==='curl') curlFrame(direction,p);
   else {
     const adjacent=page()+direction;
     dragScroll=adjacent>=0&&adjacent<total();
     if(dragScroll) moveTo((page()+direction*p)*step(),'instant');
     else viewport.style.setProperty('transform','translateX('+(-direction*p*step())+'px)');
   }
 }

 function settle(direction,start,end,style){
   const generation=motionGeneration;
   if(!window.requestAnimationFrame){
     preview(direction,end,style);
     return Promise.resolve();
   }
   return new Promise(resolve=>{
     let began;
     function frame(now){
       if(generation!==motionGeneration){resolve();return;}
       if(began===undefined) began=now;
       const t=Math.min(1,(now-began)/Math.max(75,220*Math.abs(end-start)));
       const eased=1-Math.pow(1-t,3);
       preview(direction,start+(end-start)*eased,style);
       if(t<1) window.requestAnimationFrame(frame);
       else resolve();
     }
     window.requestAnimationFrame(frame);
   });
 }

 window.paperlightMove=async function(direction){
   if(turning) return;
   const nextPage=page()+direction;
   const atEdge=scrolling
     ? (direction>0?position()>=maxOffset()-1:position()<=1)
     : (nextPage<0||nextPage>=total());
   if(atEdge&&($chapterIndex+direction<0||$chapterIndex+direction>$lastChapterIndex)){
     resetDrag();
     return;
   }
   if(atEdge){
     resetDrag();
     Paperlight.onChapterTurn($chapterIndex,direction);
     return;
   }
   if(scrolling||reducedMotion()){
     resetDrag();
     if(scrolling) moveTo(position()+direction*step(),'smooth');
     else {currentPage=nextPage;moveTo(currentPage*step(),'instant');report();}
     return;
   }
   const style=flip?'curl':'slide';
   const start=dragDirection===direction&&dragStyle===style?drag:0;
   if(!start) resetDrag();
   turning=true;
   const generation=++motionGeneration;
   try {
     await settle(direction,start,1,style);
     if(generation!==motionGeneration) return;
     resetDrag();
     currentPage=nextPage;
     moveTo(currentPage*step(),'instant');

   } finally {
     if(generation===motionGeneration){
       resetDrag();
       turning=false;
       report();
     }
   }
 };
 function textNodes(){
   const walker=document.createTreeWalker(content,NodeFilter.SHOW_TEXT,{
     acceptNode:node=>node.parentElement?.closest('script, style, noscript, svg, [hidden]')
       ?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT
   });
   const nodes=[];
   let offset=0;
   while(walker.nextNode()){
     const node=walker.currentNode;
     nodes.push({node,start:offset,end:offset+node.length});
     offset+=node.length;
   }
   return nodes;
 }
 function textOffset(nodes,container,offset){
   const item=nodes.find(item=>item.node===container);
   if(item) return item.start+offset;
   if(container.nodeType===Node.TEXT_NODE) return -1;
   const prefix=document.createRange();
   prefix.selectNodeContents(content);
   prefix.setEnd(container,offset);
   let length=0;
   for(const entry of nodes){
     if(prefix.comparePoint(entry.node,0)!==0) break;
     length=entry.end;
   }
   return length;
 }
 function makeRange(start,end,nodes){
   const first=nodes.find(entry=>entry.end>start);
   const last=nodes.find(entry=>entry.end>=end);
   if(!first||!last||end<=start||start<0) return null;
   const range=document.createRange();
   range.setStart(first.node,start-first.start);
   range.setEnd(last.node,end-last.start);
   return range;
 }
 function reveal(range, inset=.2){
   cancelMotion();
   const rect=Array.from(range.getClientRects()).find(rect=>rect.width>0&&rect.height>0);
   if(!rect) return;
   const frame=viewport.getBoundingClientRect();
   if(scrolling){
     moveTo(position()+rect.top-frame.top-viewport.clientHeight*inset,'instant');
   }else{
     const x=position()+rect.left-frame.left;
     currentPage=Math.max(0,Math.min(total()-1,Math.floor(x/pageStride)));
     moveTo(currentPage*step(),'instant');
   }
   report();
 }
 document.addEventListener('selectionchange',()=>{
   const selected=window.getSelection();
   if(selected?.rangeCount&&!selected.isCollapsed){
     const range=selected.getRangeAt(0);
     const quote=selected.toString().trim();
     if(quote&&content.contains(range.startContainer)&&content.contains(range.endContainer)){
       const nodes=textNodes();
       const start=textOffset(nodes,range.startContainer,range.startOffset);
       const end=textOffset(nodes,range.endContainer,range.endOffset);
       if(start>=0&&end>start){
         Paperlight.onSelection($chapterIndex,start,end,quote.slice(0,5000));
         return;
       }
     }
   }
   Paperlight.onSelection($chapterIndex,-1,-1,'');
 });
 let noteOffsets=[];
 let noteMarkers=[];
 const highlightColors={yellow:'#ffd664',green:'#ace4ae',blue:'#9bd9fa',pink:'#ffb9d0'};
 function clearNoteMarkers(){noteMarkers.forEach(marker=>marker.remove());noteMarkers=[];}
 function drawNoteMarkers(){
   clearNoteMarkers();
   if(!noteOffsets.length) return;
   const nodes=textNodes();
   for(const [start,end,color,id] of noteOffsets){
     const range=makeRange(start,end,nodes);
     if(!range) continue;
     for(const rect of range.getClientRects()){
       if(rect.width<=0||rect.height<=0||rect.right<=0||rect.left>=layoutWidth||rect.bottom<=0||rect.top>=layoutHeight) continue;
       const marker=document.createElement('div');
       marker.className='paperlight-note-marker';
       marker.dataset.noteId=id;
       marker.style.left=Math.max(0,rect.left)+'px';
       marker.style.top=Math.max(0,rect.top)+'px';
       marker.style.width=(Math.min(layoutWidth,rect.right)-Math.max(0,rect.left))+'px';
       marker.style.height=(Math.min(layoutHeight,rect.bottom)-Math.max(0,rect.top))+'px';
       document.body.appendChild(marker);
       noteMarkers.push(marker);
     }
   }
 }
 window.paperlightHighlights=function(offsets){
   noteOffsets=offsets.filter(item=>item[4]);

   const registry=window.CSS?.highlights;
   if(registry&&window.Highlight){
     const nodes=textNodes();
     for(const color of Object.keys(highlightColors)){
       const ranges=offsets.filter(item=>(item[2]||'yellow')===color)
         .map(([start,end])=>makeRange(start,end,nodes)).filter(Boolean);
       registry.set(color==='yellow'?'paperlight-note':'paperlight-'+color,new Highlight(...ranges));
     }
     drawNoteMarkers();
     return;
   }
   // Older WebViews use inline marks. Unwrap them before recalculating text offsets.
   for(const mark of content.querySelectorAll('mark[data-paperlight-note]')){
     const parent=mark.parentNode;
     mark.replaceWith(...mark.childNodes);
     parent.normalize();
   }
   for(const {node,start,end} of textNodes()){
     const spans=offsets.map(([a,b,color])=>[Math.max(0,a-start),Math.min(end-start,b-start),color])
       .filter(([a,b])=>a<b);
     const edges=[...new Set(spans.flatMap(([a,b])=>[a,b]))].sort((a,b)=>b-a);
     for(let i=1;i<edges.length;i++){
       const a=edges[i],b=edges[i-1];
       const span=spans.find(item=>item[0]<=a&&item[1]>=b);
       if(!span) continue;
       const range=document.createRange();
       range.setStart(node,a);
       range.setEnd(node,b);
       const mark=document.createElement('mark');
       mark.setAttribute('data-paperlight-note','');
       mark.style.setProperty('background-color',highlightColors[span[2]]||highlightColors.yellow,'important');
       range.surroundContents(mark);
     }
   }
   drawNoteMarkers();
 };
 window.paperlightShowRange=function(start,end){
   const range=makeRange(start,end,textNodes());
   if(range) reveal(range);
 };
 window.paperlightFind=function(query,occurrence){
   const nodes=textNodes();
   const parts=[];
   const starts=[];
   const ends=[];
   let whitespace=true;
   let block=null;
   for(const {node,start} of nodes){
     const nextBlock=node.parentElement?.closest('address,article,blockquote,dd,div,dt,figcaption,h1,h2,h3,h4,h5,h6,li,p,pre,section,td,th');
     if(nextBlock!==block&&!whitespace){
       parts.push(' ');
       starts.push(start);
       ends.push(start);
       whitespace=true;
     }
     block=nextBlock;
     for(let i=0;i<node.length;i++){
       const value=node.data[i];
       if(/\s/.test(value)){
         if(whitespace) continue;
         parts.push(' ');
         whitespace=true;
       }else{
         parts.push(value);
         whitespace=false;
       }
       starts.push(start+i);
       ends.push(start+i+1);
     }
   }
   const haystack=parts.join('');
   const pattern=new RegExp(Array.from(query,char=>'\\^.*+?()[]{}|'.includes(char)?String.fromCharCode(92)+char:char).join(''),'giu');
   for(const match of haystack.matchAll(pattern)){
     if(occurrence--!==0) continue;
     const range=makeRange(starts[match.index],ends[match.index+match[0].length-1],nodes);
     if(!range) break;
     if(window.CSS?.highlights&&window.Highlight){
       const marker=new Highlight(range);
       CSS.highlights.set('paperlight-search',marker);
       clearTimeout(searchMarkerTimer);
       searchMarkerTimer=setTimeout(()=>CSS.highlights.delete('paperlight-search'),4000);
     }
     reveal(range);
     return;
   }
   Paperlight.onSearchMiss($chapterIndex);
 };
 let searchMarkerTimer;
 window.paperlightChapterText=(startOffset)=>{
   const selection=window.getSelection();
   const nodes=textNodes();
   let anchor=Number.isInteger(startOffset)?startOffset:null;
   if(anchor===null && selection?.rangeCount && !selection.isCollapsed){
     const range=selection.getRangeAt(0);
     if(content.contains(range.startContainer))
       anchor=textOffset(nodes,range.startContainer,range.startOffset);
   }
   if(anchor===null || anchor<0){
     const range=document.createRange();
     for(const {node,start} of nodes){
       if(!node.data.trim()) continue;
       range.selectNodeContents(node);
       const visible=rect=>rect.width>0&&rect.height>0&&rect.right>0&&rect.left<layoutWidth&&rect.bottom>0&&rect.top<layoutHeight;
       if(!Array.from(range.getClientRects()).some(visible)) continue;
       let low=0, high=node.length;
       while(high-low>1){
         const mid=Math.floor((low+high)/2);
         range.setStart(node,0);
         range.setEnd(node,mid);
         if(Array.from(range.getClientRects()).some(visible)) high=mid;
         else low=mid;
       }
       anchor=start+high-1;
       break;
     }
   }
   if(anchor===null) return '';
   const portion=document.createRange();
   const first=nodes.find(item=>item.end>anchor);
   if(!first) return '';
   portion.setStart(first.node,Math.max(0,anchor-first.start));
   portion.setEnd(content,content.childNodes.length);
   const copy=portion.cloneContents();
   copy.querySelectorAll('script,style,noscript,svg,[hidden]').forEach(node=>node.remove());
   copy.querySelectorAll('p,div,li,h1,h2,h3,h4,h5,h6,br').forEach(node=>node.after(document.createTextNode(' ')));
   return copy.textContent.trim().slice(0,250000);
 };
 window.paperlightAnchor=function(id){
   const target=document.getElementById(id)||document.getElementsByName(id)[0];
   if(target&&content.contains(target)) reveal(target);
 };
 document.addEventListener('click',event=>{
   const href=event.target?.closest?.('a[href]')?.getAttribute('href');
   if(!href?.startsWith('#')) return;
   event.preventDefault();
   let id=href.slice(1);
   try {id=decodeURIComponent(id);} catch(_){}
   if(id) window.paperlightAnchor(id);
   else window.paperlightRestore(0);
 });
 function resetDrag(){
   underPage?.remove();backPage?.remove();underPage=null;backPage=null;
   viewport.style.removeProperty('z-index');
   if(!dragStyle) return;
   const restoreSlide=dragScroll;
   drag=0;
   dragDirection=0;
   dragStyle='';
   dragScroll=false;
   viewport.style.removeProperty('transform');
   viewport.style.removeProperty('clip-path');
   fold.style.display='none';
   fold.style.backgroundImage='';
   if(restoreSlide) moveTo(currentPage*step(),'instant');
 }
 let touchStart;
 let previousTap;
 window.addEventListener('touchstart',event=>{
   const touch=event.touches.length===1?event.touches[0]:null;
   touchStart=touch?{x:touch.clientX,y:touch.clientY,time:performance.now()}:null;
 },{passive:true});
 window.addEventListener('touchcancel',()=>{touchStart=null;previousTap=null;resetDrag();report();},{passive:true});
 window.addEventListener('touchmove',event=>{
   if(!touchStart||scrolling||turning||event.touches.length!==1||window.getSelection()?.toString()){
     resetDrag();
     return;
   }
   const touch=event.touches[0];
   const dx=touch.clientX-touchStart.x;
   const dy=touch.clientY-touchStart.y;
   if(Math.abs(dx)<18||Math.abs(dx)<Math.abs(dy)*1.4){resetDrag();return;}
   const direction=dx<0?1:-1;
   if((page()===0&&direction<0&&$chapterIndex===0)||
      (page()===total()-1&&direction>0&&$chapterIndex===$lastChapterIndex)){
     resetDrag();
     return;
   }
   if(event.cancelable) event.preventDefault();
   if(page()+direction<0||page()+direction>=total()) return;
   const style=flip?'curl':'slide';
   if(dragStyle&&(dragDirection!==direction||dragStyle!==style)) resetDrag();
   preview(direction,Math.min(1,Math.abs(dx)/Math.max(1,viewport.clientWidth)),style);
 },{passive:false});
 window.addEventListener('touchend',event=>{
   const start=touchStart;
   touchStart=null;
   if(!start||event.touches.length||window.getSelection()?.toString()){
     resetDrag();
     previousTap=null;
     return;
   }
   const touch=event.changedTouches[0];
   const dx=touch.clientX-start.x;
   const dy=touch.clientY-start.y;
   if(!scrolling&&Math.abs(dx)>45&&Math.abs(dx)>Math.abs(dy)*1.4){
     previousTap=null;
     window.paperlightMove(dx<0?1:-1);
     return;
   }
   resetDrag();
   const now=performance.now();
   if(Math.hypot(dx,dy)>12||now-start.time>260||event.target?.closest?.('a,button,input,textarea,select,video,audio')){
     previousTap=null;
     return;
   }
   const note=noteMarkers.find(marker=>{
     const rect=marker.getBoundingClientRect();
     return touch.clientX>=rect.left&&touch.clientX<=rect.right&&touch.clientY>=rect.top&&touch.clientY<=rect.bottom;
   });
   if(note){
     previousTap=null;
     Paperlight.onNoteTap($chapterIndex,note.dataset.noteId);
     return;
   }
   if(previousTap&&now-previousTap.time<320&&Math.hypot(touch.clientX-previousTap.x,touch.clientY-previousTap.y)<36){
     previousTap=null;
     Paperlight.onReaderTap($chapterIndex,2);
   }else{
     previousTap={x:touch.clientX,y:touch.clientY,time:now};
     Paperlight.onReaderTap($chapterIndex,1);
   }
 },{passive:true});
 window.paperlightTheme=function(next){mode=palettes[next]?next:'white';resetDrag();viewport.style.backgroundColor=palettes[mode].bg;document.documentElement.style.setProperty('background',palettes[mode].bg,'important');document.body.style.setProperty('color',palettes[mode].fg,'important');style.textContent=style.textContent.replace(/a\{color:[^}]+\}/,`a{color:${'$'}{palettes[mode].link}!important;}`);applyDarkText();};
 function readingAnchor(){
   if(!document.caretRangeFromPoint) return null;
   const margin=Math.min(type.margins,Math.floor(Math.min(layoutWidth,layoutHeight)/4));
   for(const y of [margin+6,margin+22,margin+40]){
     const caret=document.caretRangeFromPoint(margin+8,y);
     const node=caret?.startContainer;
     if(node?.nodeType!==3||!content.contains(node)) continue;
     const rect=caret.getBoundingClientRect();
     const offset=textOffset(textNodes(),node,caret.startOffset);
     if(offset>=0&&rect.left>=0&&rect.left<layoutWidth&&rect.top>=0&&rect.top<layoutHeight) return offset;
   }
   return null;
 }
 function reflow(change){
   resetDrag();
   const anchor=readingAnchor();
   const progress=fraction();
   cancelMotion();
   change();
   configureMode();
   function restoreSpot(){
     const range=anchor===null?null:makeRange(anchor,anchor+1,textNodes());
     if(range) reveal(range,0);
     else restore(progress);
   }
   restoreSpot();
   return restoreSpot;
 }
 window.paperlightSetAppearance=function(next,font){
   if(!font&&Object.keys(type).every(key=>type[key]===next[key])) return;
   const restoreSpot=reflow(()=>{
     type=next;
     if(font){
       fontStyle.textContent=`@font-face{font-family:PaperlightCustom;src:url("${'$'}{font}");font-display:swap;}`;
       customFontReady=true;
     }
     document.body.style.setProperty('font-size',type.size+'em','important');
   });
   if(type.font==='custom'&&customFontReady&&document.fonts){
     const generation=motionGeneration;
     document.fonts.load('16px PaperlightCustom').then(()=>{
       if(generation!==motionGeneration) return;
       measurePages();
       restoreSpot();
     }).catch(()=>{});
   }
 };
 window.paperlightFont=scale=>window.paperlightSetAppearance({...type,size:scale});
 let reportTimer;
 window.addEventListener('resize',()=>{
   if(window.innerWidth===layoutWidth&&window.innerHeight===layoutHeight) return;
   reflow(()=>{});
 });
 viewport.addEventListener('scroll',()=>{
   clearTimeout(reportTimer);
   reportTimer=setTimeout(report,120);
 });
 restore($initialFraction);
 // Image dimensions can arrive after the first pagination pass.
 content.addEventListener('load',event=>{
   if(scrolling||turning||drag||!event.target?.matches?.('img,image,video')) return;
   measurePages();
   moveTo(currentPage*step(),'instant');
   report();
 },true);

})();
""".trimIndent()
