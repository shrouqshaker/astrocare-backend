package com.yasmin.astrocare.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yasmin.astrocare.domain.model.SyncSettings
import com.yasmin.astrocare.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the WorkManager sync schedule in lockstep with Settings (single
 * writer: the settings flow). `enabled=false` cancels the work; interval /
 * Wi-Fi-only changes re-enqueue with `ExistingPeriodicWorkPolicy.UPDATE`.
 * WorkManager persists periodic work across reboots — no receiver needed.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /** Applies the current settings now and on every relevant change. */
    fun startObserving(scope: CoroutineScope) {
        settingsRepository.settings
            .distinctUntilChanged { old, new ->
                old.sync.enabled == new.sync.enabled &&
                    old.sync.intervalMinutes == new.sync.intervalMinutes &&
                    old.sync.wifiOnly == new.sync.wifiOnly
            }
            .onEach { apply(it.sync) }
            .launchIn(scope)
    }

    /** Enqueue/cancel the periodic sync job for one settings snapshot. */
    fun apply(sync: SyncSettings) {
        val workManager = WorkManager.getInstance(context)
        if (!sync.enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(ONE_TIME_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            sync.intervalMinutes.toLong(),
            java.util.concurrent.TimeUnit.MINUTES,
        )
            .setConstraints(constraints(sync.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Manual "Sync now" from Settings — one-shot, independent of the period. */
    fun syncNow(wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun constraints(wifiOnly: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

    companion object {
        const val PERIODIC_WORK_NAME = "astrocare_periodic_sync"
        const val ONE_TIME_WORK_NAME = "astrocare_sync_now"
        private const val BACKOFF_SECONDS = 30L
    }
}
