package com.mrlaki5.mystockmanager.ui.images

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import com.mrlaki5.mystockmanager.metadata.model.EditorialCaption
import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The editable copy of an image's metadata.
 *
 * [description] is the caption *body*, which is what the row stores. The location and date
 * are prepended when it is written — see [ImageDetailViewModel.caption].
 */
data class MetadataDraft(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val category: String,
) {
    /**
     * Embedding an empty title or description writes IPTC records that read back as absent
     * rather than empty, which then fails verification. An empty body would also leave the
     * caption as a bare "Belgrade, Serbia - May 23, 2026:".
     */
    val canSave: Boolean get() = title.isNotBlank() && description.isNotBlank()

    companion object {
        fun of(image: ImageEntity) = MetadataDraft(
            title = image.title.orEmpty(),
            description = image.description.orEmpty(),
            keywords = image.keywords,
            category = image.category.orEmpty(),
        )
    }
}

/** The two facts the caption is prefixed with, neither of which is edited on this screen. */
private data class CaptionLead(val location: String?, val capturedOn: String?)

@HiltViewModel
class ImageDetailViewModel @Inject constructor(
    private val repository: StockRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val imageId: Long = checkNotNull(savedStateHandle.get<Long>("imageId"))

    val image: StateFlow<ImageEntity?> = repository.observeImage(imageId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _draft = MutableStateFlow<MetadataDraft?>(null)
    val draft: StateFlow<MetadataDraft?> = _draft.asStateFlow()

    /** What is currently on disk, so [dirty] knows what an edit is being compared against. */
    private val _saved = MutableStateFlow<MetadataDraft?>(null)

    private val _lead = MutableStateFlow<CaptionLead?>(null)

    /**
     * The description exactly as saving would write it into the file. Recomputed as the body
     * is typed rather than fetched, so the screen shows the real caption live; the
     * repository assembles it again from the same inputs when it writes, so the two cannot
     * disagree.
     */
    val caption: StateFlow<String> = combine(_draft, _lead) { draft, lead ->
        if (draft == null) "" else EditorialCaption.build(
            location = lead?.location,
            capturedOn = lead?.capturedOn,
            body = draft.description,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val dirty: StateFlow<Boolean> = combine(_draft, _saved) { draft, saved ->
        draft != null && draft != saved
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Seeded once, from the first row that arrives. Later emissions are not folded in:
        // one of them is this screen's own save, and re-seeding from any of them would
        // throw away edits the user is still in the middle of making.
        viewModelScope.launch {
            val image = repository.observeImage(imageId).filterNotNull().first()
            _lead.value = CaptionLead(repository.locationFor(image), image.capturedOn)
            seed(MetadataDraft.of(image))
        }
    }

    private fun seed(draft: MetadataDraft) {
        _draft.value = draft
        _saved.value = draft
    }

    fun setTitle(value: String) = edit { it.copy(title = value) }

    fun setDescription(value: String) = edit { it.copy(description = value) }

    fun setCategory(value: String) = edit { it.copy(category = value) }

    /** Duplicates are dropped case-insensitively, matching how [StockMetadata] normalizes. */
    fun addKeyword(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        val current = _draft.value ?: return
        if (current.keywords.any { it.equals(keyword, ignoreCase = true) }) {
            _message.value = "\"$keyword\" is already in the list"
            return
        }
        if (current.keywords.size >= MAX_KEYWORDS) {
            _message.value = "That is the $MAX_KEYWORDS keyword limit both agencies enforce"
            return
        }
        edit { it.copy(keywords = it.keywords + keyword) }
    }

    fun updateKeyword(index: Int, raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return removeKeyword(index)
        val current = _draft.value ?: return
        if (index !in current.keywords.indices) return
        if (current.keywords.filterIndexed { i, _ -> i != index }
                .any { it.equals(keyword, ignoreCase = true) }
        ) {
            _message.value = "\"$keyword\" is already in the list"
            return
        }
        edit { it.copy(keywords = it.keywords.toMutableList().apply { this[index] = keyword }) }
    }

    fun removeKeyword(index: Int) = edit {
        if (index !in it.keywords.indices) it
        else it.copy(keywords = it.keywords.filterIndexed { i, _ -> i != index })
    }

    /** Relevance order is meaningful to both agencies, so it has to be reorderable. */
    fun moveKeyword(index: Int, by: Int) = edit {
        val target = index + by
        if (index !in it.keywords.indices || target !in it.keywords.indices) it
        else it.copy(
            keywords = it.keywords.toMutableList().apply { add(target, removeAt(index)) },
        )
    }

    fun discard() {
        _draft.value = _saved.value
    }

    fun save() {
        val draft = _draft.value ?: return
        if (!draft.canSave || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val request = StockMetadata(
                title = draft.title,
                // The body. The repository prepends the location and date.
                description = draft.description,
                keywords = draft.keywords,
                category = draft.category.trim().takeIf { it.isNotEmpty() },
            )
            repository.updateMetadata(imageId, request)
                .onSuccess { written ->
                    // Seed from what was written, not from the draft: the agency field
                    // limits may have clamped it, and the screen should show the truth.
                    seed(
                        MetadataDraft(
                            title = written.title,
                            description = written.description,
                            keywords = written.keywords,
                            category = written.category.orEmpty(),
                        )
                    )
                    _message.value = "Saved and written into the image"
                }
                .onFailure { _message.value = it.message ?: "Could not save" }
            _busy.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private inline fun edit(block: (MetadataDraft) -> MetadataDraft) {
        _draft.value = _draft.value?.let(block)
    }
}
