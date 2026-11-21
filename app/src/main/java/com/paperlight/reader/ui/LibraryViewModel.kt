package com.paperlight.reader.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.PdfEpubMode
import java.io.File
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import com.paperlight.reader.webnovel.WebNovelExportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: BookRepository, private val context: Context) : ViewModel() {
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

    private val _libraryBusy = MutableStateFlow(true)
    val libraryBusy = _libraryBusy.asStateFlow()
    private val _backupRecoveryBlocked = MutableStateFlow(true)
    val backupRecoveryBlocked = _backupRecoveryBlocked.asStateFlow()

    init {
        viewModelScope.launch {
            WebNovelExportService.downloadResult.collect { message ->
                if (message != null && WebNovelExportService.consumeResult(message)) {
                    _message.value = message
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.recoverInterruptedBackupRestore()
                repository.ensureDefaultLibrary()
                repository.refreshMetadata()
                _backupRecoveryBlocked.value = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _backupRecoveryBlocked.value = true
                _message.value = failure.message ?: "Could not initialize the library."
            } finally {
                _libraryBusy.value = false
            }
        }
    }

    fun clearMessage(shown: String) {
        _message.compareAndSet(shown, null)
    }

    private fun recoveryAllowsLibraryAction(): Boolean {
        if (_backupRecoveryBlocked.value) {
            _message.value = "Finish backup recovery before using the library."
            return false
        }
        return true
    }

    fun retryBackupRecovery() {
        if (!_backupRecoveryBlocked.value || _libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                repository.recoverInterruptedBackupRestore()
                repository.ensureDefaultLibrary()
                _backupRecoveryBlocked.value = false
                _message.value = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _message.value = failure.message ?: "Backup recovery could not finish."
            } finally {
                _libraryBusy.value = false
            }
        }
    }

    fun importBooks(uris: List<Uri>, libraryId: String) {
        if (uris.isEmpty() || !recoveryAllowsLibraryAction()) return
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
        if (!recoveryAllowsLibraryAction()) {
            file.delete()
            return
        }
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
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch { repository.updateProgress(bookId, progress) }
    }

    fun remove(book: BookEntity) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch { repository.remove(book) }
    }

    fun removeBooks(ids: List<String>) {
        if (!recoveryAllowsLibraryAction()) return
        viewModelScope.launch {
            runCatching { repository.removeBooks(ids) }
                .onFailure { _message.value = it.message ?: "Could not remove the selected books." }
        }
    }

    fun moveBooks(ids: List<String>, libraryId: String, onComplete: (Boolean) -> Unit) {
        if (!recoveryAllowsLibraryAction()) {
            onComplete(false)
            return
        }
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
        if (!recoveryAllowsLibraryAction()) {
            onComplete(null, "Finish backup recovery before using the library.")
            return
        }
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
        if (!recoveryAllowsLibraryAction()) {
            onComplete(false)
            return
        }
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

    fun backup(
        uri: Uri,
        restore: Boolean,
        includeApiKey: Boolean = false,
        password: String? = null,
        replaceExistingSettings: Boolean = false,
        replaceExistingGroqKey: Boolean = false,
    ) {
        if (!recoveryAllowsLibraryAction()) return
        if (_libraryBusy.value) return
        _libraryBusy.value = true
        viewModelScope.launch {
            try {
                if (restore) {
                    val count = repository.restoreBackup(uri, password, replaceExistingSettings, replaceExistingGroqKey)
                    _message.value = "Backup restore complete; added $count book${if (count == 1) "" else "s"}."
                } else {
                    repository.exportBackup(uri, includeApiKey, password)
                    _message.value = "Backup saved."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (restore && repository.backupRestorePending()) _backupRecoveryBlocked.value = true
                throw cancelled
            } catch (failure: Exception) {
                if (restore && repository.backupRestorePending()) _backupRecoveryBlocked.value = true
                _message.value = failure.message ?: "Backup operation failed."
            } finally { _libraryBusy.value = false }
        }
    }

    val downloadProgress = WebNovelExportService.downloadProgress

    fun cancelDownload() {
        try {
            WebNovelExportService.cancel(context)
        } catch (failure: Exception) {
            _message.value = failure.message ?: "Could not cancel the export."
        }
    }

    private var conversionJob: kotlinx.coroutines.Job? = null
    private val _conversionProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val conversionProgress = _conversionProgress.asStateFlow()

    fun cancelConversion() { conversionJob?.cancel() }

    fun convertPdf(source: File, fallbackTitle: String, mode: PdfEpubMode, libraryId: String) {
        if (!recoveryAllowsLibraryAction()) return
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
        if (!recoveryAllowsLibraryAction()) return
        if (downloadProgress.value != null) return
        try {
            WebNovelExportService.start(context, url, options, libraryId)
        } catch (failure: Exception) {
            _message.value = failure.message ?: "Could not start the background export."
        }
    }

    companion object {
        fun factory(repository: BookRepository, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(repository, context.applicationContext) as T
                }
            }
    }
}
