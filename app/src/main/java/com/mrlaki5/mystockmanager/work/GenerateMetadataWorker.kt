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
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.ImageEncoder
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val imageId = inputData.getLong(KEY_IMAGE_ID, -1L)
        val location = inputData.getString(KEY_LOCATION)?.takeIf { it.isNotBlank() }
        if (imageId <= 0) return Result.failure()

        val image = imageDao.getById(imageId) ?: return Result.failure()
        val mediaUri = image.mediaStoreUri?.toUri()
            ?: return fail(imageId, "Image is not in the album")
        if (!mediaStore.exists(mediaUri)) {
            return fail(imageId, "Image was removed from the album")
        }

        imageDao.updateState(imageId, ImageState.GENERATING)

        val apiKey = keyStore.apiKey
        if (apiKey.isBlank()) return fail(imageId, "No OpenAI API key set. Add one in Settings.")
        val model = keyStore.model

        val source = fileStore.newTempFile("gen-src")
        try {
            runCatching { mediaStore.copyTo(mediaUri, source) }
                .getOrElse { return fail(imageId, "Could not read image: ${it.message}") }

            val encoded = runCatching { ImageEncoder.toBase64Jpeg(source) }
                .getOrElse { return fail(imageId, "Could not decode image: ${it.message}") }

            return when (val result = openAiClient.generate(apiKey, model, encoded, location)) {
                is OpenAiResult.Transient -> {
                    // Stays GENERATING: a retry really is still in flight.
                    imageDao.markFailed(imageId, ImageState.GENERATING, result.message)
                    Result.retry()
                }

                is OpenAiResult.Terminal -> fail(imageId, result.message)

                is OpenAiResult.Success -> {
                    // The model writes the caption body; the app owns the caption's shape.
                    // Prepending the lead here rather than asking for it is what makes the
                    // format guaranteed instead of merely requested.
                    val body = result.metadata.description
                    val captioned = result.metadata.copy(
                        description = EditorialCaption.build(
                            location = location,
                            capturedOn = image.capturedOn,
                            body = body,
                        ),
                    )

                    // The embedder verifies the round trip before overwriting, and hands
                    // back what it actually wrote: the clamped values, so the row and the
                    // file cannot disagree about field limits.
                    val written = embedder.embedFrom(source, mediaUri, captioned)
                        .getOrElse { return fail(imageId, it.message ?: "Embedding failed") }

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
                        model = model,
                    )
                    imageDao.markExported(imageId, image.mediaStoreUri, now)
                    Result.success()
                }
            }
        } finally {
            source.delete()
        }
    }

    private suspend fun fail(imageId: Long, message: String): Result {
        imageDao.markFailed(imageId, ImageState.GENERATION_FAILED, message)
        return Result.failure()
    }

    companion object {
        const val KEY_IMAGE_ID = "imageId"
        const val KEY_LOCATION = "location"
    }
}
