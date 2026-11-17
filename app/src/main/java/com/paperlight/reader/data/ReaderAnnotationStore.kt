package com.paperlight.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReaderAnnotation(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    val color: String = "yellow",
)

class ReaderAnnotationStore(context: Context) {
    private val preferences = context.getSharedPreferences("readerAnnotations", Context.MODE_PRIVATE)

    fun read(bookId: String): List<ReaderAnnotation> = runCatching {
        val array = JSONArray(preferences.getString(bookId, "[]") ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ReaderAnnotation(item.getString("id"), item.getInt("chapter"), item.getInt("start"),
                item.getInt("end"), item.getString("quote"), item.getString("note"), item.optString("color", "yellow"))
        }
    }.getOrDefault(emptyList())

    fun write(bookId: String, annotations: List<ReaderAnnotation>) {
        val editor = preferences.edit()
        if (annotations.isEmpty()) editor.remove(bookId)
        else editor.putString(bookId, JSONArray().apply {
            annotations.forEach { item ->
                put(JSONObject().put("id", item.id).put("chapter", item.chapter)
                    .put("start", item.start).put("end", item.end).put("quote", item.quote).put("note", item.note).put("color", item.color))
            }
        }.toString())
        editor.apply()
    }
}
