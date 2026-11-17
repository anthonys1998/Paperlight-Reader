package com.paperlight.reader.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {
    val books = repository.books.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val libraries = repository.libraries.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { viewModelScope.launch { repository.ensureDefaultLibrary() } }

    fun clearMessage(shown: String) {
        _message.compareAndSet(shown, null)
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = repository.import(uri)
            _message.value = result.fold(
                onSuccess = { "Imported ${it.fileName}" },
                onFailure = { it.message ?: "Import failed" }
            )
        }
    }

    fun updateProgress(bookId: String, progress: Float) {
        viewModelScope.launch { repository.updateProgress(bookId, progress) }
    }

    fun remove(book: BookEntity) {
        viewModelScope.launch { repository.remove(book) }
    }

    fun removeBooks(ids: List<String>) {
        viewModelScope.launch {
            runCatching { repository.removeBooks(ids) }
                .onFailure { _message.value = it.message ?: "Could not remove the selected books." }
        }
    }

    fun moveBooks(ids: List<String>, libraryId: String) {
        viewModelScope.launch {
            runCatching { repository.moveBooks(ids, libraryId) }
                .onSuccess { _message.value = "Moved ${ids.size} book${if (ids.size == 1) "" else "s"}." }
                .onFailure { _message.value = it.message ?: "Could not move the selected books." }
        }
    }

    fun createLibrary(name: String) {
        viewModelScope.launch {
            repository.createLibrary(name).fold(
                onSuccess = { _message.value = "Created ${it.name}." },
                onFailure = { _message.value = it.message ?: "Could not create this library." }
            )
        }
    }

    fun createEpubFromUrl(
        url: String,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        libraryId: String = "default"
    ) {
        viewModelScope.launch {
            _message.value = "Downloading chapters…"
            repository.createEpubFromUrl(url, options, libraryId).fold(
                onSuccess = { _message.value = "Created ${it.title}.epub" },
                onFailure = { _message.value = it.message ?: "Could not create an EPUB from this page." }
            )
        }
    }

    companion object {
        fun factory(repository: BookRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(repository) as T
                }
            }
    }
}
