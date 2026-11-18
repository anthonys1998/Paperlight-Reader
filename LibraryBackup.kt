package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Portable, additive backups of original books, libraries, progress and annotations. */
internal class LibraryBackup(private val context: Context, private val dao: BookDao) {
    suspend fun export(destination: Uri) = withContext(Dispatchers.IO) {
        val snapshot = dao.backupSnapshot()
        val annotations = ReaderAnnotationStore(context)
        val bookmarks = BookmarkStore(context)
        val manifest = JSONObject().put("format", "paperlight-backup").put("version", 1)
        manifest.put("libraries", JSONArray().apply {
            snapshot.second.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt)) }
        })
        manifest.put("books", JSONArray().apply {
            snapshot.first.forEach { book ->
                put(JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
                    .put("fileName", book.fileName).put("format", book.format).put("libraryId", book.libraryId)
                    .put("importedAt", book.importedAt).put("lastOpenedAt", book.lastOpenedAt ?: JSONObject.NULL)
                    .put("progress", book.progress.toDouble())
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
            }
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                temporary.inputStream().use { it.copyTo(output) }
            } ?: error("Could not write the backup file.")
        } finally { temporary.delete() }
    }

    suspend fun restore(source: Uri): Int = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val archive = File(staging, "backup.zip")
        val movedFiles = mutableListOf<File>()
        val stateIds = mutableListOf<String>()
        var committed = false
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MaxBackupBytes) { "This backup is too large." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not open this backup.")
            ZipFile(archive).use { zip ->
                require(zip.size() <= 10_001) { "This backup contains too many files." }
                val entry = requireNotNull(zip.getEntry("manifest.json")) { "This is not a Vibe Reader backup." }
                require(entry.size in 1..(16L * 1024 * 1024)) { "The backup manifest is too large." }
                val manifest = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                require(manifest.optString("format") == "paperlight-backup" && manifest.optInt("version") == 1) { "Unsupported backup format." }
                val libraryArray = manifest.getJSONArray("libraries")
                val libraries = List(libraryArray.length()) { index ->
                    val item = libraryArray.getJSONObject(index)
                    LibraryEntity(item.getString("id"), item.getString("name").take(60), item.getLong("createdAt"))
                }
                val bookArray = manifest.getJSONArray("books")
                require(bookArray.length() <= 10_000) { "Too many books in this backup." }
                val existing = dao.allBooks().map { it.id }.toSet()
                val seen = mutableSetOf<String>()
                val restored = mutableListOf<BookEntity>()
                var expanded = 0L
                for (index in 0 until bookArray.length()) {
                    coroutineContext.ensureActive()
                    val item = bookArray.getJSONObject(index)
                    val id = item.getString("id")
                    require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && seen.add(id)) { "Invalid or duplicate book identifier." }
                    if (id in existing) continue
                    val format = item.getString("format")
                    require(BookRepository.formatOf("book.$format") != null) { "Unsupported book format in backup." }
                    val content = requireNotNull(zip.getEntry("books/$id")) { "A book file is missing from the backup." }
                    val destination = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.${format.lowercase(java.util.Locale.ROOT)}")
                    require(!destination.exists()) { "A book with this identifier already exists on this device." }
                    val temporary = File(staging, id)
                    zip.getInputStream(content).use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MaxBackupBytes) { "Expanded backup exceeds the size limit." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(temporary.length() > 0L) { "The backup contains an empty book file." }
                    movedFiles += destination
                    temporary.copyTo(destination, overwrite = false)
                    val progress = item.getDouble("progress").toFloat()
                    require(progress.isFinite() && progress in 0f..1f) { "Invalid reading progress." }
                    restored += BookEntity(id, item.getString("title"), item.optString("author"), item.getString("fileName"),
                        destination.absolutePath, format, item.getLong("importedAt"),
                        if (item.isNull("lastOpenedAt")) null else item.getLong("lastOpenedAt"), progress,
                        libraryId = item.getString("libraryId").takeIf { library -> libraries.any { it.id == library } } ?: "default")
                    val notes = item.optJSONArray("annotations") ?: JSONArray()
                    val annotations = List(notes.length()) { noteIndex ->
                        val note = notes.getJSONObject(noteIndex)
                        ReaderAnnotation(note.getString("id"), note.getInt("chapter"), note.getInt("start"), note.getInt("end"),
                            note.getString("quote"), note.getString("note"), note.optString("color", "yellow"))
                    }
                    val marks = item.optJSONArray("bookmarks") ?: JSONArray()
                    require(annotations.map { it.id }.distinct().size == annotations.size &&
                        annotations.all { it.chapter >= 0 && it.start >= 0 && it.end >= it.start }) { "Invalid annotations in backup." }
                    val bookmarks = List(marks.length()) { markIndex ->
                        val mark = marks.getJSONObject(markIndex)
                        ReaderBookmark(mark.getInt("chapter"), mark.getDouble("fraction").toFloat())
                    }
                    require(bookmarks.all { it.chapter >= 0 && it.fraction.isFinite() && it.fraction in 0f..1f }) { "Invalid bookmarks in backup." }
                    stateIds += id
                    ReaderAnnotationStore(context).write(id, annotations)
                    BookmarkStore(context).write(id, bookmarks)
                }
                dao.restoreSnapshot(libraries, restored)
                committed = true
                restored.size
            }
        } finally {
            if (!committed) {
                movedFiles.forEach { it.delete() }
                stateIds.forEach { id ->
                    ReaderAnnotationStore(context).write(id, emptyList())
                    BookmarkStore(context).write(id, emptyList())
                }
            }
            staging.deleteRecursively()
        }
    }

    private companion object {
        const val MaxBackupBytes = 4L * 1024 * 1024 * 1024
    }
}
