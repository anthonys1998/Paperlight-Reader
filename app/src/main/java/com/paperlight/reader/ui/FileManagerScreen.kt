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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** A file explorer that stays inside the second page of the library pager. */
@Composable
internal fun FileManagerScreen(onOpenFile: (ExternalBookFile) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val primary = remember { Environment.getExternalStorageDirectory() }
    var path by rememberSaveable { mutableStateOf(primary.absolutePath) }
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
    val entries by produceState<List<File>>(initialValue = emptyList(), path, hasAccess, accessVersion) {
        value = if (!hasAccess) emptyList() else withContext(Dispatchers.IO) {
            folder.listFiles()?.filter { it.isDirectory || (it.isFile && BookRepository.formatOf(it.name) != null) }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Device files", Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
            style = MaterialTheme.typography.headlineSmall)
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
            if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No folders or supported books here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.absolutePath }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (entry.isDirectory) "Folder" else BookRepository.formatOf(entry.name).orEmpty()) },
                        modifier = Modifier.clickable {
                            if (entry.isDirectory) path = entry.absolutePath
                            else onOpenFile(ExternalBookFile(Uri.fromFile(entry), entry.name))
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
