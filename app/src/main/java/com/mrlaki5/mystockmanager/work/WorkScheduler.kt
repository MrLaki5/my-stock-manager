package com.mrlaki5.mystockmanager.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun enqueueGeneration(imageIds: List<Long>, location: String? = null) {
        val workManager = WorkManager.getInstance(context)
        for (imageId in imageIds) {
            val request = OneTimeWorkRequestBuilder<GenerateMetadataWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(GenerateMetadataWorker.KEY_IMAGE_ID, imageId)
                        .putString(GenerateMetadataWorker.KEY_LOCATION, location)
                        .build()
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_GENERATION)
                .build()

            // KEEP means double-tapping Generate cannot enqueue the same image twice.
            workManager.enqueueUniqueWork(uniqueNameFor(imageId), ExistingWorkPolicy.KEEP, request)
        }
    }

    private fun uniqueNameFor(imageId: Long) = "generate-$imageId"

    companion object {
        const val TAG_GENERATION = "generation"
    }
}
