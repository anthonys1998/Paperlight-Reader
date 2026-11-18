package com.paperlight.reader.ui

import android.net.Uri
import android.util.Xml
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

private data class CatalogBook(val id: String, val title: String, val author: String, val epubUrl: String)

@Composable
internal fun BookDiscoveryScreen(
    onDismiss: () -> Unit,
    onImportEpub: (File, String, String) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var books by remember { mutableStateOf<List<CatalogBook>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        val term = query.trim()
        if (term.isEmpty() || loading || downloading != null) return
        focusManager.clearFocus()
        loading = true
        error = null
        books = null
        scope.launch {
            try {
                books = searchProjectGutenberg(term)
                if (books.isNullOrEmpty()) error = "No matching public-domain books were found."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not reach Project Gutenberg. Check your connection and try again."
            } finally {
                loading = false
            }
        }
    }

    fun download(book: CatalogBook) {
        if (downloading != null) return
        downloading = book.id
        error = null
        scope.launch {
            var temporary: File? = null
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    val safeName = book.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    File(context.cacheDir, "gutenberg-$safeName-${UUID.randomUUID()}.epub").also { file ->
                        try {
                            downloadEpub(book.epubUrl, file)
                        } catch (failure: Exception) {
                            file.delete()
                            throw failure
                        }
                    }
                }
                temporary = downloaded
                onImportEpub(downloaded, book.title, book.author)
            } catch (cancelled: CancellationException) {
                temporary?.delete()
                throw cancelled
            } catch (_: Exception) {
                temporary?.delete()
                error = "Could not download this EPUB. Please try again later."
            } finally {
                downloading = null
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Find books", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("Search and import free public-domain EPUBs from Project Gutenberg.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(240) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("Title or author") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Button(onClick = { search() }, enabled = query.isNotBlank() && !loading && downloading == null,
                    modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Search")
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (downloading != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    books?.let { results ->
                        items(results, key = { it.id }) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = { if (book.author.isNotBlank()) Text(book.author) },
                                trailingContent = {
                                    TextButton(
                                        enabled = downloading == null,
                                        onClick = { download(book) },
                                    ) { Text(if (downloading == book.id) "Downloading…" else "Add") }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private suspend fun searchProjectGutenberg(query: String): List<CatalogBook> = withContext(Dispatchers.IO) {
    val uri = Uri.parse("https://www.gutenberg.org/ebooks/search.opds/").buildUpon()
        .appendQueryParameter("query", query)
        .build()
    val connection = URL(uri.toString()).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "VibeReader/1.1 (Android EPUB reader)")
        check(connection.responseCode == HttpURLConnection.HTTP_OK)
        val parser = Xml.newPullParser()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            parser.setInput(reader)
            val results = mutableListOf<CatalogBook>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "entry") {
                    parseGutenbergEntry(parser)?.let(results::add)
                }
                event = parser.next()
            }
            results.distinctBy { it.id }.take(40)
        }
    } finally {
        connection.disconnect()
    }
}

private fun parseGutenbergEntry(parser: XmlPullParser): CatalogBook? {
    val depth = parser.depth
    var title = ""
    var author = ""
    var epubUrl: String? = null
    var id = ""
    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "entry")) {
        if (event == XmlPullParser.START_TAG) {
            when (parser.name) {
                "title" -> if (title.isBlank()) title = parser.nextText().trim()
                "name" -> if (author.isBlank()) author = parser.nextText().trim()
                "id" -> if (id.isBlank()) id = parser.nextText().trim()
                "link" -> {
                    val href = parser.getAttributeValue(null, "href")
                    val type = parser.getAttributeValue(null, "type").orEmpty()
                    val rel = parser.getAttributeValue(null, "rel").orEmpty()
                    if (epubUrl == null && type.contains("epub", ignoreCase = true) &&
                        (rel.contains("acquisition", ignoreCase = true) || rel.contains("http://opds-spec.org/acquisition"))) {
                        epubUrl = href
                    }
                }
            }
        }
        event = parser.next()
    }
    val safeUrl = epubUrl?.let(::validatedGutenbergUrl) ?: return null
    val bookId = id.substringAfterLast(':').ifBlank { safeUrl.substringAfterLast('/').substringBefore('.') }
    if (title.isBlank() || bookId.isBlank()) return null
    return CatalogBook(bookId, title, author, safeUrl)
}

private fun validatedGutenbergUrl(value: String): String? {
    val uri = Uri.parse(value)
    return value.takeIf { uri.scheme == "https" && uri.host == "www.gutenberg.org" && uri.userInfo == null }
}

private fun downloadEpub(url: String, destination: File) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "VibeReader/1.1 (Android EPUB reader)")
        check(connection.responseCode == HttpURLConnection.HTTP_OK)
        val declaredSize = connection.contentLengthLong
        require(declaredSize <= 80L * 1024 * 1024) { "EPUB is too large" }
        connection.inputStream.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 80L * 1024 * 1024) { "EPUB is too large" }
                    output.write(buffer, 0, count)
                }
                require(total > 0) { "Empty EPUB" }
            }
        }
    } finally {
        connection.disconnect()
    }
}
