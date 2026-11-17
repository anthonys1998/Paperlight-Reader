package com.paperlight.reader.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
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

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

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

    fun updateProgress(bookId: String, progress: Float, page: Int, total: Int) {
        viewModelScope.launch { repository.updateProgress(bookId, progress, page, total) }
    }

    fun remove(book: BookEntity) {
        viewModelScope.launch { repository.remove(book) }
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
