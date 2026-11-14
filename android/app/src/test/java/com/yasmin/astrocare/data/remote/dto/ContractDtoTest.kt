package com.yasmin.astrocare.data.remote.dto

import com.yasmin.astrocare.contractExample
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Contract conformance tests: DTOs must decode/encode the frozen examples from
 * `contracts/examples/` and must NEVER carry Android-only fields
 * (localSyncStatus/syncError) into any payload.
 */
class ContractDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val mapper = ContractMapper(json)

    private fun read(name: String): String = contractExample(name).readText(Charsets.UTF_8)

    // ------------------------------------------------------------ example round-trips

    @Test
    fun `measurement example round-trips`() {
        val dto = json.decodeFromString<MeasurementDto>(read("measurement.json"))
        val again = json.decodeFromString<MeasurementDto>(json.encodeToString(dto))
        assertEquals(dto, again)

        assertEquals("heart_rate", dto.metric)
        assertEquals(68.0, (dto.values["bpm"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        assertTrue(dto.isSynthetic)
        assertNotNull(dto.note)
    }

    @Test
    fun `symptom checkin example round-trips`() {
        val dto = json.decodeFromString<SymptomCheckInDto>(read("symptom-checkin.json"))
        val again = json.decodeFromString<SymptomCheckInDto>(json.encodeToString(dto))
        assertEquals(dto, again)
        assertEquals(listOf("dizziness"), dto.symptoms)
        assertNull(dto.chestPainTriage)
    }

    @Test
    fun `alert example round-trips with evidence and triggeredBy intact`() {
        val dto = json.decodeFromString<AlertDto>(read("alert.json"))
        val again = json.decodeFromString<AlertDto>(json.encodeToString(dto))
        assertEquals(dto, again)

        assertTrue(dto.level == "warning" || dto.level == "critical")
        assertTrue(dto.evidence.isNotEmpty())
        assertTrue(
            dto.triggeredBy.measurementIds.isNotEmpty() ||
                dto.triggeredBy.symptomCheckinIds.isNotEmpty(),
        )
        assertTrue(dto.isSynthetic)
    }

    @Test
    fun `sync request example round-trips with the three-array envelope`() {
        val dto = json.decodeFromString<SyncBatchRequestDto>(read("sync-request.json"))
        val again = json.decodeFromString<SyncBatchRequestDto>(json.encodeToString(dto))
        assertEquals(dto, again)

        assertEquals("1.0", dto.schemaVersion)
        assertEquals("demo-astronaut-01", dto.astronautId)
        assertEquals("1.0.0", dto.clientRuleSetVersion)
        assertTrue(dto.items.measurements.isNotEmpty())
        assertTrue(dto.items.symptomCheckins.isNotEmpty())
    }

    @Test
    fun `sync response example round-trips with per-item results`() {
        val dto = json.decodeFromString<SyncBatchResponseDto>(read("sync-response.json"))
        val again = json.decodeFromString<SyncBatchResponseDto>(json.encodeToString(dto))
        assertEquals(dto, again)

        assertEquals(3, dto.summary.created)
        assertEquals(0, dto.summary.rejected)
        assertEquals("created", dto.results.measurements.single().status.name.lowercase())
        assertNull(dto.results.measurements.single().error)
        assertEquals("1.0.0", dto.latestRuleSetVersion)
    }

    // ------------------------------------------------------------ payload invariants

    @Test
    fun `android-only fields never appear in any payload`() {
        // Entities carrying sync state (the state that must NOT leak):
        val measurement = mapper.toEntity(
            json.decodeFromString<MeasurementDto>(read("measurement.json")),
            localSyncStatus = SyncStatus.REJECTED,
            syncError = "VALIDATION_ERROR: bad value",
        )
        val checkIn = mapper.toEntity(
            json.decodeFromString<SymptomCheckInDto>(read("symptom-checkin.json")),
            localSyncStatus = SyncStatus.PENDING,
            syncError = "pending",
        )
        val alert = mapper.toEntity(
            json.decodeFromString<AlertDto>(read("alert.json")),
            localSyncStatus = SyncStatus.REJECTED,
            syncError = "ASTRONAUT_MISMATCH",
        )

        for (payload in listOf(
            json.encodeToString(mapper.toDto(measurement)),
            json.encodeToString(mapper.toDto(checkIn)),
            json.encodeToString(mapper.toDto(alert)),
        )) {
            assertFalse("localSyncStatus leaked: $payload", payload.contains("localSyncStatus"))
            assertFalse("syncError leaked: $payload", payload.contains("syncError"))
        }

        // And the DTO types do not even declare those fields.
        val measurementKeys = json.encodeToString(mapper.toDto(measurement)).let {
            json.parseToJsonElement(it).jsonObject.keys
        }
        assertFalse("localSyncStatus" in measurementKeys)
        assertFalse("syncError" in measurementKeys)
    }

    @Test
    fun `required fields are always encoded even with defaults involved`() {
        val alert = json.decodeFromString<AlertDto>(read("alert.json"))
        val encoded = json.parseToJsonElement(json.encodeToString(alert)).jsonObject
        for (key in listOf(
            "schemaVersion", "id", "astronautId", "createdAt", "level", "category",
            "ruleId", "ruleRevision", "ruleSetVersion", "title", "explanation",
            "evidence", "recommendedAction", "disclaimer", "source", "triggeredBy",
            "isSynthetic",
        )) {
            assertTrue("missing required key $key", key in encoded)
        }
    }

    // ------------------------------------------------------------ strictness

    @Test
    fun `unknown fields are ignored on responses`() {
        val withExtras = read("sync-response.json")
            .trimEnd()
            .dropLast(1) + ",\"futureServerField\":{\"nested\":true}}"
        val dto = json.decodeFromString<SyncBatchResponseDto>(withExtras)
        assertEquals("234feff0-0552-462b-9368-868b42b56bf5", dto.batchId)
    }

    @Test
    fun `missing required field fails fast`() {
        val broken = read("alert.json").replace(
            Regex("\"ruleId\"\\s*:\\s*\"[^\"]*\","),
            "",
        )
        try {
            json.decodeFromString<AlertDto>(broken)
            fail("expected SerializationException for missing ruleId")
        } catch (expected: SerializationException) {
            // fails fast on missing required contract fields
        }
    }

    @Test
    fun `invalid enum value is rejected`() {
        val broken = read("sync-response.json").replace(
            "\"status\": \"created\"",
            "\"status\": \"exploded\"",
        )
        try {
            json.decodeFromString<SyncBatchResponseDto>(broken)
            fail("expected SerializationException for unknown status")
        } catch (expected: SerializationException) {
            // unknown enum value must not be silently accepted
        }
    }

    @Test
    fun `sync item statuses map to contract literals`() {
        assertEquals("created", SyncItemStatus.CREATED.name.lowercase())
        assertEquals("duplicate", SyncItemStatus.DUPLICATE.name.lowercase())
        assertEquals("rejected", SyncItemStatus.REJECTED.name.lowercase())
        assertEquals(
            "VALIDATION_ERROR",
            SyncErrorCode.VALIDATION_ERROR.name,
        )
    }
}
