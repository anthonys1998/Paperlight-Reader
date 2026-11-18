package com.paperlight.reader.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.PdfEpubMode
import java.io.File
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NovelDownloadProgress(val completed: Int, val total: Int, val label: String)

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

    init {
        viewModelScope.launch {
            try {
                repository.ensureDefaultLibrary()
                repository.refreshMetadata()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not initialize the library."
            }
        }
    }

    fun clearMessage(shown: String) {
        _message.compareAndSet(shown, null)
    }

    private val _libraryBusy = MutableStateFlow(false)
    val libraryBusy = _libraryBusy.asStateFlow()

    fun importBooks(uris: List<Uri>, libraryId: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var imported = 0
            var lastFileName = ""
            var firstError: String? = null
            for (uri in uris) {
                repository.import(uri, libraryId).fold(
                    onSuccess = { imported++; lastFileName = it.fileName },
                    onFailure = {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        if (firstError == null) firstError = it.message ?: "Import failed"
                    },
                )
            }
            val failed = uris.size - imported
            _message.value = when {
                imported == 0 -> firstError ?: "Import failed"
                failed > 0 -> "Imported $imported of ${uris.size} books. $firstError"
                imported == 1 -> "Imported $lastFileName"
                else -> "Imported $imported books"
            }
        }
    }

    fun importDownloadedEpub(file: File, title: String, author: String, libraryId: String) {
        viewModelScope.launch {
            try {
                repository.importGeneratedEpub(file, title, author, libraryId).fold(
                    onSuccess = { _message.value = "Imported ${it.fileName}" },
                    onFailure = { _message.value = it.message ?: "Could not import the downloaded EPUB." },
                )
            } finally {
                file.delete()
            }
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

    fun moveBooks(ids: List<String>, libraryId: String, onComplete: (Boolean) -> Unit) {
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.moveBooks(ids, libraryId)
                _message.value = "Moved ${ids.size} book${if (ids.size == 1) "" else "s"}."
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not move the selected books."
                onComplete(false)
            } finally { _libraryBusy.value = false }
        }
    }

    fun saveLibrary(id: String?, name: String, onComplete: (String?, String?) -> Unit) {
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.saveLibrary(id, name).fold(
                    onSuccess = { onComplete(it.id, null) },
                    onFailure = { onComplete(null, it.message ?: "Could not save this library.") }
                )
            } finally { _libraryBusy.value = false }
        }
    }

    fun deleteLibrary(id: String, onComplete: (Boolean) -> Unit) {
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.deleteLibrary(id)
                onComplete(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Could not delete this library."
                onComplete(false)
            } finally { _libraryBusy.value = false }
        }
    }

    fun backup(uri: Uri, restore: Boolean) {
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                if (restore) {
                    val count = repository.restoreBackup(uri)
                    _message.value = "Restored $count book${if (count == 1) "" else "s"}."
                } else {
                    repository.exportBackup(uri)
                    _message.value = "Backup saved."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Backup operation failed."
            } finally { _libraryBusy.value = false }
        }
    }

    private var downloadJob: kotlinx.coroutines.Job? = null
    private val _downloadProgress = MutableStateFlow<NovelDownloadProgress?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()

    fun cancelDownload() { downloadJob?.cancel() }

    private var conversionJob: kotlinx.coroutines.Job? = null
    private val _conversionProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val conversionProgress = _conversionProgress.asStateFlow()

    fun cancelConversion() { conversionJob?.cancel() }

    fun convertPdf(source: File, fallbackTitle: String, mode: PdfEpubMode, libraryId: String) {
        if (conversionJob?.isActive == true) return
        _conversionProgress.value = 0 to 0
        conversionJob = viewModelScope.launch {
            try {
                repository.convertPdfToEpub(source, fallbackTitle, mode, libraryId) { completed, total ->
                    _conversionProgress.value = completed to total
                }.fold(
                    onSuccess = { _message.value = "Created ${it.title}.epub" },
                    onFailure = { _message.value = it.message ?: "Could not convert this PDF." },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _message.value = "Conversion cancelled."
                throw cancelled
            } finally {
                _conversionProgress.value = null
            }
        }
    }

    fun createEpubFromUrl(
        url: String,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        libraryId: String = "default"
    ) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                repository.createEpubFromUrl(url, options, libraryId) { completed, total, label ->
                    _downloadProgress.value = NovelDownloadProgress(completed, total, label)
                }.fold(
                    onSuccess = { _message.value = "Created ${it.title}.epub" },
                    onFailure = { _message.value = it.message ?: "Could not create an EPUB from this page." }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _message.value = "Download cancelled."
                throw cancelled
            } finally { _downloadProgress.value = null }
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
