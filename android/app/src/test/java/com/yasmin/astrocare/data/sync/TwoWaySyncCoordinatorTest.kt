package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.dto.*
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.LastSyncResult
import com.yasmin.astrocare.domain.model.SyncSettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class TwoWaySyncCoordinatorTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mapper = ContractMapper(json)
    private val settings = AppSettings(deviceId = "device-1", sync = SyncSettings(baseUrl = "https://backend.example/api/v1"))

    private class Fixture(
        val measurements: FakeMeasurementDao,
        val checkins: FakeSymptomCheckInDao,
        val alerts: FakeAlertDao,
        val settings: FakeSettingsRepository,
        val diagnostics: FakeSyncDiagnosticsRepository,
        val engine: SyncEngine,
        val coordinator: TwoWaySyncCoordinator,
    )

    private fun fixture(
        transport: FakeSyncTransport = FakeSyncTransport { accepted(it) },
        history: FakeHistoryPuller = FakeHistoryPuller(),
        measurements: FakeMeasurementDao = FakeMeasurementDao(),
        alerts: FakeAlertDao = FakeAlertDao(),
        settingsRepository: FakeSettingsRepository = FakeSettingsRepository(settings),
    ): Fixture {
        val checkins = FakeSymptomCheckInDao()
        val diagnostics = FakeSyncDiagnosticsRepository()
        val engine = SyncEngine(measurements, checkins, alerts, mapper, transport, settingsRepository, ClientRuleSetVersionProvider { "1.0.0" })
        val coordinator = TwoWaySyncCoordinator(engine, history, settingsRepository, diagnostics, measurements, checkins, alerts)
        return Fixture(measurements, checkins, alerts, settingsRepository, diagnostics, engine, coordinator)
    }

    @Test
    fun `nothing pending still downloads using saved connection`() = runTest {
        val transport = FakeSyncTransport { error("POST should not run") }
        val history = FakeHistoryPuller { HistoryPullResult(downloadedMeasurements = 2, insertedMeasurements = 2) }
        val f = fixture(transport, history)
        val outcome = f.coordinator.syncOnce() as TwoWaySyncOutcome.Completed
        assertNull(outcome.upload)
        assertEquals(1, history.calls)
        assertTrue(transport.requests.isEmpty())
        assertEquals(listOf(settings.sync.baseUrl), history.baseUrls)
        assertEquals(listOf(settings.sync.apiKey), history.apiKeys)
        assertEquals(listOf(settings.astronautId), history.astronautIds)
        assertEquals(SyncStage.COMPLETE, f.diagnostics.snapshot().stage)
        assertEquals(TransferStatus.NOTHING_PENDING, f.diagnostics.snapshot().uploadStatus)
        assertEquals(2, f.diagnostics.snapshot().inserted)
        assertEquals(LastSyncResult.success, f.settings.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `successful upload followed by pull keeps upload counts`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement() }
        val history = FakeHistoryPuller { HistoryPullResult(downloadedMeasurements = 5, insertedMeasurements = 4) }
        val f = fixture(history = history, measurements = dao)
        val outcome = f.coordinator.syncOnce() as TwoWaySyncOutcome.Completed
        assertEquals(1, outcome.upload!!.created)
        assertEquals(SyncStatus.SYNCED, dao.rows.single().localSyncStatus)
        assertEquals(SyncStage.COMPLETE, f.diagnostics.snapshot().stage)
        assertEquals(TransferStatus.SUCCESS, f.diagnostics.snapshot().downloadStatus)
        assertEquals(5, f.diagnostics.snapshot().downloaded)
        assertNotNull(f.diagnostics.snapshot().lastDownloadAt)
    }

    @Test
    fun `download failure after upload is partial not a full success`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement() }
        val f = fixture(history = FakeHistoryPuller { throw IOException("download offline") }, measurements = dao)
        val result = f.coordinator.syncOnce() as TwoWaySyncOutcome.Failed
        assertTrue(result.retryable)
        assertEquals(SyncStatus.SYNCED, dao.rows.single().localSyncStatus)
        assertEquals(LastSyncResult.partial, f.settings.snapshot().sync.lastSyncResult)
        assertEquals(TransferStatus.SUCCESS, f.diagnostics.snapshot().uploadStatus)
        assertEquals(TransferStatus.FAILED, f.diagnostics.snapshot().downloadStatus)
        assertNull(f.diagnostics.snapshot().lastDownloadAt)
        assertEquals(SyncStage.FAILED, f.diagnostics.snapshot().stage)
    }

    @Test
    fun `401 is not retried indefinitely`() = runTest {
        val f = fixture(history = FakeHistoryPuller {
            throw HttpException(Response.error<Any>(401, "{}".toResponseBody("application/json".toMediaType())))
        })
        val result = f.coordinator.syncOnce() as TwoWaySyncOutcome.Failed
        assertFalse(result.retryable)
        assertTrue(f.diagnostics.snapshot().error!!.contains("API key"))
    }

    @Test
    fun `limit and conflicts are visible and incomplete`() = runTest {
        val f = fixture(history = FakeHistoryPuller {
            HistoryPullResult(downloadedMeasurements = 1000, limitedCollections = listOf("measurements"), conflicts = listOf("measurements:m-1"))
        })
        val result = f.coordinator.syncOnce() as TwoWaySyncOutcome.Completed
        assertFalse(result.history.complete)
        assertEquals(SyncStage.PARTIAL, f.diagnostics.snapshot().stage)
        assertEquals(listOf("measurements"), f.diagnostics.snapshot().limitedCollections)
        assertEquals(listOf("measurements:m-1"), f.diagnostics.snapshot().conflicts)
        assertEquals(LastSyncResult.partial, f.settings.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `disabled or uninitialized sync does no network work`() = runTest {
        val history = FakeHistoryPuller { error("must not pull") }
        val repo = FakeSettingsRepository(settings.copy(sync = settings.sync.copy(enabled = false)))
        val f = fixture(history = history, settingsRepository = repo)
        assertEquals(TwoWaySyncOutcome.Skipped, f.coordinator.syncOnce())
        repo.update { it.copy(deviceId = "", sync = it.sync.copy(enabled = true)) }
        assertEquals(TwoWaySyncOutcome.Skipped, f.coordinator.syncOnce())
        assertEquals(0, history.calls)
    }

    @Test
    fun `acknowledgement made during POST remains pending for the next upload`() = runTest {
        val alerts = FakeAlertDao().apply { rows += alert() }
        val transport = FakeSyncTransport { request ->
            assertNull(request.items.alerts.single().acknowledgedAt)
            alerts.acknowledge("a-1", "2026-11-14T06:00:00Z")
            accepted(request)
        }
        val f = fixture(transport = transport, alerts = alerts)
        val result = f.coordinator.syncOnce() as TwoWaySyncOutcome.Completed
        assertEquals("2026-11-14T06:00:00Z", alerts.rows.single().acknowledgedAt)
        assertEquals(SyncStatus.PENDING, alerts.rows.single().localSyncStatus)
        assertEquals(1, result.pendingRemaining)
        assertEquals(SyncStage.PARTIAL, f.diagnostics.snapshot().stage)
    }

    @Test
    fun `missing result leaves pending and is reported incomplete`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement() }
        val f = fixture(transport = FakeSyncTransport { accepted(it).copy(results = SyncResultsDto(), summary = SyncSummaryDto(0, 0, 0)) }, measurements = dao)
        val result = f.coordinator.syncOnce() as TwoWaySyncOutcome.Completed
        assertEquals(1, result.pendingRemaining)
        assertEquals(SyncStatus.PENDING, dao.rows.single().localSyncStatus)
        assertEquals(LastSyncResult.partial, f.settings.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `unrequested result is rejected before modifying any local row`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement() }
        val f = fixture(transport = FakeSyncTransport { request ->
            accepted(request).copy(results = SyncResultsDto(measurements = listOf(SyncItemResultDto("other-id", SyncItemStatus.CREATED))))
        }, measurements = dao)
        assertTrue(f.coordinator.syncOnce() is TwoWaySyncOutcome.Failed)
        assertEquals(SyncStatus.PENDING, dao.rows.single().localSyncStatus)
    }

    @Test
    fun `settings change during upload cannot pull from stale connection`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement() }
        val repo = FakeSettingsRepository(settings)
        val history = FakeHistoryPuller { error("must not pull") }
        val f = fixture(transport = FakeSyncTransport { request ->
            repo.update { it.copy(sync = it.sync.copy(baseUrl = "https://new.example")) }
            accepted(request)
        }, history = history, measurements = dao, settingsRepository = repo)
        assertTrue(f.coordinator.syncOnce() is TwoWaySyncOutcome.Failed)
        assertEquals(0, history.calls)
        assertEquals(TransferStatus.SUCCESS, f.diagnostics.snapshot().uploadStatus)
        assertEquals(LastSyncResult.never, repo.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `manual and periodic attempts cannot overlap their pulls`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val history = FakeHistoryPuller { started.complete(Unit); release.await(); HistoryPullResult() }
        val f = fixture(history = history)
        val first = async { f.coordinator.syncOnce() }
        started.await()
        val second = async { f.coordinator.syncOnce() }
        runCurrent()
        assertEquals(1, history.calls)
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(2, history.calls)
    }

    @Test
    fun `cancellation is propagated and never recorded as successful sync`() = runTest {
        val started = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<Unit>()
        val f = fixture(history = FakeHistoryPuller { started.complete(Unit); blocker.await(); HistoryPullResult() })
        val task = launch { f.coordinator.syncOnce() }
        started.await()
        task.cancelAndJoin()
        assertEquals(SyncStage.CANCELLED, f.diagnostics.snapshot().stage)
        assertNotEquals(LastSyncResult.success, f.settings.snapshot().sync.lastSyncResult)
    }

    @Test
    fun `uppercase UUID response updates the exact uploaded local row`() = runTest {
        val id = "a0b1c2d3-1111-4111-8111-abcdefabcdef"
        val dao = FakeMeasurementDao().apply { rows += measurement().copy(id = id) }
        val f = fixture(transport = FakeSyncTransport { request ->
            val response = accepted(request)
            response.copy(results = response.results.copy(measurements = listOf(SyncItemResultDto(id.uppercase(), SyncItemStatus.CREATED))))
        }, measurements = dao)
        assertTrue(f.coordinator.syncOnce() is TwoWaySyncOutcome.Completed)
        assertEquals(SyncStatus.SYNCED, dao.rows.single().localSyncStatus)
    }

    @Test
    fun `inconsistent summary cannot report successful upload or change rows`() = runTest {
        val dao = FakeMeasurementDao().apply { rows += measurement() }
        val f = fixture(transport = FakeSyncTransport { request ->
            accepted(request).copy(summary = SyncSummaryDto(0, 0, 0))
        }, measurements = dao)
        assertTrue(f.coordinator.syncOnce() is TwoWaySyncOutcome.Failed)
        assertEquals(SyncStatus.PENDING, dao.rows.single().localSyncStatus)
        assertEquals(LastSyncResult.failed, f.settings.snapshot().sync.lastSyncResult)
    }

    private fun measurement() = MeasurementEntity(
        id = "m-1", schemaVersion = "1.0", astronautId = "demo-astronaut-01", metric = "heart_rate",
        valuesJson = """{"bpm":70}""", measuredAt = "2026-11-13T10:00:00Z", utcOffsetMinutes = 0,
        environment = "iss", source = "manual", isSynthetic = false, note = null, createdAt = "2026-11-13T10:00:00Z",
    )

    private fun alert() = AlertEntity(
        id = "a-1", schemaVersion = "1.0", astronautId = "demo-astronaut-01", createdAt = "2026-11-13T10:00:00Z",
        level = "warning", category = "vital", ruleId = "HR-HIGH-W", ruleRevision = 1, ruleSetVersion = "1.0.0",
        title = "High heart rate", explanation = "Heart rate above the rule threshold.", evidenceJson = "[]",
        recommendedAction = "Contact the medical team.", disclaimer = "General decision support only.", sourceJson = """{"ref":"SRC","title":"Demo source"}""", triggeredByJson = """{"measurementIds":[],"symptomCheckinIds":[]}""",
        emergencyProtocolId = null, acknowledgedAt = null, isSynthetic = false,
    )

    private fun accepted(request: SyncBatchRequestDto): SyncBatchResponseDto {
        val results = SyncResultsDto(
            measurements = request.items.measurements.map { SyncItemResultDto(it.id, SyncItemStatus.CREATED) },
            symptomCheckins = request.items.symptomCheckins.map { SyncItemResultDto(it.id, SyncItemStatus.CREATED) },
            alerts = request.items.alerts.map { SyncItemResultDto(it.id, SyncItemStatus.CREATED) },
        )
        return SyncBatchResponseDto(
            schemaVersion = "1.0", batchId = request.batchId, receivedAt = "2026-11-14T06:00:00Z", serverTime = "2026-11-14T06:00:00Z",
            summary = SyncSummaryDto(results.measurements.size + results.symptomCheckins.size + results.alerts.size, 0, 0),
            results = results, latestRuleSetVersion = "1.0.0",
        )
    }
}
