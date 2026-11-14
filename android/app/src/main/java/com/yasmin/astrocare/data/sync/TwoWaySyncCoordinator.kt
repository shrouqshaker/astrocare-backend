package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import com.yasmin.astrocare.data.remote.HistoryClient
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.LastSyncResult
import com.yasmin.astrocare.domain.repository.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

sealed class TwoWaySyncOutcome {
    data object Skipped : TwoWaySyncOutcome()
    data class Completed(
        val upload: SyncOutcome.Completed?,
        val history: HistoryPullResult,
        val pendingRemaining: Int,
    ) : TwoWaySyncOutcome()
    data class Failed(val retryable: Boolean) : TwoWaySyncOutcome()
}

/** One bounded push + full-history pull. The singleton mutex covers both WorkManager job names. */
@Singleton
class TwoWaySyncCoordinator @Inject constructor(
    private val syncEngine: SyncEngine,
    private val historyPuller: HistoryPuller,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: SyncDiagnosticsRepository,
    private val measurementDao: MeasurementDao,
    private val symptomCheckInDao: SymptomCheckInDao,
    private val alertDao: AlertDao,
) {
    private val mutex = Mutex()

    suspend fun syncOnce(): TwoWaySyncOutcome = mutex.withLock {
        val settings = settingsRepository.snapshot()
        if (!settings.sync.enabled || settings.deviceId.isBlank()) return@withLock TwoWaySyncOutcome.Skipped
        val attemptedAt = now()
        val old = diagnosticsRepository.snapshot()
        diagnosticsRepository.update {
            SyncDiagnostics(
                baseUrl = settings.sync.baseUrl,
                astronautId = settings.astronautId,
                attemptedAt = attemptedAt,
                lastDownloadAt = old.lastDownloadAt.takeIf { old.belongsTo(settings.sync.baseUrl, settings.astronautId) },
                stage = SyncStage.UPLOADING,
                uploadStatus = TransferStatus.RUNNING,
            )
        }
        var upload: SyncOutcome.Completed? = null
        try {
            // Validate the shared endpoint before sending any authenticated request.
            HistoryClient.canonicalBaseUrl(settings.sync.baseUrl)
            // Freeze the connection/identity used by push and pull for this attempt.
            val pushed = syncEngine.syncOnce(settings)
            if (pushed is SyncOutcome.SkippedDisabled || pushed is SyncOutcome.SkippedNotInitialized) {
                diagnosticsRepository.update { it.copy(stage = SyncStage.CANCELLED, finishedAt = now()) }
                return@withLock TwoWaySyncOutcome.Skipped
            }
            upload = pushed as? SyncOutcome.Completed
            val pendingAfterPush = countPending()
            diagnosticsRepository.update {
                it.copy(
                    uploadStatus = when {
                        upload == null -> TransferStatus.NOTHING_PENDING
                        (upload?.rejected ?: 0) > 0 || pendingAfterPush > 0 -> TransferStatus.PARTIAL
                        else -> TransferStatus.SUCCESS
                    },
                    created = upload?.created ?: 0,
                    duplicates = upload?.duplicates ?: 0,
                    rejected = upload?.rejected ?: 0,
                )
            }
            ensureConnection(settings)
            diagnosticsRepository.update { it.copy(stage = SyncStage.DOWNLOADING, downloadStatus = TransferStatus.RUNNING) }
            // Always pull: no pending uploads does NOT mean the server has nothing new.
            val history = historyPuller.pull(settings.sync.baseUrl, settings.sync.apiKey, settings.astronautId)
            val remaining = countPending()
            val warnings = history.warnings.toMutableList()
            if (remaining > 0) warnings += "$remaining local item(s) are still waiting to upload."
            if ((upload?.rejected ?: 0) > 0) warnings += "Some uploads were rejected; inspect Rejected sync items."
            val complete = history.complete && remaining == 0 && (upload?.rejected ?: 0) == 0
            val finishedAt = now()
            diagnosticsRepository.update {
                it.copy(
                    stage = if (complete) SyncStage.COMPLETE else SyncStage.PARTIAL,
                    downloadStatus = if (history.complete) TransferStatus.SUCCESS else TransferStatus.PARTIAL,
                    finishedAt = finishedAt,
                    lastDownloadAt = finishedAt,
                    downloaded = history.downloaded,
                    inserted = history.inserted,
                    pendingRemaining = remaining,
                    conflicts = history.conflicts.take(20),
                    limitedCollections = history.limitedCollections,
                    warnings = warnings.take(20),
                    error = null,
                )
            }
            setLastResult(settings, attemptedAt, if (complete) LastSyncResult.success else LastSyncResult.partial)
            TwoWaySyncOutcome.Completed(upload, history, remaining)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                diagnosticsRepository.update {
                    it.copy(stage = SyncStage.CANCELLED, finishedAt = now(), error = "Sync interrupted; existing local data was kept.")
                }
                setLastResult(settings, attemptedAt, if (upload != null) LastSyncResult.partial else LastSyncResult.failed)
            }
            throw e
        } catch (e: Exception) {
            diagnosticsRepository.update {
                it.copy(
                    stage = SyncStage.FAILED,
                    finishedAt = now(),
                    uploadStatus = if (it.uploadStatus == TransferStatus.RUNNING) TransferStatus.FAILED else it.uploadStatus,
                    downloadStatus = if (it.downloadStatus == TransferStatus.RUNNING) TransferStatus.FAILED else it.downloadStatus,
                    error = safeError(e),
                )
            }
            setLastResult(settings, attemptedAt, if (upload != null) LastSyncResult.partial else LastSyncResult.failed)
            TwoWaySyncOutcome.Failed(retryable = e !is HttpException || e.code() == 408 || e.code() == 429 || e.code() >= 500)
        }
    }

    private suspend fun countPending(): Int =
        measurementDao.countPending() + symptomCheckInDao.countPending() + alertDao.countPending()

    private suspend fun ensureConnection(original: AppSettings) {
        val current = settingsRepository.snapshot()
        if (!current.sync.enabled || current.astronautId != original.astronautId ||
            current.sync.baseUrl != original.sync.baseUrl || current.sync.apiKey != original.sync.apiKey
        ) throw IOException("Sync settings changed; start a new sync with the saved connection.")
    }

    private suspend fun setLastResult(original: AppSettings, attemptedAt: String, result: LastSyncResult) {
        settingsRepository.update { current ->
            if (current.astronautId != original.astronautId || current.sync.baseUrl != original.sync.baseUrl || current.sync.apiKey != original.sync.apiKey) current
            else current.copy(sync = current.sync.copy(lastSyncAt = attemptedAt, lastSyncResult = result))
        }
    }

    private fun now(): String = IsoTime.formatUtc(System.currentTimeMillis())

    private fun safeError(e: Exception): String = when (e) {
        is HttpException -> "HTTP ${e.code()}: " + when (e.code()) {
            401, 403 -> "Check the saved API key."
            404 -> "Check the saved server URL and astronaut ID."
            else -> "The server request did not succeed."
        }
        else -> "Sync could not finish. Local data was kept. " +
            (e.message ?: e.javaClass.simpleName).replace(Regex("https?://\\S+"), "[server]").take(240)
    }
}
