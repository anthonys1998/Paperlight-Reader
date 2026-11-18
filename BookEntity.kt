package com.paperlight.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val fileName: String,
    val localPath: String,
    val format: String,
    val importedAt: Long,
    val lastOpenedAt: Long? = null,
    val progress: Float = 0f,
    val status: String = "IMPORTED",
    @ColumnInfo(name = "chapterPage", defaultValue = "0") val bookPage: Int = 0,
    @ColumnInfo(name = "chapterPageCount", defaultValue = "0") val bookPageCount: Int = 0,
    @ColumnInfo(defaultValue = "'default'") val libraryId: String = "default"
)
