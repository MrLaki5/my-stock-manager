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

    // Eagerly rather than WhileSubscribed: the latter only starts the query once the
    // composable subscribes, measured at ~150ms after this ViewModel was built. Starting
    // at construction overlaps the read with composition rather than queueing it behind,
    // which is worth roughly that much on entry. It does not make the read free — the
    // screen still shows a spinner until the first emission.
    val event: StateFlow<FolderEntity?> = repository.observeEvent(eventId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Null means "not read yet", which is deliberately distinct from an empty list: only
     * the latter is a genuinely empty event, and only it should show the empty state.
     */
    val images: StateFlow<List<ImageEntity>?> = repository.observeImages(eventId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val loadedImages: List<ImageEntity> get() = images.value.orEmpty()

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
        _selection.value = loadedImages.map { it.id }.toSet()
    }

    /** Selects only what Generate would actually act on — the ungenerated and the failed. */
    fun selectUngenerated() {
        _selection.value = loadedImages
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

    /**
     * Removes the selected images and their album copies. Generation is cancelled first:
     * a worker that started in between would only rewrite a file that is about to go.
     */
    fun deleteSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            workScheduler.cancelGeneration(ids)
            val summary = repository.deleteImages(ids)
            _selection.value = emptySet()
            _busy.value = false
            _message.value = buildString {
                append("Deleted ${summary.deleted} image${if (summary.deleted == 1) "" else "s"}")
                if (summary.albumFilesLeft > 0) {
                    append(" · ${summary.albumFilesLeft} could not be removed from the album")
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
