package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.core.common.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract `evaluation.contextPolicy` behavior:
 * latest valid measurement per metric within contextWindowMinutes, latest
 * symptom check-in in-window, baseline-window history, provenance flag.
 */
class ContextBuilderTest {

    private val ruleSet = RuleTestFixtures.ruleSet
    private val builder = ContextBuilder(
        catalog = RuleTestFixtures.catalog,
        evaluationPolicy = ruleSet.evaluation,
        baselinePolicy = ruleSet.trendPolicy.baseline,
    )

    private val now = "2026-11-13T18:00:00Z"
    private val nowMillis = RuleTestFixtures.parse(now)

    private fun atMinutesAgo(minutes: Long): String = IsoTime.formatUtc(nowMillis - minutes * 60_000L)
    private fun atDaysAgo(days: Long): String = IsoTime.formatUtc(nowMillis - days * 24L * 60 * 60 * 1000)

    private fun measurement(
        id: String,
        metric: String,
        values: Map<String, Double>,
        at: String,
        synthetic: Boolean = true,
    ) = MeasurementRecord(
        id = id,
        metric = metric,
        values = values,
        observedAt = at,
        observedAtMillis = RuleTestFixtures.parse(at),
        isSynthetic = synthetic,
    )

    private fun checkin(
        id: String,
        symptoms: List<String>,
        at: String,
        synthetic: Boolean = true,
    ) = SymptomCheckInRecord(
        id = id,
        symptoms = symptoms,
        checkedAt = at,
        checkedAtMillis = RuleTestFixtures.parse(at),
        isSynthetic = synthetic,
    )

    private fun build(
        measurements: List<MeasurementRecord> = emptyList(),
        checkIns: List<SymptomCheckInRecord> = emptyList(),
        astronautId: String = "demo-astronaut-01",
    ) = builder.build(
        astronautId = astronautId,
        nowMillis = nowMillis,
        nowIso = now,
        measurements = measurements,
        checkIns = checkIns,
    )

    @Test
    fun `reading older than 1440 minutes is outside the context window`() {
        val context = build(
            listOf(
                measurement("a", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(1441)),
            ),
        )
        assertTrue("expired reading must not enter context", context.measurements.isEmpty())
        assertEquals(0, context.metricsInContext)
    }

    @Test
    fun `reading at exactly 1440 minutes is still inside the window`() {
        val context = build(
            listOf(
                measurement("a", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(1440)),
            ),
        )
        assertEquals(72.0, context.measurements.getValue("heart_rate.bpm").value, 0.0)
        assertEquals(1, context.metricsInContext)
    }

    @Test
    fun `latest valid measurement per metric wins`() {
        val context = build(
            listOf(
                measurement("old", "heart_rate", mapOf("bpm" to 60.0), atMinutesAgo(600)),
                measurement("new", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(30)),
            ),
        )
        val selected = context.measurements.getValue("heart_rate.bpm")
        assertEquals(72.0, selected.value, 0.0)
        assertEquals("new", selected.measurementId)
    }

    @Test
    fun `invalid latest reading falls back to the next valid one`() {
        // bpm 999 exceeds enums.json max 250 -> whole row invalid
        val context = build(
            listOf(
                measurement("valid", "heart_rate", mapOf("bpm" to 60.0), atMinutesAgo(600)),
                measurement("invalid", "heart_rate", mapOf("bpm" to 999.0), atMinutesAgo(30)),
            ),
        )
        val selected = context.measurements.getValue("heart_rate.bpm")
        assertEquals(60.0, selected.value, 0.0)
        assertEquals("valid", selected.measurementId)
    }

    @Test
    fun `cross-field violation invalidates the blood pressure row`() {
        val context = build(
            listOf(
                // systolic must be > diastolic (enums.json crossFieldRule)
                measurement("bad", "blood_pressure", mapOf("systolic" to 140.0, "diastolic" to 150.0), atMinutesAgo(10)),
                measurement("good", "blood_pressure", mapOf("systolic" to 118.0, "diastolic" to 76.0), atMinutesAgo(120)),
            ),
        )
        val systolic = context.measurements.getValue("blood_pressure.systolic")
        assertEquals(118.0, systolic.value, 0.0)
        assertEquals("good", systolic.measurementId)
    }

    @Test
    fun `out of range and empty rows are invalid`() {
        val context = build(
            listOf(
                measurement("hr", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(10)),
                measurement("tooLow", "heart_rate", mapOf("bpm" to 5.0), atMinutesAgo(5)),
                measurement("empty", "sleep", emptyMap(), atMinutesAgo(5)),
                measurement("unknownMetric", "unicorn", mapOf("bpm" to 72.0), atMinutesAgo(5)),
            ),
        )
        // the only valid heart_rate row is 72 (the out-of-range newer row is skipped)
        assertEquals(72.0, context.measurements.getValue("heart_rate.bpm").value, 0.0)
        assertFalse("sleep" in context.measurements.keys.map { it.substringBefore('.') })
        assertFalse(context.measurements.keys.any { it.startsWith("unicorn.") })
    }

    @Test
    fun `latest in-window check-in supplies symptoms and expired check-in is ignored`() {
        val expired = build(
            checkIns = listOf(checkin("c1", listOf("dizziness"), atMinutesAgo(1441))),
        )
        assertTrue(expired.symptoms.isEmpty())
        assertNull(expired.symptomCheckinId)

        val fresh = build(
            checkIns = listOf(
                checkin("c1", listOf("dizziness"), atMinutesAgo(1441)),
                checkin("c2", listOf("chest_pain"), atMinutesAgo(20)),
            ),
        )
        assertEquals(listOf("chest_pain"), fresh.symptoms)
        assertEquals("c2", fresh.symptomCheckinId)
        assertEquals(atMinutesAgo(20), fresh.symptomsObservedAt)
    }

    @Test
    fun `history keeps readings beyond the context window inside the baseline window`() {
        val context = build(
            listOf(
                measurement("today", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(10)),
                measurement("threeDaysAgo", "heart_rate", mapOf("bpm" to 55.0), atDaysAgo(3)),
                measurement("tooOld", "heart_rate", mapOf("bpm" to 50.0), atDaysAgo(20)), // outside 14d window
            ),
        )
        // context: only the latest in-window reading
        assertEquals(1, context.measurements.size)
        // history: newest first, 14-day window, includes the 3-day-old reading
        val history = context.fieldHistory.getValue("heart_rate.bpm")
        assertEquals(listOf(72.0, 55.0), history.map { it.value })
        assertEquals("today", history[0].measurementId)
    }

    @Test
    fun `isSynthetic only when every contributing record is synthetic`() {
        val allSynthetic = build(
            listOf(
                measurement("a", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(10), synthetic = true),
                measurement("b", "sleep", mapOf("hours" to 7.0, "quality" to 4.0), atMinutesAgo(10), synthetic = true),
            ),
        )
        assertTrue(allSynthetic.isSynthetic)

        val mixed = build(
            listOf(
                measurement("a", "heart_rate", mapOf("bpm" to 72.0), atMinutesAgo(10), synthetic = true),
                measurement("b", "sleep", mapOf("hours" to 7.0, "quality" to 4.0), atMinutesAgo(10), synthetic = false),
            ),
        )
        assertFalse(mixed.isSynthetic)

        val empty = build(emptyList())
        assertFalse(empty.isSynthetic)
    }
}
