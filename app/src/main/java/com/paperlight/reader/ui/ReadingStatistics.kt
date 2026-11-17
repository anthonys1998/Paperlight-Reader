package com.paperlight.reader.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.ReadingStatisticsStore
import kotlinx.coroutines.delay

@Composable
internal fun TrackReadingTime(bookId: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val statistics = remember(context) { ReadingStatisticsStore(context) }
    val session = remember(bookId) { ReadingSession(bookId, statistics) }
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> session.start()
                Lifecycle.Event.ON_PAUSE -> session.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.start()
        onDispose { lifecycle.removeObserver(observer); session.pause() }
    }
    LaunchedEffect(session) {
        while (true) { delay(30_000); session.flush() }
    }
}

private class ReadingSession(private val bookId: String, private val store: ReadingStatisticsStore) {
    private var began: Long? = null
    fun start() { if (began == null) began = SystemClock.elapsedRealtime() }
    fun flush() {
        val start = began ?: return
        val now = SystemClock.elapsedRealtime()
        store.add(bookId, now - start)
        began = now
    }
    fun pause() { flush(); began = null }
}

@Composable
internal fun ReadingStatisticsDialog(books: List<BookEntity>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ReadingStatisticsStore(context) }
    val readingTimes = remember { books.map { it to store.forBook(it.id) }.filter { it.second > 0L }.sortedByDescending { it.second } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reading statistics") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                item { Text("Today  ${readingDuration(store.today())}", style = MaterialTheme.typography.titleMedium) }
                item { Text("All time  ${readingDuration(store.total())}", style = MaterialTheme.typography.titleMedium) }
                item { Text("${books.count { it.progress >= .999f }} finished · ${books.count { it.progress > 0f && it.progress < .999f }} in progress") }
                items(readingTimes, key = { it.first.id }) { (book, time) ->
                    ListItem(headlineContent = { Text(book.title) }, supportingContent = { Text(readingDuration(time)) })
                }
                if (readingTimes.isEmpty()) item { Text("Reading time is recorded while a book is open and the app is active.") }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

private fun readingDuration(milliseconds: Long): String {
    val minutes = milliseconds / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
