package com.paperlight.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class], version = 3, exportSchema = false)
abstract class BookDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        private val addChapterPage = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN chapterPage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN chapterPageCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val resetChapterCounts = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE books SET chapterPage = 0, chapterPageCount = 0")
            }
        }

        @Volatile private var INSTANCE: BookDatabase? = null

        fun getInstance(context: Context): BookDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "paperlight-library.db"
                ).addMigrations(addChapterPage, resetChapterCounts).build().also { INSTANCE = it }
            }
    }
}
