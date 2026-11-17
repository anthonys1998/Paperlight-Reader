package com.paperlight.reader.data

import android.content.Context
import java.time.LocalDate

internal class ReadingStatisticsStore(context: Context) {
    private val preferences = context.getSharedPreferences("readingStatistics", Context.MODE_PRIVATE)

    fun add(bookId: String, milliseconds: Long) {
        if (milliseconds <= 0L) return
        synchronized(Lock) {
            val day = "day:${LocalDate.now()}"
            val book = "book:$bookId"
            preferences.edit()
                .putLong(day, preferences.getLong(day, 0L) + milliseconds)
                .putLong(book, preferences.getLong(book, 0L) + milliseconds)
                .putLong("total", preferences.getLong("total", 0L) + milliseconds)
                .apply()
        }
    }

    fun total(): Long = preferences.getLong("total", 0L)
    fun today(): Long = preferences.getLong("day:${LocalDate.now()}", 0L)
    fun forBook(bookId: String): Long = preferences.getLong("book:$bookId", 0L)

    private companion object {
        val Lock = Any()
    }
}
