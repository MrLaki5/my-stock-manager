package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.metadata.MetadataEmbedder
import com.mrlaki5.mystockmanager.metadata.model.EditorialCaption
import com.mrlaki5.mystockmanager.openai.OpenAiClient
import com.mrlaki5.mystockmanager.openai.OpenAiResult
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
 * Generates and embeds metadata for exactly one image, rewriting it in place in the
 * album.
 *
 * One worker per image, not one per batch: that is what gives partial-failure
 * isolation, per-image progress, and independent retry. A failure here marks its own
 * row and leaves every sibling untouched.
 *
 * The album file is only overwritten after the embedded copy has been read back and
 * verified, so a failed embed can never damage the image the user already has.
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
    private val embedder: MetadataEmbedder,
    private val workScheduler: WorkScheduler,
    private val alerts: GenerationAlerts,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val imageId = inputData.getLong(KEY_IMAGE_ID, -1L)
        val hint = inputData.getString(KEY_HINT)?.takeIf { it.isNotBlank() }
        if (imageId <= 0) return Result.failure()

        return try {
            generate(imageId, hint)
        } catch (e: CancellationException) {
            // Cancelled by delete, by a sibling that hit an account-wide error, or by the system.
            withContext(NonCancellable) { imageDao.restoreAfterFailedGeneration(imageId, "Generation was stopped") }
            throw e
        } catch (e: Exception) {
            giveUp(imageId, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun generate(imageId: Long, hint: String?): Result {
        val image = imageDao.getById(imageId) ?: return Result.failure()
        val mediaUri = image.mediaStoreUri?.toUri()
            ?: return giveUp(imageId, "Image is not in the album")
        if (!mediaStore.exists(mediaUri)) {
            return giveUp(imageId, "Image was removed from the album")
        }

        imageDao.updateState(imageId, ImageState.GENERATING)

        val apiKey = keyStore.apiKey
        if (apiKey.isBlank()) return giveUp(imageId, "No OpenAI API key set. Add one in Settings.", stopsBatch = true)
        val model = keyStore.model
        val effort = model.effective(keyStore.reasoningEffort)
        val systemPrompt = keyStore.systemPrompt.ifBlank { VisionPrompt.DEFAULT_SYSTEM }

        val source = fileStore.newTempFile("gen-src")
        try {
            runCatching { mediaStore.copyTo(mediaUri, source) }
                .getOrElse { return giveUp(imageId, "Could not read image: ${it.message}") }

            val encoded = runCatching { ImageEncoder.toBase64Jpeg(source) }
                .getOrElse { return giveUp(imageId, "Could not decode image: ${it.message}") }

            return when (val result = openAiClient.generate(apiKey, model.id, effort, encoded, hint, systemPrompt)) {
                is OpenAiResult.Transient -> if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                    // Stays GENERATING: a retry really is still in flight.
                    imageDao.markFailed(imageId, ImageState.GENERATING, result.message)
                    Result.retry()
                } else {
                    giveUp(imageId, result.message)
                }

                is OpenAiResult.Terminal -> giveUp(imageId, result.message, result.stopsBatch)

                // Once OpenAI has answered, finish the write even if cancelled, so file and row agree.
                is OpenAiResult.Success -> withContext(NonCancellable) {
                    // The model writes the caption body; the app owns the caption's shape.
                    // Prepending the lead here rather than asking for it is what makes the
                    // format guaranteed instead of merely requested.
                    val body = result.metadata.description
                    val captioned = result.metadata.copy(
                        description = EditorialCaption.build(
                            location = result.place,
                            capturedOn = image.capturedOn,
                            body = body,
                        ),
                    )

                    // The embedder verifies the round trip before overwriting, and hands
                    // back what it actually wrote: the clamped values, so the row and the
                    // file cannot disagree about field limits.
                    val written = embedder.embedFrom(source, mediaUri, captioned)
                        .getOrElse { return@withContext giveUp(imageId, it.message ?: "Embedding failed") }

                    val now = System.currentTimeMillis()
                    imageDao.saveGenerated(
                        id = imageId,
                        title = written.title,
                        // The row keeps the body, the file the assembled caption. Storing
                        // the assembled form would prepend the lead a second time on the
                        // next edit.
                        description = body,
                        keywords = written.keywords,
                        category = written.category,
                        state = ImageState.GENERATED,
                        generatedAt = now,
                        model = model.id,
                        captionPlace = result.place.orEmpty(),
                    )
                    imageDao.markExported(imageId, image.mediaStoreUri, now)
                    Result.success()
                }
            }
        } finally {
            source.delete()
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

        /** Network and rate-limit retries, so a lasting outage ends in a message rather than a spinner. */
        private const val MAX_ATTEMPTS = 3
    }
}
