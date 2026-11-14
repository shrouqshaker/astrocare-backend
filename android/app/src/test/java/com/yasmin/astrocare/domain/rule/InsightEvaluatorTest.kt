package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.core.common.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Insight rules (baseline_deviation / window_average): calculations, rounding
 * per trendPolicy, insufficient baseline samples, and too-few-readings cases.
 * Insights never produce alerts and never affect overall status.
 */
class InsightEvaluatorTest {

    private val ruleSet = RuleTestFixtures.ruleSet
    private val catalog = RuleTestFixtures.catalog
    private val nowMillis = RuleTestFixtures.parse("2026-11-13T18:00:00Z")

    private fun reading(field: String, metric: String, value: Double, atMillis: Long, index: Int) =
        ContextMeasurement(
            field = field,
            metric = metric,
            value = value,
            observedAt = IsoTime.formatUtc(atMillis),
            observedAtMillis = atMillis,
            measurementId = "50000000-0000-4000-8000-%012d".format(index),
        )

    private fun hoursAgo(hours: Long): Long = nowMillis - hours * 60 * 60 * 1000
    private fun daysAgo(days: Long): Long = nowMillis - days * 24 * 60 * 60 * 1000

    private fun contextWith(
        latest: Map<String, Double>,
        history: Map<String, List<ContextMeasurement>>,
    ): EvaluationContext {
        val base = RuleTestFixtures.context(latest)
        return base.copy(fieldHistory = base.fieldHistory + history)
    }

    private fun evaluate(context: EvaluationContext): InsightOutcome =
        InsightEvaluator(ruleSet, catalog).evaluate(context)

    // ------------------------------------------------------------------ baseline_deviation

    @Test
    fun `baseline deviation fires with contract rounding for mean sd and z`() {
        // 10 samples across 4 distinct days, all outside the 24h exclusion
        val values = listOf(60.0, 61.0, 59.0, 60.0, 61.0, 59.0, 60.0, 61.0, 59.0, 60.0)
        val history = values.mapIndexed { index, value ->
            reading("heart_rate.bpm", "heart_rate", value, daysAgo(2L + index % 4), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("heart_rate.bpm" to 72.0), history = mapOf("heart_rate.bpm" to history)))

        val insight = outcome.insights.single { it.ruleId == "INS-BASE-HR" }
        assertEquals("info", insight.severity)
        assertEquals("heart_rate.bpm", insight.field)
        assertEquals(
            "Your latest heart rate differs notably from your personal baseline.",
            insight.text,
        )

        val evidence = insight.evidence!!
        // mean 60.0, sample SD sqrt(6/9)=0.816... -> 0.82; sdFloor for HR is 5
        assertEquals(72.0, evidence.observed, 0.0)
        assertEquals(60.0, evidence.baselineMean!!, 0.0)
        assertEquals(0.82, evidence.baselineSd!!, 0.001)
        assertEquals(2.4, evidence.zScore!!, 0.001) // (72-60)/max(0.82, 5)
        assertEquals(">", evidence.comparator)
        assertEquals(60.0, evidence.threshold, 0.0)
        assertEquals("bpm", evidence.unit)
        assertTrue(outcome.diagnostics.none { it.startsWith("INS-BASE-HR:") })
    }

    @Test
    fun `insufficient baseline samples produce a diagnostic and no insight`() {
        // 3 samples (need 5) across 3 distinct days
        val history = listOf(60.0, 61.0, 59.0).mapIndexed { index, value ->
            reading("heart_rate.bpm", "heart_rate", value, daysAgo(2L + index), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("heart_rate.bpm" to 72.0), history = mapOf("heart_rate.bpm" to history)))

        assertTrue(outcome.insights.none { it.ruleId == "INS-BASE-HR" })
        val note = outcome.diagnostics.single { it.startsWith("INS-BASE-HR:") }
        assertTrue(note, note.contains("had 3 samples"))
        assertTrue(note, note.contains("insight not evaluated"))
    }

    @Test
    fun `insufficient distinct days produce a diagnostic even with enough samples`() {
        // 6 samples all on the SAME UTC day (need 3 distinct days)
        val history = (0 until 6).map { index ->
            reading("heart_rate.bpm", "heart_rate", 60.0 + index, hoursAgo(26L + index), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("heart_rate.bpm" to 72.0), history = mapOf("heart_rate.bpm" to history)))

        assertTrue(outcome.insights.none { it.ruleId == "INS-BASE-HR" })
        val note = outcome.diagnostics.single { it.startsWith("INS-BASE-HR:") }
        assertTrue(note, note.contains("across 1 day(s)"))
    }

    @Test
    fun `no insight when the latest value is inside the deviation band`() {
        val values = listOf(60.0, 61.0, 59.0, 60.0, 61.0, 59.0, 60.0, 61.0, 59.0, 60.0)
        val history = values.mapIndexed { index, value ->
            reading("heart_rate.bpm", "heart_rate", value, daysAgo(2L + index % 4), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("heart_rate.bpm" to 60.5), history = mapOf("heart_rate.bpm" to history)))
        assertTrue(outcome.insights.none { it.ruleId == "INS-BASE-HR" })
        // a normal non-fire produces NO diagnostic for that rule
        assertTrue(outcome.diagnostics.none { it.startsWith("INS-BASE-HR:") })
    }

    @Test
    fun `baseline deviation requires the latest value in context`() {
        val outcome = evaluate(contextWith(latest = emptyMap(), history = emptyMap()))
        assertTrue(outcome.insights.isEmpty())
        assertTrue(
            outcome.diagnostics.any { it.startsWith("INS-BASE-HR:") && it.contains("not in context") },
        )
    }

    // ------------------------------------------------------------------ window_average

    @Test
    fun `window average fires when the mean of the last N readings matches the op`() {
        // newest first: 4.5, 5.5, 6.5 -> mean 5.5 (below the contract value)
        val history = listOf(4.5, 5.5, 6.5).mapIndexed { index, value ->
            reading("sleep.hours", "sleep", value, daysAgo(1L + index), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("sleep.hours" to 4.5), history = mapOf("sleep.hours" to history)))

        val insight = outcome.insights.single { it.ruleId == "INS-TRND-SLEEP" }
        assertEquals("warning", insight.severity)
        assertEquals(
            "Average sleep over your last 3 logged nights is below 6 hours.",
            insight.text,
        )
        val evidence = insight.evidence!!
        assertEquals(5.5, evidence.observed, 0.0)
        assertEquals("<", evidence.comparator)
        assertEquals(6.0, evidence.threshold, 0.0)
        assertEquals("hours", evidence.unit)
        assertNull(evidence.baselineMean)
        assertNull(evidence.zScore)
    }

    @Test
    fun `window average is not evaluated with fewer than lastN readings`() {
        val history = listOf(4.5, 5.5).mapIndexed { index, value ->
            reading("sleep.hours", "sleep", value, daysAgo(1L + index), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("sleep.hours" to 4.5), history = mapOf("sleep.hours" to history)))

        assertTrue(outcome.insights.none { it.ruleId == "INS-TRND-SLEEP" })
        val note = outcome.diagnostics.single { it.startsWith("INS-TRND-SLEEP:") }
        assertTrue(note, note.contains("need lastN=3"))
    }

    @Test
    fun `window average does not fire when the mean satisfies the op`() {
        val history = listOf(7.0, 7.5, 8.0).mapIndexed { index, value ->
            reading("sleep.hours", "sleep", value, daysAgo(1L + index), index)
        }
        val outcome = evaluate(contextWith(latest = mapOf("sleep.hours" to 7.5), history = mapOf("sleep.hours" to history)))
        assertTrue(outcome.insights.none { it.ruleId == "INS-TRND-SLEEP" })
        assertTrue(outcome.diagnostics.none { it.startsWith("INS-TRND-SLEEP:") })
    }

    // ------------------------------------------------------------------ evaluator-wide invariants

    @Test
    fun `insight rules never create alerts`() {
        val history = listOf(4.5, 5.5, 6.5).mapIndexed { index, value ->
            reading("sleep.hours", "sleep", value, daysAgo(1L + index), index)
        }
        val context = contextWith(
            latest = mapOf(
                "heart_rate.bpm" to 72.0,
                "blood_pressure.systolic" to 118.0,
                "blood_pressure.diastolic" to 76.0,
                "spo2.percent" to 98.0,
                "temperature.celsius" to 36.7,
                "sleep.hours" to 7.5,
                "sleep.quality" to 4.0,
            ),
            history = mapOf("sleep.hours" to history),
        )
        val result = RuleTestFixtures.engine().evaluate(context)

        assertTrue(result.insights.any { it.ruleId == "INS-TRND-SLEEP" })
        assertTrue(result.alerts.isEmpty())
        assertEquals("normal", result.overallStatus.status.name)
    }
}
