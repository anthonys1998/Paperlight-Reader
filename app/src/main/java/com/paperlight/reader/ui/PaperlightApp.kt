package com.paperlight.reader.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.paperlight.reader.data.BookArtwork
import com.paperlight.reader.ui.reader.ImagePageReaderScreen
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.ui.reader.EpubReaderScreen
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import com.paperlight.reader.ai.GeminiAiClient
import com.paperlight.reader.ai.GeminiApiKeyStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role

private data class FolderBook(val uri: Uri, val name: String)

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperlightApp(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val accessibilityPreferences = remember(context) { context.getSharedPreferences("accessibility", 0) }
    val readerPreferences = remember(context) { context.getSharedPreferences("reader", 0) }
    var uiTextScale by rememberSaveable {
        mutableFloatStateOf(accessibilityPreferences.getFloat("uiTextScale", 1f).coerceIn(.85f, 1.5f))
    }
    var reduceMotion by rememberSaveable { mutableStateOf(accessibilityPreferences.getBoolean("reduceMotion", false)) }
    var eInkMode by rememberSaveable { mutableStateOf(readerPreferences.getBoolean("eInkMode", false)) }
    var readingTextScale by rememberSaveable {
        mutableFloatStateOf(readerPreferences.getFloat("textSize", 1f).coerceIn(.7f, 2.2f))
    }
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * uiTextScale)) {
        PaperlightAppContent(
            viewModel = viewModel,
            uiTextScale = uiTextScale,
            reduceMotion = reduceMotion,
            eInkMode = eInkMode,
            readingTextScale = readingTextScale,
            onUiTextScaleChange = { value ->
                uiTextScale = value.coerceIn(.85f, 1.5f)
                accessibilityPreferences.edit().putFloat("uiTextScale", uiTextScale).apply()
            },
            onReduceMotionChange = { value ->
                reduceMotion = value
                accessibilityPreferences.edit().putBoolean("reduceMotion", value).apply()
            },
            onEInkModeChange = { value ->
                eInkMode = value
                readerPreferences.edit().putBoolean("eInkMode", value).apply()
            },
            onReadingTextScaleChange = { value ->
                readingTextScale = value.coerceIn(.7f, 2.2f)
                readerPreferences.edit().putFloat("textSize", readingTextScale).apply()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PaperlightAppContent(
    viewModel: LibraryViewModel,
    uiTextScale: Float,
    reduceMotion: Boolean,
    eInkMode: Boolean,
    readingTextScale: Float,
    onUiTextScaleChange: (Float) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onEInkModeChange: (Boolean) -> Unit,
    onReadingTextScaleChange: (Float) -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val libraries by viewModel.libraries.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(setOf<String>()) }
    var activeLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val pagerState = rememberPagerState(pageCount = { 2 })
    var externalBook by remember { mutableStateOf<BookEntity?>(null) }
    var selectedExternalUri by remember { mutableStateOf<Uri?>(null) }
    var openingExternalFileName by remember { mutableStateOf<String?>(null) }
    val libraryPreferences = remember { context.getSharedPreferences("libraryAppearance", 0) }
    var listView by rememberSaveable { mutableStateOf(libraryPreferences.getBoolean("listView", false)) }
    var sortOrder by rememberSaveable { mutableStateOf(libraryPreferences.getString("sortOrder", "Recent") ?: "Recent") }
    var showSort by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var editingLibraryId by remember { mutableStateOf<String?>(null) }
    var deletingLibraryId by remember { mutableStateOf<String?>(null) }
    val libraryBusy by viewModel.libraryBusy.collectAsState()
    var libraryError by remember { mutableStateOf<String?>(null) }
    var showStatistics by remember { mutableStateOf(false) }
    var showAccessibility by remember { mutableStateOf(false) }
    var showGeminiApiKeyScreen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showLibraryMenu by remember { mutableStateOf(false) }
    var showCreateLibrary by remember { mutableStateOf(false) }
    var libraryName by remember { mutableStateOf("") }
    var showMoveBooks by remember { mutableStateOf(false) }
    var showDeleteBooks by remember { mutableStateOf(false) }
    var showCreateFromWeb by remember { mutableStateOf(false) }
    var novelUrl by remember { mutableStateOf("") }
    var chapterLimit by rememberSaveable { mutableStateOf("2000") }
    var chapterLinkSelector by remember { mutableStateOf("a[href]") }
    var chapterContentSelector by remember { mutableStateOf(".chapter-content, .chapter-body, #chapter-content, .read-content, .entry-content, article, main, #content") }
    var chapterTitleSelector by remember { mutableStateOf("h1, h2") }
    var removeSelector by remember { mutableStateOf("script, style, noscript, nav, header, footer, aside, .ads, .advertisement, .share, .comments") }
    var aiScrapeBusy by remember { mutableStateOf(false) }
    var aiScrapeError by remember { mutableStateOf<String?>(null) }
    var showScrapeOptions by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<Uri?>(null) }
    var candidates by remember { mutableStateOf<List<FolderBook>?>(null) }
    var checked by remember { mutableStateOf(setOf<Uri>()) }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.backup(it, restore = false) }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }
    val openBookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) scope.launch { pagerState.animateScrollToPage(0) }
        else selectedExternalUri = uri
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { viewModel.import(it, activeLibraryId ?: "default") }
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder = it }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1 && externalBook == null) openBookPicker.launch(arrayOf("*/*"))
    }

    fun openExternalBook(file: ExternalBookFile) {
        if (openingExternalFileName != null) return
        openingExternalFileName = file.name
        scope.launch {
            try {
                val openedFile = withContext(Dispatchers.IO) {
                    val id = "external-" + UUID.nameUUIDFromBytes(file.uri.toString().toByteArray()).toString()
                    val extension = file.name.substringAfterLast('.', "book").lowercase(java.util.Locale.ROOT)
                    val local = File(context.cacheDir, "$id.$extension")
                    try {
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            local.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Unable to read this file.")
                    } catch (failure: Exception) {
                        local.delete()
                        throw failure
                    }
                    BookEntity(
                        id = id,
                        title = file.name.substringBeforeLast('.', file.name),
                        fileName = file.name,
                        localPath = local.absolutePath,
                        format = file.format,
                        importedAt = System.currentTimeMillis(),
                    )
                }
                externalBook = openedFile
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: "Could not open this book.")
                pagerState.animateScrollToPage(0)
            } finally {
                openingExternalFileName = null
            }
        }
    }

    fun leaveOpenedBook() {
        externalBook?.let { temporary ->
            File(temporary.localPath).delete()
            File(context.filesDir, "epub/${temporary.id}").deleteRecursively()
        }
        externalBook = null
        selectedId = null
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(selectedExternalUri) {
        val uri = selectedExternalUri ?: return@LaunchedEffect
        try {
            val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
            if (name.isNullOrBlank() || BookRepository.formatOf(name) == null) {
                snackbar.showSnackbar("That file type is not supported. Choose an EPUB, PDF, TXT, MOBI/AZW, CBZ or CBR book.")
                pagerState.animateScrollToPage(0)
            } else {
                openExternalBook(ExternalBookFile(uri, name))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snackbar.showSnackbar("Could not read the selected file.")
            pagerState.animateScrollToPage(0)
        } finally {
            selectedExternalUri = null
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage(it)
        }
    }
    LaunchedEffect(folder) {
        val tree = folder ?: return@LaunchedEffect
        try {
            val found = withContext(Dispatchers.IO) {
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val result = mutableListOf<FolderBook>()
                val pending = java.util.ArrayDeque<String>().apply {
                    add(DocumentsContract.getTreeDocumentId(tree))
                }
                val visited = mutableSetOf<String>()
                while (pending.isNotEmpty()) {
                    val directory = pending.removeFirst()
                    if (!visited.add(directory)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
                    context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                pending.add(id)
                            } else if (BookRepository.formatOf(name) != null) {
                                result += FolderBook(DocumentsContract.buildDocumentUriUsingTree(tree, id), name)
                            }
                        }
                    } ?: error("Could not read this folder")
                }
                result.sortedBy { it.name.lowercase() }
            }
            checked = emptySet()
            candidates = found
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snackbar.showSnackbar("Could not read this folder")
        } finally {
            folder = null
        }
    }

    val opened = externalBook ?: books.find { it.id == selectedId }
    
    if (opened != null && externalBook == null) TrackReadingTime(opened.id)
    if (opened != null && opened.format in setOf("PDF", "CBZ", "CBR")) {
        BackHandler { leaveOpenedBook() }
        ImagePageReaderScreen(opened, onBack = { leaveOpenedBook() },
            onProgress = { if (externalBook == null) viewModel.updateProgress(opened.id, it) })
        return
    }
    if (opened != null && opened.format in setOf("EPUB", "TXT", "MOBI", "AZW3")) {
        BackHandler { leaveOpenedBook() }
        Box(Modifier.fillMaxSize()) {
            EpubReaderScreen(
                book = opened,
                onBack = { leaveOpenedBook() },
                libraryBooks = books,
                onProgress = { progress ->
                    if (externalBook == null) viewModel.updateProgress(opened.id, progress)
                }
            )
        }
        return
    }

    val libraryView = androidx.compose.ui.platform.LocalView.current
    val libraryDark = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, libraryView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = !libraryDark
            }
        }
    }
    val latest = books.firstOrNull { it.lastOpenedAt != null }?.id
    val filtered = books.filter { book ->
        (activeLibraryId == null || book.libraryId == activeLibraryId) &&
            (when (filter) {
                "Reading" -> book.progress > 0f && book.progress < .999f
                "Finished" -> book.progress >= .999f
                "Unread" -> book.progress == 0f
                else -> true
            }) &&
            (book.title.contains(search, ignoreCase = true) || book.author.contains(search, ignoreCase = true))
    }.let { matches ->
        when (sortOrder) {
            "Title" -> matches.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            "Author" -> matches.sortedWith(compareBy<BookEntity> { it.author.lowercase(java.util.Locale.ROOT) }.thenBy { it.title })
            "Progress" -> matches.sortedByDescending { it.progress }
            "Imported" -> matches.sortedByDescending { it.importedAt }
            else -> matches
        }
    }
    val activeLibrary = libraries.find { it.id == activeLibraryId }
    val title = activeLibrary?.name ?: "All books"
    BackHandler(selectedBooks.isNotEmpty()) { selectedBooks = emptySet() }
    BackHandler(pagerState.currentPage == 1) { scope.launch { pagerState.animateScrollToPage(0) } }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { screen ->
      if (screen == 1) {
        FileManagerScreen()
      } else {
       ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Vibe Reader", Modifier.padding(24.dp), fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.headlineMedium)
                NavigationDrawerItem(label = { Text("All books") }, selected = activeLibraryId == null,
                    icon = { Icon(Icons.Rounded.LibraryBooks, null) }, badge = { Text("${books.size}") },
                    onClick = { activeLibraryId = null; selectedBooks = emptySet(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                Text("Libraries", Modifier.padding(start = 28.dp, top = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge)
                libraries.forEach { library ->
                        NavigationDrawerItem(label = { Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = activeLibraryId == library.id,
                            icon = { Icon(Icons.Rounded.MenuBook, null) },
                            badge = { Text("${books.count { it.libraryId == library.id }}") },
                            onClick = { activeLibraryId = library.id; selectedBooks = emptySet(); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp))
                }
                NavigationDrawerItem(label = { Text("New library") }, selected = false,
                    icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    onClick = { libraryName = ""; libraryError = null; editingLibraryId = null; showCreateLibrary = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(label = { Text("Import books") }, selected = false,
                    icon = { Icon(Icons.Rounded.Add, null) }, onClick = { import.launch(arrayOf("*/*")); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Scan folder") }, selected = false,
                    icon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { scan.launch(null); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Browse device files") }, selected = false,
                    icon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = {
                        scope.launch { drawer.close(); pagerState.animateScrollToPage(1) }
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Create EPUB from website") }, selected = false,
                    icon = { Icon(Icons.Rounded.Link, null) }, onClick = { showCreateFromWeb = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(label = { Text("Reading statistics") }, selected = false,
                    icon = { Icon(Icons.Rounded.BarChart, null) }, onClick = { showStatistics = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Accessibility") }, selected = false,
                    icon = { Icon(Icons.Rounded.Accessibility, null) }, onClick = { showAccessibility = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Gemini AI key") }, selected = false,
                    icon = { Icon(Icons.Rounded.Key, null) }, onClick = { showGeminiApiKeyScreen = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Export backup") }, selected = false,
                    icon = { Icon(Icons.Rounded.Backup, null) }, onClick = {
                        if (!libraryBusy) exportBackup.launch("VibeReader-backup.zip")
                        scope.launch { drawer.close() }
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Restore backup") }, selected = false,
                    icon = { Icon(Icons.Rounded.Restore, null) }, onClick = {
                        if (!libraryBusy) restoreBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                        scope.launch { drawer.close() }
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(16.dp))
                }
            }
        }
    ) {
      Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (selectedBooks.isNotEmpty()) {
                        Text("${selectedBooks.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                    } else {
                        Box {
                            Row(
                                Modifier.clickable { showLibraryMenu = true }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose library")
                            }
                            DropdownMenu(expanded = showLibraryMenu, onDismissRequest = { showLibraryMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("All books") },
                                    leadingIcon = { Icon(Icons.Rounded.LibraryBooks, null) },
                                    trailingIcon = { if (activeLibraryId == null) Icon(Icons.Rounded.CheckCircle, "Current library") },
                                    onClick = { activeLibraryId = null; selectedBooks = emptySet(); showLibraryMenu = false }
                                )
                                libraries.forEach { library ->
                                    DropdownMenuItem(
                                        text = { Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Rounded.MenuBook, null) },
                                        trailingIcon = { if (activeLibraryId == library.id) Icon(Icons.Rounded.CheckCircle, "Current library") },
                                        onClick = { activeLibraryId = library.id; selectedBooks = emptySet(); showLibraryMenu = false }
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectedBooks.isEmpty()) scope.launch { drawer.open() } else selectedBooks = emptySet() }) {
                        Icon(if (selectedBooks.isEmpty()) Icons.Rounded.Menu else Icons.Rounded.Close,
                            if (selectedBooks.isEmpty()) "Open library menu" else "Clear selection")
                    }
                },
                actions = {
                    if (selectedBooks.isNotEmpty()) {
                        IconButton(onClick = { showMoveBooks = true }) { Icon(Icons.Rounded.DriveFileMove, "Move selected books") }
                        IconButton(onClick = { showDeleteBooks = true }) { Icon(Icons.Rounded.Delete, "Delete selected books") }
                        IconButton(onClick = { selectedBooks = filtered.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all visible books") }
                    } else {
                        IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) search = "" }) {
                            Icon(if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search, "Search books")
                        }
                        Box {
                        IconButton(onClick = { showMoreActions = true }) { Icon(Icons.Rounded.MoreVert, "More library actions") }
                        DropdownMenu(expanded = showMoreActions, onDismissRequest = { showMoreActions = false }) {
                            DropdownMenuItem(
                                text = { Text("Create library") },
                                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                                onClick = { showMoreActions = false; libraryName = ""; libraryError = null; editingLibraryId = null; showCreateLibrary = true }
                            )
                            if (activeLibrary != null) {
                                DropdownMenuItem(text = { Text("Rename library") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { showMoreActions = false; editingLibraryId = activeLibrary.id; libraryName = activeLibrary.name; libraryError = null; showCreateLibrary = true })
                                if (activeLibrary.id != "default") DropdownMenuItem(text = { Text("Delete library") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { showMoreActions = false; deletingLibraryId = activeLibrary.id })
                            }
                            DropdownMenuItem(text = { Text("Select books") },
                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) }, enabled = filtered.isNotEmpty(),
                                onClick = { showMoreActions = false; selectedBooks = filtered.map { it.id }.toSet() })
                            DropdownMenuItem(
                                text = { Text("Create EPUB from website") },
                                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                onClick = { showMoreActions = false; showCreateFromWeb = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Scan folder") },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                                onClick = { showMoreActions = false; scan.launch(null) },
                                enabled = folder == null
                            )
                            DropdownMenuItem(
                                text = { Text("Import books") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                onClick = { showMoreActions = false; import.launch(arrayOf("*/*")) }
                            )
                        }
                    }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF302820),
                    navigationIconContentColor = Color(0xFFFFF4E3),
                    titleContentColor = Color(0xFFFFF4E3),
                    actionIconContentColor = Color(0xFFFFF4E3)
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { Spacer(Modifier.navigationBarsPadding()) }
      ) { padding ->
          Column(Modifier.fillMaxSize().padding(padding)) {
            if (libraryBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            downloadProgress?.let { progress ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(progress.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (progress.total > 0) Text("${progress.completed} / ${progress.total} chapters", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = viewModel::cancelDownload) { Icon(Icons.Rounded.Close, "Cancel EPUB download") }
                        }
                        if (progress.total > 0) LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (searchVisible) OutlinedTextField(
                value = search, onValueChange = { search = it }, label = { Text("Title or author") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} book${if (filtered.size == 1) "" else "s"}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { showSort = true }) { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(sortOrder) }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        listOf("Recent", "Title", "Author", "Progress", "Imported").forEach { order ->
                            DropdownMenuItem(text = { Text(order) }, onClick = {
                                sortOrder = order; showSort = false; libraryPreferences.edit().putString("sortOrder", order).apply()
                            })
                        }
                    }
                }
                IconButton(onClick = { listView = !listView; libraryPreferences.edit().putBoolean("listView", listView).apply() }) {
                    Icon(if (listView) Icons.Rounded.GridView else Icons.Rounded.ViewList, if (listView) "Show bookshelf" else "Show list")
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Reading", "Unread", "Finished")) { value ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
                }
            }
            if (filtered.isEmpty() && listView) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(if (books.isEmpty()) "Your next read starts here" else "No books here yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(if (search.isNotBlank()) "Try another title or author." else "Import books or move them here from another library.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { import.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Add, null); Text("Import books") }
                }
            } else {
                val toggle: (BookEntity) -> Unit = { book ->
                    selectedBooks = if (book.id in selectedBooks) selectedBooks - book.id else selectedBooks + book.id
                }
                if (listView) LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { book ->
                        BookListRow(book, book.id == latest, book.id in selectedBooks,
                            onClick = { if (selectedBooks.isEmpty()) selectedId = book.id else toggle(book) }, onLongClick = { toggle(book) })
                    }
                } else {
                    val rows = filtered.chunked(3)
                    LazyColumn(Modifier.weight(1f).background(Color(0xFF61412C)), contentPadding = PaddingValues(top = 4.dp)) {
                        items(count = maxOf(4, rows.size), key = { index -> "shelf:$index" }) { index ->
                            ShelfRow(rows.getOrNull(index).orEmpty(), latest, selectedBooks,
                                onOpen = { selectedId = it.id }, onToggle = toggle)
                        }
                    }
                }
            }
          }
      }
       }
      }
    }

    if (showStatistics) ReadingStatisticsDialog(books) { showStatistics = false }
    if (showAccessibility) {
        AlertDialog(
            onDismissRequest = { showAccessibility = false },
            title = { Text("Accessibility") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Interface text size", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = uiTextScale,
                        onValueChange = onUiTextScaleChange,
                        valueRange = .85f..1.5f,
                        steps = 12
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Smaller")
                        Text("${(uiTextScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Text("Larger")
                    }
                    Text("Book text size", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = readingTextScale,
                        onValueChange = onReadingTextScaleChange,
                        valueRange = .7f..2.2f,
                        steps = 14
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Smaller")
                        Text("${(readingTextScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Text("Larger")
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reduce motion", style = MaterialTheme.typography.titleSmall)
                            Text("Use instant page turns and shorter settling animations.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = reduceMotion, onCheckedChange = onReduceMotionChange)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("E-Ink display mode", style = MaterialTheme.typography.titleSmall)
                            Text("Use black and white reading pages and turn off page animations and textures.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = eInkMode, onCheckedChange = onEInkModeChange)
                    }
                    TextButton(onClick = { onUiTextScaleChange(1f); onReadingTextScaleChange(1f); onReduceMotionChange(false); onEInkModeChange(false) }) { Text("Reset accessibility settings") }
                }
            },
            confirmButton = { TextButton(onClick = { showAccessibility = false }) { Text("Done") } }
        )
    }
    if (showGeminiApiKeyScreen) GeminiApiKeyScreen(onClose = { showGeminiApiKeyScreen = false })
    pendingRestore?.let { uri ->
        AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("Restore backup?") },
            text = { Text("Books, libraries, reading progress, notes and bookmarks will be imported. Books already present will be skipped.") },
            confirmButton = { TextButton(enabled = !libraryBusy, onClick = { pendingRestore = null; viewModel.backup(uri, restore = true) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } })
    }
    opened?.let {
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(it.title) },
            text = { Text("Reading support for ${it.format} is still in development.") },
            confirmButton = { TextButton(onClick = { selectedId = null }) { Text("Close") } }
        )
    }
    if (showCreateLibrary) {
        AlertDialog(
            onDismissRequest = { if (!libraryBusy) showCreateLibrary = false },
            title = { Text(if (editingLibraryId == null) "New library" else "Rename library") },
            text = {
                OutlinedTextField(
                    isError = libraryError != null,
                    supportingText = { libraryError?.let { Text(it) } },
                    enabled = !libraryBusy,
                    value = libraryName,
                    onValueChange = { libraryName = it.take(60) },
                    label = { Text("Library name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = libraryName.isNotBlank() && !libraryBusy, onClick = {
                    viewModel.saveLibrary(editingLibraryId, libraryName) { id, failure ->
                        if (id != null) { activeLibraryId = id; filter = "All"; search = ""; showCreateLibrary = false }
                        libraryError = failure
                    }
                }) { Text(if (libraryBusy) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !libraryBusy, onClick = { showCreateLibrary = false }) { Text("Cancel") } }
        )
    }
    if (showMoveBooks) {
        AlertDialog(
            onDismissRequest = { showMoveBooks = false },
            title = { Text("Move ${selectedBooks.size} book${if (selectedBooks.size == 1) "" else "s"} to") },
            text = {
                if (libraries.isEmpty()) Text("Create a library first.")
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(libraries, key = { it.id }) { library ->
                        TextButton(
                            onClick = {
                                viewModel.moveBooks(selectedBooks.toList(), library.id) { success ->
                                    if (success) {
                                        activeLibraryId = library.id; filter = "All"; search = ""
                                        selectedBooks = emptySet(); showMoveBooks = false
                                    }
                                }
                            },
                            enabled = !libraryBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(library.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { libraryName = ""; editingLibraryId = null; libraryError = null; showCreateLibrary = true }) { Text("New library") } },
            dismissButton = { TextButton(onClick = { showMoveBooks = false }) { Text("Cancel") } }
        )
    }
    deletingLibraryId?.let { id ->
        AlertDialog(onDismissRequest = { deletingLibraryId = null }, title = { Text("Delete library?") },
            text = { Text("Its books will move to My Library.") },
            confirmButton = { TextButton(enabled = !libraryBusy, onClick = {
                viewModel.deleteLibrary(id) { success -> if (success) { activeLibraryId = "default"; deletingLibraryId = null } }
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingLibraryId = null }) { Text("Cancel") } })
    }
    if (showDeleteBooks) {
        AlertDialog(
            onDismissRequest = { showDeleteBooks = false },
            title = { Text("Delete selected books?") },
            text = { Text("${selectedBooks.size} book${if (selectedBooks.size == 1) "" else "s"} and their reading notes will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBooks(selectedBooks.toList())
                    selectedBooks = emptySet()
                    showDeleteBooks = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteBooks = false }) { Text("Cancel") } }
        )
    }
    if (showCreateFromWeb) {
        AlertDialog(
            onDismissRequest = { showCreateFromWeb = false },
            title = { Text("Create EPUB from website") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = novelUrl,
                        onValueChange = { novelUrl = it },
                        label = { Text("Table of contents URL") },
                        singleLine = true
                    )
                    TextButton(onClick = { showScrapeOptions = !showScrapeOptions }) {
                        Text(if (showScrapeOptions) "Hide site selectors" else "Advanced site selectors")
                    }
                    if (showScrapeOptions) {
                        OutlinedTextField(value = chapterLimit,
                            onValueChange = { chapterLimit = it.filter(Char::isDigit).take(5) },
                            label = { Text("Maximum chapters") }, singleLine = true,
                            supportingText = { Text("1–9,000. A longer book will ask you to increase this limit.") })
                        OutlinedTextField(
                            value = chapterLinkSelector,
                            onValueChange = { chapterLinkSelector = it },
                            label = { Text("Chapter link CSS selector") },
                            supportingText = { Text("Default: a[href]") }
                        )
                        OutlinedTextField(
                            value = chapterContentSelector,
                            onValueChange = { chapterContentSelector = it },
                            label = { Text("Chapter content CSS selector") },
                            supportingText = { Text("Used when a site needs a custom selector") }
                        )
                        OutlinedTextField(
                            value = chapterTitleSelector,
                            onValueChange = { chapterTitleSelector = it },
                            label = { Text("Chapter title CSS selector") },
                            supportingText = { Text("Default: h1, h2") }
                        )
                        OutlinedTextField(
                            value = removeSelector,
                            onValueChange = { removeSelector = it },
                            label = { Text("Remove from chapter CSS selector") },
                            supportingText = { Text("Navigation, advertisements, comments, etc.") }
                        )
                        TextButton(
                            enabled = !aiScrapeBusy && novelUrl.startsWith("http", ignoreCase = true),
                            onClick = {
                                aiScrapeError = null
                                if (!GeminiApiKeyStore(context).hasKey()) showGeminiApiKeyScreen = true
                                else scope.launch {
                                    aiScrapeBusy = true
                                    try {
                                        val suggestion = withContext(Dispatchers.IO) {
                                            GeminiAiClient(context).suggestScrapeConfiguration(novelUrl)
                                        }
                                        chapterLinkSelector = suggestion.chapterLinkSelector
                                        chapterContentSelector = suggestion.chapterContentSelector
                                        chapterTitleSelector = suggestion.chapterTitleSelector
                                        removeSelector = suggestion.removeSelector
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        aiScrapeError = failure.message ?: "Could not suggest scraper settings."
                                    } finally {
                                        aiScrapeBusy = false
                                    }
                                }
                            }
                        ) {
                            if (aiScrapeBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (aiScrapeBusy) "Analyzing site…" else "Suggest selectors with Gemini")
                        }
                        Text("A sanitized sample from the table of contents and one chapter is sent to Gemini. Review the selectors before downloading.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        aiScrapeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("Chapter pages are downloaded one at a time. Some sites may block automated access or need custom selectors.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = downloadProgress == null && novelUrl.startsWith("http", ignoreCase = true) &&
                    chapterLimit.toIntOrNull()?.let { it in 1..9_000 } == true, onClick = {
                    viewModel.createEpubFromUrl(
                        novelUrl,
                        WebNovelScrapeOptions(
                            maxChapters = chapterLimit.toIntOrNull() ?: 2_000,
                            chapterLinkSelector = chapterLinkSelector,
                            chapterContentSelector = chapterContentSelector,
                            chapterTitleSelector = chapterTitleSelector,
                            removeSelector = removeSelector
                        ),
                        activeLibraryId ?: "default"
                    )
                    showCreateFromWeb = false
                }) { Text("Download and create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFromWeb = false }) { Text("Cancel") } }
        )
    }
    candidates?.let { files ->
        AlertDialog(
            onDismissRequest = { candidates = null },
            title = { Text("Choose books") },
            text = {
                if (files.isEmpty()) Text("No supported book files in this folder.")
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(files.size) { index ->
                        val entry = files[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.uri in checked, onCheckedChange = { selected ->
                                checked = if (selected) checked + entry.uri else checked - entry.uri
                            })
                            Text(entry.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = checked.isNotEmpty(), onClick = {
                    files.filter { it.uri in checked }.forEach { viewModel.import(it.uri, activeLibraryId ?: "default") }
                    candidates = null
                }) { Text("Import selected") }
            },
            dismissButton = { TextButton(onClick = { candidates = null }) { Text("Close") } }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookEntity,
    recent: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit
) {
    val cover by produceState<android.graphics.Bitmap?>(null, book.id) {
        value = withContext(Dispatchers.IO) { BookArtwork.load(book) }
    }
    val covers = listOf(0xFF344B53, 0xFF526048, 0xFF794942, 0xFF695474, 0xFF4F586E)
    Column {
        val coverShape = RoundedCornerShape(4.dp)
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().aspectRatio(.74f)
                .shadow(12.dp, coverShape, clip = false)
                .clip(coverShape)
                .then(if (selected) Modifier.border(2.dp, Color(0xFFFFD36A), coverShape) else Modifier)
                .background(Color(covers[Math.floorMod(book.id.hashCode(), covers.size)]))
                .semantics { this.selected = selected }
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (selectionMode) onToggleSelection() else onOpen() },
                    onLongClick = onToggleSelection,
                    onLongClickLabel = "Select book"
                )
        ) {
            val artwork = cover
            if (artwork != null) {
                Image(artwork.asImageBitmap(), book.title, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            } else Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.MenuBook, null, tint = Color(0xFFFFEFCE))
                Text(book.title, color = Color.White, fontFamily = FontFamily.Serif,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            if (recent) Icon(Icons.Rounded.Bookmark, "Last read", tint = Color(0xFFB5F0DB),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = Color(0xFFFFD36A),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xC918211E)).padding(horizontal = 7.dp, vertical = 5.dp)) {
                Text("${(book.progress * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { book.progress }, color = Color(0xFFE4CD92),
                    trackColor = Color(0xFF55605B), modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }

    }
}

@Composable
private fun ShelfRow(
    books: List<BookEntity>, recentId: String?, selectedIds: Set<String>,
    onOpen: (BookEntity) -> Unit, onToggle: (BookEntity) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Image(painterResource(com.paperlight.reader.R.drawable.library_shelf_row), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                repeat(3) { index ->
                    Box(Modifier.weight(1f).aspectRatio(.74f)) {
                        books.getOrNull(index)?.let { book ->
                            BookTile(book, book.id == recentId, book.id in selectedIds, selectedIds.isNotEmpty(),
                                onOpen = { onOpen(book) }, onToggleSelection = { onToggle(book) })
                        }
                    }
                }
            }
        }

    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(book: BookEntity, recent: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cover by produceState<android.graphics.Bitmap?>(null, book.id) {
        value = withContext(Dispatchers.IO) { BookArtwork.load(book) }
    }
    ListItem(
        headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOf(book.author, book.format, "${(book.progress * 100).toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")) },
        leadingContent = {
            Box(Modifier.size(width = 48.dp, height = 68.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Icon(Icons.Rounded.MenuBook, null)
            }
        },
        trailingContent = { if (selected) Icon(Icons.Rounded.CheckCircle, "Selected") else if (recent) Icon(Icons.Rounded.Bookmark, "Last read") },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.semantics { this.selected = selected }.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
    )
    HorizontalDivider()
}
