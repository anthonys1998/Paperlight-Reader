package com.paperlight.reader.ui.reader

import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.ai.GeminiAiClient
import com.paperlight.reader.ai.GeminiApiKeyStore
import com.paperlight.reader.ui.GeminiApiKeyScreen
import com.paperlight.reader.epub.EpubChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal data class ReaderSearchHit(val chapter: Int, val query: String, val occurrence: Int, val excerpt: String)

@Composable
internal fun ReaderSearchDialog(
    chapters: List<EpubChapter>,
    titles: List<String>,
    onOpen: (ReaderSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ReaderSearchHit>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(submitted) {
        if (submitted.isBlank()) return@LaunchedEffect
        results = null
        error = false
        try {
            results = withContext(Dispatchers.IO) {
                val matches = mutableListOf<ReaderSearchHit>()
                val normalized = submitted.replace(Regex("[\\s\\u00a0]+"), " ")
                for ((chapter, entry) in chapters.withIndex()) {
                    ensureActive()
                    val html = chapterHtml(entry).replace(
                        Regex("""<head\b[^>]*>[\s\S]*?</head\s*>""", RegexOption.IGNORE_CASE), ""
                    )
                    val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                        .toString().replace(Regex("[\\s\\u00a0]+"), " ")
                    var start = 0
                    var occurrence = 0
                    while (matches.size < 200) {
                        val index = text.indexOf(normalized, start, ignoreCase = true)
                        if (index < 0) break
                        val end = index + normalized.length
                        val excerpt = text.substring((index - 45).coerceAtLeast(0), (end + 65).coerceAtMost(text.length)).trim()
                        matches.add(ReaderSearchHit(chapter, normalized, occurrence++, excerpt))
                        start = end
                    }
                    if (matches.size == 200) break
                }
                matches
            }
        } catch (e: java.io.IOException) {
            error = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search this book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(200) },
                    label = { Text("Find text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitted = query.trim() }),
                )
                when {
                    error -> Text("Could not search this book.")
                    submitted.isBlank() -> Unit
                    results == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    results.orEmpty().isEmpty() -> Text("No matches for “$submitted”.")
                    else -> {
                        if (results?.size == 200) Text("Showing the first 200 matches.")
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(results.orEmpty()) { hit ->
                                ListItem(
                                    headlineContent = { Text(titles[hit.chapter]) },
                                    supportingContent = { Text(hit.excerpt) },
                                    modifier = Modifier.clickable { onOpen(hit) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { submitted = query.trim() }, enabled = query.isNotBlank()) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ReaderNotesDialog(
    book: BookEntity,
    chapterTitles: List<String>,
    palette: ReaderPalette,
    otherBooks: List<BookEntity>,
    annotations: List<ReaderAnnotation>,
    otherBook: BookEntity?,
    onOtherBook: (BookEntity) -> Unit,
    readOther: (String) -> List<ReaderAnnotation>,
    onOpen: (ReaderAnnotation) -> Unit,
    onEdit: (BookEntity, ReaderAnnotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyStore = remember(context) { GeminiApiKeyStore(context) }
    val aiClient = remember(context) { GeminiAiClient(context) }
    var hasApiKey by remember { mutableStateOf(keyStore.hasKey()) }
    var showApiKeyScreen by remember { mutableStateOf(false) }
    var summary by remember(book.id) { mutableStateOf<String?>(null) }
    var summaryError by remember(book.id) { mutableStateOf<String?>(null) }
    var summarizing by remember(book.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val otherNotes = otherBook?.let { readOther(it.id) }.orEmpty()
    val order = compareBy(ReaderAnnotation::chapter, ReaderAnnotation::start)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = palette.paper, contentColor = palette.ink) {
            Column {
                Surface(color = palette.panel, contentColor = palette.ink) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to book")
                        }
                        Text("Highlights & notes", style = MaterialTheme.typography.titleLarge)
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Current book", color = palette.accent, style = MaterialTheme.typography.labelLarge)
                            Text(book.title, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = !summarizing && annotations.any { it.note.isNotBlank() },
                                onClick = {
                                    if (!hasApiKey) showApiKeyScreen = true
                                    else coroutineScope.launch {
                                        summarizing = true
                                        summaryError = null
                                        try {
                                            summary = aiClient.summarizeNotes(annotations)
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            summaryError = failure.message ?: "Could not summarize these notes."
                                        } finally {
                                            summarizing = false
                                        }
                                    }
                                }
                            ) {
                                if (summarizing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("Summarize my notes with Gemini")
                            }
                            Text(
                                "Only your written notes are sent to Gemini for this summary; highlighted book text is excluded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.ink.copy(alpha = .72f)
                            )
                            summary?.let { result ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Summary of your notes", style = MaterialTheme.typography.titleSmall)
                                        Text(result, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            summaryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (annotations.isEmpty()) item {
                        Text("Select a passage in the reader to save a highlight or note.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(annotations.sortedWith(order), key = { "current:${it.id}" }) { annotation ->
                        AnnotationCard(annotation, chapterTitles.getOrNull(annotation.chapter), palette,
                            onOpen = { onOpen(annotation) }, onEdit = { onEdit(book, annotation) })
                    }
                    if (otherBooks.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider()
                                Text("Other books", style = MaterialTheme.typography.titleMedium)
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) {
                                        Text(otherBook?.title ?: "Choose a book", Modifier.weight(1f, fill = false),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        otherBooks.forEach { candidate ->
                                            DropdownMenuItem(text = { Text(candidate.title) },
                                                onClick = { onOtherBook(candidate); expanded = false })
                                        }
                                    }
                                }
                            }
                        }
                        if (otherBook != null) {
                            if (otherNotes.isEmpty()) item { Text("No highlights or notes in this book.") }
                            items(otherNotes.sortedWith(order), key = { "other:${it.id}" }) { annotation ->
                                AnnotationCard(annotation, "${if (otherBook.format in setOf("PDF", "CBZ", "CBR")) "Page" else "Section"} ${annotation.chapter + 1}", palette,
                                    onOpen = null, onEdit = { onEdit(otherBook, annotation) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (showApiKeyScreen) GeminiApiKeyScreen(
        onClose = { showApiKeyScreen = false },
        onSaved = { hasApiKey = keyStore.hasKey() },
    )
}

@Composable
private fun AnnotationCard(
    annotation: ReaderAnnotation,
    chapter: String?,
    palette: ReaderPalette,
    onOpen: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    val highlight = when (annotation.color) {
        "green" -> androidx.compose.ui.graphics.Color(0xFF87C79A)
        "blue" -> androidx.compose.ui.graphics.Color(0xFF94BFE3)
        "pink" -> androidx.compose.ui.graphics.Color(0xFFE7A7B7)
        else -> androidx.compose.ui.graphics.Color(0xFFE9D079)
    }
    val tint = androidx.compose.ui.graphics.lerp(palette.panel, highlight, .32f)
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = palette.panel, contentColor = palette.ink)) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chapter ?: "Highlight", Modifier.weight(1f), color = palette.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit highlight or note") }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                color = tint, contentColor = palette.ink, shape = MaterialTheme.shapes.small,
            ) {
                Text(annotation.quote, Modifier.padding(12.dp), maxLines = 6, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (annotation.note.isNotBlank()) {
                Text(annotation.note, Modifier.padding(top = 10.dp, end = 6.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
            if (onOpen != null) {
                TextButton(onClick = onOpen, modifier = Modifier.padding(top = 4.dp)) { Text("Go to passage") }
            }
        }
    }
}

@Composable
internal fun ReaderNoteEditor(
    annotation: ReaderAnnotation,
    existing: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(annotation.id) { mutableStateOf(annotation.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing) "Edit note" else "Add note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(annotation.quote, Modifier.fillMaxWidth().padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(note, { note = it.take(5000) }, label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7)
                if (existing) TextButton(onClick = onDelete) {
                    Text("Remove highlight and note", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note.trim()) }, enabled = existing || note.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
