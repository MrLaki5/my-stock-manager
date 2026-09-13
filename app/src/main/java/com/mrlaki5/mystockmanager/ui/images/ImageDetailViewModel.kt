package com.mrlaki5.mystockmanager.ui.images

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import com.mrlaki5.mystockmanager.data.repository.StockRepository
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

/** The editable copy of an image's metadata, held apart from the row it came from. */
data class MetadataDraft(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val category: String,
) {
    fun toMetadata() = StockMetadata(
        title = title,
        description = description,
        keywords = keywords,
        category = category.trim().takeIf { it.isNotEmpty() },
    )

    /**
     * Embedding an empty title or description writes an IPTC record that reads back as
     * absent rather than empty, which then fails verification. Requiring both up front
     * turns that into a disabled button instead of a confusing save failure.
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
        viewModelScope.launch { seed(repository.observeImage(imageId).filterNotNull().first()) }
    }

    private fun seed(image: ImageEntity) {
        val draft = MetadataDraft.of(image)
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
            repository.updateMetadata(imageId, draft.toMetadata())
                .onSuccess { written ->
                    // Seed from what was written, not from the draft: the agency field
                    // limits may have clamped it, and the screen should show the truth.
                    val saved = MetadataDraft(
                        title = written.title,
                        description = written.description,
                        keywords = written.keywords,
                        category = written.category.orEmpty(),
                    )
                    _draft.value = saved
                    _saved.value = saved
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
