package com.yasmin.astrocare.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.data.local.AstroCareDatabase
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.HistoryTransport
import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.AlertSourceDto
import com.yasmin.astrocare.data.remote.dto.EvidenceDto
import com.yasmin.astrocare.data.remote.dto.HistorySnapshot
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import com.yasmin.astrocare.data.remote.dto.TriggeredByDto
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.SyncSettings
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryRepositoryIntegrationTest {
    private lateinit var db: AstroCareDatabase
    private lateinit var context: Context
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val mapper by lazy { ContractMapper(json) }
    private val url = "https://backend.example/api/v1"
    private val astronautId = "demo-astronaut-01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AstroCareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `pull inserts all collections as synced and is idempotent`() = runBlocking {
        val snapshot = HistorySnapshot(
            measurements = listOf(measurement(MEASUREMENT_ID)),
            symptomCheckins = listOf(checkin(CHECKIN_ID)),
            alerts = listOf(alert(ALERT_ID)),
        )
        val transport = FakeHistoryTransport(snapshot)
        val first = repository(transport).pull(url, "key", astronautId)
        val second = repository(transport).pull(url, "key", astronautId)

        assertEquals(3, first.inserted)
        assertEquals(0, second.inserted)
        assertEquals(1, db.measurementDao().count())
        assertEquals(1, db.symptomCheckInDao().count())
        assertEquals(1, db.alertDao().count())
        assertEquals(SyncStatus.SYNCED, db.measurementDao().findById(MEASUREMENT_ID)!!.localSyncStatus)
        assertEquals(SyncStatus.SYNCED, db.symptomCheckInDao().findById(CHECKIN_ID)!!.localSyncStatus)
        assertEquals(SyncStatus.SYNCED, db.alertDao().findById(ALERT_ID)!!.localSyncStatus)
    }

    @Test
    fun `empty pull never removes local rows and cap is propagated`() = runBlocking {
        db.measurementDao().insertAll(listOf(localMeasurement(MEASUREMENT_ID, SyncStatus.PENDING)))
        val transport = FakeHistoryTransport(
            HistorySnapshot(emptyList(), emptyList(), emptyList(), limitedCollections = listOf("alerts")),
        )

        val result = repository(transport).pull(url, "key", astronautId)

        assertEquals(0, result.inserted)
        assertEquals(listOf("alerts"), result.limitedCollections)
        assertFalse(result.complete)
        assertEquals(1, db.measurementDao().count())
    }

    @Test
    fun `pending and rejected collisions preserve local lifecycle state`() = runBlocking {
        val pending = localMeasurement(MEASUREMENT_ID, SyncStatus.PENDING)
        val rejected = alert(ALERT_ID).let { mapper.toEntity(it, SyncStatus.REJECTED, "old error") }
        db.measurementDao().insertAll(listOf(pending))
        db.alertDao().insertAll(listOf(rejected))

        val result = repository(
            FakeHistoryTransport(
                HistorySnapshot(
                    measurements = listOf(measurement(MEASUREMENT_ID)),
                    symptomCheckins = emptyList(),
                    alerts = listOf(alert(ALERT_ID)),
                ),
            ),
        ).pull(url, "key", astronautId)

        assertEquals(0, result.inserted)
        assertEquals(SyncStatus.PENDING, db.measurementDao().findById(MEASUREMENT_ID)!!.localSyncStatus)
        assertEquals(SyncStatus.REJECTED, db.alertDao().findById(ALERT_ID)!!.localSyncStatus)
        assertEquals("old error", db.alertDao().findById(ALERT_ID)!!.syncError)
    }

    @Test
    fun `immutable mismatch is surfaced and local row is not overwritten`() = runBlocking {
        db.measurementDao().insertAll(listOf(localMeasurement(MEASUREMENT_ID, SyncStatus.PENDING)))
        val remote = measurement(MEASUREMENT_ID).copy(values = buildJsonObject { put("bpm", 99) })

        val result = repository(
            FakeHistoryTransport(HistorySnapshot(listOf(remote), emptyList(), emptyList())),
        ).pull(url, "key", astronautId)

        assertEquals(listOf("measurements:$MEASUREMENT_ID immutable content differs"), result.conflicts)
        assertEquals("{\"bpm\":70}", db.measurementDao().findById(MEASUREMENT_ID)!!.valuesJson)
        assertEquals(SyncStatus.PENDING, db.measurementDao().findById(MEASUREMENT_ID)!!.localSyncStatus)
    }

    @Test
    fun `immutable comparison treats numeric 70 and 70 point 0 as equal`() = runBlocking {
        db.measurementDao().insertAll(listOf(localMeasurement(MEASUREMENT_ID, SyncStatus.PENDING)))
        val remote = measurement(MEASUREMENT_ID).copy(values = buildJsonObject { put("bpm", 70.0) })

        val result = repository(
            FakeHistoryTransport(HistorySnapshot(listOf(remote), emptyList(), emptyList())),
        ).pull(url, "key", astronautId)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(1, db.measurementDao().count())
    }

    @Test
    fun `ids match case insensitively and alert ack uses canonical server timestamp`() = runBlocking {
        val localAlert = mapper.toEntity(alert(ALERT_ID).copy(acknowledgedAt = "2026-11-14T06:00:00Z"), SyncStatus.SYNCED)
        db.alertDao().insertAll(listOf(localAlert))
        val remote = alert(ALERT_ID.uppercase()).copy(acknowledgedAt = "2026-11-14T07:00:00Z")

        val result = repository(
            FakeHistoryTransport(HistorySnapshot(emptyList(), emptyList(), listOf(remote))),
        ).pull(url, "key", astronautId)

        assertEquals(0, result.inserted)
        assertEquals(1, db.alertDao().count())
        assertEquals("2026-11-14T07:00:00Z", db.alertDao().findById(ALERT_ID)!!.acknowledgedAt)
        assertEquals(SyncStatus.SYNCED, db.alertDao().findById(ALERT_ID)!!.localSyncStatus)
    }

    @Test
    fun `local pending acknowledgement is never regressed by server`() = runBlocking {
        val local = mapper.toEntity(
            alert(ALERT_ID).copy(acknowledgedAt = "2026-11-14T08:00:00Z"),
            SyncStatus.PENDING,
        )
        db.alertDao().insertAll(listOf(local))
        val remote = alert(ALERT_ID).copy(acknowledgedAt = "2026-11-14T07:00:00Z")

        repository(FakeHistoryTransport(HistorySnapshot(emptyList(), emptyList(), listOf(remote))))
            .pull(url, "key", astronautId)

        assertEquals("2026-11-14T08:00:00Z", db.alertDao().findById(ALERT_ID)!!.acknowledgedAt)
        assertEquals(SyncStatus.PENDING, db.alertDao().findById(ALERT_ID)!!.localSyncStatus)
    }

    @Test
    fun `invalid response fails before any collection is merged`() = runBlocking {
        val invalid = measurement(MEASUREMENT_ID).copy(metric = "not-a-metric")
        var failed = false
        try {
            repository(FakeHistoryTransport(HistorySnapshot(listOf(measurement(OTHER_ID), invalid), emptyList(), emptyList())))
                .pull(url, "key", astronautId)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(0, db.measurementDao().count())
    }

    @Test
    fun `astronaut mismatch fails before any collection is merged`() = runBlocking {
        var failed = false
        try {
            repository(
                FakeHistoryTransport(
                    HistorySnapshot(listOf(measurement(MEASUREMENT_ID).copy(astronautId = "other-astronaut")), emptyList(), emptyList()),
                ),
            ).pull(url, "key", astronautId)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(0, db.measurementDao().count())
    }

    @Test
    fun `settings change after fetch rejects stale response`() = runBlocking {
        val settingsRepository = FakeHistorySettingsRepository(settings())
        val transport = FakeHistoryTransport(HistorySnapshot(listOf(measurement(MEASUREMENT_ID)), emptyList(), emptyList())) {
            settingsRepository.current = settings().copy(astronautId = "other-astronaut")
        }
        var failed = false
        try {
            repository(transport, settingsRepository).pull(url, "key", astronautId)
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(0, db.measurementDao().count())
    }

    @Test
    fun `conditional upload status cannot clear a newer acknowledgement`() = runBlocking {
        db.alertDao().insertAll(listOf(mapper.toEntity(alert(ALERT_ID), SyncStatus.PENDING)))
        db.alertDao().updateSyncStatusIfAcknowledgementUnchanged(ALERT_ID, null, SyncStatus.SYNCED, null)
        db.alertDao().acknowledge(ALERT_ID, "2026-11-14T08:00:00Z")
        db.alertDao().updateSyncStatusIfAcknowledgementUnchanged(ALERT_ID, null, SyncStatus.SYNCED, null)

        val row = db.alertDao().findById(ALERT_ID)!!
        assertEquals(SyncStatus.PENDING, row.localSyncStatus)
        assertEquals("2026-11-14T08:00:00Z", row.acknowledgedAt)
    }

    @Test
    fun `API key changed during fetch prevents merging old authority response`() = runBlocking {
        val settingsRepository = FakeHistorySettingsRepository(settings())
        val transport = FakeHistoryTransport(HistorySnapshot(listOf(measurement(MEASUREMENT_ID)), emptyList(), emptyList())) {
            settingsRepository.current = settings().copy(sync = settings().sync.copy(apiKey = "new-key"))
        }
        var failed = false
        try {
            repository(transport, settingsRepository).pull(url, "key", astronautId)
        } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(0, db.measurementDao().count())
    }

    @Test
    fun `UUID looking note text is not case normalized as an identifier`() = runBlocking {
        val upperNote = "ABCDEFAB-1111-4111-8111-ABCDEFABCDEF"
        val local = measurement(MEASUREMENT_ID).copy(note = upperNote)
        db.measurementDao().insertAll(listOf(mapper.toEntity(local, SyncStatus.SYNCED)))
        val remote = local.copy(note = upperNote.lowercase())
        val result = repository(FakeHistoryTransport(HistorySnapshot(listOf(remote), emptyList(), emptyList()))).pull(url, "key", astronautId)
        assertEquals(1, result.conflicts.size)
        assertEquals(upperNote, db.measurementDao().findById(MEASUREMENT_ID)!!.note)
    }

    private fun repository(
        transport: FakeHistoryTransport,
        settings: FakeHistorySettingsRepository = FakeHistorySettingsRepository(settings()),
    ) = HistoryRepository(db, mapper, transport, settings)

    private fun settings() = AppSettings(
        astronautId = astronautId,
        deviceId = "device-1",
        sync = SyncSettings(enabled = true, baseUrl = url, apiKey = "key"),
    )

    private fun measurement(id: String) = MeasurementDto(
        schemaVersion = "1.0",
        id = id,
        astronautId = astronautId,
        metric = "heart_rate",
        values = buildJsonObject { put("bpm", 70) },
        measuredAt = "2026-11-13T10:00:00Z",
        utcOffsetMinutes = 0,
        environment = "iss",
        source = "manual",
        isSynthetic = false,
        note = null,
        createdAt = "2026-11-13T10:00:00Z",
    )

    private fun localMeasurement(id: String, status: String) = mapper.toEntity(measurement(id), status)

    private fun checkin(id: String) = SymptomCheckInDto(
        schemaVersion = "1.0",
        id = id,
        astronautId = astronautId,
        symptoms = listOf("dizziness"),
        checkedAt = "2026-11-13T10:00:00Z",
        utcOffsetMinutes = 0,
        environment = "iss",
        source = "manual",
        isSynthetic = false,
        createdAt = "2026-11-13T10:00:00Z",
    )

    private fun alert(id: String) = AlertDto(
        schemaVersion = "1.0",
        id = id,
        astronautId = astronautId,
        createdAt = "2026-11-13T10:00:00Z",
        level = "warning",
        category = "vital",
        ruleId = "HR-HIGH-W",
        ruleRevision = 1,
        ruleSetVersion = "1.0.0",
        title = "High heart rate",
        explanation = "The heart rate is above the configured warning threshold.",
        evidence = listOf(
            EvidenceDto(
                field = "heart_rate.bpm",
                observed = 70.0,
                unit = "bpm",
                comparator = ">",
                threshold = 65.0,
                observedAt = "2026-11-13T10:00:00Z",
                measurementId = MEASUREMENT_ID,
            ),
        ),
        recommendedAction = "Check the measurement and contact the flight surgeon.",
        disclaimer = "AstroCare provides general decision support only.",
        source = AlertSourceDto(ref = "rules.v1", title = "AstroCare rules"),
        triggeredBy = TriggeredByDto(measurementIds = listOf(MEASUREMENT_ID)),
        acknowledgedAt = null,
        isSynthetic = false,
    )

    private class FakeHistoryTransport(
        private val snapshot: HistorySnapshot,
        private val onFetch: () -> Unit = {},
    ) : HistoryTransport {
        override suspend fun fetch(baseUrl: String, apiKey: String, astronautId: String): HistorySnapshot {
            onFetch()
            return snapshot
        }
    }

    private class FakeHistorySettingsRepository(initial: AppSettings) : com.yasmin.astrocare.domain.repository.SettingsRepository {
        var current: AppSettings = initial
        override val settings = kotlinx.coroutines.flow.MutableStateFlow(current)
        override suspend fun snapshot(): AppSettings = current
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            current = transform(current)
            settings.value = current
        }
        override suspend fun ensureInitialized() = Unit
    }

    companion object {
        private val MEASUREMENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111").toString()
        private val OTHER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222").toString()
        private val CHECKIN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333").toString()
        private val ALERT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444").toString()
    }
}
