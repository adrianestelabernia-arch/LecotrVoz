package com.lectorvoz.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ReaderEngine.repo
    private val importer = Importer(app, repo)

    val docs = MutableStateFlow<List<DocItem>>(emptyList())
    val folders = MutableStateFlow<List<String>>(emptyList())
    val importStatus = MutableStateFlow<ImportStatus?>(null)
    val error = MutableStateFlow<String?>(null)
    private var importJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            docs.value = repo.listDocs().map {
                DocItem(it.id, it.title, it.format, repo.folderOf(it.id), repo.percent(it.id), it.total)
            }
            folders.value = repo.folders()
        }
    }

    fun import(uri: Uri, folder: String) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            importStatus.value = ImportStatus("Preparando…", 0f)
            try {
                val meta = importer.import(uri) { m, p -> importStatus.value = ImportStatus(m, p) }
                if (folder.isNotEmpty()) repo.setFolder(meta.id, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = e.message ?: "Error al importar el archivo"
            } finally {
                importStatus.value = null
                refresh()
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }
    fun clearError() { error.value = null }

    fun addFolder(name: String) { repo.addFolder(name); refresh() }
    fun deleteFolder(name: String) { repo.deleteFolder(name); refresh() }
    fun move(id: String, folder: String) { repo.setFolder(id, folder); refresh() }

    fun delete(id: String) {
        ReaderEngine.closeIfLoaded(id)
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteDoc(id)
            refresh()
        }
    }
}
