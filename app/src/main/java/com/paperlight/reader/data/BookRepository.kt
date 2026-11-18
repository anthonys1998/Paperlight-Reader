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

    suspend fun saveLibrary(id: String?, name: String): Result<LibraryEntity> = withContext(Dispatchers.IO) {
        try {
            val cleanName = name.trim().take(60)
            require(cleanName.isNotBlank()) { "Enter a name for this library." }
            require(dao.libraryNameCount(cleanName, id.orEmpty()) == 0) { "A library with this name already exists." }
            val library = if (id == null) {
                LibraryEntity(UUID.randomUUID().toString(), cleanName).also { dao.insertLibrary(it) }
            } else {
                val existing = requireNotNull(dao.findLibrary(id)) { "This library no longer exists." }
                dao.renameLibrary(id, cleanName)
                existing.copy(name = cleanName)
            }
            Result.success(library)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun deleteLibrary(id: String) = withContext(Dispatchers.IO) {
        ensureDefaultLibrary()
        dao.deleteLibrary(id)
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
                try {
                    file.copyTo(destination, overwrite = false)
                    BookEntity(
                        id = id,
                        title = title.ifBlank { file.nameWithoutExtension },
                        author = author,
                        fileName = "${title.ifBlank { file.nameWithoutExtension }}.epub",
                        localPath = destination.absolutePath,
                        format = "EPUB",
                        importedAt = System.currentTimeMillis(),
                        libraryId = libraryId
                    ).also {
                        dao.insertBook(it)
                        file.delete()
                    }
                } catch (failure: Exception) {
                    destination.delete()
                    throw failure
                }
            }
        }

    suspend fun convertPdfToEpub(
        source: File,
        fallbackTitle: String,
        mode: PdfEpubMode,
        libraryId: String,
        onProgress: (Int, Int) -> Unit,
    ): Result<BookEntity> {
        val destination = File(context.cacheDir, "converted-${UUID.randomUUID()}.epub")
        return try {
            val (title, author) = PdfEpubConverter(context).convert(source, destination, fallbackTitle, mode, onProgress)
            importGeneratedEpub(destination, title, author, libraryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            destination.delete()
        }
    }

    suspend fun createEpubFromUrl(
        url: String,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        libraryId: String = "default",
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result<BookEntity> {
        val tempFile = File(context.cacheDir, "webnovel-${UUID.randomUUID()}.epub")
        return try {
            val generated = WebNovelEpubUtility().createEpub(url, tempFile, options, onProgress)
            importGeneratedEpub(generated.file, generated.title, libraryId = libraryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun import(uri: Uri, libraryId: String = "default"): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDefaultLibrary()
            require(dao.findLibrary(libraryId) != null) { "This library no longer exists." }
            val resolver = context.contentResolver
            val localFile = if (uri.scheme == "file") File(requireNotNull(uri.path)) else null
            val displayName = localFile?.name ?: resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: "Imported book"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val format = formatOf(displayName) ?: error("Unsupported file type: .$extension")
            val id = UUID.randomUUID().toString()
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            val destination = File(booksDir, "$id.$extension")
            try {
                (localFile?.inputStream() ?: resolver.openInputStream(uri))?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to read selected file")
                val metadata = if (format == "EPUB") BookArtwork.metadata(destination) else null
                val book = BookEntity(
                    id = id,
                    title = metadata?.first?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.', displayName),
                    author = metadata?.second.orEmpty(),
                    fileName = displayName,
                    localPath = destination.absolutePath,
                    format = format,
                    importedAt = System.currentTimeMillis(),
                    libraryId = libraryId
                )
                dao.insertBook(book)
                book
            } catch (failure: Exception) {
                destination.delete()
                throw failure
            }
        }
    }

    suspend fun exportBackup(uri: Uri) = LibraryBackup(context, dao).export(uri)
    suspend fun restoreBackup(uri: Uri): Int = LibraryBackup(context, dao).restore(uri)

    suspend fun refreshMetadata() = withContext(Dispatchers.IO) {
        dao.allBooks().filter { it.format == "EPUB" && it.author.isBlank() }.forEach { book ->
            BookArtwork.metadata(File(book.localPath))?.let { (title, author) ->
                if (title.isNotBlank() || author.isNotBlank()) dao.updateMetadata(book.id, title.ifBlank { book.title }, author)
            }
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
        val selected = ids.distinct().chunked(500).flatMap { dao.booksByIds(it) }
        removeFilesAndState(selected)
        selected.map { it.id }.chunked(500).forEach { dao.deleteBooks(it) }
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
