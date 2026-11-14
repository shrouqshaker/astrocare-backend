package com.yasmin.astrocare.domain.rule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the ID namespace/name layout to the AI/Data Python reference and the
 * Node backend (`docs/AI-DATA-INTEGRATION.md` §3). A mismatch would store the
 * same alert twice under different ids.
 */
class StableUuidTest {

    @Test
    fun `known reference value matches Python and Node`() {
        assertEquals("0565a3b2-100b-5ef6-ba25-fd0842c4fb01", StableUuid.v5("test"))
    }

    @Test
    fun `reference parity alert id matches the frozen fixture`() {
        // test/fixtures/reference-parity.v1.json -> HR-HIGH-W
        assertEquals(
            "6ce4d0e5-d55f-517f-86c1-7c8a5703cfe3",
            StableUuid.alertId(
                astronautId = "demo-astronaut-01",
                ruleId = "HR-HIGH-W",
                revision = 1,
                measurementIds = listOf("df4287d4-624e-5920-92b3-96ed4ef1ca45"),
                symptomCheckinIds = emptyList(),
            ),
        )
    }

    @Test
    fun `ids are order-insensitive within each collection`() {
        val a = StableUuid.alertId("a", "R-X", 1, listOf("m-2", "m-1"), listOf("c-2", "c-1"))
        val b = StableUuid.alertId("a", "R-X", 1, listOf("m-1", "m-2"), listOf("c-1", "c-2"))
        assertEquals(a, b)
    }
}
