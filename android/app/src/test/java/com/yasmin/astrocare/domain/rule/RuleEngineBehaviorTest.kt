package com.yasmin.astrocare.domain.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine behaviors required by the Phase 4 brief: group policy, warning vs
 * critical precedence, missing measurements, insufficient data, duplicate
 * prevention, symptom-triggered rules, evidence ordering, explanation
 * templates, provenance, determinism, and insight/status independence.
 *
 * No threshold appears here as knowledge — inputs are scenario values and
 * expected outcomes trace to rules.v1.json / rule-vectors.v1.json.
 */
class RuleEngineBehaviorTest {

    private val ruleSet = RuleTestFixtures.ruleSet
    private val catalog = RuleTestFixtures.catalog

    private val normalVitals = mapOf(
        "heart_rate.bpm" to 72.0,
        "blood_pressure.systolic" to 118.0,
        "blood_pressure.diastolic" to 76.0,
        "spo2.percent" to 98.0,
        "temperature.celsius" to 36.7,
        "sleep.hours" to 7.5,
        "sleep.quality" to 4.0,
    )

    private fun evaluate(
        measurements: Map<String, Double>,
        symptoms: List<String> = emptyList(),
    ): EvaluationResult = RuleTestFixtures.engine()
        .evaluate(RuleTestFixtures.context(measurements = measurements, symptoms = symptoms))

    private fun ids(result: EvaluationResult): List<String> = result.alerts.map { it.ruleId }

    // ------------------------------------------------------------------ group policy & precedence

    @Test
    fun `same group emits only the highest level firing rule`() {
        val result = evaluate(normalVitals + ("heart_rate.bpm" to 39.0))
        assertEquals(listOf("HR-LOW-C"), ids(result))
        assertEquals("critical", result.overallStatus.status.name)
        assertEquals(listOf("HR-LOW-C"), result.overallStatus.firingRuleIds)
        assertTrue(
            "suppression must be reported, not silent: ${result.diagnostics}",
            result.diagnostics.any { it.contains("groupPolicy") && it.contains("HR-LOW-W") },
        )
    }

    @Test
    fun `critical wins within bp_high and warning fires below the critical threshold`() {
        val critical = evaluate(normalVitals + ("blood_pressure.systolic" to 180.0))
        assertEquals(listOf("BP-HIGH-C"), ids(critical))
        assertEquals("critical", critical.overallStatus.status.name)
        assertTrue(critical.diagnostics.any { it.contains("BP-HIGH-W") })

        val warning = evaluate(normalVitals + ("blood_pressure.systolic" to 140.0))
        assertEquals(listOf("BP-HIGH-W"), ids(warning))
        assertEquals("warning", warning.overallStatus.status.name)
    }

    @Test
    fun `different groups both survive`() {
        // sleep duration + sleep quality rules live in different groups
        val result = evaluate(normalVitals + mapOf("sleep.hours" to 5.0, "sleep.quality" to 2.0))
        assertEquals(listOf("SLP-DUR-W", "SLP-QUAL-W"), ids(result).sorted())
        assertEquals("warning", result.overallStatus.status.name)
    }

    // ------------------------------------------------------------------ missing data & status

    @Test
    fun `missing spo2 blocks the combination rule while the symptom rule still fires`() {
        val vitalsWithoutSpo2 = normalVitals.filterKeys { !it.startsWith("spo2.") }
        val result = evaluate(vitalsWithoutSpo2, symptoms = listOf("shortness_of_breath"))
        assertEquals(listOf("SYM-DYSP-W"), ids(result))
        assertEquals("warning", result.overallStatus.status.name)
        assertEquals(4, result.overallStatus.metricsInContext)
    }

    @Test
    fun `fewer than three metrics yields insufficient_data when nothing fires`() {
        val onlyHr = evaluate(mapOf("heart_rate.bpm" to 72.0))
        assertEquals("insufficient_data", onlyHr.overallStatus.status.name)
        assertTrue(onlyHr.alerts.isEmpty())
        assertEquals(1, onlyHr.overallStatus.metricsInContext)

        val none = evaluate(emptyMap())
        assertEquals("insufficient_data", none.overallStatus.status.name)
        assertEquals(0, none.overallStatus.metricsInContext)
        assertTrue(none.alerts.isEmpty())
    }

    @Test
    fun `warning status takes precedence over insufficient_data`() {
        // one metric only — but a rule fires, so status is warning, not insufficient
        val result = evaluate(mapOf("heart_rate.bpm" to 49.0))
        assertEquals("warning", result.overallStatus.status.name)
        assertEquals(listOf("HR-LOW-W"), ids(result))
    }

    // ------------------------------------------------------------------ dedupe

    @Test
    fun `duplicate alerts are prevented only for identical triggeredBy ids`() {
        val context = RuleTestFixtures.context(mapOf("heart_rate.bpm" to 49.0))

        val first = RuleTestFixtures.engine().evaluate(context)
        assertEquals(listOf("HR-LOW-W"), ids(first))
        val created = first.alerts.single()
        assertTrue(created.triggeredBy.measurementIds.isNotEmpty())
        assertNull(created.triggeredBy.symptomCheckinIds.firstOrNull())

        val existing = listOf(
            ExistingAlertRef(
                ruleId = created.ruleId,
                measurementIds = created.triggeredBy.measurementIds.toSet(),
                symptomCheckinIds = created.triggeredBy.symptomCheckinIds.toSet(),
            ),
        )

        // identical triggeredBy -> suppressed, but status recomputed from context
        val second = RuleTestFixtures.engine().evaluate(context, existing)
        assertTrue("dedupe must suppress creation", second.alerts.isEmpty())
        assertEquals("status must not depend on stored history", "warning", second.overallStatus.status.name)
        assertEquals(listOf("HR-LOW-W"), second.overallStatus.firingRuleIds)
        assertTrue(second.diagnostics.any { it.contains("dedupe") && it.contains("HR-LOW-W") })

        // same rule, different triggering reading -> new alert allowed
        val otherIdContext = context.copy(
            measurements = context.measurements.mapValues { (_, m) ->
                m.copy(measurementId = "99999999-9999-4999-8999-999999999999")
            },
        )
        val third = RuleTestFixtures.engine().evaluate(otherIdContext, existing)
        assertEquals(listOf("HR-LOW-W"), ids(third))
    }

    // ------------------------------------------------------------------ symptom rules

    @Test
    fun `chest pain emits the emergency alert with protocol id and exact explanation`() {
        val result = evaluate(normalVitals, symptoms = listOf("chest_pain"))
        assertEquals(listOf("SYM-CHEST-C"), ids(result))
        assertEquals("critical", result.overallStatus.status.name)
        val alert = result.alerts.single()
        assertEquals("emergency", alert.category)
        assertEquals("chest-pain-v1", alert.emergencyProtocolId)
        assertEquals(
            "You reported chest pain. Chest pain can signal a serious emergency, " +
                "so emergency guidance has been opened.",
            alert.explanation,
        )
        assertEquals(ruleSet.actions.getValue("ACT-CHEST-EMERGENCY"), alert.recommendedAction)
    }

    @Test
    fun `dizziness fires the symptom warning and none or absence fires nothing`() {
        val dizziness = evaluate(normalVitals, symptoms = listOf("dizziness"))
        assertEquals(listOf("SYM-DIZZ-W"), ids(dizziness))
        assertEquals("You reported dizziness.", dizziness.alerts.single().explanation)
        assertEquals("warning", dizziness.overallStatus.status.name)

        val none = evaluate(normalVitals, symptoms = listOf("none"))
        assertTrue(none.alerts.isEmpty())
        assertEquals("normal", none.overallStatus.status.name)

        val noCheckin = evaluate(normalVitals)
        assertTrue(noCheckin.alerts.isEmpty())
        assertEquals("normal", noCheckin.overallStatus.status.name)
    }

    @Test
    fun `symptom evidence follows the contract shape`() {
        val result = evaluate(normalVitals, symptoms = listOf("dizziness"))
        val evidence = result.alerts.single().evidence.single()
        assertEquals("symptom.dizziness", evidence.field)
        assertEquals(1.0, evidence.observed, 0.0)
        assertEquals("flag", evidence.unit)
        assertEquals("==", evidence.comparator)
        assertEquals(1.0, evidence.threshold, 0.0)
        assertEquals(RuleTestFixtures.CHECKIN_ID, evidence.symptomCheckinId)
        assertEquals(RuleTestFixtures.CHECKIN_ID, result.alerts.single().triggeredBy.symptomCheckinIds.single())
    }

    // ------------------------------------------------------------------ combination & evidence order

    @Test
    fun `combination rule fires with symptom and low value - evidence in when order`() {
        val result = evaluate(
            normalVitals + ("blood_pressure.systolic" to 89.0),
            symptoms = listOf("dizziness"),
        )
        assertEquals(listOf("BP-LOW-W", "CMB-DIZZ-BP-C"), ids(result))
        assertEquals("critical", result.overallStatus.status.name)

        val combination = result.alerts.first { it.ruleId == "CMB-DIZZ-BP-C" }
        assertEquals(
            listOf("symptom.dizziness", "blood_pressure.systolic"),
            combination.evidence.map { it.field },
        )
        assertEquals(
            "Dizziness was reported together with systolic pressure of 89 mmHg, below 90 mmHg.",
            combination.explanation,
        )
        // SYM-DIZZ-W fired but lost its group to the critical combination rule
        assertTrue(result.diagnostics.any { it.contains("SYM-DIZZ-W") })

        // BP-LOW-W keeps only the TRUE leaf (diastolic 76 is not below its value)
        val bpLow = result.alerts.first { it.ruleId == "BP-LOW-W" }
        assertEquals(listOf("blood_pressure.systolic"), bpLow.evidence.map { it.field })
    }

    @Test
    fun `any-rule evidence lists every true leaf in appearance order`() {
        val result = evaluate(
            normalVitals + mapOf(
                "blood_pressure.systolic" to 140.0,
                "blood_pressure.diastolic" to 95.0,
            ),
        )
        assertEquals(listOf("BP-HIGH-W"), ids(result))
        val evidence = result.alerts.single().evidence
        assertEquals(listOf("blood_pressure.systolic", "blood_pressure.diastolic"), evidence.map { it.field })
        assertEquals(listOf(">=", ">="), evidence.map { it.comparator })
        assertEquals(listOf("mmHg", "mmHg"), evidence.map { it.unit })
        assertEquals(listOf(140.0, 90.0), evidence.map { it.threshold }) // rule values, not observations
        assertEquals(140.0, evidence[0].observed, 0.0)
        assertEquals(95.0, evidence[1].observed, 0.0)
    }

    // ------------------------------------------------------------------ explanations & provenance

    @Test
    fun `default explanation template renders with contract labels and units`() {
        val result = evaluate(mapOf("heart_rate.bpm" to 49.0) + normalVitals.filterKeys { it != "heart_rate.bpm" })
        val alert = result.alerts.first { it.ruleId == "HR-LOW-W" }
        assertEquals(
            "Rule HR-LOW-W triggered: heart rate = 49 bpm (< 50 bpm).",
            alert.explanation,
        )
    }

    @Test
    fun `provenance flows from contract sources actions and ruleset`() {
        val alert = evaluate(normalVitals + ("heart_rate.bpm" to 49.0)).alerts
            .first { it.ruleId == "HR-LOW-W" }

        assertEquals("SRC-VITALS-GENERAL", alert.source.ref)
        assertEquals(
            "Unverified illustrative physiological thresholds; not astronaut clinical limits",
            alert.source.title,
        )
        assertNull(alert.source.url)
        assertEquals(ruleSet.actions.getValue("ACT-WARN-VITAL"), alert.recommendedAction)
        assertEquals(ruleSet.disclaimer, alert.disclaimer)
        assertEquals("1.0.0", alert.ruleSetVersion)
        assertEquals(1, alert.ruleRevision)
        assertEquals("1.0", alert.schemaVersion)
        assertEquals("demo-astronaut-01", alert.astronautId)
        assertTrue("vector-like demo context is synthetic", alert.isSynthetic)
        assertNull(alert.acknowledgedAt)
    }

    // ------------------------------------------------------------------ determinism

    @Test
    fun `same engine and input produce the same result modulo injected ids`() {
        val engine = RuleTestFixtures.engine()
        val context = RuleTestFixtures.context(
            measurements = normalVitals + mapOf("heart_rate.bpm" to 39.0, "blood_pressure.systolic" to 89.0),
            symptoms = listOf("dizziness"),
        )
        val first = engine.evaluate(context)
        val second = engine.evaluate(context)

        assertEquals(first.overallStatus, second.overallStatus)
        assertEquals(first.insights, second.insights)
        assertEquals(first.diagnostics, second.diagnostics)
        // ids continue the injected sequence — everything else must be identical
        assertEquals(
            first.alerts.map { it.copy(id = "") },
            second.alerts.map { it.copy(id = "") },
        )
        // and the engine's own ordering guarantee holds
        val order = first.alerts.map { it.ruleId }
        assertEquals(order, order.sorted())
    }

    @Test
    fun `insight availability never changes overall status`() {
        val nowMillis = RuleTestFixtures.parse("2026-11-13T18:00:00Z")
        val baseline = listOf(60.0, 61.0, 59.0, 60.0, 61.0, 59.0, 60.0, 61.0, 59.0, 60.0)
        val history = baseline.mapIndexed { index, value ->
            val at = nowMillis - (2L + index % 4) * 24 * 60 * 60 * 1000
            ContextMeasurement(
                field = "heart_rate.bpm",
                metric = "heart_rate",
                value = value,
                observedAt = com.yasmin.astrocare.core.common.IsoTime.formatUtc(at),
                observedAtMillis = at,
                measurementId = "40000000-0000-4000-8000-%012d".format(index),
            )
        }
        val base = RuleTestFixtures.context(normalVitals + ("heart_rate.bpm" to 72.0))
        val context = base.copy(fieldHistory = base.fieldHistory + ("heart_rate.bpm" to history))

        val result = RuleTestFixtures.engine().evaluate(context)

        assertTrue("baseline insight should fire", result.insights.any { it.ruleId == "INS-BASE-HR" })
        assertTrue("no threshold rules fire on normal vitals", result.alerts.isEmpty())
        assertEquals("insights must not affect status", "normal", result.overallStatus.status.name)
    }
}
