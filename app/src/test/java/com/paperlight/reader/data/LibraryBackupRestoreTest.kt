package com.paperlight.reader.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryBackupRestoreTest {
    private lateinit var context: Context
    private lateinit var database: BookDatabase
    private lateinit var backup: LibraryBackup

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Robolectric defaults to zero filesystem capacity unless explicitly configured.
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 500_000, 500_000)
        database = Room.inMemoryDatabaseBuilder(context, BookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backup = LibraryBackup(context, database.bookDao())
        File(context.filesDir, "books").deleteRecursively()
        File(context.filesDir, "backup-restore-pending").deleteRecursively()
        File(context.filesDir, "backup-restore-journal.json").delete()
        File(context.filesDir, "backup-restore-journal.json.tmp").delete()
        listOf("readerAnnotations", "bookmarks", "groqApiKey", "reader", "accessibility", "libraryAppearance",
            "groqModels", "readingStatistics", "noteSummaries", "webNovelCooldowns").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun restoreStagesAndPublishesBooksAndReaderState() = runBlocking {
        val contents = "a staged text book".toByteArray()
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putInt("fontSize", 18).commit()
        val manifest = bookManifest("book-1", contents).apply {
            put("preferences", JSONObject().put("reader", JSONObject()
                .put("fontSize", JSONObject().put("type", "int").put("value", 30))
                .put("lineSpacing", JSONObject().put("type", "float").put("value", 1.25))))
        }
        val archive = createArchive(manifest, mapOf("books/book-1" to contents))

        assertEquals(1, backup.restore(Uri.fromFile(archive)))

        val restored = requireNotNull(database.bookDao().bookById("book-1"))
        assertEquals("shelf", restored.libraryId)
        assertArrayEquals(contents, File(restored.localPath).readBytes())
        assertEquals("A note", ReaderAnnotationStore(context).read("book-1").single().note)
        assertEquals(ReaderBookmark(3, 0.25f), BookmarkStore(context).read("book-1").single())
        val readerSettings = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertEquals(18, readerSettings.getInt("fontSize", 0))
        assertEquals(1.25f, readerSettings.getFloat("lineSpacing", 0f), 0f)
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun insufficientStorageRejectsRestoreBeforePublishingBooks() = runBlocking {
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 0, 0)
        val contents = "a staged text book".toByteArray()
        val archive = createArchive(bookManifest("low-space", contents), mapOf("books/low-space" to contents))

        val failure = runCatching { backup.restore(Uri.fromFile(archive)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("not enough free storage"))
        assertNull(database.bookDao().bookById("low-space"))
        assertFalse(File(context.filesDir, "books/low-space.txt").exists())
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun corruptBookIsRejectedBeforeAnyLiveBookIsPublished() = runBlocking {
        val contents = "tampered content".toByteArray()
        val manifest = bookManifest("book-bad", contents).apply {
            getJSONArray("books").getJSONObject(0).put("sha256", "0".repeat(64))
        }
        val archive = createArchive(manifest, mapOf("books/book-bad" to contents))

        runCatching { backup.restore(Uri.fromFile(archive)) }
            .onSuccess { error("Corrupt restore unexpectedly succeeded") }

        assertNull(database.bookDao().bookById("book-bad"))
        assertFalse(File(context.filesDir, "books/book-bad.txt").exists())
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun roomFailureRollsBackAlreadyPublishedBookFiles() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_restore BEFORE INSERT ON books WHEN NEW.id = 'db-failure' " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        val contents = "must be rolled back".toByteArray()
        val archive = createArchive(bookManifest("db-failure", contents), mapOf("books/db-failure" to contents))

        runCatching { backup.restore(Uri.fromFile(archive)) }
            .onSuccess { error("Restore unexpectedly ignored the Room failure") }

        assertNull(database.bookDao().bookById("db-failure"))
        assertFalse(File(context.filesDir, "books/db-failure.txt").exists())
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun existingBookAndItsAnnotationsAreNeverOverwritten() = runBlocking {
        val id = "already-here"
        val localFile = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.txt")
        localFile.writeText("local copy")
        database.bookDao().restoreSnapshot(emptyList(), listOf(
            BookEntity(id, "Local title", fileName = "local.txt", localPath = localFile.absolutePath,
                format = "TXT", importedAt = 5L)
        ))
        ReaderAnnotationStore(context).writeAndCommit(id, listOf(
            ReaderAnnotation("local-note", 0, 0, 5, "local", "keep this")
        ))
        val replacement = "backup copy".toByteArray()
        val archive = createArchive(bookManifest(id, replacement), mapOf("books/$id" to replacement))

        assertEquals(0, backup.restore(Uri.fromFile(archive)))

        assertEquals("Local title", database.bookDao().bookById(id)?.title)
        assertEquals("local copy", localFile.readText())
        assertEquals("keep this", ReaderAnnotationStore(context).read(id).single().note)
    }

    @Test
    fun corruptEntryForAnExistingBookStillRejectsTheWholeBackup() = runBlocking {
        val id = "already-here"
        val localFile = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.txt")
        localFile.writeText("local copy")
        database.bookDao().restoreSnapshot(emptyList(), listOf(
            BookEntity(id, "Local title", fileName = "local.txt", localPath = localFile.absolutePath,
                format = "TXT", importedAt = 5L)
        ))
        val badContents = "changed backup copy".toByteArray()
        val manifest = bookManifest(id, badContents).apply {
            getJSONArray("books").getJSONObject(0).put("sha256", "f".repeat(64))
        }
        val archive = createArchive(manifest, mapOf("books/$id" to badContents))

        runCatching { backup.restore(Uri.fromFile(archive)) }
            .onSuccess { error("Corrupt existing-book entry unexpectedly succeeded") }

        assertEquals("Local title", database.bookDao().bookById(id)?.title)
        assertEquals("local copy", localFile.readText())
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun recoveryRollsBackFilesFromAnUncommittedRestore() = runBlocking {
        val transactionId = UUID.randomUUID().toString()
        val liveFile = File(File(context.filesDir, "books").apply { mkdirs() }, "orphan.txt")
        liveFile.writeText("not committed")
        File(context.filesDir, "backup-restore-journal.json").writeText(JSONObject()
            .put("journalVersion", 2)
            .put("transactionId", transactionId)
            .put("committed", false)
            .put("bookIds", JSONArray().put("orphan"))
            .put("libraries", JSONArray())
            .put("settings", JSONObject())
            .put("states", JSONArray())
            .toString())

        backup.recoverInterruptedRestore()

        assertFalse(liveFile.exists())
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun unreadableRecoveryJournalKeepsLibraryBlockedForRetry() = runBlocking {
        File(context.filesDir, "backup-restore-journal.json").writeText("{")

        val failure = runCatching { backup.recoverInterruptedRestore() }.exceptionOrNull()

        assertTrue(failure is BackupRestoreRecoveryException)
        assertTrue(backup.hasPendingRestore())
    }

    @Test
    fun repositoryBlocksBackgroundBookPublicationWhileRecoveryIsPending() = runBlocking {
        File(context.filesDir, "backup-restore-journal.json").writeText("{")
        val generatedFile = File(context.cacheDir, "generated-test.epub").apply { writeText("generated") }
        val repository = BookRepository(context, database.bookDao())

        val result = repository.importGeneratedEpub(generatedFile, "Generated")

        assertTrue(result.isFailure)
        assertTrue(database.bookDao().allBooks().isEmpty())
        assertFalse(File(context.filesDir, "books").exists())
        assertTrue(backup.hasPendingRestore())
    }

    @Test
    fun recoveryReplaysFinalizationAfterDatabaseCommit() = runBlocking {
        val id = "committed-book"
        val file = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.txt")
        file.writeText("committed")
        database.bookDao().restoreSnapshot(emptyList(), listOf(
            BookEntity(id, "Committed", fileName = "committed.txt", localPath = file.absolutePath,
                format = "TXT", importedAt = 1L)
        ))
        val transactionId = UUID.randomUUID().toString()
        val state = JSONObject()
            .put("id", id)
            .put("annotations", JSONArray().put(JSONObject()
                .put("id", "n1").put("chapter", 0).put("start", 2).put("end", 4)
                .put("quote", "ok").put("note", "recovered").put("color", "yellow")))
            .put("bookmarks", JSONArray().put(JSONObject().put("chapter", 2).put("fraction", 0.75)))
        File(context.filesDir, "backup-restore-journal.json").writeText(JSONObject()
            .put("journalVersion", 2)
            .put("transactionId", transactionId)
            .put("committed", true)
            .put("bookIds", JSONArray().put(id))
            .put("libraries", JSONArray())
            .put("settings", JSONObject())
            .put("states", JSONArray().put(state))
            .put("groqKeyEncrypted", JSONObject.NULL)
            .put("fontIncluded", false)
            .toString())

        backup.recoverInterruptedRestore()

        assertEquals("recovered", ReaderAnnotationStore(context).read(id).single().note)
        assertEquals(ReaderBookmark(2, 0.75f), BookmarkStore(context).read(id).single())
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun zeroBookRecoveryCompletesLibraryInsertionIdempotently() = runBlocking {
        val transactionId = UUID.randomUUID().toString()
        File(context.filesDir, "backup-restore-journal.json").writeText(JSONObject()
            .put("journalVersion", 2)
            .put("transactionId", transactionId)
            .put("committed", false)
            .put("bookIds", JSONArray())
            .put("libraries", JSONArray().put(JSONObject()
                .put("id", "from-backup").put("name", "From backup").put("createdAt", 1)))
            .put("settings", JSONObject())
            .put("states", JSONArray())
            .put("groqKeyEncrypted", JSONObject.NULL)
            .put("fontIncluded", false)
            .toString())

        backup.recoverInterruptedRestore()

        assertEquals("From backup", database.bookDao().findLibrary("from-backup")?.name)
        assertFalse(backup.hasPendingRestore())
    }

    @Test
    fun plaintextCredentialArchiveIsRejectedWithoutChangingLibrary() = runBlocking {
        val savedCiphertext = "preserved-existing-groq-value"
        context.getSharedPreferences("groqApiKey", Context.MODE_PRIVATE).edit()
            .putString("encrypted", savedCiphertext).commit()
        val manifest = JSONObject()
            .put("format", "paperlight-backup")
            .put("version", 2)
            .put("libraries", JSONArray())
            .put("books", JSONArray())
            .put("credentials", JSONObject().put("groqApiKey", "gsk_" + "x".repeat(40)))
        val archive = createArchive(manifest, emptyMap())

        runCatching { backup.restore(Uri.fromFile(archive)) }
            .onSuccess { error("Unencrypted credential archive unexpectedly succeeded") }

        assertTrue(database.bookDao().allBooks().isEmpty())
        assertNull(database.bookDao().findLibrary("default"))
        assertEquals(savedCiphertext, context.getSharedPreferences("groqApiKey", Context.MODE_PRIVATE)
            .getString("encrypted", null))
    }

    private fun bookManifest(id: String, content: ByteArray) = JSONObject()
        .put("format", "paperlight-backup")
        .put("version", 2)
        .put("preferences", JSONObject())
        .put("libraries", JSONArray().put(JSONObject().put("id", "shelf").put("name", "Shelf").put("createdAt", 1)))
        .put("books", JSONArray().put(JSONObject()
            .put("id", id)
            .put("title", "Staged book")
            .put("author", "Author")
            .put("fileName", "book.txt")
            .put("format", "TXT")
            .put("libraryId", "shelf")
            .put("importedAt", 1)
            .put("lastOpenedAt", JSONObject.NULL)
            .put("progress", 0.5)
            .put("sha256", sha256(content))
            .put("annotations", JSONArray().put(JSONObject()
                .put("id", "note-1").put("chapter", 1).put("start", 2).put("end", 4)
                .put("quote", "text").put("note", "A note").put("color", "yellow")))
            .put("bookmarks", JSONArray().put(JSONObject().put("chapter", 3).put("fraction", 0.25)))))

    private fun createArchive(manifest: JSONObject, entries: Map<String, ByteArray>): File {
        val archive = File(context.cacheDir, "restore-test-${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
