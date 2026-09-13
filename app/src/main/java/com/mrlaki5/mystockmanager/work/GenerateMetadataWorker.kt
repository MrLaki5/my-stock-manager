package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.data.db.entity.ImageState
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.metadata.MetadataReader
import com.mrlaki5.mystockmanager.metadata.MetadataWriter
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
    private val metadataWriter: MetadataWriter,
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
        val embedded = fileStore.newTempFile("gen-out")
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
                    val metadata = result.metadata
                    val problems = runCatching {
                        metadataWriter.embed(source, embedded, metadata)
                        MetadataReader.read(embedded).matches(metadata.normalized())
                    }.getOrElse { return fail(imageId, "Embedding failed: ${it.message}") }

                    if (problems.isNotEmpty()) {
                        return fail(imageId, "Verification failed: ${problems.first()}")
                    }

                    // Only now touch the file the user can see.
                    runCatching { mediaStore.overwrite(mediaUri, embedded) }
                        .getOrElse { return fail(imageId, "Could not update album file: ${it.message}") }

                    val now = System.currentTimeMillis()
                    imageDao.saveGenerated(
                        id = imageId,
                        title = metadata.title,
                        description = metadata.description,
                        keywords = metadata.keywords,
                        category = metadata.category,
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
            embedded.delete()
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
