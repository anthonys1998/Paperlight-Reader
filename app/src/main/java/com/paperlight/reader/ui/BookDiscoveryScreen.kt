package com.paperlight.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class CatalogBook(val key: String, val title: String, val byline: String)

private val externalCatalogs = listOf("Anna’s Archive", "Library Genesis", "Z-Library")

@Composable
internal fun BookDiscoveryScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings = remember(context) { context.getSharedPreferences("book_discovery", 0) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var books by remember { mutableStateOf<List<CatalogBook>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceTemplate by rememberSaveable { mutableStateOf("") }
    var templateError by rememberSaveable { mutableStateOf(false) }

    fun openInBrowser(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            error = "No browser is available."
        }
    }

    fun editSource(name: String) {
        sourceTemplate = settings.getString(name, "").orEmpty()
        templateError = false
        editingSource = name
    }

    fun search() {
        val term = query.trim()
        if (term.isEmpty() || loading) return
        focusManager.clearFocus()
        loading = true
        error = null
        books = null
        scope.launch {
            try {
                books = searchOpenLibrary(term)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not reach Open Library. Try again in a moment."
            } finally {
                loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Find books", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(240) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title, author, or ISBN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Button(onClick = { search() }, enabled = query.isNotBlank() && !loading,
                    modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Search Open Library")
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Text("Search other catalogs in your browser", style = MaterialTheme.typography.titleSmall)
                    }
                    items(externalCatalogs) { name ->
                        ListItem(
                            headlineContent = { Text(name) },
                            trailingContent = { TextButton(onClick = { editSource(name) }) { Text("Set URL") } },
                            modifier = Modifier.clickable {
                                val template = settings.getString(name, "").orEmpty()
                                val uri = externalSearchUri(template, query.trim())
                                when {
                                    uri == null -> editSource(name)
                                    query.isBlank() -> error = "Enter a title, author, or ISBN first."
                                    else -> { error = null; openInBrowser(uri) }
                                }
                            }
                        )
                    }
                    books?.let { results ->
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("Open Library results", style = MaterialTheme.typography.titleSmall)
                            if (results.isEmpty()) Text("No matching books.")
                        }
                        items(results, key = { it.key }) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = { if (book.byline.isNotEmpty()) Text(book.byline) },
                                modifier = Modifier.clickable {
                                    openInBrowser(Uri.parse("https://openlibrary.org/works/${book.key}"))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editingSource?.let { name ->
        AlertDialog(
            onDismissRequest = { editingSource = null },
            title = { Text("$name search URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use the site's search URL and replace the search term with {query}.")
                    OutlinedTextField(
                        value = sourceTemplate,
                        onValueChange = { sourceTemplate = it.take(1500); templateError = false },
                        label = { Text("HTTPS search URL") },
                        placeholder = { Text("https://example.org/search?q={query}") },
                        isError = templateError,
                        supportingText = {
                            if (templateError) Text("Enter an HTTPS URL with {query} in the path or query string.")
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val template = sourceTemplate.trim()
                    if (externalSearchUri(template, "book") == null) templateError = true
                    else {
                        settings.edit().putString(name, template).apply()
                        editingSource = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSource = null }) { Text("Cancel") } },
        )
    }
}

private fun externalSearchUri(template: String, query: String): Uri? {
    val marker = "__book_search_term__"
    if (!template.contains("{query}") || template.contains(marker)) return null
    val parsed = Uri.parse(template.replace("{query}", marker))
    if (parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null ||
        parsed.host.orEmpty().contains(marker) ||
        (parsed.encodedPath.orEmpty() + parsed.encodedQuery.orEmpty()).contains(marker).not()
    ) return null
    return Uri.parse(template.replace("{query}", Uri.encode(query)))
}

private suspend fun searchOpenLibrary(query: String): List<CatalogBook> = withContext(Dispatchers.IO) {
    val uri = Uri.parse("https://openlibrary.org/search.json").buildUpon()
        .appendQueryParameter("q", query)
        .appendQueryParameter("fields", "key,title,author_name,first_publish_year")
        .appendQueryParameter("limit", "25")
        .build()
    val connection = URL(uri.toString()).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", "VibeReader/1.0")
        check(connection.responseCode == HttpURLConnection.HTTP_OK)
        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val docs = JSONObject(response).getJSONArray("docs")
        buildList {
            for (index in 0 until docs.length()) {
                val doc = docs.optJSONObject(index) ?: continue
                val key = doc.optString("key").substringAfterLast('/')
                if (!key.matches(Regex("OL[0-9]+W"))) continue
                val title = doc.optString("title").takeIf { it.isNotBlank() } ?: continue
                val authors = doc.optJSONArray("author_name")
                val byline = buildList {
                    for (authorIndex in 0 until minOf(authors?.length() ?: 0, 3)) {
                        authors?.optString(authorIndex)?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    doc.optInt("first_publish_year").takeIf { it > 0 }?.let { add(it.toString()) }
                }.joinToString(" · ")
                add(CatalogBook(key, title, byline))
            }
        }.distinctBy { it.key }
    } finally {
        connection.disconnect()
    }
}
