package com.paperlight.reader.data

import android.content.Context
import org.json.JSONObject

data class SavedNoteSummary(
    val text: String,
    val manuallyEdited: Boolean,
    val updatedAt: Long,
    val model: String?,
    val noteIds: List<String>,
)

/** Local, offline storage for summaries of a book's written notes. */
class NoteSummaryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("noteSummaries", Context.MODE_PRIVATE)

    fun read(bookId: String): SavedNoteSummary? {
        val value = preferences.getString(bookId, null) ?: return null
        return runCatching {
        val json = JSONObject(value)
        SavedNoteSummary(
            text = json.getString("text"),
            manuallyEdited = json.optBoolean("manuallyEdited", false),
            updatedAt = json.optLong("updatedAt", 0L),
            model = json.optString("model").takeIf(String::isNotBlank),
            noteIds = json.optJSONArray("noteIds")?.let { ids ->
                List(ids.length()) { ids.optString(it) }.filter(String::isNotBlank)
            }.orEmpty(),
        )
        }.getOrNull()
    }

    fun save(bookId: String, text: String, manuallyEdited: Boolean, model: String? = null, noteIds: List<String> = emptyList()) {
        require(bookId.isNotBlank() && text.isNotBlank())
        preferences.edit().putString(bookId, JSONObject()
            .put("text", text)
            .put("manuallyEdited", manuallyEdited)
            .put("updatedAt", System.currentTimeMillis())
            .put("model", model)
            .put("noteIds", org.json.JSONArray(noteIds.distinct()))
            .toString()).apply()
    }

    fun delete(bookId: String) {
        preferences.edit().remove(bookId).apply()
    }
}
