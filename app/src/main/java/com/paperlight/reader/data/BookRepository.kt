package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.CancellationException

class BookRepository(private val context: Context, private val dao: BookDao) {
    companion object {
        fun formatOf(name: String): String? = when (val extension = name.substringAfterLast('.', "").uppercase(java.util.Locale.ROOT)) {
            "EPUB", "PDF", "MOBI", "AZW3", "TXT", "CBZ", "CBR" -> extension
            "AZW" -> "AZW3"
            else -> null
        }
    }

    val books: Flow<List<BookEntity>> = dao.observeBooks()
    val libraries: Flow<List<LibraryEntity>> = dao.observeLibraries()

    suspend fun ensureDefaultLibrary() = withContext(Dispatchers.IO) {
        if (dao.findLibrary("default") == null) {
            dao.insertLibrary(LibraryEntity("default", "My Library", 0L))
        }
    }

    suspend fun createLibrary(name: String): Result<LibraryEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = name.trim().take(60)
            require(cleanName.isNotBlank()) { "Enter a name for this library." }
            val library = LibraryEntity(UUID.randomUUID().toString(), cleanName)
            dao.insertLibrary(library)
            library
        }
    }

    suspend fun moveBooks(ids: List<String>, libraryId: String) = withContext(Dispatchers.IO) {
        require(ids.isNotEmpty()) { "Select at least one book." }
        require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
        dao.moveBooks(ids.distinct(), libraryId)
    }

    suspend fun importGeneratedEpub(
        file: File,
        title: String,
        author: String = "",
        libraryId: String = "default"
    ): Result<BookEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(file.isFile && file.length() > 0L) { "EPUB generation produced an empty file." }
                ensureDefaultLibrary()
                require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
                val id = UUID.randomUUID().toString()
                val destination = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.epub")
                file.copyTo(destination, overwrite = false)
                file.delete()
                BookEntity(
                    id = id,
                    title = title.ifBlank { file.nameWithoutExtension },
                    author = author,
                    fileName = "${title.ifBlank { file.nameWithoutExtension }}.epub",
                    localPath = destination.absolutePath,
                    format = "EPUB",
                    importedAt = System.currentTimeMillis(),
                    libraryId = libraryId
                ).also { dao.upsert(it) }
            }
        }

    suspend fun createEpubFromUrl(
        url: String,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        libraryId: String = "default"
    ): Result<BookEntity> {
        val tempFile = File(context.cacheDir, "webnovel-${UUID.randomUUID()}.epub")
        return try {
            val generated = WebNovelEpubUtility().createEpub(url, tempFile, options)
            importGeneratedEpub(generated.file, generated.title, libraryId = libraryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            tempFile.delete()
        }
    }

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
                importedAt = System.currentTimeMillis(),
                libraryId = "default"
            )
            dao.upsert(book)
            book
        }
    }

    suspend fun updateProgress(bookId: String, progress: Float) = withContext(Dispatchers.IO) {
        dao.updateProgress(bookId, progress.coerceIn(0f, 1f))
    }

    suspend fun remove(book: BookEntity) = withContext(Dispatchers.IO) {
        removeFilesAndState(listOf(book))
        dao.deleteById(book.id)
    }

    suspend fun removeBooks(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val selected = dao.booksByIds(ids.distinct())
        removeFilesAndState(selected)
        dao.deleteBooks(selected.map { it.id })
    }

    private fun removeFilesAndState(selected: List<BookEntity>) {
        selected.forEach { book ->
            File(book.localPath).delete()
            File(context.filesDir, "epub/${book.id}").deleteRecursively()
            File(context.filesDir, "epub/${book.id}.prepared-epub").delete()
            BookmarkStore(context).write(book.id, emptyList())
            ReaderAnnotationStore(context).write(book.id, emptyList())
        }
    }
}
