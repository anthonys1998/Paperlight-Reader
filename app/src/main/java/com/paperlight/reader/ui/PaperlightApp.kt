package com.paperlight.reader.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.ui.reader.EpubReaderScreen
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class FolderBook(val uri: Uri, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperlightApp(viewModel: LibraryViewModel) {
    val books by viewModel.books.collectAsState()
    val libraries by viewModel.libraries.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(setOf<String>()) }
    var activeLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showCreateLibrary by remember { mutableStateOf(false) }
    var libraryName by remember { mutableStateOf("") }
    var showMoveBooks by remember { mutableStateOf(false) }
    var showDeleteBooks by remember { mutableStateOf(false) }
    var showCreateFromWeb by remember { mutableStateOf(false) }
    var novelUrl by remember { mutableStateOf("") }
    var chapterLinkSelector by remember { mutableStateOf("a[href]") }
    var chapterContentSelector by remember { mutableStateOf(".chapter-content, .chapter-body, #chapter-content, .read-content, .entry-content, article, main, #content") }
    var showScrapeOptions by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<Uri?>(null) }
    var candidates by remember { mutableStateOf<List<FolderBook>?>(null) }
    var checked by remember { mutableStateOf(setOf<Uri>()) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(viewModel::import)
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder = it }

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

    val opened = books.find { it.id == selectedId }
    if (opened != null && opened.format in setOf("PDF", "CBZ", "CBR")) {
        BackHandler { selectedId = null }
        ImagePageReaderScreen(opened, onBack = { selectedId = null },
            onProgress = { viewModel.updateProgress(opened.id, it) })
        return
    }
    if (opened != null && opened.format in setOf("EPUB", "TXT", "MOBI", "AZW3")) {
        BackHandler { selectedId = null }
        Box(Modifier.fillMaxSize()) {
            EpubReaderScreen(
                book = opened,
                onBack = { selectedId = null },
                libraryBooks = books,
                onProgress = { progress ->
                    viewModel.updateProgress(opened.id, progress)
                }
            )
        }
        return
    }

    val latest = books.firstOrNull { it.lastOpenedAt != null }?.id
    val filtered = books.filter { book ->
        (activeLibraryId == null || book.libraryId == activeLibraryId) &&
            (book.title.contains(search, ignoreCase = true) || book.author.contains(search, ignoreCase = true))
    }
    Box(Modifier.fillMaxSize()) {
      Image(
          painter = painterResource(com.paperlight.reader.R.drawable.library_bookshelf),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize()
      )
      Box(Modifier.fillMaxSize().background(
          Brush.verticalGradient(listOf(Color(0x220D0906), Color.Transparent, Color(0x330D0906)))
      ))
      Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (selectedBooks.isEmpty()) "My Library" else "${selectedBooks.size} selected", fontFamily = FontFamily.Serif) },
                actions = {
                    if (selectedBooks.isNotEmpty()) {
                        IconButton(onClick = { showMoveBooks = true }) { Icon(Icons.Rounded.DriveFileMove, "Move selected books") }
                        IconButton(onClick = { showDeleteBooks = true }) { Icon(Icons.Rounded.Delete, "Delete selected books") }
                        IconButton(onClick = { selectedBooks = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                    } else {
                        IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) search = "" }) {
                            Icon(if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search, "Search books")
                        }
                        IconButton(onClick = { showMoreActions = true }) { Icon(Icons.Rounded.MoreVert, "More library actions") }
                        DropdownMenu(expanded = showMoreActions, onDismissRequest = { showMoreActions = false }) {
                            DropdownMenuItem(
                                text = { Text("Create library") },
                                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                                onClick = { showMoreActions = false; libraryName = ""; showCreateLibrary = true }
                            )
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xE92E241E),
                    titleContentColor = Color(0xFFFFF4E3),
                    actionIconContentColor = Color(0xFFFFF4E3)
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { Spacer(Modifier.navigationBarsPadding()) }
      ) { padding ->
          Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (searchVisible) OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search your library") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(selected = activeLibraryId == null, onClick = { activeLibraryId = null }, label = { Text("All books") })
                }
                items(libraries, key = { it.id }) { library ->
                    FilterChip(
                        selected = activeLibraryId == library.id,
                        onClick = { activeLibraryId = library.id },
                        label = { Text(library.name, maxLines = 1) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (books.isEmpty()) "Add books with + or scan a folder." else "No books in this library.", color = Color(0xFF3A2B22))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(94.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookTile(
                            book = book,
                            recent = book.id == latest,
                            selected = book.id in selectedBooks,
                            selectionMode = selectedBooks.isNotEmpty(),
                            onOpen = { selectedId = book.id },
                            onToggleSelection = {
                                selectedBooks = if (book.id in selectedBooks) selectedBooks - book.id else selectedBooks + book.id
                            }
                        )
                    }
                }
            }
          }
      }
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
            onDismissRequest = { showCreateLibrary = false },
            title = { Text("Create library") },
            text = {
                OutlinedTextField(
                    value = libraryName,
                    onValueChange = { libraryName = it.take(60) },
                    label = { Text("Library name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = libraryName.isNotBlank(), onClick = {
                    viewModel.createLibrary(libraryName)
                    showCreateLibrary = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateLibrary = false }) { Text("Cancel") } }
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
                                viewModel.moveBooks(selectedBooks.toList(), library.id)
                                selectedBooks = emptySet()
                                showMoveBooks = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(library.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMoveBooks = false }) { Text("Cancel") } }
        )
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
                    }
                    Text("Chapter pages are downloaded one at a time. Some sites may block automated access or need custom selectors.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = novelUrl.startsWith("http", ignoreCase = true), onClick = {
                    viewModel.createEpubFromUrl(
                        novelUrl,
                        WebNovelScrapeOptions(
                            chapterLinkSelector = chapterLinkSelector,
                            chapterContentSelector = chapterContentSelector
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
                    files.filter { it.uri in checked }.forEach { viewModel.import(it.uri) }
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
                .combinedClickable(
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
            if (artwork == null) Text(book.format, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "${(book.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = Color(0xFF2E241E), modifier = Modifier.padding(top = 3.dp)
        )
        LinearProgressIndicator(
            progress = { book.progress },
            color = Color(0xFF584033),
            trackColor = Color(0x883A2A20),
            modifier = Modifier.fillMaxWidth().height(3.dp)
        )
    }
}
