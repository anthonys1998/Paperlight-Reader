package com.paperlight.reader.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.data.readPdfPageText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@androidx.annotation.RequiresApi(35)
@Composable
internal fun PdfTextDialog(book: BookEntity, page: Int, onSave: (ReaderAnnotation, Boolean) -> Unit, onDismiss: () -> Unit) {
    var text by remember(book.id, page) { mutableStateOf<TextFieldValue?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf("yellow") }
    LaunchedEffect(book.id, page) {
        try {
            val content = withContext(Dispatchers.IO) { readPdfPageText(book.localPath, page) }
            text = TextFieldValue(content)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not read this page’s text."
        }
    }
    fun save(withNote: Boolean) {
        val value = text ?: return
        val selection = value.selection
        if (selection.collapsed) return
        val start = selection.min
        val end = selection.max
        onSave(ReaderAnnotation(UUID.randomUUID().toString(), page, start, end,
            value.text.substring(start, end), "", color), withNote)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Page ${page + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val value = text
                when {
                    error != null -> Text(error.orEmpty())
                    value == null -> CircularProgressIndicator()
                    value.text.isBlank() -> Text("This page has no selectable text.")
                    else -> {
                        Text("Select a passage to highlight or annotate.", style = MaterialTheme.typography.bodySmall)
                        BasicTextField(value = value, onValueChange = { text = it }, readOnly = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("yellow", "green", "blue", "pink").forEach { name ->
                                FilterChip(selected = color == name, onClick = { color = name },
                                    label = { Text(name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text?.selection?.collapsed == false, onClick = { save(false) }) { Text("Highlight") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = text?.selection?.collapsed == false, onClick = { save(true) }) { Text("Note") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        })
}
