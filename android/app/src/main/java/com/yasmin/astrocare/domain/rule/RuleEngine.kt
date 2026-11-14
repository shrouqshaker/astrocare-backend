package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.Rule
import com.yasmin.astrocare.domain.rule.model.RuleSet

/**
 * The rule engine — a thin orchestrator over the separated responsibilities:
 *
 *  1. [RuleSetLoader] / [ContractCatalogLoader] — loading & parsing (once);
 *  2. [ConditionEvaluator] — `when` condition evaluation;
 *  3. [EvidenceGenerator] — evidence from TRUE leaves;
 *  4. [AlertAssembler] — alert generation, group policy, dedupe;
 *  5. [StatusCalculator] — overall status;
 *  6. [InsightEvaluator] — insight rules (with [BaselineCalculator] math).
 *
 * Guarantees: deterministic (fixed rules-file order, sorted outputs, injected
 * id generator), pure CPU (callers run it off the main thread — see
 * RuleSetRepository), fully offline, and bounded by
 * evaluation.maxEvaluationMillis (overruns are reported as diagnostics).
 */
class RuleEngine(
    private val ruleSet: RuleSet,
    private val catalog: ContractCatalog,
) {
    val ruleSetVersion: String get() = ruleSet.ruleSetVersion

    fun evaluate(
        context: EvaluationContext,
        existing: List<ExistingAlertRef> = emptyList(),
    ): EvaluationResult {
        val startedNanos = System.nanoTime()
        val diagnostics = mutableListOf<String>()

        // 2. conditions — every threshold rule is evaluated, none skipped
        val evaluator = ConditionEvaluator(context)
        val firing = ruleSet.rules.filterIsInstance<Rule.Threshold>().mapNotNull { rule ->
            val condition = evaluator.evaluate(rule.whenNode)
            if (condition.fired) FiringThreshold(rule, condition) else null
        }

        // 3 + 4. evidence, explanation, group policy, dedupe
        val assembler = AlertAssembler(
            ruleSet = ruleSet,
            evidenceGenerator = EvidenceGenerator(catalog),
            renderer = ExplanationRenderer(catalog),
        )
        val assembled = assembler.assemble(firing, context, existing)
        diagnostics += assembled.notes

        // 5. status — recomputed from current context (pre-dedupe candidates)
        val status = StatusCalculator(ruleSet).calculate(
            groupSelected = assembled.groupSelected,
            metricsInContext = context.metricsInContext,
            computedAt = context.nowIso,
        )

        // 6. insights — never affect status
        val insightOutcome = InsightEvaluator(ruleSet, catalog).evaluate(context)
        diagnostics += insightOutcome.diagnostics

        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
        if (elapsedMillis > ruleSet.evaluation.maxEvaluationMillis) {
            diagnostics += "evaluation took ${elapsedMillis}ms — exceeds maxEvaluationMillis=" +
                ruleSet.evaluation.maxEvaluationMillis
        }

        return EvaluationResult(
            overallStatus = status,
            alerts = assembled.alerts,
            insights = insightOutcome.insights,
            diagnostics = diagnostics,
        )
    }
}
