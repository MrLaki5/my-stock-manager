package com.mrlaki5.mystockmanager.work

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.AlbumState
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.db.entity.MediaKind
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.generation.GenerationProvider
import com.mrlaki5.mystockmanager.ondevice.OnDeviceClient
import com.mrlaki5.mystockmanager.ondevice.OnDeviceModel
import com.mrlaki5.mystockmanager.ondevice.OnDeviceModelStore
import com.mrlaki5.mystockmanager.openai.OpenAiClient
import com.mrlaki5.mystockmanager.generation.GenerationResult
import com.mrlaki5.mystockmanager.openai.VisionPrompt
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImageEncoder
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Generates metadata for exactly one image or clip, a clip from its middle frame. The album
 * file is only read; metadata lives in the row and reaches the agencies through the event's CSV.
 *
 * One worker per image, not one per batch: that is what gives partial-failure
 * isolation, per-image progress, and independent retry. A failure here marks its own
 * row and leaves every sibling untouched.
 *
 * Any failure puts the image back in the state it had before generation and is reported
 * through [GenerationAlerts]; it is never left on GENERATING.
 */
@HiltWorker
class GenerateMetadataWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val imageDao: ImageDao,
    private val fileStore: AppFileStore,
    private val mediaStore: MediaStoreExporter,
    private val keyStore: SecureKeyStore,
    private val openAiClient: OpenAiClient,
    private val onDeviceClient: OnDeviceClient,
    private val modelStore: OnDeviceModelStore,
    private val workScheduler: WorkScheduler,
    private val alerts: GenerationAlerts,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val imageId = inputData.getLong(KEY_IMAGE_ID, -1L)
        val hint = inputData.getString(KEY_HINT)?.takeIf { it.isNotBlank() }
        val provider = GenerationProvider.of(inputData.getString(KEY_PROVIDER).orEmpty())
        if (imageId <= 0) return Result.failure()

        val result = try {
            generate(imageId, hint, provider)
        } catch (e: CancellationException) {
            // Cancelled by delete, by a sibling that hit an account-wide error, or by the system.
            withContext(NonCancellable) { imageDao.restoreAfterFailedGeneration(imageId, "Generation was stopped") }
            throw e
        } catch (e: Exception) {
            giveUp(imageId, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
        // On-device jobs run as one chain, where a failure would fail every image after it.
        // This image's failure is already on its row and in the alerts.
        return if (provider == GenerationProvider.ON_DEVICE && result is Result.Failure) Result.success() else result
    }

    private suspend fun generate(imageId: Long, hint: String?, provider: GenerationProvider): Result {
        val image = imageDao.getById(imageId) ?: return Result.failure()
        val mediaUri = image.mediaStoreUri?.toUri()
            ?: return giveUp(imageId, "Image is not in the album")
        if (image.albumState == AlbumState.TRASHED) {
            return giveUp(imageId, "Image is in the trash. Tap it in the event to restore it first.")
        }
        if (!mediaStore.exists(mediaUri)) {
            return giveUp(imageId, "Image was removed from the album")
        }

        imageDao.updateState(imageId, ImageState.GENERATING)

        val apiKey = keyStore.apiKey
        val onDeviceModel = modelStore.readyFile()
        when (provider) {
            GenerationProvider.OPENAI -> if (apiKey.isBlank()) {
                return giveUp(imageId, "No OpenAI API key set. Add one in Settings.", stopsBatch = true)
            }
            GenerationProvider.ON_DEVICE -> if (onDeviceModel == null) {
                return giveUp(imageId, "The on-device model is not downloaded. Download it in Settings.", stopsBatch = true)
            }
        }
        val model = keyStore.model
        val modelId = if (provider == GenerationProvider.ON_DEVICE) OnDeviceModel.ID else model.id
        val systemPrompt = keyStore.systemPrompt.ifBlank { VisionPrompt.DEFAULT_SYSTEM }

        val jpeg = when (image.mediaKind) {
            MediaKind.IMAGE -> fileStore.withTempFile("gen-src") { source ->
                runCatching { mediaStore.copyTo(mediaUri, source) }
                    .getOrElse { return giveUp(imageId, "Could not read image: ${it.message}") }
                runCatching { ImageEncoder.toJpeg(source) }
                    .getOrElse { return giveUp(imageId, "Could not decode image: ${it.message}") }
            }
            MediaKind.VIDEO -> runCatching { mediaStore.withDescriptor(mediaUri, ImageEncoder::videoFrameToJpeg) }
                .getOrElse { return giveUp(imageId, "Could not read video: ${it.message}") }
        }

        val result = when (provider) {
            GenerationProvider.OPENAI -> {
                val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                openAiClient.generate(apiKey, model.id, model.effective(keyStore.reasoningEffort), encoded, hint, systemPrompt, image.mediaKind)
            }
            GenerationProvider.ON_DEVICE -> onDeviceClient.generate(onDeviceModel!!, jpeg, hint, image.mediaKind)
        }
        return when (result) {
            is GenerationResult.Transient -> if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                // Stays GENERATING: a retry really is still in flight.
                imageDao.markFailed(imageId, ImageState.GENERATING, result.message)
                Result.retry()
            } else {
                giveUp(imageId, result.message)
            }

            is GenerationResult.Terminal -> giveUp(imageId, result.message, result.stopsBatch)

            // Once the model has answered, save it even if cancelled.
            is GenerationResult.Success -> withContext(NonCancellable) {
                // Only a hint can name the place; a model guessing one from pixels is ignored.
                val place = result.place.takeIf { hint != null }
                // The row keeps the caption body; the CSV prepends the lead when it is written.
                val written = result.metadata.normalized()
                imageDao.saveGenerated(
                    id = imageId,
                    title = written.title,
                    description = written.description,
                    keywords = written.keywords,
                    category = written.category,
                    state = ImageState.GENERATED,
                    generatedAt = System.currentTimeMillis(),
                    model = modelId,
                    captionPlace = place.orEmpty(),
                )
                Result.success()
            }
        }
    }

    /** [stopsBatch] cancels the rest of the queue too, since every other image would fail the same way. */
    private suspend fun giveUp(imageId: Long, reason: String, stopsBatch: Boolean = false): Result {
        imageDao.restoreAfterFailedGeneration(imageId, reason)
        val stopped = if (stopsBatch) workScheduler.cancelOtherGeneration(id) else emptySet()
        alerts.report(reason, stopped + imageId)
        return Result.failure()
    }

    companion object {
        const val KEY_IMAGE_ID = "imageId"
        const val KEY_HINT = "hint"
        const val KEY_PROVIDER = "provider"

        /** Network and rate-limit retries, so a lasting outage ends in a message rather than a spinner. */
        private const val MAX_ATTEMPTS = 3
    }
}
