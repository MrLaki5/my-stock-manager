package com.mrlaki5.mystockmanager.nextcloud

enum class SyncWork { NONE, QUEUED, RETRYING, RUNNING }

data class SyncStatusInput(
    val hasAccount: Boolean,
    val enabled: Boolean,
    val wifiOnly: Boolean,
    val work: SyncWork,
    val pending: Int,
    val failed: Int,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val pull: PullState = PullState.Idle,
)

object SyncStatus {

    fun describe(input: SyncStatusInput, formatTime: (Long) -> String): String {
        val pull = input.pull
        val main = when {
            !input.hasAccount -> "Not set up"
            // A pull runs whether or not sync is on, so it is reported first.
            pull is PullState.Running ->
                if (pull.total > 0) "Pulling from NextCloud… ${pull.done} of ${pull.total}" else "Pulling from NextCloud…"
            pull is PullState.Waiting -> when {
                pull.retrying -> "Pull will retry soon"
                input.wifiOnly -> "Pull waiting for Wi-Fi"
                else -> "Pull waiting for a connection"
            }
            !input.enabled -> "Sync is off"
            input.work == SyncWork.RUNNING -> if (input.pending > 0) "Syncing… ${input.pending} left" else "Syncing…"
            input.work == SyncWork.RETRYING -> "Retrying soon" + input.lastError?.let { ": $it" }.orEmpty()
            input.work == SyncWork.QUEUED -> if (input.wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection"
            input.lastError != null -> "Stopped: ${input.lastError}"
            input.pending > 0 -> "${input.pending} waiting to sync"
            input.lastSuccessAt != null -> "Up to date · last synced ${formatTime(input.lastSuccessAt)}"
            else -> "Up to date"
        }
        val showFailed = input.hasAccount && input.enabled && input.failed > 0
        return if (showFailed) "$main · ${input.failed} could not be uploaded" else main
    }
}
