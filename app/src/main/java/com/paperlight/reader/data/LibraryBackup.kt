package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.paperlight.reader.ai.GroqApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

internal class BackupRestoreRecoveryException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private data class ValidatedRestoreBook(
    val id: String,
    val format: String,
    val fileName: String,
    val title: String,
    val author: String,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val progress: Float,
    val libraryId: String,
    val contentEntry: ZipEntry,
    val sha256: String,
    val annotations: List<ReaderAnnotation>,
    val bookmarks: List<ReaderBookmark>,
)

/** Portable, additive backups of original books, libraries, progress and annotations. */
internal class LibraryBackup(private val context: Context, private val dao: BookDao) {
    suspend fun export(destination: Uri, includeApiKey: Boolean = false, password: String? = null) = withContext(Dispatchers.IO) {
        require(!includeApiKey || !password.isNullOrBlank()) { "A password is required when including the Groq API key." }
        val apiKey = if (includeApiKey) requireNotNull(GroqApiKeyStore(context).getKey()) {
            "No Groq API key is saved on this device."
        } else null
        val snapshot = dao.backupSnapshot()
        val annotations = ReaderAnnotationStore(context)
        val bookmarks = BookmarkStore(context)
        val manifest = JSONObject().put("format", "paperlight-backup").put("version", 2)
            .put("preferences", exportPreferences())
        apiKey?.let { manifest.put("credentials", JSONObject().put("groqApiKey", it)) }
        manifest.put("libraries", JSONArray().apply {
            snapshot.second.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt)) }
        })
        manifest.put("books", JSONArray().apply {
            snapshot.first.forEach { book ->
                put(JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
                    .put("fileName", book.fileName).put("format", book.format).put("libraryId", book.libraryId)
                    .put("importedAt", book.importedAt).put("lastOpenedAt", book.lastOpenedAt ?: JSONObject.NULL)
                    .put("progress", book.progress.toDouble())
                    .put("sha256", sha256(File(book.localPath)))
                    .put("annotations", JSONArray().apply {
                        annotations.read(book.id).forEach { note ->
                            put(JSONObject().put("id", note.id).put("chapter", note.chapter).put("start", note.start)
                                .put("end", note.end).put("quote", note.quote).put("note", note.note).put("color", note.color))
                        }
                    }).put("bookmarks", JSONArray().apply {
                        bookmarks.read(book.id).forEach { put(JSONObject().put("chapter", it.chapter).put("fraction", it.fraction.toDouble())) }
                    }))
            }
        })
        require(manifest.toString().toByteArray(Charsets.UTF_8).size <= MaxManifestBytes) { "The backup metadata is too large." }
        val temporary = File(context.cacheDir, "backup-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                snapshot.first.forEach { book ->
                    coroutineContext.ensureActive()
                    val source = File(book.localPath)
                    require(source.isFile) { "Missing book file: ${book.title}" }
                    zip.putNextEntry(ZipEntry("books/${book.id}"))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val customFont = File(context.filesDir, "reading-font")
                if (customFont.isFile) {
                    zip.putNextEntry(ZipEntry("settings/reading-font"))
                    customFont.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                temporary.inputStream().use { input ->
                    if (apiKey != null) BackupEncryption.encrypt(input, output, requireNotNull(password).toCharArray())
                    else input.copyTo(output)
                }
            } ?: error("Could not write the backup file.")
        } finally { temporary.delete() }
    }

    suspend fun restore(
        source: Uri,
        password: String? = null,
        replaceExistingSettings: Boolean = false,
        replaceExistingGroqKey: Boolean = false,
    ): Int = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, "restore-${UUID.randomUUID()}.zip")
        val transactionId = UUID.randomUUID().toString()
        val transactionDir = File(File(context.filesDir, RestoreDirectory), transactionId)
        val stagedBooks = File(transactionDir, "books")
        var committed = false
        var journalWritten = false
        var encryptedBackup = false
        try {
            check(!hasPendingRestore()) { "An earlier backup restore must be recovered before starting another." }
            context.contentResolver.openInputStream(source)?.use { input ->
                archive.outputStream().use { output ->
                    val bounded = object : java.io.FilterOutputStream(output) {
                        var total = 0L
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            total += length
                            require(total <= MaxBackupBytes) { "This backup is too large." }
                            out.write(bytes, offset, length)
                        }
                        override fun write(value: Int) {
                            total++
                            require(total <= MaxBackupBytes) { "This backup is too large." }
                            out.write(value)
                        }
                    }
                    encryptedBackup = BackupEncryption.decryptOrLegacy(input, bounded, password?.toCharArray())
                    bounded.flush()
                }
            } ?: error("Could not open this backup.")

            ZipFile(archive).use { zip ->
                require(zip.size() <= MaxBackupEntries) { "This backup contains too many files." }
                val seenEntries = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entryName = entries.nextElement().name
                    require(seenEntries.add(entryName)) { "This backup contains duplicate archive entries." }
                    require(!entryName.startsWith('/') && '\\' !in entryName && '\u0000' !in entryName &&
                        entryName.split('/').none { it == ".." }) { "This backup contains an unsafe archive path." }
                }
                val manifestEntry = requireNotNull(zip.getEntry("manifest.json")) { "This is not a Vibe Reader backup." }
                require(manifestEntry.size in 1..MaxManifestBytes) { "The backup manifest is too large." }
                val manifest = JSONObject(zip.getInputStream(manifestEntry).use { readUtf8Bounded(it, MaxManifestBytes) })
                val version = manifest.optInt("version")
                require(manifest.optString("format") == "paperlight-backup" && version in 1..2) { "Unsupported backup format." }

                val credentials = manifest.optJSONObject("credentials")
                require(credentials == null || encryptedBackup) { "Backups containing credentials must be password encrypted." }
                val apiKey = credentials?.optString("groqApiKey")?.takeIf(String::isNotBlank)
                if (credentials != null) require(apiKey != null && apiKey.length in 20..500) { "The Groq key in this backup is invalid." }

                val libraryArray = manifest.getJSONArray("libraries")
                require(libraryArray.length() <= MaxLibraries) { "Too many libraries in this backup." }
                val libraries = List(libraryArray.length()) { index ->
                    val item = libraryArray.getJSONObject(index)
                    val id = item.getString("id")
                    val name = item.getString("name").trim().take(60)
                    require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.isNotBlank()) { "Invalid library metadata in backup." }
                    LibraryEntity(id, name, item.getLong("createdAt").coerceAtLeast(0L))
                }
                require(libraries.map { it.id }.distinct().size == libraries.size) { "Duplicate library identifiers in backup." }

                val bookArray = manifest.getJSONArray("books")
                require(bookArray.length() <= MaxBooks) { "Too many books in this backup." }
                val existing = dao.allBooks().map { it.id }.toSet()
                val seen = mutableSetOf<String>()
                val validatedBooks = ArrayList<ValidatedRestoreBook>(bookArray.length())
                var estimatedBytes = 0L
                var expandedBytes = 0L
                for (index in 0 until bookArray.length()) {
                    coroutineContext.ensureActive()
                    val item = bookArray.getJSONObject(index)
                    val id = item.getString("id")
                    require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && seen.add(id)) { "Invalid or duplicate book identifier." }
                    val format = requireNotNull(BookRepository.formatOf("book.${item.getString("format")}")) {
                        "Unsupported book format in backup."
                    }
                    val content = requireNotNull(zip.getEntry("books/$id")) { "A book file is missing from the backup." }
                    require(content.size > 0L) { "A book file is empty." }
                    if (id !in existing) {
                        val booksDirectory = File(context.filesDir, "books")
                        require(SupportedBookFormats.none { File(booksDirectory, "$id.$it").exists() }) {
                            "A book with this identifier already exists on this device."
                        }
                        estimatedBytes = Math.addExact(estimatedBytes, content.size)
                        require(estimatedBytes <= MaxBackupBytes) { "Expanded backup exceeds the size limit." }
                    }
                    if (item.has("sha256")) require(item.getString("sha256").matches(Regex("[a-fA-F0-9]{64}"))) {
                        "Invalid book integrity metadata."
                    }
                    val expectedSha256 = item.optString("sha256")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var actualBytes = 0L
                    zip.getInputStream(content).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            actualBytes = Math.addExact(actualBytes, count.toLong())
                            expandedBytes = Math.addExact(expandedBytes, count.toLong())
                            require(expandedBytes <= MaxBackupBytes) { "Expanded backup exceeds the size limit." }
                            digest.update(buffer, 0, count)
                        }
                    }
                    require(actualBytes == content.size) { "A book file is incomplete or corrupt." }
                    if (expectedSha256.isNotBlank()) require(expectedSha256.equals(digest.digest().toHex(), ignoreCase = true)) {
                        "A book file failed its integrity check."
                    }
                    val progress = item.getDouble("progress").toFloat()
                    require(progress.isFinite() && progress in 0f..1f) { "Invalid reading progress." }
                    val title = item.getString("title")
                    require(title.isNotBlank() && title.length <= 4_000) { "Invalid book title." }
                    val fileName = item.getString("fileName")
                    val author = item.optString("author")
                    require(fileName.isNotBlank() && fileName.length <= 1_000 && author.length <= 4_000) { "Invalid book metadata." }
                    val rawLibraryId = item.optString("libraryId", "default")
                    require(rawLibraryId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid book library." }
                    val importedAt = item.getLong("importedAt")
                    require(importedAt >= 0L) { "Invalid book import timestamp." }
                    val lastOpenedAt = if (item.isNull("lastOpenedAt")) null else item.getLong("lastOpenedAt")
                    require(lastOpenedAt == null || lastOpenedAt >= 0L) { "Invalid last-opened timestamp." }
                    val annotations = parseAnnotations(item.optJSONArray("annotations") ?: JSONArray())
                    val bookmarks = parseBookmarks(item.optJSONArray("bookmarks") ?: JSONArray())
                    validatedBooks += ValidatedRestoreBook(
                        id, format, fileName, title, author, importedAt, lastOpenedAt, progress,
                        rawLibraryId, content, expectedSha256, annotations, bookmarks
                    )
                }
                val freeBytes = StatFs(context.filesDir.path).availableBytes
                require(estimatedBytes <= (freeBytes - MinRestoreFreeBytes).coerceAtLeast(0L)) {
                    "There is not enough free storage to safely stage this restore."
                }

                validatePreferences(manifest.optJSONObject("preferences"), version)
                val groqStore = GroqApiKeyStore(context)
                val encryptedGroqValue = apiKey?.takeIf { !groqStore.hasStoredValue() || replaceExistingGroqKey }
                    ?.let(groqStore::prepareEncryptedValue)

                check(transactionDir.isDirectory || transactionDir.mkdirs()) { "Could not create restore staging storage." }
                check(stagedBooks.isDirectory || stagedBooks.mkdirs()) { "Could not create restore book staging storage." }
                val stagedFont = if (version >= 2) zip.getEntry("settings/reading-font")?.let { fontEntry ->
                    require(fontEntry.size in 4..MaxFontBytes) { "The backup font is invalid or too large." }
                    val font = File(transactionDir, "reading-font")
                    zip.getInputStream(fontEntry).use { input ->
                        val copied = font.outputStream().use { output -> copyBounded(input, output, MaxFontBytes) }
                        require(copied == fontEntry.size) { "The backup font is incomplete." }
                    }
                    validateFont(font)
                    font
                } else null
                val targetFont = File(context.filesDir, "reading-font")
                val applyFont = stagedFont != null && (replaceExistingSettings || !targetFont.isFile)

                val planned = validatedBooks.filterNot { it.id in existing }
                val restoredBooks = ArrayList<BookEntity>(planned.size)
                val restoredStates = JSONArray()
                var expanded = 0L
                for (item in planned) {
                    coroutineContext.ensureActive()
                    val id = item.id
                    val extension = item.format.lowercase(java.util.Locale.ROOT)
                    val stagedFile = File(stagedBooks, id)
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(item.contentEntry).use { input ->
                        stagedFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MaxBackupBytes) { "Expanded backup exceeds the size limit." }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(stagedFile.length() == item.contentEntry.size && stagedFile.length() > 0L) { "A staged book file is incomplete." }
                    if (item.sha256.isNotBlank()) require(item.sha256.equals(digest.digest().toHex(), ignoreCase = true)) {
                        "A book file failed its integrity check."
                    }
                    val destination = File(File(context.filesDir, "books"), "$id.$extension")
                    require(!destination.exists()) { "A book with this identifier already exists on this device." }
                    val libraryId = item.libraryId
                        .takeIf { bookLibrary -> libraries.any { it.id == bookLibrary } } ?: "default"
                    restoredBooks += BookEntity(id, item.title, item.author, item.fileName,
                        destination.absolutePath, item.format, item.importedAt, item.lastOpenedAt,
                        item.progress, libraryId = libraryId)
                    restoredStates.put(JSONObject()
                        .put("id", id)
                        .put("annotations", annotationsToJson(item.annotations))
                        .put("bookmarks", bookmarksToJson(item.bookmarks)))
                }

                val journal = JSONObject()
                    .put("journalVersion", 2)
                    .put("transactionId", transactionId)
                    .put("committed", false)
                    .put("bookIds", JSONArray(restoredBooks.map { it.id }))
                    .put("settings", manifest.optJSONObject("preferences") ?: JSONObject())
                    .put("replaceSettings", replaceExistingSettings)
                    .put("libraries", librariesToJson(libraries))
                    .put("states", restoredStates)
                    .put("groqKeyEncrypted", encryptedGroqValue ?: JSONObject.NULL)
                    .put("fontIncluded", stagedFont != null)
                    .put("applyFont", applyFont)
                    .put("fontSha256", stagedFont?.let(::sha256).orEmpty())
                writeRestoreJournal(journal)
                journalWritten = true

                File(context.filesDir, "books").apply { check(isDirectory || mkdirs()) }
                for (book in restoredBooks) {
                    coroutineContext.ensureActive()
                    val stagedFile = File(stagedBooks, book.id)
                    val destination = File(book.localPath)
                    require(!destination.exists()) { "A book with this identifier already exists on this device." }
                    moveAtomically(stagedFile, destination)
                }
                dao.restoreSnapshot(libraries, restoredBooks)
                committed = true
                journal.put("committed", true)
                writeRestoreJournal(journal)
                finishRestoreJournal(journal)
                check(!restoreJournalFile().exists() || restoreJournalFile().delete()) { "Could not clear the completed restore journal." }
                transactionDir.deleteRecursively()
                restoredBooks.size
            }
        } catch (failure: Exception) {
            var recoveryRequired = committed
            var persistedIds: Set<String>? = null
            if (!committed && journalWritten) {
                recoveryRequired = try {
                    withContext(NonCancellable) {
                        val journal = JSONObject(restoreJournalFile().inputStream().use { readUtf8Bounded(it, MaxJournalBytes) })
                        val ids = journal.getJSONArray("bookIds")
                        val bookIds = List(ids.length()) { ids.getString(it) }
                        val persisted = dao.allBooks().mapTo(mutableSetOf()) { it.id }
                        persistedIds = persisted
                        bookIds.isEmpty() || bookIds.all { it in persisted }
                    }
                } catch (_: Exception) {
                    // The database outcome is ambiguous. Keep both the journal and published files
                    // so startup recovery can decide without risking a row pointing at a deleted file.
                    true
                }
            }
            if (!recoveryRequired) {
                if (journalWritten) {
                    recoveryRequired = runCatching {
                        val journalFile = restoreJournalFile()
                        val ids = JSONObject(journalFile.inputStream().use { readUtf8Bounded(it, MaxJournalBytes) }).getJSONArray("bookIds")
                        val booksDirectory = File(context.filesDir, "books")
                        repeat(ids.length()) { index ->
                            val id = ids.getString(index)
                            if (id !in persistedIds.orEmpty()) {
                                SupportedBookFormats.forEach { extension ->
                                    val file = File(booksDirectory, "$id.$extension")
                                    check(!file.exists() || file.delete()) { "Could not roll back a staged book file." }
                                }
                            }
                        }
                        check(transactionDir.deleteRecursively() || !transactionDir.exists()) { "Could not remove staged restore data." }
                        check(!journalFile.exists() || journalFile.delete()) { "Could not remove the rollback journal." }
                        false
                    }.getOrElse { cleanupFailure ->
                        failure.addSuppressed(cleanupFailure)
                        true
                    }
                } else {
                    transactionDir.deleteRecursively()
                }
            } else if (failure !is kotlinx.coroutines.CancellationException) {
                failure.addSuppressed(IllegalStateException("Restore data is committed or ambiguous; startup recovery is required."))
            }
            throw failure
        } finally {
            archive.delete()
        }
    }

    suspend fun recoverInterruptedRestore() = withContext(Dispatchers.IO) {
        val journalFile = restoreJournalFile()
        if (!journalFile.isFile) {
            File(context.filesDir, "$RestoreJournal.tmp").delete()
            File(context.filesDir, RestoreDirectory).listFiles()?.forEach { it.deleteRecursively() }
            context.cacheDir.listFiles()?.filter { it.name.startsWith("restore-") }?.forEach { it.deleteRecursively() }
            return@withContext
        }
        val journal = try {
            JSONObject(journalFile.inputStream().use { readUtf8Bounded(it, MaxJournalBytes) })
        } catch (failure: Exception) {
            throw BackupRestoreRecoveryException("Restore recovery journal is unreadable. Do not modify the library until it is repaired.", failure)
        }
        try {
            if (journal.optInt("journalVersion") < 2) {
                recoverLegacyJournal(journal)
            } else {
                val transactionId = journal.getString("transactionId")
                require(transactionId.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid restore transaction identifier." }
                val ids = journal.optJSONArray("bookIds")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
                require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }) {
                    "Invalid identifiers in restore journal."
                }
                val presentIds = dao.allBooks().mapTo(mutableSetOf()) { it.id }
                val databaseCommitted = journal.optBoolean("committed") || ids.isEmpty() || ids.all { it in presentIds }
                if (databaseCommitted) {
                    finishRestoreJournal(journal)
                } else {
                    val booksDirectory = File(context.filesDir, "books")
                    ids.filterNot { it in presentIds }.forEach { id ->
                        SupportedBookFormats.forEach { extension ->
                            val file = File(booksDirectory, "$id.$extension")
                            check(!file.exists() || file.delete()) { "Could not remove an uncommitted restored book file." }
                        }
                    }
                }
                transactionDirectory(transactionId).deleteRecursively()
            }
            check(!journalFile.exists() || journalFile.delete()) { "Could not clear the completed restore journal." }
            File(context.filesDir, RestoreDirectory).listFiles()?.forEach { it.deleteRecursively() }
            context.cacheDir.listFiles()?.filter { it.name.startsWith("restore-") }?.forEach { it.deleteRecursively() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw BackupRestoreRecoveryException("Backup restore recovery could not finish. Library actions remain blocked; retry recovery after checking available storage.", failure)
        }
    }

    fun hasPendingRestore(): Boolean = restoreJournalFile().isFile

    private suspend fun recoverLegacyJournal(journal: JSONObject) {
        val ids = journal.optJSONArray("bookIds")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        require(ids.all { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }) { "Invalid legacy restore journal." }
        val presentIds = dao.allBooks().mapTo(mutableSetOf()) { it.id }
        val committed = journal.optBoolean("committed") || (ids.isNotEmpty() && ids.all { it in presentIds })
        if (!committed) {
            // Old versions changed these stores before their database commit. Keep any keys not
            // represented in the old journal; it did not contain a complete Groq preference snapshot.
            journal.optJSONObject("previousPreferences")?.let {
                restorePreferences(it, replaceExisting = true, preserveExistingSummaries = false, clearMissing = false)
            }
            ids.filterNot { it in presentIds }.forEach { id ->
                check(ReaderAnnotationStore(context).writeAndCommit(id, emptyList())) { "Could not roll back annotations." }
                check(BookmarkStore(context).writeAndCommit(id, emptyList())) { "Could not roll back bookmarks." }
                SupportedBookFormats.forEach { extension ->
                    val file = File(File(context.filesDir, "books"), "$id.$extension")
                    check(!file.exists() || file.delete()) { "Could not roll back a legacy restored book file." }
                }
            }
        }
    }

    private suspend fun finishRestoreJournal(journal: JSONObject) {
        val transactionId = journal.getString("transactionId")
        val transactionDir = transactionDirectory(transactionId)
        require(journal.optInt("journalVersion") == 2) { "Unsupported restore journal version." }
        val ids = journal.optJSONArray("bookIds")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }) {
            "Invalid book identifiers in restore journal."
        }
        val restoredBooks = dao.allBooks().associateBy { it.id }
        require(ids.all { id -> restoredBooks[id]?.let { File(it.localPath).isFile } == true }) {
            "A restored book is missing from the library or storage."
        }
        val journalLibraries = parseLibraries(journal.optJSONArray("libraries") ?: JSONArray())
        validatePreferences(journal.optJSONObject("settings"), version = 2)
        val states = journal.optJSONArray("states") ?: JSONArray()
        val parsedStates = List(states.length()) { index ->
            val state = states.getJSONObject(index)
            Triple(state.getString("id"), parseAnnotations(state.getJSONArray("annotations")), parseBookmarks(state.getJSONArray("bookmarks")))
        }
        require(parsedStates.map { it.first }.distinct().size == parsedStates.size &&
            parsedStates.map { it.first }.toSet() == ids.toSet()) { "Reader state does not match restored books." }
        val encryptedKey = journal.opt("groqKeyEncrypted")
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()
        encryptedKey?.let { GroqApiKeyStore(context).validateEncryptedValue(it) }
        val applyFont = journal.optBoolean("fontIncluded") && journal.optBoolean("applyFont")
        val stagedFont = File(transactionDir, "reading-font")
        val targetFont = File(context.filesDir, "reading-font")
        val expectedFontHash = journal.optString("fontSha256")
        if (applyFont) {
            if (stagedFont.isFile) {
                validateFont(stagedFont)
                require(expectedFontHash.matches(Regex("[a-fA-F0-9]{64}")) && sha256(stagedFont).equals(expectedFontHash, true)) {
                    "The staged custom font failed its integrity check."
                }
            } else {
                require(targetFont.isFile && sha256(targetFont).equals(expectedFontHash, true)) {
                    "The restored custom font is missing or corrupt."
                }
                validateFont(targetFont)
            }
        }
        // Replaying library insertion is idempotent and also completes a zero-book restore whose
        // Room transaction may have been interrupted before its post-transaction journal update.
        dao.restoreSnapshot(journalLibraries, emptyList())
        restorePreferences(journal.optJSONObject("settings"), journal.optBoolean("replaceSettings"))
        encryptedKey?.let { GroqApiKeyStore(context).restoreEncryptedValue(it) }
        for ((id, annotations, bookmarks) in parsedStates) {
            check(ReaderAnnotationStore(context).writeAndCommit(id, annotations)) {
                "Could not restore annotations for a book."
            }
            check(BookmarkStore(context).writeAndCommit(id, bookmarks)) {
                "Could not restore bookmarks for a book."
            }
        }
        if (applyFont) {
            if (stagedFont.isFile) {
                moveAtomically(stagedFont, targetFont)
            }
        }
    }

    private fun librariesToJson(libraries: List<LibraryEntity>) = JSONArray().apply {
        libraries.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt)) }
    }

    private fun parseLibraries(items: JSONArray): List<LibraryEntity> {
        require(items.length() <= MaxLibraries) { "Too many libraries in restore journal." }
        val libraries = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            LibraryEntity(item.getString("id"), item.getString("name"), item.getLong("createdAt"))
        }
        require(libraries.map { it.id }.distinct().size == libraries.size &&
            libraries.all { it.id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && it.name.isNotBlank() && it.name.length <= 60 }) {
            "Invalid library metadata in restore journal."
        }
        return libraries
    }

    private fun parseAnnotations(items: JSONArray): List<ReaderAnnotation> {
        val notes = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            ReaderAnnotation(item.getString("id"), item.getInt("chapter"), item.getInt("start"), item.getInt("end"),
                item.getString("quote"), item.getString("note"), item.optString("color", "yellow"))
        }
        require(notes.map { it.id }.distinct().size == notes.size &&
            notes.all { it.id.isNotBlank() && it.chapter >= 0 && it.start >= 0 && it.end >= it.start }) {
            "Invalid annotations in backup."
        }
        return notes
    }

    private fun parseBookmarks(items: JSONArray): List<ReaderBookmark> {
        val bookmarks = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            ReaderBookmark(item.getInt("chapter"), item.getDouble("fraction").toFloat())
        }
        require(bookmarks.all { it.chapter >= 0 && it.fraction.isFinite() && it.fraction in 0f..1f }) {
            "Invalid bookmarks in backup."
        }
        return bookmarks
    }

    private fun annotationsToJson(items: List<ReaderAnnotation>) = JSONArray().apply {
        items.forEach { item -> put(JSONObject().put("id", item.id).put("chapter", item.chapter).put("start", item.start)
            .put("end", item.end).put("quote", item.quote).put("note", item.note).put("color", item.color)) }
    }

    private fun bookmarksToJson(items: List<ReaderBookmark>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("chapter", it.chapter).put("fraction", it.fraction.toDouble())) }
    }

    private fun validateFont(file: File) {
        require(file.length() in 4..MaxFontBytes) { "The backup font is invalid or too large." }
        val signature = ByteArray(4)
        file.inputStream().use { require(it.read(signature) == signature.size) }
        require(signature.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(signature, Charsets.US_ASCII) == "OTTO") {
            "The backup contains an unsupported font file."
        }
    }

    private fun readUtf8Bounded(input: java.io.InputStream, maximum: Long): String =
        ByteArrayOutputStream().use { output ->
            copyBounded(input, output, maximum)
            output.toString(Charsets.UTF_8.name())
        }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maximum: Long): Long {
        var copied = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied = Math.addExact(copied, count.toLong())
            require(copied <= maximum) { "An item in this backup exceeds its size limit." }
            output.write(buffer, 0, count)
        }
        return copied
    }

    private fun validatePreferences(snapshot: JSONObject?, version: Int) {
        if (version < 2 || snapshot == null) return
        for (name in BackupPreferenceNames) {
            val entries = snapshot.optJSONObject(name) ?: continue
            for (index in 0 until entries.length()) {
                val key = entries.names()?.optString(index) ?: continue
                val item = entries.optJSONObject(key) ?: error("Invalid backup setting.")
                val value = item.opt("value")
                when (item.optString("type")) {
                    "string" -> require(value is String)
                    "boolean" -> require(value is Boolean)
                    "int", "long" -> require(value is Number)
                    "float" -> require(value is Number && value.toFloat().isFinite())
                    "strings" -> require(value is JSONArray)
                    else -> error("Unsupported setting type in backup.")
                }
            }
        }
    }

    private fun transactionDirectory(id: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid restore transaction identifier." }
        return File(File(context.filesDir, RestoreDirectory), id)
    }

    private fun writeRestoreJournal(value: JSONObject) {
        val temporary = File(context.filesDir, "$RestoreJournal.tmp")
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxJournalBytes) { "Restore recovery data is too large." }
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveAtomically(temporary, restoreJournalFile())
    }

    private fun moveAtomically(source: File, destination: File) {
        destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Could not create a restore destination." } }
        runCatching {
            java.nio.file.Files.move(source.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            java.nio.file.Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restoreJournalFile() = File(context.filesDir, RestoreJournal)

    private fun exportPreferences(): JSONObject {
        return JSONObject().apply {
            BackupPreferenceNames.forEach { name ->
                val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                put(name, JSONObject().apply {
                    values.forEach { (key, value) ->
                        if (name == "groqModels" && key != "model") return@forEach
                        val type = when (value) {
                            is String -> "string"
                            is Boolean -> "boolean"
                            is Int -> "int"
                            is Long -> "long"
                            is Float -> "float"
                            is Set<*> -> if (value.all { it is String }) "strings" else null
                            else -> null
                        } ?: return@forEach
                        val item = JSONObject().put("type", type)
                        when (type) {
                            "strings" -> item.put("value", JSONArray((value as Set<*>).filterIsInstance<String>()))
                            else -> item.put("value", value)
                        }
                        put(key, item)
                    }
                })
            }
        }
    }

    private fun restorePreferences(
        snapshot: JSONObject?,
        replaceExisting: Boolean,
        preserveExistingSummaries: Boolean = true,
        clearMissing: Boolean = false,
    ) {
        for (name in BackupPreferenceNames) {
            val entries = snapshot?.optJSONObject(name) ?: continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (clearMissing) editor.clear()
            for (index in 0 until entries.length()) {
                val key = entries.names()?.optString(index) ?: continue
                if (prefs.contains(key) && (!replaceExisting || (name == "noteSummaries" && preserveExistingSummaries))) continue
                val item = entries.optJSONObject(key) ?: continue
                val value = item.opt("value")
                when (item.optString("type")) {
                    "string" -> if (value is String) editor.putString(key, value)
                    "boolean" -> if (value is Boolean) editor.putBoolean(key, value)
                    "int" -> if (value is Number) editor.putInt(key, value.toInt())
                    "long" -> if (value is Number) editor.putLong(key, value.toLong())
                    "float" -> if (value is Number && value.toFloat().isFinite()) editor.putFloat(key, value.toFloat())
                    "strings" -> if (value is JSONArray) editor.putStringSet(key,
                        (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotEmpty) }.toSet())
                }
            }
            check(editor.commit()) { "Could not restore the $name settings." }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String {
        require(file.isFile) { "Missing book file: ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        const val RestoreDirectory = "backup-restore-pending"
        const val RestoreJournal = "backup-restore-journal.json"
        const val MaxBooks = 10_000
        const val MaxLibraries = 1_000
        const val MaxBackupEntries = 10_002
        const val MaxFontBytes = 6L * 1024 * 1024
        const val MaxJournalBytes = 20L * 1024 * 1024
        const val MinRestoreFreeBytes = 16L * 1024 * 1024
        val BackupPreferenceNames = listOf(
            "accessibility", "reader", "libraryAppearance", "groqModels", "readingStatistics", "noteSummaries", "webNovelCooldowns"
        )
        val SupportedBookFormats = listOf("epub", "pdf", "mobi", "azw3", "txt", "cbz", "cbr")
        const val MaxManifestBytes = 16L * 1024 * 1024
        const val MaxBackupBytes = 4L * 1024 * 1024 * 1024
    }
}
