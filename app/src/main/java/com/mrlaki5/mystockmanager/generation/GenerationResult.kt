package com.mrlaki5.mystockmanager.generation

import com.mrlaki5.mystockmanager.metadata.model.StockMetadata

/**
 * Outcome shape mirrors what WorkManager needs: [Transient] becomes Result.retry(),
 * [Terminal] becomes Result.failure(). Deciding that here keeps the retry policy in one
 * place instead of spread across the worker.
 */
sealed interface GenerationResult {
    data class Success(
        val metadata: StockMetadata,
        /** The place the hint named, for the caption lead; null when it named none. */
        val place: String?,
        val promptTokens: Int?,
        val completionTokens: Int?,
    ) : GenerationResult

    data class Transient(val message: String, val retryAfterSeconds: Long?) : GenerationResult

    /** [stopsBatch] marks account-wide failures (no credits, bad key) every queued image would hit too. */
    data class Terminal(val message: String, val stopsBatch: Boolean = false) : GenerationResult
}
