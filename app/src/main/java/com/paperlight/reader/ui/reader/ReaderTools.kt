package com.paperlight.reader.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

internal fun openDictionary(context: Context, word: String): Boolean = runCatching {
    val url = Uri.Builder().scheme("https").authority("en.wiktionary.org")
        .appendPath("wiki").appendPath(word.trim()).build()
    context.startActivity(Intent(Intent.ACTION_VIEW, url))
}.isSuccess

internal class ReaderSpeech(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var run = 0
    private var released = false
    var ready by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val engine = TextToSpeech(context.applicationContext) { status ->
        main.post {
            if (!released) {
                ready = status == TextToSpeech.SUCCESS
            }
        }
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { if (utteranceId == "$run:last") speaking = false }
            }
            @Deprecated("Deprecated Android callback")
            override fun onError(utteranceId: String?) {
                main.post {
                    if (utteranceId?.startsWith("$run:") == true) {
                        stop()
                        error = "Android could not read this text aloud."
                    }
                }
            }
        })
    }

    fun play(text: String, language: String) {
        if (released || text.isBlank()) return
        stop()
        error = null
        playOnDevice(text, language)
    }

    private fun playOnDevice(text: String, language: String) {
        if (!ready) {
            error = "Android text-to-speech is unavailable. Check your installed speech engine."
            return
        }
        if (text.isBlank()) return
        val locale = language.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val voice = engine.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                "notInstalled" !in it.features.orEmpty()
        }.maxByOrNull { if (it.locale == locale) 1 else 0 }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            error = "Install an offline ${locale.displayLanguage} voice in Android text-to-speech settings."
            return
        }
        val limit = minOf(1500, TextToSpeech.getMaxSpeechInputLength() - 1)
        var start = 0
        speaking = true
        while (start < text.length) {
            val end = speechChunkEnd(text, start, limit)
            val id = if (end == text.length) "$run:last" else "$run:$start"
            if (engine.speak(text.substring(start, end), TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                stop()
                error = "Android could not read this text aloud."
                return
            }
            start = end
        }
    }

    private fun speechChunkEnd(text: String, start: Int, limit: Int): Int {
        val maximum = minOf(start + limit, text.length)
        if (maximum == text.length) return maximum
        val space = text.lastIndexOf(' ', maximum)
        if (space > start) return space
        return if (Character.isHighSurrogate(text[maximum - 1])) maximum - 1 else maximum
    }

    fun clearError() { error = null }

    fun stop() {
        run++
        engine.stop()
        speaking = false
    }

    fun close() {
        released = true
        stop()
        engine.shutdown()
    }
}
