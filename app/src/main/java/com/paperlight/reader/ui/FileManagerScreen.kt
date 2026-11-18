package com.paperlight.reader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paperlight.reader.data.BookRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ExternalBookFile(val uri: Uri, val name: String) {
    val format: String get() = BookRepository.formatOf(name).orEmpty()
}

internal enum class FileManagerMode { Browse, Import, Scan }

/** A file explorer that stays inside the second page of the library pager. */
@Composable
internal fun FileManagerScreen(
    mode: FileManagerMode,
    onClose: () -> Unit,
    onOpenFile: (ExternalBookFile) -> Unit,
    onImportFiles: (List<Uri>) -> Unit,
    onScanFolder: (File) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val primary = remember { Environment.getExternalStorageDirectory() }
    var path by rememberSaveable { mutableStateOf(primary.absolutePath) }
    var selectedPaths by rememberSaveable(mode) { mutableStateOf(arrayListOf<String>()) }
    var accessVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accessVersion++ }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { accessVersion++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) accessVersion++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val hasAccess = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val roots = remember(context) {
        buildList {
            add(primary)
            context.getExternalFilesDirs(null).drop(1).forEach { directory ->
                val root = directory?.absolutePath?.substringBefore("/Android/")?.let(::File)
                if (root != null && root.isDirectory && none { it.absolutePath == root.absolutePath }) add(root)
            }
        }
    }
    val folder = remember(path) { File(path) }
    val listing by key(folder, hasAccess, accessVersion, mode) {
        produceState<Result<List<File>>?>(initialValue = null) {
            if (hasAccess) value = withContext(Dispatchers.IO) {
                runCatching {
                    checkNotNull(folder.listFiles()).filter {
                        it.isDirectory || (mode != FileManagerMode.Scan && it.isFile && BookRepository.formatOf(it.name) != null)
                    }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
        .statusBarsPadding().navigationBarsPadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (mode) {
                        FileManagerMode.Browse -> "Device files"
                        FileManagerMode.Import -> "Import books"
                        FileManagerMode.Scan -> "Scan folder"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (mode != FileManagerMode.Browse) TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
        if (!hasAccess) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Allow file access to browse books on this device.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    } else permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }) { Text("Allow file access") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { path = folder.parentFile?.absolutePath ?: primary.absolutePath },
                    enabled = roots.none { it.absolutePath == path }) { Text("Up") }
                Text(folder.absolutePath, Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (roots.size > 1) Row(Modifier.padding(horizontal = 16.dp)) {
                roots.forEachIndexed { index, root ->
                    TextButton(onClick = { path = root.absolutePath }) {
                        Text(if (index == 0) "Internal storage" else "SD card $index")
                    }
                }
            }
            HorizontalDivider()
            val currentEntries = listing?.getOrNull()
            if (currentEntries.isNullOrEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (listing == null) CircularProgressIndicator()
                else Text(
                    when {
                        currentEntries == null -> "Folder contents unavailable."
                        mode == FileManagerMode.Scan -> "No subfolders here."
                        else -> "No folders or supported books here."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(currentEntries, key = { it.absolutePath }) { entry ->
                    val importingBook = mode == FileManagerMode.Import && !entry.isDirectory
                    val selected = entry.absolutePath in selectedPaths
                    ListItem(
                        leadingContent = {
                            val format = BookRepository.formatOf(entry.name)
                            val icon = when {
                                entry.isDirectory -> Icons.Rounded.Folder
                                format == "PDF" -> Icons.Rounded.PictureAsPdf
                                format == "EPUB" -> Icons.Rounded.MenuBook
                                format == "TXT" -> Icons.Rounded.Description
                                else -> Icons.Rounded.InsertDriveFile
                            }
                            val tint = when {
                                entry.isDirectory -> MaterialTheme.colorScheme.primary
                                format == "PDF" -> MaterialTheme.colorScheme.error
                                format == "EPUB" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.secondary
                            }
                            Icon(icon, contentDescription = null, tint = tint)
                        },
                        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (entry.isDirectory) "Folder" else "${BookRepository.formatOf(entry.name)} file") },
                        trailingContent = if (importingBook) {
                            { Checkbox(checked = selected, onCheckedChange = null) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = if (importingBook) {
                            Modifier.toggleable(value = selected, role = Role.Checkbox) { checked ->
                                val entryPath = entry.absolutePath
                                selectedPaths = ArrayList(if (checked) selectedPaths + entryPath else selectedPaths - entryPath)
                            }
                        } else Modifier.clickable {
                            if (entry.isDirectory) path = entry.absolutePath
                            else onOpenFile(ExternalBookFile(Uri.fromFile(entry), entry.name))
                        }
                    )
                    HorizontalDivider()
                }
            }
            if (mode != FileManagerMode.Browse) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    if (mode == FileManagerMode.Import) {
                        Button(enabled = selectedPaths.isNotEmpty(), onClick = {
                            onImportFiles(selectedPaths.map { Uri.fromFile(File(it)) })
                        }) { Text("Import selected (${selectedPaths.size})") }
                    } else {
                        Button(enabled = currentEntries != null, onClick = { onScanFolder(folder) }) {
                            Text("Scan this folder")
                        }
                    }
                }
            }
        }
    }
}
