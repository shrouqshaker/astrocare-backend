package com.yasmin.astrocare.domain.trend

import com.yasmin.astrocare.domain.rule.RuleTestFixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 5 GATE — runs `contracts/test-vectors/trend-vectors.v1.json`
 * VERBATIM (copied byte-for-byte to
 * `app/src/test/resources/contracts/trend-vectors.v1.json`): every expected
 * aggregate (dailyCount, weekMean, latest, latestAtDate, missingDays) must
 * match TrendCalculator.aggregate exactly. Vectors are never modified.
 */
class TrendVectorAcceptanceTest {

    @Serializable
    private data class VectorFile(
        val schemaVersion: String = "",
        val ruleSetVersion: String = "",
        val note: String = "",
        val vectors: List<TrendVector> = emptyList(),
    )

    @Serializable
    private data class TrendVector(
        val name: String,
        val now: String,
        val utcOffsetMinutes: Int = 0,
        val input: TrendVectorInput,
        val expected: TrendVectorExpected,
    )

    @Serializable
    private data class TrendVectorInput(
        val metric: String,
        val field: String,
        val dailyValues: List<DailyValuesDto> = emptyList(),
    )

    @Serializable
    private data class DailyValuesDto(
        val date: String,
        val values: List<Double> = emptyList(),
    )

    @Serializable
    private data class TrendVectorExpected(
        val dailyCount: Int,
        val weekMean: Double? = null,
        val latest: Double? = null,
        val latestAtDate: String? = null,
        val missingDays: Int = 0,
    )

    private fun loadTrendVectors(): VectorFile {
        val raw = TrendVectorAcceptanceTest::class.java
            .getResourceAsStream("/contracts/trend-vectors.v1.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("trend-vectors.v1.json missing from app/src/test/resources/contracts/")
        return RuleTestFixtures.json.decodeFromString<VectorFile>(raw)
    }

    @Test
    fun allFiveContractTrendVectorsPass() {
        val file = loadTrendVectors()
        assertEquals("1.0", file.schemaVersion)
        assertEquals("1.0.0", file.ruleSetVersion)
        assertEquals(
            listOf(
                "heart_rate-seven-days",
                "spo2-seven-days",
                "temperature-seven-days",
                "sleep-seven-days",
                "missing-days",
            ),
            file.vectors.map { it.name },
        )

        val calculator = TrendCalculator(
            policy = RuleTestFixtures.ruleSet.trendPolicy,
            catalog = RuleTestFixtures.catalog,
        )

        for (vector in file.vectors) {
            val daily = vector.input.dailyValues.map { DailyValues(it.date, it.values) }
            val actual = calculator.aggregate(daily)
            val expected = vector.expected

            assertEquals("${vector.name}: dailyCount", expected.dailyCount, actual.dailyCount)
            assertEquals("${vector.name}: missingDays", expected.missingDays, actual.missingDays)

            expected.weekMean?.let {
                assertNotNull("${vector.name}: weekMean expected", actual.weekMean)
                assertEquals("${vector.name}: weekMean", it, actual.weekMean!!, 1e-9)
            }
            expected.latest?.let {
                assertNotNull("${vector.name}: latest expected", actual.latest)
                assertEquals("${vector.name}: latest", it, actual.latest!!, 1e-9)
            }
            assertEquals("${vector.name}: latestAtDate", expected.latestAtDate, actual.latestAtDate)
        }
    }

    @Test
    fun gapVectorNeverZeroFillsMissingDays() {
        val vector = loadTrendVectors().vectors.first { it.name == "missing-days" }
        val calculator = TrendCalculator(
            policy = RuleTestFixtures.ruleSet.trendPolicy,
            catalog = RuleTestFixtures.catalog,
        )
        val actual = calculator.aggregate(
            vector.input.dailyValues.map { DailyValues(it.date, it.values) },
        )
        // 4 gap days, mean over the 3 days that HAVE data (never 0-filled).
        assertEquals(4, actual.missingDays)
        assertEquals(73.0, actual.weekMean!!, 1e-9)
        assertTrue("weekMean must not be the zero-filled mean", actual.weekMean!! > 0.0)
        assertEquals("2026-11-13", actual.latestAtDate)
    }

    @Test
    fun aggregateOfAllEmptyDaysIsNullNotZero() {
        val calculator = TrendCalculator(
            policy = RuleTestFixtures.ruleSet.trendPolicy,
            catalog = RuleTestFixtures.catalog,
        )
        val actual = calculator.aggregate(
            (7..13).map { DailyValues("2026-11-%02d".format(it), emptyList()) },
        )
        assertEquals(7, actual.dailyCount)
        assertEquals(7, actual.missingDays)
        assertNull(actual.weekMean)
        assertNull(actual.latest)
        assertNull(actual.latestAtDate)
    }
}
