package com.interpretertrainer.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.interpretertrainer.app.data.database.PracticeLibraryEntity
import com.interpretertrainer.app.data.repository.PracticeLibraryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PracticeLibraryViewModel(private val repository: PracticeLibraryRepository) : ViewModel() {
    val items = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    fun addFile(title: String, uri: String) = viewModelScope.launch {
        repository.importPersonal(title, mediaUri = uri, sourceUrl = null)
    }

    fun addLink(title: String, url: String) = viewModelScope.launch {
        repository.importPersonal(title, mediaUri = null, sourceUrl = url)
    }

    fun toggleFavorite(item: PracticeLibraryEntity) = viewModelScope.launch {
        repository.setFavorite(item.id, !item.isFavorite)
    }

    fun markOpened(item: PracticeLibraryEntity) = viewModelScope.launch {
        repository.markOpened(item.id)
    }

    fun markCompleted(item: PracticeLibraryEntity) = viewModelScope.launch {
        repository.updateProgress(item.id, item.durationMillis, completed = true)
    }

    fun deletePersonal(item: PracticeLibraryEntity) = viewModelScope.launch {
        repository.deletePersonal(item.id)
    }

    class Factory(private val repository: PracticeLibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PracticeLibraryViewModel(repository) as T
    }
}
