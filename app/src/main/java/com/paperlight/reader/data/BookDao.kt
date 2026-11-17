package com.paperlight.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
    suspend fun moveBooks(ids: List<String>, libraryId: String)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteBooks(ids: List<String>)

    @Query("SELECT * FROM libraries ORDER BY createdAt, name COLLATE NOCASE")
    fun observeLibraries(): Flow<List<LibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibrary(library: LibraryEntity)

    @Query("SELECT * FROM libraries WHERE id = :id LIMIT 1")
    suspend fun findLibrary(id: String): LibraryEntity?
}
