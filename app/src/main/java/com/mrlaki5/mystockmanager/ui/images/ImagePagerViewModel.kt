package com.mrlaki5.mystockmanager.ui.images

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ImagePagerViewModel @Inject constructor(
    repository: StockRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The image the screen was opened on. */
    val openedId: Long = checkNotNull(savedStateHandle.get<Long>("imageId"))

    /** The event's image ids in the grid's order; null until read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val ids: StateFlow<List<Long>?> = repository.observeImage(openedId)
        .filterNotNull()
        .map { it.folderId }
        .distinctUntilChanged()
        .flatMapLatest { folderId ->
            if (folderId == null) flowOf(listOf(openedId))
            else repository.observeImages(folderId).map { images -> images.map { it.id } }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
