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
import kotlinx.coroutines.sync.Mutex

private object BookRepositoryOperationLock {
    val mutex = Mutex()
}

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

    private suspend fun <T> withLibraryOperation(allowPendingRestore: Boolean = false, operation: suspend () -> T): T {
        BookRepositoryOperationLock.mutex.lock()
        try {
            if (!allowPendingRestore) {
                check(!LibraryBackup(context, dao).hasPendingRestore()) {
                    "A backup restore is pending recovery. Recover it before using the library."
                }
            }
            return operation()
        } finally {
            BookRepositoryOperationLock.mutex.unlock()
        }
    }

    private suspend fun ensureDefaultLibraryUnlocked() {
        if (dao.findLibrary("default") == null) {
            dao.insertLibrary(LibraryEntity("default", "My Library", 0L))
        }
    }

    suspend fun ensureDefaultLibrary() = withContext(Dispatchers.IO) {
        withLibraryOperation { ensureDefaultLibraryUnlocked() }
    }

    suspend fun saveLibrary(id: String?, name: String): Result<LibraryEntity> = withContext(Dispatchers.IO) {
        try {
            withLibraryOperation {
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
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun deleteLibrary(id: String) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            ensureDefaultLibraryUnlocked()
            dao.deleteLibrary(id)
        }
    }

    suspend fun moveBooks(ids: List<String>, libraryId: String) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            require(ids.isNotEmpty()) { "Select at least one book." }
            require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
            dao.moveBooks(ids.distinct(), libraryId)
        }
    }

    suspend fun importGeneratedEpub(
        file: File,
        title: String,
        author: String = "",
        libraryId: String = "default",
        stableId: String? = null,
    ): Result<BookEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                withLibraryOperation {
                    require(file.isFile && file.length() > 0L) { "EPUB generation produced an empty file." }
                    ensureDefaultLibraryUnlocked()
                    require(dao.findLibrary(libraryId) != null) { "That library no longer exists." }
                    val id = stableId ?: UUID.randomUUID().toString()
                    require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid generated-book identifier." }
                    dao.bookById(id)?.let { existing ->
                        file.delete()
                        return@withLibraryOperation existing
                    }
                    val destination = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.epub")
                    val incoming = File(destination.parentFile, ".$id.importing")
                    try {
                        file.copyTo(incoming, overwrite = true)
                        runCatching {
                            java.nio.file.Files.move(incoming.toPath(), destination.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        }.getOrElse {
                            java.nio.file.Files.move(incoming.toPath(), destination.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
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
                        incoming.delete()
                        destination.delete()
                        throw failure
                    }
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
            val generated = WebNovelEpubUtility(context).createEpub(url, tempFile, options, onProgress)
            val stableId = "webnovel-${UUID.nameUUIDFromBytes((url + "\n" + options.toString()).toByteArray(Charsets.UTF_8))}"
            importGeneratedEpub(generated.file, generated.title, libraryId = libraryId, stableId = stableId)
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
            withLibraryOperation {
                ensureDefaultLibraryUnlocked()
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
    }

    suspend fun exportBackup(
        uri: Uri,
        includeApiKey: Boolean = false,
        password: String? = null,
        onProgress: (BackupProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        withLibraryOperation { LibraryBackup(context, dao).export(uri, includeApiKey, password, onProgress) }
    }

    suspend fun recoverInterruptedBackupRestore() = withContext(Dispatchers.IO) {
        withLibraryOperation(allowPendingRestore = true) { LibraryBackup(context, dao).recoverInterruptedRestore() }
    }

    fun backupRestorePending(): Boolean = LibraryBackup(context, dao).hasPendingRestore()
    suspend fun restoreBackup(
        uri: Uri,
        password: String? = null,
        replaceExistingSettings: Boolean = false,
        replaceExistingGroqKey: Boolean = false,
        onProgress: (BackupProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        withLibraryOperation {
            LibraryBackup(context, dao).restore(uri, password, replaceExistingSettings, replaceExistingGroqKey, onProgress)
        }
    }

    suspend fun refreshMetadata() = withContext(Dispatchers.IO) {
        withLibraryOperation {
            dao.allBooks().filter { it.format == "EPUB" && it.author.isBlank() }.forEach { book ->
                BookArtwork.metadata(File(book.localPath))?.let { (title, author) ->
                    if (title.isNotBlank() || author.isNotBlank()) dao.updateMetadata(book.id, title.ifBlank { book.title }, author)
                }
            }
        }
    }

    suspend fun updateProgress(bookId: String, progress: Float) = withContext(Dispatchers.IO) {
        withLibraryOperation { dao.updateProgress(bookId, progress.coerceIn(0f, 1f)) }
    }

    suspend fun remove(book: BookEntity) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            removeFilesAndState(listOf(book))
            dao.deleteById(book.id)
        }
    }

    suspend fun removeBooks(ids: List<String>) = withContext(Dispatchers.IO) {
        withLibraryOperation {
            if (ids.isEmpty()) return@withLibraryOperation
            val selected = ids.distinct().chunked(500).flatMap { dao.booksByIds(it) }
            removeFilesAndState(selected)
            selected.map { it.id }.chunked(500).forEach { dao.deleteBooks(it) }
        }
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
