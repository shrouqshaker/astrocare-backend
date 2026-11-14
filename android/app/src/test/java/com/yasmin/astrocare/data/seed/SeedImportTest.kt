package com.yasmin.astrocare.data.seed

import com.yasmin.astrocare.bundledAsset
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.mapper.ContractMapper
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the frozen synthetic seed imports VERBATIM (docs/DECISIONS.md §1):
 * original ids/timestamps/values, clearly labeled, nothing regenerated.
 */
class SeedImportTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val raw: String by lazy {
        bundledAsset(SeedImporter.SEED_ASSET).readText(Charsets.UTF_8)
    }

    private val seed: SeedFile by lazy { parseSeedFile(json, raw) }

    @Test
    fun `seed parses with the expected contract counts`() {
        assertEquals("1.0", seed.schemaVersion)
        assertEquals("1.0.0", seed.seedVersion)
        assertEquals("demo-astronaut-01", seed.astronautId)
        assertEquals("iss", seed.environment)
        assertEquals(14, seed.days)
        assertEquals("2026-11-13", seed.endDate)
        assertEquals(20261113, seed.rngSeed)

        assertEquals(126, seed.measurements.size)
        assertEquals(1, seed.symptomCheckins.size)
        assertTrue("seed ships no alerts", seed.alerts.isEmpty())
    }

    @Test
    fun `original timestamps labels and provenance preserved verbatim`() {
        assertEquals(
            "SYNTHETIC DEMO DATA \u2013 NOT REAL ASTRONAUT DATA",
            seed.label,
        )
        assertEquals("2026-11-13T12:00:00Z", seed.generatedAt)

        // Original seed window — never shifted to "today" (gap to today is intentional).
        val times = seed.measurements.map { it.measuredAt }.sorted()
        assertTrue("earliest stays on 2026-10-31", times.first().startsWith("2026-10-31"))
        assertEquals("2026-10-31T06:15:00Z", times.first())
        assertTrue("latest stays on 2026-11-13", times.last().startsWith("2026-11-13"))
        assertEquals("2026-11-13T17:19:00Z", times.last())

        // Every row is clearly labeled synthetic demo data.
        assertTrue(seed.measurements.all { it.isSynthetic && it.source == "synthetic" })
        assertTrue(seed.symptomCheckins.all { it.isSynthetic && it.source == "synthetic" })
        assertTrue(seed.measurements.all { it.astronautId == seed.astronautId })

        // All five contract metrics are present with their metric-dependent values.
        assertEquals(
            setOf("heart_rate", "blood_pressure", "spo2", "temperature", "sleep"),
            seed.measurements.map { it.metric }.toSet(),
        )
        assertTrue(seed.measurements.all { it.values.isNotEmpty() })

        // The one check-in keeps its original content.
        val checkIn = seed.symptomCheckins.single()
        assertEquals("9fd46ccc-d519-5ba5-a990-651f60bf331c", checkIn.id)
        assertEquals(listOf("dizziness"), checkIn.symptoms)
        assertEquals("2026-11-13T17:00:00Z", checkIn.checkedAt)
    }

    @Test
    fun `maps to room rows with synced status and lossless round-trip`() {
        val mapper = ContractMapper(json)

        val measurement = seed.measurements.single { it.id == "4dba7f59-3936-528b-b1b1-86aa51fd641c" }
        val entity = mapper.toEntity(measurement, localSyncStatus = SyncStatus.SYNCED)

        // Android-only columns set as designed; contract fields untouched.
        assertEquals(SyncStatus.SYNCED, entity.localSyncStatus)
        assertEquals(null, entity.syncError)
        assertEquals(measurement.measuredAt, entity.measuredAt)
        assertEquals(measurement.createdAt, entity.createdAt)

        // DTO → entity → DTO is lossless (incl. the metric-dependent values object).
        assertEquals(measurement, mapper.toDto(entity))

        // Check-in and its (absent) triage round-trip too.
        val checkIn = seed.symptomCheckins.single()
        val checkInEntity = mapper.toEntity(checkIn, localSyncStatus = SyncStatus.SYNCED)
        assertEquals(null, checkInEntity.chestPainTriageJson)
        assertEquals(checkIn, mapper.toDto(checkInEntity))
    }
}
