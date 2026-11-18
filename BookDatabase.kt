package com.paperlight.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class, LibraryEntity::class], version = 4, exportSchema = false)
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

        private val addLibraries = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `libraries` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT OR IGNORE INTO `libraries` (`id`, `name`, `createdAt`) VALUES ('default', 'My Library', 0)")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `libraryId` TEXT NOT NULL DEFAULT 'default'")
            }
        }

        @Volatile private var INSTANCE: BookDatabase? = null

        fun getInstance(context: Context): BookDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "paperlight-library.db"
                ).addMigrations(addChapterPage, resetChapterCounts, addLibraries).build().also { INSTANCE = it }
            }
    }
}
