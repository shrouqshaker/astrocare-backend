package com.yasmin.astrocare.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.seed.SeedImporter
import com.yasmin.astrocare.data.sync.RejectedCollection
import com.yasmin.astrocare.data.sync.RejectedItemsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Room (in-memory SQLite) integration tests on the JVM via Robolectric:
 * schema creation, DAO queries, seed import and rejected-row observation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomIntegrationTest {

    private lateinit var db: AstroCareDatabase
    private lateinit var context: Context
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AstroCareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun pending(id: String, at: String) = MeasurementEntity(
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

    @Test
    fun `bundled seed imports verbatim exactly once`() = runBlocking {
        val importer = SeedImporter(
            context = context,
            measurementDao = db.measurementDao(),
            symptomCheckInDao = db.symptomCheckInDao(),
            alertDao = db.alertDao(),
            mapper = ContractMapper(json),
            json = json,
        )

        val first = importer.ensureSeeded()
        assertFalse(first.alreadySeeded)
        assertEquals(126, db.measurementDao().count())
        assertEquals(1, db.symptomCheckInDao().count())
        assertEquals(0, db.alertDao().count())
        // Seed rows ship as synced: they are already on the backend, never uploaded.
        assertEquals(0, db.measurementDao().countPending())
        assertEquals(0, db.symptomCheckInDao().countPending())

        val second = importer.ensureSeeded()
        assertTrue(second.alreadySeeded)
        assertEquals(126, db.measurementDao().count())
        assertEquals(1, db.symptomCheckInDao().count())
    }

    @Test
    fun `insert ignores duplicate ids and never overwrites`() = runBlocking {
        db.measurementDao().insertAll(listOf(pending("m-1", "2026-11-13T10:00:00Z")))
        db.measurementDao().insertAll(
            listOf(pending("m-1", "2026-11-13T10:00:00Z").copy(valuesJson = """{"bpm":99}""")),
        )
        assertEquals(1, db.measurementDao().count())
        assertEquals("""{"bpm":70}""", db.measurementDao().observeAll().first().single().valuesJson)
    }

    @Test
    fun `pending query is oldest-first and capped`() = runBlocking {
        db.measurementDao().insertAll(
            listOf(
                pending("m-3", "2026-11-13T12:00:00Z"),
                pending("m-1", "2026-11-13T08:00:00Z"),
                pending("m-2", "2026-11-13T10:00:00Z"),
            ),
        )
        assertEquals(listOf("m-1", "m-2"), db.measurementDao().getPending(2).map { it.id })
        assertEquals(3, db.measurementDao().countPending())
    }

    @Test
    fun `rejected rows leave the pending set and are observable`() = runBlocking {
        db.measurementDao().insertAll(listOf(pending("m-1", "2026-11-13T10:00:00Z")))
        db.measurementDao().updateSyncStatus("m-1", SyncStatus.REJECTED, "VALIDATION_ERROR: bad bpm")

        assertEquals(0, db.measurementDao().countPending())
        assertTrue(db.measurementDao().getPending(100).isEmpty())

        val repository = RejectedItemsRepository(
            db.measurementDao(),
            db.symptomCheckInDao(),
            db.alertDao(),
        )
        val item = repository.observe().first().single()
        assertEquals(RejectedCollection.MEASUREMENT, item.collection)
        assertEquals("m-1", item.id)
        assertEquals("VALIDATION_ERROR: bad bpm", item.error)
        assertTrue(item.label.contains("heart_rate"))
    }

    private fun alert(id: String, status: String) = AlertEntity(
        id = id,
        schemaVersion = "1.0",
        astronautId = "demo-astronaut-01",
        createdAt = "2026-11-13T10:00:00Z",
        level = "warning",
        category = "vital",
        ruleId = "HR-HIGH-W",
        ruleRevision = 1,
        ruleSetVersion = "1.0.0",
        title = "High heart rate",
        explanation = "Rule HR-HIGH-W triggered.",
        evidenceJson = "[]",
        recommendedAction = "Check the measurement and report concerns.",
        disclaimer = "AstroCare provides general decision support only.",
        sourceJson = "{}",
        triggeredByJson = "{}",
        emergencyProtocolId = null,
        acknowledgedAt = null,
        isSynthetic = false,
        localSyncStatus = status,
        syncError = null,
    )

    @Test
    fun `acknowledging a synced alert re-queues it for the backend`() = runBlocking {
        db.alertDao().insertAll(listOf(alert("a-1", SyncStatus.SYNCED)))
        db.alertDao().acknowledge("a-1", "2026-11-14T06:00:00Z")

        val row = db.alertDao().observeAll().first().single()
        assertEquals("2026-11-14T06:00:00Z", row.acknowledgedAt)
        assertEquals(SyncStatus.PENDING, row.localSyncStatus)
        assertEquals(listOf("a-1"), db.alertDao().getPending(10).map { it.id })
    }

    @Test
    fun `acknowledging a rejected alert never resurrects it`() = runBlocking {
        db.alertDao().insertAll(listOf(alert("a-2", SyncStatus.REJECTED)))
        db.alertDao().acknowledge("a-2", "2026-11-14T06:00:00Z")

        val row = db.alertDao().observeAll().first().single()
        assertEquals(SyncStatus.REJECTED, row.localSyncStatus)
        assertTrue(db.alertDao().getPending(10).isEmpty())
    }

    @Test
    fun `window queries filter by ISO timestamp range`() = runBlocking {
        db.measurementDao().insertAll(
            listOf(
                pending("m-1", "2026-11-08T00:00:00Z"),
                pending("m-2", "2026-11-10T12:00:00Z"),
                pending("m-3", "2026-11-14T00:00:00Z"),
            ),
        )
        val from = "2026-11-10T00:00:00Z"
        val to = "2026-11-14T00:00:00Z"
        val rows = db.measurementDao()
            .observeMetricWindow("heart_rate", from, to)
            .first()
        assertEquals(listOf("m-2"), rows.map { it.id })
        assertEquals(listOf("m-2", "m-3"), db.measurementDao().getFrom(from).map { it.id })
    }
}
