package com.paperlight.reader.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class GeminiSpeechClient(context: Context) {
    private val keyStore = GeminiApiKeyStore(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        check(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val key = checkNotNull(keyStore.getKey())
        val content = JSONObject().put("type", "text").put("text", text)
        val turn = JSONObject().put("type", "user_input").put("content", JSONArray().put(content))
        val speech = JSONArray().put(JSONObject().put("voice", "Kore"))
        val body = JSONObject()
            .put("model", "gemini-3.8-flash-tts")
            .put("store", false)
            .put("input", JSONArray().put(turn))
            .put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/wav"))
            .put("generation_config", JSONObject().put("speech_config", speech))
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/interactions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val steps = JSONObject(response).getJSONArray("steps")
            for (i in steps.length() - 1 downTo 0) {
                val parts = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    if (part.optString("type") == "audio") {
                        return@withContext Base64.decode(part.getString("data"), Base64.DEFAULT)
                    }
                }
            }
            error("Gemini did not return audio.")
        } finally {
            connection.disconnect()
        }
    }
}
