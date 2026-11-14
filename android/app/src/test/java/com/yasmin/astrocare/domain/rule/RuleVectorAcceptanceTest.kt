package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.RuleTestFixtures.engine
import com.yasmin.astrocare.domain.rule.RuleTestFixtures.loadVectors
import com.yasmin.astrocare.domain.rule.RuleTestFixtures.contextOf
import com.yasmin.astrocare.domain.rule.RuleTestFixtures.ruleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 4 ACCEPTANCE GATE — all 34 vectors of the frozen file
 * `contracts/test-vectors/rule-vectors.v1.json` (bundled verbatim at
 * `app/src/test/resources/contracts/rule-vectors.v1.json`).
 *
 * For every vector we compare:
 *  - overall status (exact),
 *  - fired rule ids + alert levels (as a set — the contract does not define
 *    alert list ordering; our output order is additionally asserted to be the
 *    deterministic ruleId sort),
 *  - schema bounds of every produced alert.
 *
 * Expected vectors are NEVER modified. No vector specifies expected evidence
 * (verified: none carries an `evidence` key), so evidence is asserted on the
 * contract schema shape instead.
 */
class RuleVectorAcceptanceTest {

    private val vectors = loadVectors()

    @Test
    fun `gate - the bundled file contains exactly 34 vectors`() {
        assertEquals(
            "rule-vectors.v1.json must contain exactly 34 vectors (DECISIONS §6)",
            34,
            vectors.size,
        )
        assertEquals("1.0", ruleSet.schemaVersion)
        assertEquals("1.0.0", ruleSet.ruleSetVersion)
    }

    @Test
    fun `all 34 contract vectors pass`() {
        val failures = mutableListOf<String>()

        for (vector in vectors) {
            val result = engine().evaluate(contextOf(vector))

            // 1. overall status
            val actualStatus = result.overallStatus.status.name
            if (actualStatus != vector.expected.status) {
                failures += "${vector.name}: status '$actualStatus' != expected '${vector.expected.status}'"
            }

            // 2. fired rule ids + levels (set comparison)
            val actualAlerts = result.alerts.map { it.ruleId to it.level }.toSet()
            val expectedAlerts = vector.expected.alerts.map { it.ruleId to it.level }.toSet()
            if (actualAlerts != expectedAlerts) {
                failures += "${vector.name}: alerts $actualAlerts != expected $expectedAlerts"
            }

            // deterministic output order: sorted by ruleId
            val ids = result.alerts.map { it.ruleId }
            if (ids != ids.sorted()) {
                failures += "${vector.name}: alert order not sorted by ruleId: $ids"
            }

            // 3. schema bounds for every alert (alert.schema.json)
            for (alert in result.alerts) {
                val prefix = "${vector.name}/${alert.ruleId}"
                val expectedId = StableUuid.alertId(
                    astronautId = alert.astronautId,
                    ruleId = alert.ruleId,
                    revision = alert.ruleRevision,
                    measurementIds = alert.triggeredBy.measurementIds,
                    symptomCheckinIds = alert.triggeredBy.symptomCheckinIds,
                )
                if (alert.id != expectedId) failures += "$prefix: id ${alert.id} != deterministic $expectedId"
                if (alert.title.length !in 3..80) failures += "$prefix: title length ${alert.title.length}"
                if (alert.explanation.length !in 10..400) failures += "$prefix: explanation length ${alert.explanation.length}"
                if (alert.explanation.contains('{')) failures += "$prefix: unrendered placeholder in '${alert.explanation}'"
                if (alert.recommendedAction.length !in 10..400) failures += "$prefix: action length"
                if (alert.disclaimer.length !in 20..400) failures += "$prefix: disclaimer length ${alert.disclaimer.length}"
                if (alert.evidence.isEmpty() || alert.evidence.size > 6) failures += "$prefix: evidence size ${alert.evidence.size}"
                if (alert.level !in setOf("warning", "critical")) failures += "$prefix: level ${alert.level}"
                if (!RULE_ID.matches(alert.ruleId)) failures += "$prefix: ruleId pattern"
                if (alert.ruleRevision < 1) failures += "$prefix: ruleRevision"
                if (alert.source.ref.isBlank() || alert.source.title.isBlank()) failures += "$prefix: source provenance"
                if (alert.triggeredBy.measurementIds.isEmpty() && alert.triggeredBy.symptomCheckinIds.isEmpty()) {
                    failures += "$prefix: triggeredBy empty"
                }
                for (e in alert.evidence) {
                    if (e.unit !in UNITS) failures += "$prefix: evidence unit '${e.unit}'"
                    if (e.comparator !in setOf("<", "<=", ">", ">=", "==")) failures += "$prefix: comparator '${e.comparator}'"
                    if (e.field.startsWith("symptom.") && !SYMPTOM_FIELDS.matches(e.field)) {
                        failures += "$prefix: symptom evidence field '${e.field}'"
                    }
                }
            }

            // overall status object shape (common.schema.json)
            if (result.overallStatus.metricsInContext !in 0..5) {
                failures += "${vector.name}: metricsInContext ${result.overallStatus.metricsInContext}"
            }
            if (result.overallStatus.ruleSetVersion != "1.0.0") {
                failures += "${vector.name}: ruleSetVersion ${result.overallStatus.ruleSetVersion}"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `evaluation is deterministic - identical input yields identical output`() {
        for (vector in vectors) {
            val first = engine().evaluate(contextOf(vector))
            val second = engine().evaluate(contextOf(vector))
            assertEquals("status differs for ${vector.name}", first.overallStatus, second.overallStatus)
            assertEquals("alerts differ for ${vector.name}", first.alerts, second.alerts)
            assertEquals("insights differ for ${vector.name}", first.insights, second.insights)
            assertEquals("diagnostics differ for ${vector.name}", first.diagnostics, second.diagnostics)
        }
    }

    @Test
    fun `every vector evaluates within the contract time budget`() {
        val budget = ruleSet.evaluation.maxEvaluationMillis
        val overruns = mutableListOf<String>()
        for (vector in vectors) {
            val started = System.nanoTime()
            engine().evaluate(contextOf(vector))
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (elapsed > budget) overruns += "${vector.name}: ${elapsed}ms > ${budget}ms"
        }
        assertTrue(overruns.joinToString("\n"), overruns.isEmpty())
    }

    private companion object {
        val RULE_ID = Regex("""^[A-Z0-9]+(-[A-Z0-9]+)+$""")
        val SYMPTOM_FIELDS = Regex("""^symptom\.(chest_pain|dizziness|shortness_of_breath)$""")
        val UNITS = setOf("bpm", "mmHg", "percent", "celsius", "hours", "score", "flag")
    }
}
