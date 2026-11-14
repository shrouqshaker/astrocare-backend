package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import com.yasmin.astrocare.data.remote.dto.SyncItemResultDto
import com.yasmin.astrocare.data.remote.dto.SyncItemStatus
import java.util.Locale
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.LastSyncResult
import com.yasmin.astrocare.domain.repository.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

sealed class SyncOutcome {
    data object SkippedDisabled : SyncOutcome()
    data object SkippedNotInitialized : SyncOutcome()
    /** No POST needed. The two-way coordinator still downloads history. */
    data object NothingToSync : SyncOutcome()
    data class Completed(
        val batchId: String,
        val created: Int,
        val duplicates: Int,
        val rejected: Int,
    ) : SyncOutcome()
}

/** Bounded upload step. Only the versions actually sent can be marked synced. */
@Singleton
class SyncEngine @Inject constructor(
    private val measurementDao: MeasurementDao,
    private val symptomCheckInDao: SymptomCheckInDao,
    private val alertDao: AlertDao,
    private val mapper: ContractMapper,
    private val transport: SyncTransport,
    private val settingsRepository: SettingsRepository,
    private val clientRuleSetVersion: ClientRuleSetVersionProvider,
) {
    suspend fun syncOnce(settingsSnapshot: AppSettings? = null): SyncOutcome {
        val settings = settingsSnapshot ?: settingsRepository.snapshot()
        if (!settings.sync.enabled) return SyncOutcome.SkippedDisabled
        if (settings.deviceId.isBlank()) return SyncOutcome.SkippedNotInitialized
        val measurements = measurementDao.getPending(SyncBatchPlanner.MAX_ITEMS_PER_COLLECTION)
        val checkIns = symptomCheckInDao.getPending(SyncBatchPlanner.MAX_ITEMS_PER_COLLECTION)
        val alerts = alertDao.getPending(SyncBatchPlanner.MAX_ITEMS_PER_COLLECTION)
        if (measurements.isEmpty() && checkIns.isEmpty() && alerts.isEmpty()) return SyncOutcome.NothingToSync
        val attemptAt = IsoTime.formatUtc(System.currentTimeMillis())
        val request = SyncBatchPlanner.plan(
            schemaVersion = AppDefaults.SCHEMA_VERSION,
            astronautId = settings.astronautId,
            deviceId = settings.deviceId,
            sentAt = attemptAt,
            clientRuleSetVersion = clientRuleSetVersion.version(),
            measurements = measurements.map(mapper::toDto),
            symptomCheckins = checkIns.map(mapper::toDto),
            alerts = alerts.map(mapper::toDto),
        )
        return try {
            val response = transport.post(settings.sync.baseUrl, settings.sync.apiKey, request)
            if (response.batchId != request.batchId || response.schemaVersion != AppDefaults.SCHEMA_VERSION) {
                throw IOException("Invalid sync response version or batch ID; pending rows were kept.")
            }
            validateResultIds(response.results.measurements, request.items.measurements.map { it.id })
            validateResultIds(response.results.symptomCheckins, request.items.symptomCheckins.map { it.id })
            validateResultIds(response.results.alerts, request.items.alerts.map { it.id })
            validateSummary(response)
            applyUpdates(response, request)
            settingsRepository.update { current ->
                if (current.sync.baseUrl != settings.sync.baseUrl || current.astronautId != settings.astronautId || current.sync.apiKey != settings.sync.apiKey) current
                else current.copy(sync = current.sync.copy(
                    lastSyncAt = attemptAt,
                    lastSyncResult = SyncResultPlanner.lastSyncResult(response),
                ))
            }
            SyncOutcome.Completed(request.batchId, response.summary.created, response.summary.duplicates, response.summary.rejected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                settingsRepository.update { current ->
                    if (current.sync.baseUrl != settings.sync.baseUrl || current.astronautId != settings.astronautId || current.sync.apiKey != settings.sync.apiKey) current
                    else current.copy(sync = current.sync.copy(lastSyncAt = attemptAt, lastSyncResult = LastSyncResult.failed))
                }
            }
            throw e
        }
    }

    private fun validateResultIds(results: List<SyncItemResultDto>, sentIds: List<String>) {
        val normalizedResults = results.map { it.id.lowercase(Locale.ROOT) }
        val normalizedSent = sentIds.map { it.lowercase(Locale.ROOT) }.toSet()
        if (normalizedResults.distinct().size != results.size || normalizedResults.any { it !in normalizedSent }) {
            throw IOException("Sync response contains duplicate or unrequested item IDs; pending rows were kept.")
        }
        // Missing result IDs remain pending; the coordinator reports an incomplete upload.
    }

    private fun validateSummary(response: SyncBatchResponseDto) {
        val results = response.results.measurements + response.results.symptomCheckins + response.results.alerts
        if (response.summary.created != results.count { it.status == SyncItemStatus.CREATED } ||
            response.summary.duplicates != results.count { it.status == SyncItemStatus.DUPLICATE } ||
            response.summary.rejected != results.count { it.status == SyncItemStatus.REJECTED }
        ) throw IOException("Sync summary does not match item results; pending rows were kept.")
    }

    private suspend fun applyUpdates(response: SyncBatchResponseDto, request: SyncBatchRequestDto) {
        val measurementIds = request.items.measurements.associate { it.id.lowercase(Locale.ROOT) to it.id }
        val checkinIds = request.items.symptomCheckins.associate { it.id.lowercase(Locale.ROOT) to it.id }
        val uploadedAlerts = request.items.alerts.associateBy { it.id.lowercase(Locale.ROOT) }
        for (update in SyncResultPlanner.plan(response)) {
            when (update.collection) {
                SyncCollection.MEASUREMENTS -> measurementDao.updateSyncStatus(measurementIds.getValue(update.id.lowercase(Locale.ROOT)), update.status, update.error)
                SyncCollection.SYMPTOM_CHECKINS -> symptomCheckInDao.updateSyncStatus(checkinIds.getValue(update.id.lowercase(Locale.ROOT)), update.status, update.error)
                SyncCollection.ALERTS -> alertDao.updateSyncStatusIfAcknowledgementUnchanged(
                    id = uploadedAlerts.getValue(update.id.lowercase(Locale.ROOT)).id,
                    uploadedAcknowledgedAt = uploadedAlerts.getValue(update.id.lowercase(Locale.ROOT)).acknowledgedAt,
                    status = update.status,
                    error = update.error,
                )
            }
        }
    }
}
