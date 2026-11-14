package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic envelope tests (docs/DECISIONS.md §3):
 * the same pending set must always produce the same batchId, and the payload
 * must never contain Android-only fields.
 */
class SyncBatchPlannerTest {

    private val json = Json { explicitNulls = false }

    private fun measurement(id: String) = MeasurementDto(
        schemaVersion = "1.0",
        id = id,
        astronautId = "demo-astronaut-01",
        metric = "heart_rate",
        values = buildJsonObject { put("bpm", 70) },
        measuredAt = "2026-11-13T10:00:00Z",
        utcOffsetMinutes = 0,
        environment = "iss",
        source = "manual",
        isSynthetic = false,
        createdAt = "2026-11-13T10:00:00Z",
    )

    private fun plan(vararg ids: String) = SyncBatchPlanner.plan(
        schemaVersion = "1.0",
        astronautId = "demo-astronaut-01",
        deviceId = "device-1",
        sentAt = "2026-11-14T06:00:00Z",
        clientRuleSetVersion = "1.0.0",
        measurements = ids.map(::measurement),
        symptomCheckins = emptyList(),
        alerts = emptyList(),
    )

    @Test
    fun `same pending set always yields the same batchId`() {
        val a = plan("m-2", "m-1")
        val b = plan("m-1", "m-2")
        assertEquals(a.batchId, b.batchId)
        // UUID format with version 5 / IETF variant bits.
        assertTrue(a.batchId.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
    }

    @Test
    fun `a changed pending set is a new batch`() {
        val a = plan("m-1")
        val b = plan("m-1", "m-2")
        assertNotEquals(a.batchId, b.batchId)
    }

    @Test
    fun `batchId is scoped to the astronaut`() {
        val base = SyncBatchPlanner.batchId("astronaut-a", listOf("m-1"), emptyList(), emptyList())
        val other = SyncBatchPlanner.batchId("astronaut-b", listOf("m-1"), emptyList(), emptyList())
        assertNotEquals(base, other)
    }

    @Test
    fun `collections cannot collide through id reuse`() {
        val asMeasurement = SyncBatchPlanner.batchId("a", listOf("x"), emptyList(), emptyList())
        val asCheckin = SyncBatchPlanner.batchId("a", emptyList(), listOf("x"), emptyList())
        val asAlert = SyncBatchPlanner.batchId("a", emptyList(), emptyList(), listOf("x"))
        assertEquals(3, setOf(asMeasurement, asCheckin, asAlert).size)
    }

    @Test
    fun `items are sorted by id and capped at 100 per collection`() {
        val ids = (1..150).map { "m-%03d".format(it) }
        val planned = plan(*ids.toTypedArray())
        assertEquals(100, planned.items.measurements.size)
        assertEquals(ids.take(100), planned.items.measurements.map { it.id })
    }

    @Test
    fun `payload always includes all three item arrays required by the backend`() {
        val encoded = json.encodeToString(plan("m-1"))
        assertTrue(encoded, encoded.contains("\"symptomCheckins\""))
        assertTrue(encoded, encoded.contains("\"alerts\""))
    }

    @Test
    fun `payload carries contract fields only`() {
        val encoded = json.encodeToString(plan("m-1"))
        assertTrue(encoded.contains("\"batchId\""))
        assertTrue(encoded.contains("\"measurements\""))
        assertFalse(encoded.contains("localSyncStatus"))
        assertFalse(encoded.contains("syncError"))
        assertFalse(encoded.contains("clientId"))
    }
}
