package com.mrlaki5.mystockmanager.work

import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.data.repository.StockRepository
import com.mrlaki5.mystockmanager.generation.GenerationProvider
import com.mrlaki5.mystockmanager.ondevice.OnDeviceModelStore
import javax.inject.Inject
import javax.inject.Singleton

/** Shared by every Generate button, so the event grid and the image screen refuse for the same reasons. */
@Singleton
class GenerationQueue @Inject constructor(
    private val repository: StockRepository,
    private val workScheduler: WorkScheduler,
    private val keyStore: SecureKeyStore,
    private val networkStatus: NetworkStatus,
    private val modelStore: OnDeviceModelStore,
) {

    /** Why generation cannot start right now, or null if it can. */
    fun blocker(): String? = when (keyStore.provider) {
        GenerationProvider.OPENAI -> when {
            !keyStore.hasApiKey -> "Add your OpenAI API key in Settings first."
            !networkStatus.isOnline() -> "No internet connection. Connect and try again."
            else -> null
        }
        GenerationProvider.ON_DEVICE -> if (modelStore.isReady) null else "Download the on-device model in Settings first."
    }

    /** [hint] is optional; blank means the model works from the pixels alone. Returns the message to show. */
    suspend fun enqueue(eventId: Long?, imageIds: List<Long>, hint: String?): String {
        val trimmed = hint?.trim()?.takeIf { it.isNotEmpty() }
        if (eventId != null) repository.setEventHint(eventId, trimmed)
        workScheduler.enqueueGeneration(imageIds, trimmed, keyStore.provider)
        return buildString {
            append("Queued ${imageIds.size} image${if (imageIds.size == 1) "" else "s"}")
            if (trimmed != null) append(" · hint: $trimmed")
        }
    }
}
