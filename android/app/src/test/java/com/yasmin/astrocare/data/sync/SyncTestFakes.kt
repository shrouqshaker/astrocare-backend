package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory test doubles for the sync stack. Shared by the pure JVM engine
 * tests and the Robolectric WorkManager worker test so both exercise the same
 * row-state semantics.
 */
internal class FakeSyncTransport(
    private val handler: suspend (SyncBatchRequestDto) -> SyncBatchResponseDto,
) : SyncTransport {
    val requests = mutableListOf<SyncBatchRequestDto>()
    val baseUrls = mutableListOf<String>()
    val apiKeys = mutableListOf<String>()

    override suspend fun post(
        baseUrl: String,
        apiKey: String,
        request: SyncBatchRequestDto,
    ): SyncBatchResponseDto {
        requests += request
        baseUrls += baseUrl
        apiKeys += apiKey
        return handler(request)
    }
}

internal class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    private val flow = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = flow
    override suspend fun snapshot(): AppSettings = flow.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        flow.value = transform(flow.value)
    }
    override suspend fun ensureInitialized() = Unit
}

internal class FakeMeasurementDao : MeasurementDao {
    val rows = mutableListOf<MeasurementEntity>()
    override suspend fun count(): Int = rows.size
    override suspend fun findById(id: String): MeasurementEntity? = rows.firstOrNull { it.id.equals(id, ignoreCase = true) }
    override fun observeAll(): Flow<List<MeasurementEntity>> = MutableStateFlow(rows.toList())
    override fun observeMetricWindow(
        metric: String,
        fromIso: String,
        toIso: String,
    ): Flow<List<MeasurementEntity>> = MutableStateFlow(emptyList())
    override suspend fun getFrom(fromIso: String): List<MeasurementEntity> =
        rows.filter { it.measuredAt >= fromIso }
    override suspend fun getPending(limit: Int): List<MeasurementEntity> =
        rows.filter { it.localSyncStatus == SyncStatus.PENDING }
            .sortedBy { it.measuredAt }
            .take(limit)
    override suspend fun countPending(): Int = rows.count { it.localSyncStatus == SyncStatus.PENDING }
    override fun observeRejected(limit: Int): Flow<List<MeasurementEntity>> =
        MutableStateFlow(rows.filter { it.localSyncStatus == SyncStatus.REJECTED }.take(limit))
    override suspend fun updateSyncStatus(id: String, status: String, error: String?) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(localSyncStatus = status, syncError = error)
    }
    override suspend fun insertAll(rows: List<MeasurementEntity>) {
        rows.forEach { row ->
            if (this.rows.none { it.id.equals(row.id, ignoreCase = true) }) this.rows += row
        }
    }
}

internal class FakeSymptomCheckInDao : SymptomCheckInDao {
    val rows = mutableListOf<SymptomCheckInEntity>()
    override suspend fun count(): Int = rows.size
    override suspend fun findById(id: String): SymptomCheckInEntity? = rows.firstOrNull { it.id.equals(id, ignoreCase = true) }
    override fun observeAll(): Flow<List<SymptomCheckInEntity>> = MutableStateFlow(rows.toList())
    override fun observeWindow(
        fromIso: String,
        toIso: String,
    ): Flow<List<SymptomCheckInEntity>> = MutableStateFlow(emptyList())
    override suspend fun getFrom(fromIso: String): List<SymptomCheckInEntity> =
        rows.filter { it.checkedAt >= fromIso }
    override suspend fun getPending(limit: Int): List<SymptomCheckInEntity> =
        rows.filter { it.localSyncStatus == SyncStatus.PENDING }.take(limit)
    override suspend fun countPending(): Int = rows.count { it.localSyncStatus == SyncStatus.PENDING }
    override fun observeRejected(limit: Int): Flow<List<SymptomCheckInEntity>> =
        MutableStateFlow(rows.filter { it.localSyncStatus == SyncStatus.REJECTED }.take(limit))
    override suspend fun updateSyncStatus(id: String, status: String, error: String?) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(localSyncStatus = status, syncError = error)
    }
    override suspend fun insertAll(rows: List<SymptomCheckInEntity>) {
        rows.forEach { row ->
            if (this.rows.none { it.id.equals(row.id, ignoreCase = true) }) this.rows += row
        }
    }
}

internal class FakeAlertDao : AlertDao {
    val rows = mutableListOf<AlertEntity>()
    override suspend fun count(): Int = rows.size
    override suspend fun findById(id: String): AlertEntity? = rows.firstOrNull { it.id.equals(id, ignoreCase = true) }
    override fun observeAll(): Flow<List<AlertEntity>> = MutableStateFlow(rows.toList())
    override fun observeRecent(limit: Int): Flow<List<AlertEntity>> = MutableStateFlow(rows.take(limit))
    override suspend fun getByRuleId(ruleId: String): List<AlertEntity> = rows.filter { it.ruleId == ruleId }
    override suspend fun getAll(): List<AlertEntity> = rows
    override suspend fun acknowledge(id: String, at: String) {
        val index = rows.indexOfFirst { it.id.equals(id, ignoreCase = true) && it.acknowledgedAt == null }
        if (index >= 0) {
            val row = rows[index]
            rows[index] = row.copy(
                acknowledgedAt = at,
                localSyncStatus = if (row.localSyncStatus == SyncStatus.REJECTED) {
                    SyncStatus.REJECTED
                } else {
                    SyncStatus.PENDING
                },
                syncError = if (row.localSyncStatus == SyncStatus.REJECTED) row.syncError else null,
            )
        }
    }
    override suspend fun mergeServerAcknowledgement(id: String, serverAcknowledgedAt: String) {
        val index = rows.indexOfFirst { row ->
            row.id.equals(id, ignoreCase = true) &&
                (row.acknowledgedAt == null || row.localSyncStatus == SyncStatus.SYNCED)
        }
        if (index >= 0) rows[index] = rows[index].copy(acknowledgedAt = serverAcknowledgedAt)
    }
    override suspend fun getPending(limit: Int): List<AlertEntity> =
        rows.filter { it.localSyncStatus == SyncStatus.PENDING }.take(limit)
    override suspend fun countPending(): Int = rows.count { it.localSyncStatus == SyncStatus.PENDING }
    override fun observeRejected(limit: Int): Flow<List<AlertEntity>> =
        MutableStateFlow(rows.filter { it.localSyncStatus == SyncStatus.REJECTED }.take(limit))
    override suspend fun updateSyncStatus(id: String, status: String, error: String?) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(localSyncStatus = status, syncError = error)
    }
    override suspend fun updateSyncStatusIfAcknowledgementUnchanged(
        id: String,
        uploadedAcknowledgedAt: String?,
        status: String,
        error: String?,
    ) {
        val index = rows.indexOfFirst {
            it.id.equals(id, ignoreCase = true) &&
                it.localSyncStatus == SyncStatus.PENDING &&
                it.acknowledgedAt == uploadedAcknowledgedAt
        }
        if (index >= 0) rows[index] = rows[index].copy(localSyncStatus = status, syncError = error)
    }
    override suspend fun insertAll(rows: List<AlertEntity>) {
        rows.forEach { row ->
            if (this.rows.none { it.id.equals(row.id, ignoreCase = true) }) this.rows += row
        }
    }
}
