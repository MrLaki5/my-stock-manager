package com.mrlaki5.mystockmanager.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.db.dao.FolderSummary
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventListViewModel @Inject constructor(
    private val repository: StockRepository,
    nextcloud: NextcloudSettings,
) : ViewModel() {

    val events: StateFlow<List<FolderSummary>> = repository.observeEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncEnabled: StateFlow<Boolean> = nextcloud.enabledOffMain()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun createEvent(name: String) = viewModelScope.launch {
        repository.createEvent(name)
            .onSuccess { _message.value = "Created \"${name.trim()}\"" }
            .onFailure { _message.value = it.message }
    }

    fun renameEvent(id: Long, name: String) = viewModelScope.launch {
        repository.renameEvent(id, name).onFailure { _message.value = it.message }
    }

    fun reorderEvents(orderedIds: List<Long>) = viewModelScope.launch {
        repository.reorderEvents(orderedIds)
    }

    fun deleteEvent(id: Long) = viewModelScope.launch {
        repository.deleteEvent(id)
        _message.value = "Event deleted"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
