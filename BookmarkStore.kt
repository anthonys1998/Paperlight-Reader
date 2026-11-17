package com.paperlight.reader.data

import android.content.Context

data class ReaderBookmark(val chapter: Int, val fraction: Float)

class BookmarkStore(context: Context) {
    private val preferences = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun read(bookId: String): List<ReaderBookmark> =
        preferences.getStringSet(bookId, emptySet()).orEmpty().mapNotNull { entry ->
            val chapter = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val fraction = entry.substringAfter(':', "").toFloatOrNull() ?: return@mapNotNull null
            if (chapter < 0 || fraction !in 0f..1f) return@mapNotNull null
            ReaderBookmark(chapter, fraction)
        }.sortedWith(compareBy(ReaderBookmark::chapter, ReaderBookmark::fraction))

    fun write(bookId: String, bookmarks: List<ReaderBookmark>) {
        val editor = preferences.edit()
        if (bookmarks.isEmpty()) editor.remove(bookId)
        else editor.putStringSet(bookId, bookmarks.map { "${it.chapter}:${it.fraction}" }.toSet())
        editor.apply()
    }
}
