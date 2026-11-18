package com.paperlight.reader.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.paperlight.reader.data.ReaderAnnotation
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GeminiApiKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("geminiApiKey", Context.MODE_PRIVATE)

    fun hasKey(): Boolean = getKey() != null

    fun getKey(): String? {
        return runCatching {
            val encoded = preferences.getString(KEY_VALUE, null) ?: return@runCatching null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, packed, 0, GCM_IV_BYTES))
            String(cipher.doFinal(packed, GCM_IV_BYTES, packed.size - GCM_IV_BYTES), Charsets.UTF_8).takeIf(String::isNotBlank)
        }.getOrElse {
            preferences.edit().remove(KEY_VALUE).apply()
            null
        }
    }

    fun saveKey(key: String) {
        val clean = key.trim()
        require(clean.length in 20..500) { "Enter a valid Gemini API key." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(KEY_VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_VALUE).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "vibe-reader-gemini-api-key"
        const val KEY_VALUE = "encrypted"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

data class GeminiScrapeSuggestion(
    val chapterLinkSelector: String,
    val chapterContentSelector: String,
    val chapterTitleSelector: String,
    val removeSelector: String,
    val bookTitle: String,
)

class GeminiAiClient(private val context: Context) {
    private val keyStore = GeminiApiKeyStore(context)

    suspend fun suggestScrapeConfiguration(
        url: String,
        sampleChapterUrl: String = "",
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
    ): GeminiScrapeSuggestion = withContext(Dispatchers.IO) {
        val samples = WebNovelEpubUtility(context).inspectForAi(url, sampleChapterUrl, options)
        val prompt = """You configure a user-requested web-novel EPUB scraper. Read the sanitized HTML samples and return ONLY a JSON object with string fields: chapterLinkSelector, chapterContentSelector, chapterTitleSelector, removeSelector, bookTitle. Use valid CSS selectors only. Select chapter links on the table-of-contents page, the main readable chapter content, its title, and removable clutter. Do not return JavaScript, URLs to fetch, or explanations. If uncertain, use conservative selectors. bookTitle should be a short publication title.
TOC HTML:
${samples.tocHtml}
CHAPTER HTML:
${samples.chapterHtml}"""
        val json = parseJson(request(prompt))
        val suggestion = GeminiScrapeSuggestion(
            chapterLinkSelector = json.optString("chapterLinkSelector").trim().take(300),
            chapterContentSelector = json.optString("chapterContentSelector").trim().take(500),
            chapterTitleSelector = json.optString("chapterTitleSelector", "h1, h2").trim().take(300),
            removeSelector = json.optString("removeSelector", "script, style, nav, header, footer, aside").trim().take(500),
            bookTitle = json.optString("bookTitle").trim().take(160),
        )
        require(suggestion.chapterLinkSelector.isNotBlank() && suggestion.chapterContentSelector.isNotBlank()) {
            "Gemini did not return usable selectors. Try a different URL or enter the selectors manually."
        }
        require(suggestion.chapterLinkSelector.length <= 300 && suggestion.chapterContentSelector.length <= 500 &&
            suggestion.chapterTitleSelector.length <= 300 && suggestion.removeSelector.length <= 500) {
            "Gemini returned selectors that are too long. Try again or enter them manually."
        }
        val toc = Jsoup.parse(samples.tocHtml, url)
        val chapter = Jsoup.parse(samples.chapterHtml, url)
        val linkMatches = runCatching { toc.select(suggestion.chapterLinkSelector) }
            .getOrElse { throw IllegalArgumentException("The suggested chapter-link selector is invalid. Review it or enter it manually.") }
        require(linkMatches.any { it.normalName() == "a" || it.selectFirst("a[href]") != null }) {
            "The suggested chapter-link selector did not match the table of contents. Review the selectors or enter them manually."
        }
        val contentMatches = runCatching { chapter.select(suggestion.chapterContentSelector) }
            .getOrElse { throw IllegalArgumentException("The suggested content selector is invalid. Review it or enter it manually.") }
        require(contentMatches.isNotEmpty()) {
            "The suggested content selector did not match the sample chapter. Review the selectors or enter them manually."
        }
        if (suggestion.chapterTitleSelector.isNotBlank()) {
            runCatching { chapter.select(suggestion.chapterTitleSelector) }.getOrElse {
                throw IllegalArgumentException("The suggested title selector is invalid. Review it or enter it manually.")
            }
        }
        if (suggestion.removeSelector.isNotBlank()) {
            runCatching { chapter.select(suggestion.removeSelector) }.getOrElse {
                throw IllegalArgumentException("The suggested cleanup selector is invalid. Review it or enter it manually.")
            }
        }
        suggestion
    }

    suspend fun summarizeNotes(annotations: List<ReaderAnnotation>): String {
        val notes = annotations.asSequence().filter { it.note.isNotBlank() }.take(100)
            .mapIndexed { index, note -> "${index + 1}. ${note.note.take(1_500)}" }.toList()
        require(notes.isNotEmpty()) { "Add written notes before asking for a summary." }
        val input = notes.joinToString("\n").take(MAX_NOTES_CHARS)
        return request(
            "Summarize these reader-written notes into a concise, organized overview. Preserve uncertainty and do not add facts that are not in the notes. Do not reproduce book passages.\n\nReader notes:\n$input"
        ).trim().take(MAX_SUMMARY_CHARS).ifBlank { "Gemini returned an empty summary. Try again." }
    }

    private suspend fun request(prompt: String): String {
        val key = keyStore.getKey() ?: throw IllegalStateException("Add your Gemini API key to use AI features.")
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { requestOnce(prompt, key) }
            } catch (failure: GeminiHttpException) {
                if (failure.status != 503 || attempt >= 2) throw failure
                kotlinx.coroutines.delay(800L shl attempt)
                attempt++
            }
        }
    }

    private fun requestOnce(prompt: String, key: String): String {
        val connection = (URL(INTERACTIONS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            val body = JSONObject().put("model", MODEL_ALIAS).put("store", false).put("input", prompt).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
            if (status !in 200..299) throw GeminiHttpException(status, errorMessage(status, response))
            val root = JSONObject(response)
            if (root.optString("status") in setOf("failed", "incomplete", "budget_exceeded")) {
                throw IllegalStateException("Gemini could not finish this request. Try again with a shorter input.")
            }
            val steps = root.optJSONArray("steps")
            val output = buildString {
                for (stepIndex in 0 until (steps?.length() ?: 0)) {
                    val step = steps?.optJSONObject(stepIndex) ?: continue
                    if (step.optString("type") != "model_output") continue
                    val parts = step.optJSONArray("content") ?: continue
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
            }
            return output.ifBlank { throw IllegalStateException("Gemini returned no text. Try again.") }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJson(raw: String): JSONObject {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Gemini returned an invalid selector configuration. Try again." }
        return JSONObject(cleaned.substring(start, end + 1))
    }

    private fun errorMessage(status: Int, response: String): String {
        val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return when (status) {
            400, 401, 403 -> if (detail.contains("key", true) || status == 401 || status == 403)
                "Gemini rejected this API key. Check it in AI settings." else "Gemini could not process the request. $detail"
            429 -> "Gemini's free or paid API quota is currently limited. Check your AI Studio quota and try again later."
            503 -> "Gemini is temporarily overloaded. The app retried this request. Try again later or choose another model in AI settings."
            else -> "Gemini request failed (HTTP $status). ${detail.take(240)}".trim()
        }
    }

    private companion object {
        const val MODEL_ALIAS = "gemini-flash-latest"
        const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val MAX_RESPONSE_CHARS = 64 * 1024
        const val MAX_NOTES_CHARS = 24_000
        const val MAX_SUMMARY_CHARS = 10_000
    }

    private class GeminiHttpException(val status: Int, message: String) : IllegalStateException(message)
}
