package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class BookRepository(private val context: Context, private val dao: BookDao) {
    companion object {
        fun formatOf(name: String): String? = when (val extension = name.substringAfterLast('.', "").uppercase(java.util.Locale.ROOT)) {
            "EPUB", "PDF", "MOBI", "AZW3", "TXT", "CBZ", "CBR" -> extension
            "AZW" -> "AZW3"
            else -> null
        }
    }

    val books: Flow<List<BookEntity>> = dao.observeBooks()

    suspend fun import(uri: Uri): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: "Imported book"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val format = formatOf(displayName) ?: error("Unsupported file type: .$extension")
            val id = UUID.randomUUID().toString()
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            val destination = File(booksDir, "$id.$extension")
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read selected file")
            val book = BookEntity(
                id = id,
                title = displayName.substringBeforeLast('.', displayName),
                fileName = displayName,
                localPath = destination.absolutePath,
                format = format,
                importedAt = System.currentTimeMillis()
            )
            dao.upsert(book)
            book
        }
    }

    suspend fun updateProgress(bookId: String, progress: Float) = withContext(Dispatchers.IO) {
        dao.updateProgress(bookId, progress.coerceIn(0f, 1f))
    }

    suspend fun remove(book: BookEntity) = withContext(Dispatchers.IO) {
        File(book.localPath).delete()
        File(context.filesDir, "epub/${book.id}").deleteRecursively()
        File(context.filesDir, "epub/${book.id}.prepared-epub").delete()
        dao.deleteById(book.id)
        BookmarkStore(context).write(book.id, emptyList())
        ReaderAnnotationStore(context).write(book.id, emptyList())
    }
}
