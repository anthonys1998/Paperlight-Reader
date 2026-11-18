package com.paperlight.reader.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookRepository

internal data class ExternalBookFile(val uri: Uri, val name: String) {
    val format: String get() = BookRepository.formatOf(name).orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileManagerScreen(
    folderName: String?,
    files: List<ExternalBookFile>,
    loading: Boolean,
    openingFileName: String?,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpen: (ExternalBookFile) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to library") } },
                actions = { IconButton(onClick = onChooseFolder) { Icon(Icons.Rounded.FolderOpen, "Choose a folder") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folderName?.takeIf(String::isNotBlank) ?: "Browse folders", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (folderName == null) "Open a book without adding it to your library." else "${files.size} supported book${if (files.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (folderName == null) FilledTonalButton(onClick = onChooseFolder) { Text("Choose folder") }
            }
            HorizontalDivider()
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                folderName == null -> EmptyFileManager(onChooseFolder,
                    "Swipe left from the library to browse files. Choose a folder to start.")
                files.isEmpty() -> EmptyFileManager(onChooseFolder, "No supported books found in this folder.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.uri.toString() }) { file ->
                        ListItem(
                            headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(file.format) },
                            leadingContent = { Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { if (openingFileName == file.name) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
                            modifier = Modifier.clickable(enabled = openingFileName == null) { onOpen(file) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFileManager(onChooseFolder: () -> Unit, message: String = "Choose a folder to browse books on your device.") {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text("Opening a file here reads it from a temporary copy; it will not appear in your imported libraries.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onChooseFolder) { Text("Choose folder") }
    }
}
