package com.mrlaki5.mystockmanager.ui.events

import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.db.entity.AlbumState
import com.mrlaki5.mystockmanager.data.db.entity.FolderEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import com.mrlaki5.mystockmanager.nextcloud.CloudMark
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import com.mrlaki5.mystockmanager.storage.ImportSummary
import com.mrlaki5.mystockmanager.storage.OriginalTrash
import com.mrlaki5.mystockmanager.storage.OriginalTrashRequest
import com.mrlaki5.mystockmanager.work.GenerationQueue
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
    private val generationQueue: GenerationQueue,
    private val originalTrash: OriginalTrash,
    nextcloud: NextcloudSettings,
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

    val syncEnabled: StateFlow<Boolean> = nextcloud.enabledOffMain()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val cloudMarks: StateFlow<Map<Long, CloudMark>> = repository.observeCloudMarks(eventId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** How many originals the app's own "remove the originals?" prompt is asking about. */
    private val _trashPrompt = MutableStateFlow<Int?>(null)
    val trashPrompt: StateFlow<Int?> = _trashPrompt.asStateFlow()
    private var pendingTrash: OriginalTrashRequest? = null

    /** The system "move to trash" dialog, until it is shown. */
    private val _trashRequest = MutableStateFlow<IntentSenderRequest?>(null)
    val trashRequest: StateFlow<IntentSenderRequest?> = _trashRequest.asStateFlow()

    /** Held back while the trash dialog is up, so the snackbar reports both steps at once. */
    private var importReport: String? = null
    private var trashCount = 0

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
            .filter { it.albumState == AlbumState.PRESENT }
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
            val request = if (keyStore.trashOriginalsAfterImport) originalTrash.request(summary.importedSources) else null
            _busy.value = false
            if (request == null) {
                _message.value = report(summary)
            } else {
                importReport = report(summary)
                trashCount = request.count
                pendingTrash = request
                _trashPrompt.value = request.count
            }
        }
    }

    /** Android's own dialog only says "move to trash", so the app explains why first. */
    fun answerTrashPrompt(remove: Boolean) {
        _trashPrompt.value = null
        val request = pendingTrash.also { pendingTrash = null }
        if (remove && request != null) {
            _trashRequest.value = IntentSenderRequest.Builder(request.intent).build()
        } else {
            onTrashResult(false)
        }
    }

    fun onTrashRequestShown() {
        _trashRequest.value = null
    }

    fun onTrashResult(trashed: Boolean) {
        val outcome = if (trashed) "$trashCount original${if (trashCount == 1) "" else "s"} moved to trash" else "originals kept"
        _message.value = listOfNotNull(importReport, outcome).joinToString(" · ")
        importReport = null
    }

    /** Silent and immediate: the app owns the file, so Android asks for no confirmation. */
    fun restore(imageId: Long) {
        viewModelScope.launch {
            _message.value = if (repository.restoreFromTrash(imageId)) "Restored from trash" else "Could not restore this image"
        }
    }

    private fun report(summary: ImportSummary) = buildString {
        append("Imported ${summary.imported} of ${summary.total}")
        if (summary.duplicates > 0) append(" · ${summary.duplicates} already here")
        if (summary.failed > 0) append(" · ${summary.failed} failed")
        if (summary.unsupported > 0) append(" · ${summary.unsupported} skipped, only MP4 videos are supported")
    }

    fun generateSelected(hint: String?) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        generationQueue.blocker()?.let {
            _message.value = it
            return
        }
        _selection.value = emptySet()
        viewModelScope.launch { _message.value = generationQueue.enqueue(eventId, ids, hint) }
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
