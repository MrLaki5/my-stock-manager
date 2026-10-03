package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun enqueueGeneration(imageIds: List<Long>, hint: String? = null) {
        val workManager = WorkManager.getInstance(context)
        for (imageId in imageIds) {
            val request = OneTimeWorkRequestBuilder<GenerateMetadataWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(GenerateMetadataWorker.KEY_IMAGE_ID, imageId)
                        .putString(GenerateMetadataWorker.KEY_HINT, hint)
                        .build()
                )
                // No network constraint: offline work would wait silently, so the worker fails fast and says why.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_GENERATION)
                .addTag(imageTagFor(imageId))
                .build()

            // KEEP means double-tapping Generate cannot enqueue the same image twice.
            workManager.enqueueUniqueWork(uniqueNameFor(imageId), ExistingWorkPolicy.KEEP, request)
        }
    }

    /**
     * Drops queued generation for images that are going away. The worker already bails
     * out on a missing row, but cancelling stops WorkManager from waking up and retrying
     * a job that has nothing left to act on.
     */
    fun cancelGeneration(imageIds: Collection<Long>) {
        val workManager = WorkManager.getInstance(context)
        for (imageId in imageIds) {
            workManager.cancelUniqueWork(uniqueNameFor(imageId))
        }
    }

    /** Cancels every unfinished generation except [runningId]; returns the image ids it stopped. */
    suspend fun cancelOtherGeneration(runningId: UUID): Set<Long> {
        val workManager = WorkManager.getInstance(context)
        val others = workManager.getWorkInfosByTagFlow(TAG_GENERATION).first()
            .filter { it.id != runningId && !it.state.isFinished }
        others.forEach { workManager.cancelWorkById(it.id) }
        return others.mapNotNull { info ->
            info.tags.firstNotNullOfOrNull { tag ->
                if (tag.startsWith(IMAGE_TAG_PREFIX)) tag.removePrefix(IMAGE_TAG_PREFIX).toLongOrNull() else null
            }
        }.toSet()
    }

    private fun uniqueNameFor(imageId: Long) = "generate-$imageId"

    private fun imageTagFor(imageId: Long) = "$IMAGE_TAG_PREFIX$imageId"

    companion object {
        const val TAG_GENERATION = "generation"
        private const val IMAGE_TAG_PREFIX = "generate-image:"
    }
}
