package com.yasmin.astrocare.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import com.yasmin.astrocare.data.remote.dto.SyncItemResultDto
import com.yasmin.astrocare.data.remote.dto.SyncItemStatus
import com.yasmin.astrocare.data.remote.dto.SyncResultsDto
import com.yasmin.astrocare.data.remote.dto.SyncSummaryDto
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.SyncSettings
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WorkManager integration on the JVM via Robolectric + work-testing:
 * the scheduler enqueues/cancels unique work with the right constraints, and
 * [SyncWorker] maps engine outcomes to Result.success / Result.retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerIntegrationTest {

    private lateinit var context: Context
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun settings(enabled: Boolean = true) = AppSettings(
        deviceId = "device-1",
        sync = SyncSettings(enabled = enabled),
    )

    private fun scheduler() = SyncScheduler(
        context = context,
        settingsRepository = FakeSettingsRepository(settings()),
    )

    @Test
    fun `enabled sync enqueues one unique periodic job with constraints`() = runBlocking {
        scheduler().apply(SyncSettings(enabled = true, intervalMinutes = 15, wifiOnly = true))
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun `disabling sync cancels the periodic job`() = runBlocking {
        val scheduler = scheduler()
        scheduler.apply(SyncSettings(enabled = true, intervalMinutes = 15))
        scheduler.apply(SyncSettings(enabled = false))
        // WorkManager cancellation is asynchronous; wait briefly for the state to settle.
        var infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
            .get()
        val deadline = System.currentTimeMillis() + 5_000
        while (infos.any { it.state == WorkInfo.State.ENQUEUED } && System.currentTimeMillis() < deadline) {
            delay(50)
            infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
                .get()
        }
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `sync now enqueues one-time work with the requested network constraint`() = runBlocking {
        scheduler().syncNow(wifiOnly = true)
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.ONE_TIME_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(NetworkType.UNMETERED, infos.single().constraints.requiredNetworkType)
    }

    @Test
    fun `worker returns success and exposes batch counts`() = runBlocking {
        val engine = engine(
            dao = FakeMeasurementDao().apply { rows += measurement("m-1") },
            transport = FakeSyncTransport { request -> response(request, created = true) },
        )
        val result = worker(engine).doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, data.getInt(SyncWorker.KEY_CREATED, -1))
        assertEquals(0, data.getInt(SyncWorker.KEY_REJECTED, -1))
    }

    @Test
    fun `worker retries on envelope failure and leaves rows pending`() = runBlocking {
        val dao = FakeMeasurementDao().apply { rows += measurement("m-1") }
        val engine = engine(dao = dao, transport = FakeSyncTransport { throw IOException("offline") })
        val result = worker(engine).doWork()
        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals("pending", dao.rows.single().localSyncStatus)
    }

    private fun worker(engine: SyncEngine): SyncWorker {
        val coordinator = TwoWaySyncCoordinator(
            syncEngine = engine,
            historyPuller = FakeHistoryPuller(),
            settingsRepository = FakeSettingsRepository(settings()),
            diagnosticsRepository = FakeSyncDiagnosticsRepository(),
            measurementDao = FakeMeasurementDao(),
            symptomCheckInDao = FakeSymptomCheckInDao(),
            alertDao = FakeAlertDao(),
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = SyncWorker(appContext, workerParameters, coordinator)
        }
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    private fun engine(dao: FakeMeasurementDao, transport: FakeSyncTransport): SyncEngine = SyncEngine(
        measurementDao = dao,
        symptomCheckInDao = FakeSymptomCheckInDao(),
        alertDao = FakeAlertDao(),
        mapper = ContractMapper(json),
        transport = transport,
        settingsRepository = FakeSettingsRepository(settings()),
        clientRuleSetVersion = ClientRuleSetVersionProvider { "1.0.0" },
    )

    private fun measurement(id: String) = com.yasmin.astrocare.data.local.entity.MeasurementEntity(
        id = id,
        schemaVersion = "1.0",
        astronautId = "demo-astronaut-01",
        metric = "heart_rate",
        valuesJson = """{"bpm":70}""",
        measuredAt = "2026-11-13T10:00:00Z",
        utcOffsetMinutes = 0,
        environment = "iss",
        source = "manual",
        isSynthetic = false,
        note = null,
        createdAt = "2026-11-13T10:00:00Z",
    )

    private fun response(request: SyncBatchRequestDto, created: Boolean): SyncBatchResponseDto =
        SyncBatchResponseDto(
            schemaVersion = "1.0",
            batchId = request.batchId,
            receivedAt = "2026-11-14T06:00:00Z",
            serverTime = "2026-11-14T06:00:00Z",
            summary = SyncSummaryDto(created = if (created) 1 else 0, duplicates = 0, rejected = 0),
            results = SyncResultsDto(
                measurements = listOf(
                    SyncItemResultDto(
                        "m-1",
                        if (created) SyncItemStatus.CREATED else SyncItemStatus.DUPLICATE,
                    ),
                ),
            ),
            latestRuleSetVersion = "1.0.0",
        )
}
