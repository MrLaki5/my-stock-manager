package com.mrlaki5.mystockmanager.ui.events

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import com.mrlaki5.mystockmanager.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repository: StockRepository,
    private val workScheduler: WorkScheduler,
    private val keyStore: SecureKeyStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val eventId: Long = checkNotNull(savedStateHandle.get<Long>("eventId"))

    val event: StateFlow<FolderEntity?> = repository.observeEvent(eventId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val images: StateFlow<List<ImageEntity>> = repository.observeImages(eventId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun albumNameFor(eventName: String): String = repository.albumNameFor(eventName)

    fun toggleSelection(imageId: Long) {
        _selection.value = _selection.value.let {
            if (imageId in it) it - imageId else it + imageId
        }
    }

    fun selectAll() {
        _selection.value = images.value.map { it.id }.toSet()
    }

    /** Selects only what Generate would actually act on — the ungenerated and the failed. */
    fun selectUngenerated() {
        _selection.value = images.value
            .filter { it.state == ImageState.FILED || it.state == ImageState.GENERATION_FAILED }
            .map { it.id }
            .toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val summary = repository.importInto(eventId, uris)
            _busy.value = false
            _message.value = buildString {
                append("Imported ${summary.imported} of ${summary.total}")
                if (summary.duplicates > 0) append(" · ${summary.duplicates} already here")
                if (summary.failed > 0) append(" · ${summary.failed} failed")
            }
        }
    }

    /** [location] is optional; blank means "tell the model nothing about place". */
    fun generateSelected(location: String?) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        if (!keyStore.hasApiKey) {
            _message.value = "Add your OpenAI API key in Settings first."
            return
        }
        val trimmed = location?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch { repository.setEventLocation(eventId, trimmed) }

        workScheduler.enqueueGeneration(ids, trimmed)
        _selection.value = emptySet()
        _message.value = buildString {
            append("Queued ${ids.size} image${if (ids.size == 1) "" else "s"}")
            if (trimmed != null) append(" · location: $trimmed")
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
