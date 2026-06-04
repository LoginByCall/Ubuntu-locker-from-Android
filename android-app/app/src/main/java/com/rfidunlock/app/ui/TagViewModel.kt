package com.rfidunlock.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfidunlock.app.data.Tag
import com.rfidunlock.app.data.TagRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagViewModel(private val repository: TagRepository) : ViewModel() {

    val tags: StateFlow<List<Tag>> = repository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun registerOrRename(uid: String, name: String) = viewModelScope.launch {
        repository.registerOrRename(uid, name)
    }

    fun setEnabled(tag: Tag, enabled: Boolean) = viewModelScope.launch {
        repository.setEnabled(tag, enabled)
    }

    fun delete(tag: Tag) = viewModelScope.launch {
        repository.delete(tag)
    }

    class Factory(private val repository: TagRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TagViewModel(repository) as T
    }
}
