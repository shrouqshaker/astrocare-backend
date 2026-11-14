package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.data.remote.dto.EvidenceDto
import com.yasmin.astrocare.domain.rule.model.Rule
import com.yasmin.astrocare.domain.rule.model.RuleSet
import kotlin.math.abs

data class InsightOutcome(
    val insights: List<Insight>,
    val diagnostics: List<String>,
)

/**
 * Evaluates output=insight rules — `baseline_deviation` and `window_average`.
 * Insights NEVER affect overall status (statusPolicy.note) and never become
 * alerts. Every REJECTED evaluation (field missing, insufficient baseline,
 * too few readings) is recorded as a diagnostic — nothing is skipped silently;
 * a rule whose condition simply isn't met produces no insight and no noise.
 *
 * Interpretations the contract leaves open (documented in the Phase 4 report):
 *  - baseline evidence uses comparator ">" or "<" with threshold=baselineMean
 *    (direction of deviation) and carries baselineMean/baselineSd/zScore;
 *  - z fires when |z| >= zThreshold (sdFloor floors the divisor: max(sd, sdFloor));
 *  - window_average averages the newest `lastN` readings of the field within
 *    the trend baseline window (including the last 24h — "last N logged").
 */
class InsightEvaluator(
    private val ruleSet: RuleSet,
    private val catalog: ContractCatalog,
) {

    fun evaluate(context: EvaluationContext): InsightOutcome {
        val insights = mutableListOf<Insight>()
        val diagnostics = mutableListOf<String>()

        for (rule in ruleSet.rules) {
            when (rule) {
                is Rule.BaselineDeviation -> evaluateBaseline(rule, context, insights, diagnostics)
                is Rule.WindowAverage -> evaluateWindowAverage(rule, context, insights, diagnostics)
                is Rule.Threshold -> Unit // alerts are produced by the alert pipeline
            }
        }
        return InsightOutcome(insights = insights, diagnostics = diagnostics)
    }

    private fun evaluateBaseline(
        rule: Rule.BaselineDeviation,
        context: EvaluationContext,
        insights: MutableList<Insight>,
        diagnostics: MutableList<String>,
    ) {
        val latest = context.measurements[rule.field]
        if (latest == null) {
            diagnostics += "${rule.id}: field ${rule.field} not in context — insight not evaluated"
            return
        }

        val policy = ruleSet.trendPolicy.baseline
        val stats = BaselineCalculator.calculate(
            readingsNewestFirst = context.fieldHistory[rule.field].orEmpty(),
            policy = policy,
            nowMillis = context.nowMillis,
            roundingDecimals = ruleSet.trendPolicy.roundingDecimals,
        )
        if (!stats.sufficient) {
            diagnostics += "${rule.id}: ${stats.insufficientReason} — insight not evaluated"
            return
        }

        // sdFloor guarantees a positive divisor (validated > 0 at load time).
        val effectiveSd = maxOf(stats.sd, rule.sdFloor)
        val z = (latest.value - stats.mean) / effectiveSd
        if (abs(z) < rule.zThreshold) return

        val decimals = ruleSet.trendPolicy.roundingDecimals
        val evidence = EvidenceDto(
            field = rule.field,
            observed = latest.value,
            unit = catalog.unitFor(rule.field),
            comparator = if (latest.value >= stats.mean) ">" else "<",
            threshold = stats.mean,
            observedAt = latest.observedAt,
            measurementId = latest.measurementId,
            baselineMean = stats.mean,
            baselineSd = stats.sd,
            zScore = BaselineCalculator.round(z, decimals),
        )
        insights += Insight(
            ruleId = rule.id,
            ruleRevision = rule.revision,
            severity = rule.severity,
            text = rule.text,
            field = rule.field,
            evidence = evidence,
        )
    }

    private fun evaluateWindowAverage(
        rule: Rule.WindowAverage,
        context: EvaluationContext,
        insights: MutableList<Insight>,
        diagnostics: MutableList<String>,
    ) {
        val history = context.fieldHistory[rule.field].orEmpty()
        val mean = BaselineCalculator.averageOfLastN(
            readingsNewestFirst = history,
            lastN = rule.lastN,
            roundingDecimals = ruleSet.trendPolicy.roundingDecimals,
        )
        if (mean == null) {
            diagnostics += "${rule.id}: only ${history.size} reading(s) for ${rule.field}, " +
                "need lastN=${rule.lastN} — insight not evaluated"
            return
        }
        if (!rule.op.test(mean, rule.value)) return

        val newest = history.first()
        val evidence = EvidenceDto(
            field = rule.field,
            observed = mean,
            unit = catalog.unitFor(rule.field),
            comparator = rule.op.symbol,
            threshold = rule.value,
            observedAt = newest.observedAt,
            measurementId = newest.measurementId,
        )
        insights += Insight(
            ruleId = rule.id,
            ruleRevision = rule.revision,
            severity = rule.severity,
            text = rule.text,
            field = rule.field,
            evidence = evidence,
        )
    }
}
