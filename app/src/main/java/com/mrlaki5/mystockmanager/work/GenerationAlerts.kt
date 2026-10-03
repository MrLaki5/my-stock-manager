package com.mrlaki5.mystockmanager.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** One reason generation stopped, and the images it stopped. */
data class GenerationFailure(val reason: String, val imageIds: Set<Long>)

/**
 * Failures from background generation, held until the user dismisses them. In memory only:
 * if the process dies first, the images are still safely back in their previous state.
 */
@Singleton
class GenerationAlerts @Inject constructor() {

    private val _failures = MutableStateFlow<List<GenerationFailure>>(emptyList())
    val failures: StateFlow<List<GenerationFailure>> = _failures.asStateFlow()

    /** A batch fails image by image, so the same reason is merged into one entry. */
    fun report(reason: String, imageIds: Set<Long>) {
        _failures.update { current ->
            val existing = current.indexOfFirst { it.reason == reason }
            if (existing < 0) {
                current + GenerationFailure(reason, imageIds)
            } else {
                current.toMutableList().also { it[existing] = it[existing].copy(imageIds = it[existing].imageIds + imageIds) }
            }
        }
    }

    fun dismiss() {
        _failures.value = emptyList()
    }
}
