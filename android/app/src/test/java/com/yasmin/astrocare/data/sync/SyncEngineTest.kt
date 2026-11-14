package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import com.yasmin.astrocare.data.remote.dto.SyncErrorCode
import com.yasmin.astrocare.data.remote.dto.SyncErrorDto
import com.yasmin.astrocare.data.remote.dto.SyncItemResultDto
import com.yasmin.astrocare.data.remote.dto.SyncItemStatus
import com.yasmin.astrocare.data.remote.dto.SyncResultsDto
import com.yasmin.astrocare.data.remote.dto.SyncSummaryDto
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.LastSyncResult
import com.yasmin.astrocare.domain.model.SyncSettings
import com.yasmin.astrocare.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [SyncEngine] (docs/DECISIONS.md §3) with no sockets:
 * fake DAOs + fake [SyncTransport]. Covers created/duplicate/rejected handling,
 * envelope failures, batchId mismatch, partial results and idempotent retries.
 */
class SyncEngineTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun measurement(id: String, at: String = "2026-11-13T10:00:00Z") = MeasurementEntity(
        id = id,
        schemaVersion = "1.0",
        astronautId = "demo-astronaut-01",
        metric = "heart_rate",
        valuesJson = """{"bpm":70}""",
        measuredAt = at,
        utcOffsetMinutes = 0,
        environment = "iss",
        source = "manual",
        isSynthetic = false,
        note = null,
        createdAt = at,
    )

    private fun settings(
        enabled: Boolean = true,
        deviceId: String = "device-1",
    ) = AppSettings(
        deviceId = deviceId,
        sync = SyncSettings(
            enabled = enabled,
            baseUrl = "https://backend.example/api/v1",
            apiKey = "demo-key",
        ),
    )

    private fun response(
        request: SyncBatchRequestDto,
        measurements: List<SyncItemResultDto> = emptyList(),
        checkins: List<SyncItemResultDto> = emptyList(),
        alerts: List<SyncItemResultDto> = emptyList(),
        rejected: Int = 0,
        batchId: String = request.batchId,
    ) = SyncBatchResponseDto(
        schemaVersion = "1.0",
        batchId = batchId,
        receivedAt = "2026-11-14T06:00:00Z",
        serverTime = "2026-11-14T06:00:00Z",
        summary = SyncSummaryDto(
            created = (measurements + checkins + alerts).count { it.status == SyncItemStatus.CREATED },
            duplicates = (measurements + checkins + alerts).count { it.status == SyncItemStatus.DUPLICATE },
            rejected = rejected,
        ),
        results = SyncResultsDto(measurements, checkins, alerts),
        latestRuleSetVersion = "1.0.0",
    )

    private fun engine(
        measurementDao: FakeMeasurementDao,
        settingsRepository: FakeSettingsRepository,
        transport: FakeSyncTransport,
        checkInDao: FakeSymptomCheckInDao = FakeSymptomCheckInDao(),
        alertDao: FakeAlertDao = FakeAlertDao(),
    ) = SyncEngine(
        measurementDao = measurementDao,
        symptomCheckInDao = checkInDao,
        alertDao = alertDao,
        mapper = ContractMapper(json),
        transport = transport,
        settingsRepository = settingsRepository,
        clientRuleSetVersion = ClientRuleSetVersionProvider { "1.0.0" },
    )

    @Test
    fun `disabled sync sends nothing`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val transport = FakeSyncTransport { error("must not be called") }
        val repo = FakeSettingsRepository(settings(enabled = false))
        assertEquals(SyncOutcome.SkippedDisabled, engine(dao, repo, transport).syncOnce())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `blank device id defers sync`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val transport = FakeSyncTransport { error("must not be called") }
        val repo = FakeSettingsRepository(settings(deviceId = ""))
        assertEquals(SyncOutcome.SkippedNotInitialized, engine(dao, repo, transport).syncOnce())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `nothing pending performs no network call`() = runTest {
        val transport = FakeSyncTransport { error("must not be called") }
        val repo = FakeSettingsRepository(settings())
        assertEquals(SyncOutcome.NothingToSync, engine(FakeMeasurementDao(), repo, transport).syncOnce())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `created and duplicate mark rows synced and keep them`() = runTest {
        val dao = FakeMeasurementDao().apply {
            rows += measurement("m-1", "2026-11-13T09:00:00Z")
            rows += measurement("m-2", "2026-11-13T10:00:00Z")
        }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { request ->
            response(
                request,
                measurements = listOf(
                    SyncItemResultDto("m-1", SyncItemStatus.CREATED),
                    SyncItemResultDto("m-2", SyncItemStatus.DUPLICATE),
                ),
            )
        }
        val outcome = engine(dao, repo, transport).syncOnce() as SyncOutcome.Completed
        assertEquals(1, outcome.created)
        assertEquals(1, outcome.duplicates)
        assertEquals(0, outcome.rejected)
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.all { it.localSyncStatus == SyncStatus.SYNCED })
        assertEquals(LastSyncResult.success, repo.snapshot().sync.lastSyncResult)
        assertNotNull(repo.snapshot().sync.lastSyncAt)
    }

    @Test
    fun `request uses the configured base url and api key`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { request ->
            response(request, measurements = listOf(SyncItemResultDto("m-1", SyncItemStatus.CREATED)))
        }
        engine(dao, repo, transport).syncOnce()
        assertEquals("https://backend.example/api/v1", transport.baseUrls.single())
        assertEquals("demo-key", transport.apiKeys.single())
    }

    @Test
    fun `rejected row stores the error and is never retried`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { request ->
            response(
                request,
                measurements = listOf(
                    SyncItemResultDto(
                        "m-1",
                        SyncItemStatus.REJECTED,
                        SyncErrorDto(SyncErrorCode.VALIDATION_ERROR, "bpm out of range"),
                    ),
                ),
                rejected = 1,
            )
        }
        val sync = engine(dao, repo, transport)
        val outcome = sync.syncOnce() as SyncOutcome.Completed
        assertEquals(1, outcome.rejected)
        assertEquals(SyncStatus.REJECTED, dao.rows.single().localSyncStatus)
        assertEquals("VALIDATION_ERROR: bpm out of range", dao.rows.single().syncError)
        assertEquals(LastSyncResult.partial, repo.snapshot().sync.lastSyncResult)

        // Second run: the rejected row is not pending, so nothing is sent.
        assertEquals(SyncOutcome.NothingToSync, sync.syncOnce())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `envelope failure keeps every row pending and surfaces failed`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { throw IOException("offline") }
        try {
            engine(dao, repo, transport).syncOnce()
            error("expected IOException")
        } catch (_: IOException) {
            // expected — WorkManager will retry with backoff
        }
        assertEquals(SyncStatus.PENDING, dao.rows.single().localSyncStatus)
        assertEquals(LastSyncResult.failed, repo.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `batch id mismatch is a contract violation and changes nothing`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { request ->
            response(
                request,
                measurements = listOf(SyncItemResultDto("m-1", SyncItemStatus.CREATED)),
                batchId = "00000000-0000-5000-8000-000000000000",
            )
        }
        try {
            engine(dao, repo, transport).syncOnce()
            error("expected IOException")
        } catch (_: IOException) {
            // expected
        }
        assertEquals(SyncStatus.PENDING, dao.rows.single().localSyncStatus)
        assertEquals(LastSyncResult.failed, repo.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `rows missing from the response stay pending`() = runTest {
        val dao = FakeMeasurementDao().apply {
            rows += measurement("m-1", "2026-11-13T09:00:00Z")
            rows += measurement("m-2", "2026-11-13T10:00:00Z")
        }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { request ->
            response(request, measurements = listOf(SyncItemResultDto("m-1", SyncItemStatus.CREATED)))
        }
        engine(dao, repo, transport).syncOnce()
        assertEquals(SyncStatus.SYNCED, dao.rows.first { it.id == "m-1" }.localSyncStatus)
        assertEquals(SyncStatus.PENDING, dao.rows.first { it.id == "m-2" }.localSyncStatus)
    }

    @Test
    fun `retrying the same pending set reuses the batch id`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val repo = FakeSettingsRepository(settings())
        val transport = FakeSyncTransport { throw IOException("offline") }
        val sync = engine(dao, repo, transport)
        repeat(2) {
            try {
                sync.syncOnce()
            } catch (_: IOException) {
                // expected
            }
        }
        assertEquals(2, transport.requests.size)
        assertEquals(transport.requests[0].batchId, transport.requests[1].batchId)
    }
}
