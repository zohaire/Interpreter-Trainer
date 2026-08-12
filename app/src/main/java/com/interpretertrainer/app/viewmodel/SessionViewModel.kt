package com.interpretertrainer.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.interpretertrainer.app.data.database.PracticeSessionEntity
import com.interpretertrainer.app.data.repository.SessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionViewModel(private val repository: SessionRepository) : ViewModel() {
    val sessions = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    fun save(session: PracticeSessionEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch { onSaved(repository.save(session)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    class Factory(private val repository: SessionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SessionViewModel(repository) as T
        }
    }
}
