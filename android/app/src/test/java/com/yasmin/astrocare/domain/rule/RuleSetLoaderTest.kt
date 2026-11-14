package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.ComparatorOp
import com.yasmin.astrocare.domain.rule.model.Rule
import com.yasmin.astrocare.domain.rule.model.RuleSetException
import com.yasmin.astrocare.domain.rule.model.WhenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuleSet loading/parsing responsibility:
 *  - the real contract file loads with complete provenance (27 rules);
 *  - the `when` grammar parses into the sealed node hierarchy;
 *  - malformed or UNSUPPORTED definitions throw [RuleSetException] naming the
 *    rule — never a silent skip.
 */
class RuleSetLoaderTest {

    private val catalog = RuleTestFixtures.catalog
    private val ruleSet = RuleTestFixtures.ruleSet

    @Test
    fun `contract rules load with full provenance`() {
        assertEquals("1.0", ruleSet.schemaVersion)
        assertEquals("1.0.0", ruleSet.ruleSetVersion)
        assertEquals("2026-10-12", ruleSet.effectiveFrom)
        assertTrue(ruleSet.disclaimer.length in 20..400)
        assertTrue(ruleSet.demoOnly)
        assertEquals(4, ruleSet.sources.size)
        assertEquals(6, ruleSet.actions.size)

        val thresholds = ruleSet.rules.filterIsInstance<Rule.Threshold>()
        val baselines = ruleSet.rules.filterIsInstance<Rule.BaselineDeviation>()
        val windows = ruleSet.rules.filterIsInstance<Rule.WindowAverage>()
        assertEquals("21 threshold + 6 insight rules expected", 27, ruleSet.rules.size)
        assertEquals(21, thresholds.size)
        assertEquals(5, baselines.size)
        assertEquals(1, windows.size)

        // every alert rule carries complete group/level/category/refs provenance
        for (rule in thresholds) {
            assertTrue("${rule.id} group", rule.group.isNotBlank())
            assertTrue("${rule.id} level", rule.level in setOf("warning", "critical"))
            assertTrue(
                "${rule.id} metrics",
                rule.metrics.isNotEmpty() || rule.whenNode is WhenNode.Symptom,
            )
            assertNotNull("${rule.id} source", ruleSet.sources[rule.sourceRef])
            assertNotNull("${rule.id} action", ruleSet.actions[rule.actionRef])
            assertTrue("${rule.id} title", rule.title.length in 3..80)
        }
        for (rule in ruleSet.rules) {
            assertTrue("${rule.id} revision", rule.revision >= 1)
        }
    }

    @Test
    fun `when grammar parses into sealed nodes matching the contract file`() {
        val hrLowW = ruleSet.rules.first { it.id == "HR-LOW-W" } as Rule.Threshold
        // leaf: field/op/value
        val field = hrLowW.whenNode as WhenNode.Field
        assertEquals("heart_rate.bpm", field.field)
        assertEquals(ComparatorOp.LT, field.op)
        assertEquals(50.0, field.value, 0.0)

        val bpHighW = ruleSet.rules.first { it.id == "BP-HIGH-W" } as Rule.Threshold
        // any: two leaves in appearance order
        val any = bpHighW.whenNode as WhenNode.AnyOf
        assertEquals(2, any.children.size)
        assertEquals("blood_pressure.systolic", (any.children[0] as WhenNode.Field).field)
        assertEquals("blood_pressure.diastolic", (any.children[1] as WhenNode.Field).field)

        val cmb = ruleSet.rules.first { it.id == "CMB-DYSP-SPO2-C" } as Rule.Threshold
        // all: symptom first, then field (e1 = the measurement, per its template)
        val all = cmb.whenNode as WhenNode.AllOf
        assertEquals("shortness_of_breath", (all.children[0] as WhenNode.Symptom).symptom)
        assertEquals("spo2.percent", (all.children[1] as WhenNode.Field).field)

        val chest = ruleSet.rules.first { it.id == "SYM-CHEST-C" } as Rule.Threshold
        assertEquals("chest_pain", (chest.whenNode as WhenNode.Symptom).symptom)
        assertEquals("chest-pain-v1", chest.emergencyProtocolId)
    }

    // ------------------------------------------------------------------ malformed input

    @Test
    fun `unsupported type and output are rejected explicitly`() {
        assertRejects(
            rule = thresholdRule(type = "wizard", output = "alert"),
            marker = "unsupported type/output 'wizard'",
        )
        assertRejects(
            rule = thresholdRule(type = "threshold", output = "digest"),
            marker = "unsupported type/output 'threshold'/'digest'",
        )
    }

    @Test
    fun `unknown operators are rejected explicitly`() {
        assertRejects(
            rule = thresholdRule(whenBlock = """{"field":"heart_rate.bpm","op":"~","value":50}"""),
            marker = "op in < <= > >= ==",
        )
        assertRejects(
            rule = thresholdRule(whenBlock = """{"field":"heart_rate.bpm","value":50}"""),
            marker = "op in < <= > >= ==",
        )
    }

    @Test
    fun `multiple when forms in one node are rejected`() {
        assertRejects(
            rule = thresholdRule(
                whenBlock = """{"field":"heart_rate.bpm","op":"<","value":50,"symptom":"dizziness"}""",
            ),
            marker = "multiple forms",
        )
    }

    @Test
    fun `empty and non-object when constructs are rejected`() {
        assertRejects(rule = thresholdRule(whenBlock = """{"any":[]}"""), marker = "non-empty array")
        assertRejects(rule = thresholdRule(whenBlock = """{"all":"x"}"""), marker = "non-empty array")
        assertRejects(rule = thresholdRule(whenBlock = "null"), marker = "when block missing")
        assertRejects(rule = thresholdRule(whenBlock = """{"not":"a form"}"""), marker = "no recognized form")
    }

    @Test
    fun `unknown field and symptom leaves are rejected`() {
        assertRejects(
            rule = thresholdRule(whenBlock = """{"field":"brain.temp","op":"<","value":36}"""),
            marker = "unknown field 'brain.temp'",
        )
        assertRejects(
            rule = thresholdRule(whenBlock = """{"symptom":"headache"}"""),
            marker = "unknown symptom 'headache'",
        )
        assertRejects(
            rule = thresholdRule(whenBlock = """{"symptom":"none"}"""),
            marker = "unknown symptom 'none'",
        )
    }

    @Test
    fun `dangling refs, bad level and duplicate ids are rejected`() {
        assertRejects(thresholdRule(actionRef = "ACT-NOPE"), marker = "actionRef not found")
        assertRejects(thresholdRule(sourceRef = "SRC-NOPE"), marker = "sourceRef not found")
        assertRejects(thresholdRule(level = "fatal"), marker = "level must be warning|critical")
        assertRejects(thresholdRule(metrics = listOf("heart_rate"), unknownMetric = true), marker = "unknown metric")
        assertRejects(
            rule = thresholdRule() + "," + thresholdRule(),
            marker = "duplicate id",
        )
    }

    @Test
    fun `unsupported explanation placeholders are rejected`() {
        assertRejects(
            rule = thresholdRule(extra = ""","explanationTemplate":"Bad {e0.foo} input""""),
            marker = "unsupported placeholder '{e0.foo}'",
        )
    }

    @Test
    fun `unparsable json fails loudly`() {
        try {
            RuleSetLoader.load("{ this is not json", catalog)
            throw AssertionError("expected RuleSetException")
        } catch (e: RuleSetException) {
            assertTrue(e.problems.joinToString().contains("not parsable JSON"))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertRejects(rule: String, marker: String) {
        try {
            RuleSetLoader.load(minimalRuleSet(rule), catalog)
            throw AssertionError("expected RuleSetException containing '$marker' for: $rule")
        } catch (e: RuleSetException) {
            val problems = e.problems.joinToString("\n")
            assertTrue(
                "problems did not contain '$marker':\n$problems",
                problems.contains(marker),
            )
        }
    }

    private fun thresholdRule(
        id: String = "TEST-LOW-W",
        type: String = "threshold",
        output: String = "alert",
        level: String = "warning",
        category: String = "vital",
        group: String = "test_group",
        metrics: List<String> = listOf("heart_rate"),
        whenBlock: String = """{"field":"heart_rate.bpm","op":"<","value":50}""",
        actionRef: String = "ACT-TEST",
        sourceRef: String = "SRC-TEST",
        unknownMetric: Boolean = false,
        extra: String = "",
    ): String {
        val metricJson = if (unknownMetric) metrics + "unicorn_metric" else metrics
        val metricsJson = metricJson.joinToString(",") { "\"$it\"" }
        return """
            {"id":"$id","revision":1,"type":"$type","output":"$output","level":"$level",
             "category":"$category","group":"$group","metrics":[$metricsJson],
             "title":"Test rule title","when":$whenBlock,
             "actionRef":"$actionRef","sourceRef":"$sourceRef"$extra}
        """.trimIndent()
    }

    private fun minimalRuleSet(ruleJson: String): String = """
        {"schemaVersion":"1.0","ruleSetVersion":"1.0.0","effectiveFrom":"2026-10-12",
         "disclaimer":"AstroCare provides general decision support only. Test rules file.",
         "evaluation":{"trigger":"on_save_of_measurement_or_symptom_checkin",
           "contextPolicy":"latest valid measurement per metric",
           "contextWindowMinutes":1440,"groupPolicy":"highest level wins",
           "dedupe":"same ruleId and identical triggeredBy ids","maxEvaluationMillis":1000,
           "defaultExplanationTemplate":"Rule {ruleId} triggered: {e0.label} = {e0.observed} {e0.unit}.",
           "templatePlaceholders":["ruleId"],
           "evidenceOrder":"one item per TRUE leaf in order of appearance"},
         "statusPolicy":{"minMetricsForNormal":3,"note":"test note"},
         "trendPolicy":{"windowDays":7,
           "baseline":{"windowDays":14,"excludeLastHours":24,"minSamples":5,"minDistinctDays":3,
             "method":"mean and sample SD of valid readings per field"},
           "direction":{"compare":"mean of last 3 days vs earlier 4 days",
             "minDaysWithDataPerSide":2,"stableBand":{}},
           "rounding":"means and SDs rounded to 2 decimals"},
         "sources":{"SRC-TEST":{"title":"Test source title","url":null,"verified":false}},
         "actions":{"ACT-TEST":"This is a sufficiently long test action text."},
         "rules":[$ruleJson],
         "demoOnly":true}
    """.trimIndent()
}
