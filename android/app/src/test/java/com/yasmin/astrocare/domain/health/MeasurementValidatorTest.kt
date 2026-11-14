package com.yasmin.astrocare.domain.health

import com.yasmin.astrocare.domain.rule.RuleTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entry-time validation mirrors the engine's contract rules: enums.json
 * min/max bounds, integer types and the metric crossFieldRule. Nothing is
 * hardcoded — every bound comes from the real bundled catalog.
 */
class MeasurementValidatorTest {

    private val catalog get() = RuleTestFixtures.catalog

    @Test
    fun validHeartRatePasses() {
        assertEquals(
            emptyList<String>(),
            MeasurementValidator.validate(catalog, "heart_rate", mapOf("bpm" to 72.0)),
        )
    }

    @Test
    fun validBloodPressurePasses() {
        assertEquals(
            emptyList<String>(),
            MeasurementValidator.validate(
                catalog,
                "blood_pressure",
                mapOf("systolic" to 120.0, "diastolic" to 80.0),
            ),
        )
    }

    @Test
    fun decimalAllowedForNumberType() {
        assertEquals(
            emptyList<String>(),
            MeasurementValidator.validate(catalog, "spo2", mapOf("percent" to 97.5)),
        )
        assertEquals(
            emptyList<String>(),
            MeasurementValidator.validate(catalog, "temperature", mapOf("celsius" to 36.6)),
        )
    }

    @Test
    fun outOfRangeValueRejected() {
        val problems = MeasurementValidator.validate(catalog, "heart_rate", mapOf("bpm" to 300.0))
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("between 20 and 250"))
    }

    @Test
    fun nonIntegerRejectedForIntegerField() {
        val problems = MeasurementValidator.validate(catalog, "heart_rate", mapOf("bpm" to 72.5))
        assertTrue(problems.any { it.contains("whole number") })
    }

    @Test
    fun missingFieldRejected() {
        val problems = MeasurementValidator.validate(
            catalog,
            "blood_pressure",
            mapOf("systolic" to 120.0),
        )
        assertTrue(problems.any { it.contains("missing value for 'diastolic'") })
    }

    @Test
    fun crossFieldRuleEnforced() {
        // enums.json: blood_pressure crossFieldRule "systolic > diastolic".
        val problems = MeasurementValidator.validate(
            catalog,
            "blood_pressure",
            mapOf("systolic" to 80.0, "diastolic" to 90.0),
        )
        assertTrue(problems.any { it.contains("'systolic' must be > 'diastolic'") })
    }

    @Test
    fun unknownMetricRejected() {
        val problems = MeasurementValidator.validate(catalog, "hydration", mapOf("x" to 1.0))
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("unknown metric"))
    }

    @Test
    fun unknownFieldRejected() {
        val problems = MeasurementValidator.validate(
            catalog,
            "heart_rate",
            mapOf("bpm" to 72.0, "extra" to 1.0),
        )
        assertTrue(problems.any { it.contains("unknown field 'extra'") })
    }

    @Test
    fun emptyValuesRejected() {
        val problems = MeasurementValidator.validate(catalog, "heart_rate", emptyMap())
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("no values entered"))
    }

    @Test
    fun nonFiniteValueRejected() {
        val problems = MeasurementValidator.validate(catalog, "spo2", mapOf("percent" to Double.NaN))
        assertTrue(problems.any { it.contains("finite") })
    }
}
