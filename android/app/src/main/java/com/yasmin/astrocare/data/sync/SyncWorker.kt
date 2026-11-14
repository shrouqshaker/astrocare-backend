package com.yasmin.astrocare.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Both periodic and manual jobs execute the same push/pull coordinator. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: TwoWaySyncCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (val outcome = coordinator.syncOnce()) {
        TwoWaySyncOutcome.Skipped -> Result.success()
        is TwoWaySyncOutcome.Failed -> if (outcome.retryable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        is TwoWaySyncOutcome.Completed -> {
            if (outcome.pendingRemaining > 0 && runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.success(workDataOf(
                KEY_BATCH_ID to (outcome.upload?.batchId ?: ""),
                KEY_CREATED to (outcome.upload?.created ?: 0),
                KEY_DUPLICATES to (outcome.upload?.duplicates ?: 0),
                KEY_REJECTED to (outcome.upload?.rejected ?: 0),
                KEY_DOWNLOADED to outcome.history.downloaded,
                KEY_INSERTED to outcome.history.inserted,
                KEY_COMPLETE to (outcome.history.complete && outcome.pendingRemaining == 0 && (outcome.upload?.rejected ?: 0) == 0),
            ))
        }
    }

    companion object {
        const val KEY_BATCH_ID = "batchId"
        const val KEY_CREATED = "created"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_REJECTED = "rejected"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_INSERTED = "inserted"
        const val KEY_COMPLETE = "complete"
        private const val MAX_RETRIES = 3
    }
}
