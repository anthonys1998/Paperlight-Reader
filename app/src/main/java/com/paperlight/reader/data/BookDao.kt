package com.paperlight.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, importedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET progress = :progress, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float, openedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun booksByIds(ids: List<String>): List<BookEntity>

    @Query("UPDATE books SET libraryId = :libraryId WHERE id IN (:ids)")
    suspend fun updateBookLibrary(ids: List<String>, libraryId: String)

    @Transaction
    suspend fun moveBooks(ids: List<String>, libraryId: String) {
        require(findLibrary(libraryId) != null) { "That library no longer exists." }
        ids.chunked(500).forEach { updateBookLibrary(it, libraryId) }
    }

    @Transaction
    suspend fun insertBook(book: BookEntity) {
        require(findLibrary(book.libraryId) != null) { "That library no longer exists." }
        upsert(book)
    }

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun bookById(id: String): BookEntity?

    @Query("SELECT * FROM libraries")
    suspend fun allLibraries(): List<LibraryEntity>

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String, author: String)

    @Transaction
    suspend fun backupSnapshot(): Pair<List<BookEntity>, List<LibraryEntity>> = allBooks() to allLibraries()

    @Transaction
    suspend fun restoreSnapshot(libraries: List<LibraryEntity>, books: List<BookEntity>) {
        insertLibrary(LibraryEntity("default", "My Library", 0L))
        libraries.forEach { insertLibrary(it) }
        val existing = allBooks().map { it.id }.toSet()
        require(books.none { it.id in existing }) { "The library changed during restore. Please retry." }
        books.forEach { insertBook(it) }
    }

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteBooks(ids: List<String>)

    @Query("UPDATE libraries SET name = :name WHERE id = :id")
    suspend fun renameLibrary(id: String, name: String)

    @Query("SELECT COUNT(*) FROM libraries WHERE name = :name COLLATE NOCASE AND id != :exceptId")
    suspend fun libraryNameCount(name: String, exceptId: String): Int

    @Query("UPDATE books SET libraryId = 'default' WHERE libraryId = :id")
    suspend fun moveLibraryBooksToDefault(id: String)

    @Query("DELETE FROM libraries WHERE id = :id")
    suspend fun deleteLibraryRow(id: String)

    @Transaction
    suspend fun deleteLibrary(id: String) {
        require(id != "default") { "The default library cannot be deleted." }
        moveLibraryBooksToDefault(id)
        deleteLibraryRow(id)
    }

    @Query("SELECT * FROM libraries ORDER BY createdAt, name COLLATE NOCASE")
    fun observeLibraries(): Flow<List<LibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibrary(library: LibraryEntity)

    @Query("SELECT * FROM libraries WHERE id = :id LIMIT 1")
    suspend fun findLibrary(id: String): LibraryEntity?
}
