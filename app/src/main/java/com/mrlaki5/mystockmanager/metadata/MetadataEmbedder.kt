package com.mrlaki5.mystockmanager.metadata

import android.net.Uri
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes metadata into the album copy of an image.
 *
 * The ordering here is the safety property: embed into a temp file, read that file back
 * and compare it against what was asked for, and only overwrite the file the user can
 * see once the round trip verifies. A failed embed therefore can never damage an image
 * the user already has.
 *
 * Shared by generation and by hand edits so that one ordering, not two, is what every
 * write to an album file goes through.
 */
@Singleton
class MetadataEmbedder @Inject constructor(
    private val fileStore: AppFileStore,
    private val mediaStore: MediaStoreExporter,
    private val writer: MetadataWriter,
) {

    /** Copies the album file out first. For a caller that has no copy of its own. */
    suspend fun embed(mediaUri: Uri, metadata: StockMetadata): Result<StockMetadata> =
        withContext(Dispatchers.IO) {
            val source = fileStore.newTempFile("embed-src")
            try {
                runCatching { mediaStore.copyTo(mediaUri, source) }.getOrElse {
                    return@withContext failure("Could not read image", it)
                }
                embedFrom(source, mediaUri, metadata)
            } finally {
                source.delete()
            }
        }

    /**
     * As [embed], for a caller that already holds a copy of the album bytes in [source],
     * which is left untouched.
     *
     * Returns the metadata as it was actually written — [StockMetadata.normalized] clamps
     * to the agency field limits, so this can differ from what was passed in, and it is
     * what a caller should persist if it wants its database to match the file.
     */
    suspend fun embedFrom(
        source: File,
        mediaUri: Uri,
        metadata: StockMetadata,
    ): Result<StockMetadata> = withContext(Dispatchers.IO) {
        val normalized = metadata.normalized()
        val embedded = fileStore.newTempFile("embed-out")
        try {
            val problems = runCatching {
                writer.embed(source, embedded, normalized)
                MetadataReader.read(embedded).matches(normalized)
            }.getOrElse { return@withContext failure("Embedding failed", it) }

            if (problems.isNotEmpty()) {
                return@withContext Result.failure(IOException("Verification failed: ${problems.first()}"))
            }

            // Only now touch the file the user can see.
            runCatching { mediaStore.overwrite(mediaUri, embedded) }.getOrElse {
                return@withContext failure("Could not update album file", it)
            }
            Result.success(normalized)
        } finally {
            embedded.delete()
        }
    }

    private fun failure(context: String, cause: Throwable): Result<StockMetadata> =
        Result.failure(IOException("$context: ${cause.message}", cause))
}
