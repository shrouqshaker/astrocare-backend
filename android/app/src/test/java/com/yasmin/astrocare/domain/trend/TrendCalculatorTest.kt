package com.yasmin.astrocare.domain.trend

import com.yasmin.astrocare.domain.rule.MeasurementRecord
import com.yasmin.astrocare.domain.rule.RuleTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TrendReport builder behavior (contracts/schemas/trend-report.schema.json):
 * 7-day local window, gap points, direction policy, baseline insufficiency
 * surfaced with a reason, and out-of-window records excluded from points.
 */
class TrendCalculatorTest {

    private val calculator = TrendCalculator(
        policy = RuleTestFixtures.ruleSet.trendPolicy,
        catalog = RuleTestFixtures.catalog,
    )

    private val now = "2026-11-13T18:00:00Z"
    private val nowMillis = RuleTestFixtures.parse(now)

    private fun record(
        metric: String,
        values: Map<String, Double>,
        at: String,
        id: String,
    ): MeasurementRecord = MeasurementRecord(
        id = id,
        metric = metric,
        values = values,
        observedAt = at,
        observedAtMillis = RuleTestFixtures.parse(at),
        isSynthetic = false,
    )

    private fun report(records: List<MeasurementRecord>, offset: Int = 0): TrendReport =
        calculator.buildReport(
            astronautId = "demo-astronaut-01",
            nowMillis = nowMillis,
            nowIso = now,
            utcOffsetMinutes = offset,
            records = records,
            ruleSetVersion = "1.0.0",
        )

    private fun TrendReport.field(fieldKey: String): FieldTrend =
        metrics.first { it.metric == fieldKey.substringBefore('.') }
            .fields.first { it.field == fieldKey }

    @Test
    fun reportWindowCoversSevenLocalDaysEndingToday() {
        val result = report(emptyList())
        assertEquals(TrendWindow("2026-11-07", "2026-11-13", 7, 0), result.window)
        assertEquals("1.0", result.schemaVersion)
        assertEquals("android_local", result.generatedBy)
        assertEquals("1.0.0", result.ruleSetVersion)
        assertEquals(now, result.generatedAt)
        // Every catalog metric appears even with no data (schema minItems=1).
        assertEquals(
            RuleTestFixtures.catalog.metrics.keys.toList(),
            result.metrics.map { it.metric },
        )
        assertTrue(result.insights.isEmpty())
    }

    @Test
    fun gapsProduceCountZeroPointsAndWeekMeanSkipsThem() {
        val records = listOf(
            record("heart_rate", mapOf("bpm" to 70.0), "2026-11-07T09:00:00Z", "m1"),
            record("heart_rate", mapOf("bpm" to 73.0), "2026-11-10T09:00:00Z", "m2"),
            record("heart_rate", mapOf("bpm" to 76.0), "2026-11-13T09:00:00Z", "m3"),
        )
        val trend = report(records).field("heart_rate.bpm")

        assertEquals(7, trend.points.size)
        assertEquals(
            listOf("2026-11-07", "2026-11-10", "2026-11-13"),
            trend.points.filter { it.count > 0 }.map { it.date },
        )
        // Gap days: count 0 and mean null (never zero-filled).
        val gap = trend.points.first { it.date == "2026-11-08" }
        assertEquals(0, gap.count)
        assertNull(gap.mean)
        assertNull(gap.min)
        assertNull(gap.max)
        // weekMean averages the three days WITH data: (70+73+76)/3 = 73.0.
        assertEquals(73.0, trend.weekMean!!, 1e-9)
        assertEquals(76.0, trend.latest!!, 1e-9)
        assertEquals("2026-11-13T09:00:00Z", trend.latestAt)
    }

    @Test
    fun recordsOutsideWindowAreExcludedFromPoints() {
        val records = listOf(
            record("heart_rate", mapOf("bpm" to 70.0), "2026-11-05T09:00:00Z", "old"),
        )
        val trend = report(records).field("heart_rate.bpm")
        assertTrue(trend.points.all { it.count == 0 })
        assertNull(trend.latest)
        assertNull(trend.weekMean)
    }

    @Test
    fun directionIsUpWhenRecentMeanExceedsBand() {
        val records = (0..6).map { day ->
            val value = if (day < 4) 70.0 else 80.0
            record(
                metric = "heart_rate",
                values = mapOf("bpm" to value),
                at = "2026-11-%02dT09:00:00Z".format(7 + day),
                id = "up-$day",
            )
        }
        // earlier 4 days mean 70, recent 3 days mean 80 -> diff 10 > band 5.
        assertEquals(TrendDirection.up, report(records).field("heart_rate.bpm").direction)
    }

    @Test
    fun directionIsStableWithinBand() {
        val records = (0..6).map { day ->
            val value = if (day < 4) 70.0 else 72.0
            record(
                metric = "heart_rate",
                values = mapOf("bpm" to value),
                at = "2026-11-%02dT09:00:00Z".format(7 + day),
                id = "stable-$day",
            )
        }
        // diff 2 <= band 5 -> stable.
        assertEquals(TrendDirection.stable, report(records).field("heart_rate.bpm").direction)
    }

    @Test
    fun directionIsDownWhenRecentMeanDrops() {
        val records = (0..6).map { day ->
            val value = if (day < 4) 90.0 else 70.0
            record(
                metric = "heart_rate",
                values = mapOf("bpm" to value),
                at = "2026-11-%02dT09:00:00Z".format(7 + day),
                id = "down-$day",
            )
        }
        assertEquals(TrendDirection.down, report(records).field("heart_rate.bpm").direction)
    }

    @Test
    fun directionIsInsufficientWhenASideHasTooFewDays() {
        // Only the recent side has data -> earlier side has 0 days (< min 2).
        val records = (4..6).map { day ->
            record(
                metric = "heart_rate",
                values = mapOf("bpm" to 75.0),
                at = "2026-11-%02dT09:00:00Z".format(7 + day),
                id = "ins-$day",
            )
        }
        assertEquals(
            TrendDirection.insufficient_data,
            report(records).field("heart_rate.bpm").direction,
        )
    }

    @Test
    fun insufficientBaselineIsSurfacedWithReason() {
        // 3 samples on 2 distinct days, all outside the excluded last 24h:
        // below minSamples=5 and minDistinctDays=3.
        val records = listOf(
            record("spo2", mapOf("percent" to 97.0), "2026-11-01T09:00:00Z", "b1"),
            record("spo2", mapOf("percent" to 98.0), "2026-11-01T18:00:00Z", "b2"),
            record("spo2", mapOf("percent" to 99.0), "2026-11-02T09:00:00Z", "b3"),
        )
        val trend = report(records).field("spo2.percent")
        assertNull(trend.baselineMean)
        assertNull(trend.baselineSd)
        assertNull(trend.deltaVsBaseline)

        val metricBaseline = report(records).metrics.first { it.metric == "spo2" }.baseline
        assertTrue(!metricBaseline.available)
        assertNotNull(metricBaseline.reason)
        assertEquals(14, metricBaseline.windowDays)
    }

    @Test
    fun sufficientBaselineIsComputedAndDeltaRendered() {
        // 6 samples over 3 distinct days: inside the 7-day window AND older
        // than the excluded last 24h, so both window and baseline see them.
        val records = (0 until 6).map { i ->
            record(
                metric = "spo2",
                values = mapOf("percent" to (96.0 + (i % 4))),
                at = "2026-11-%02dT%02d:00:00Z".format(7 + i / 2, 8 + i),
                id = "s$i",
            )
        }
        val trend = report(records).field("spo2.percent")
        assertNotNull(trend.baselineMean)
        assertNotNull(trend.baselineSd)
        // Newest in-window reading exists too (2026-11-07..09 inside window).
        assertNotNull(trend.latest)
        assertNotNull(trend.deltaVsBaseline)
        assertTrue(report(records).metrics.first { it.metric == "spo2" }.baseline.available)
    }

    @Test
    fun emptyDataYieldsNullFieldValuesAndUnavailableBaseline() {
        val result = report(emptyList())
        val trend = result.field("temperature.celsius")
        assertNull(trend.latest)
        assertNull(trend.weekMean)
        assertNull(trend.baselineMean)
        assertEquals(TrendDirection.insufficient_data, trend.direction)
        val baseline = result.metrics.first { it.metric == "temperature" }.baseline
        assertTrue(!baseline.available)
        assertNotNull(baseline.reason)
    }
}
