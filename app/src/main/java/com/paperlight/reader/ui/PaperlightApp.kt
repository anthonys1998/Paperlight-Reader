package com.paperlight.reader.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.MenuBook
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class FolderBook(val uri: Uri, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperlightApp(viewModel: LibraryViewModel) {
    val books by viewModel.books.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<BookEntity?>(null) }
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
                val directory = DocumentsContract.getTreeDocumentId(tree)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1) ?: continue
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR ||
                                BookRepository.formatOf(name) == null) continue
                            add(FolderBook(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), name))
                        }
                    }.sortedBy { it.name.lowercase() }
                } ?: error("Could not read this folder")
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
    if (opened?.format == "EPUB") {
        BackHandler { selectedId = null }
        Box(Modifier.fillMaxSize().displayCutoutPadding()) {
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
    val filtered = books.filter {
        it.title.contains(search, ignoreCase = true) || it.author.contains(search, ignoreCase = true)
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Paperlight", fontFamily = FontFamily.Serif) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { import.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import books")
                }
                OutlinedButton(onClick = { scan.launch(null) }, modifier = Modifier.fillMaxWidth(), enabled = folder == null) {
                    Text(if (folder == null) "Scan folder" else "Scanning…")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search your library") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (books.isEmpty()) "Import a book to start reading." else "No matching books.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(94.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookTile(book, book.id == latest, { selectedId = book.id }, { pendingRemoval = book })
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
    pendingRemoval?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove this book?") },
            text = { Text(book.title) },
            confirmButton = { TextButton(onClick = { viewModel.remove(book); pendingRemoval = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } }
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
private fun BookTile(book: BookEntity, recent: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val covers = listOf(0xFF344B53, 0xFF526048, 0xFF794942, 0xFF695474, 0xFF4F586E)
    Column {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().aspectRatio(.74f)
                .background(Color(covers[Math.floorMod(book.id.hashCode(), covers.size)]))
                .combinedClickable(onClick = onOpen, onLongClick = onRemove, onLongClickLabel = "Remove book")
        ) {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.MenuBook, null, tint = Color(0xFFFFEFCE))
                Text(book.title, color = Color.White, fontFamily = FontFamily.Serif,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            if (recent) Icon(Icons.Rounded.Bookmark, "Last read", tint = Color(0xFFB5F0DB),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
            Text(book.format, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "${(book.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall, maxLines = 1
        )
        LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(
            Brush.verticalGradient(listOf(Color(0xFFB77E49), Color(0xFF674024)))
        ))
    }
}
