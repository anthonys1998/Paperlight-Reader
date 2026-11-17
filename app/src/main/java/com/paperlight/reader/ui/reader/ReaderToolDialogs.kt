package com.paperlight.reader.ui.reader

import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.epub.EpubChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
    val otherNotes = otherBook?.let { readOther(it.id) }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlights & notes") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { Text(book.title, style = MaterialTheme.typography.titleSmall) }
                if (annotations.isEmpty()) item { Text("No highlights or notes yet.", Modifier.padding(vertical = 12.dp)) }
                items(annotations, key = { it.id }) { annotation ->
                    AnnotationRow(annotation, { onOpen(annotation) }, { onEdit(book, annotation) })
                }
                if (otherBooks.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(otherBook?.title ?: "Choose another book", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                otherBooks.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.title) },
                                        onClick = { onOtherBook(candidate); expanded = false },
                                    )
                                }
                            }
                        }
                    }
                    if (otherBook != null && otherNotes.isEmpty()) item { Text("No highlights or notes in this book.") }
                    items(otherNotes, key = { it.id }) { annotation ->
                        AnnotationRow(annotation, null, { otherBook?.let { onEdit(it, annotation) } })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AnnotationRow(annotation: ReaderAnnotation, onOpen: (() -> Unit)?, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val body = Modifier.weight(1f)
        Column(
            (if (onOpen == null) body else body.clickable(onClick = onOpen))
                .padding(vertical = 8.dp),
        ) {
            Text(annotation.quote, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (annotation.note.isNotBlank()) {
                Text(annotation.note, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onEdit) { Text("Edit") }
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
                Text(annotation.quote, maxLines = 4, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(note, { note = it.take(5000) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                if (existing) TextButton(onClick = onDelete) { Text("Remove highlight and note") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note.trim()) }, enabled = existing || note.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
