package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.ComparatorOp
import com.yasmin.astrocare.domain.rule.model.WhenNode

/**
 * Resolved TRUE leaf of a `when` tree — exactly what [EvidenceGenerator] needs.
 * Field leaves carry the observed value/time/id of the reading that matched;
 * symptom leaves carry the check-in id/time (evidence: observed=1, unit=flag,
 * comparator "==", threshold=1 — contracts evaluation.evidenceOrder).
 */
sealed interface LeafMatch {
    val field: String
}

data class FieldMatch(
    override val field: String,
    val op: ComparatorOp,
    val threshold: Double,
    val observed: Double,
    val observedAt: String,
    val measurementId: String?,
) : LeafMatch

data class SymptomMatch(
    val symptom: String,
    val observedAt: String,
    val checkinId: String?,
) : LeafMatch {
    override val field: String get() = "symptom.$symptom"
}

/**
 * Outcome of evaluating one rule's `when` block against an [EvaluationContext].
 * [matches] lists TRUE leaves ONLY, in order of appearance in `when`
 * (contracts evaluation.evidenceOrder) — empty unless [fired] is true.
 */
data class ConditionResult(
    val fired: Boolean,
    val matches: List<LeafMatch>,
)

/**
 * Pure, deterministic condition evaluation for the complete grammar:
 * `field/op/value`, `symptom`, `all`, `any` (nested arbitrarily).
 *
 * Missing data never fires: a field leaf whose field is absent from the
 * context is FALSE (not an error) — a symptom absent from the latest in-window
 * check-in is FALSE.
 */
class ConditionEvaluator(private val context: EvaluationContext) {

    fun evaluate(node: WhenNode): ConditionResult = when (node) {
        is WhenNode.Field -> evaluateField(node)
        is WhenNode.Symptom -> evaluateSymptom(node)
        is WhenNode.AllOf -> {
            val childResults = node.children.map { evaluate(it) }
            if (childResults.all { it.fired }) {
                ConditionResult(fired = true, matches = childResults.flatMap { it.matches })
            } else {
                ConditionResult(fired = false, matches = emptyList())
            }
        }

        is WhenNode.AnyOf -> {
            val firedChildren = node.children.map { evaluate(it) }.filter { it.fired }
            if (firedChildren.isEmpty()) {
                ConditionResult(fired = false, matches = emptyList())
            } else {
                ConditionResult(fired = true, matches = firedChildren.flatMap { it.matches })
            }
        }
    }

    private fun evaluateField(node: WhenNode.Field): ConditionResult {
        val measurement = context.measurements[node.field] ?: return NO_MATCH
        if (!node.op.test(measurement.value, node.value)) return NO_MATCH
        return ConditionResult(
            fired = true,
            matches = listOf(
                FieldMatch(
                    field = node.field,
                    op = node.op,
                    threshold = node.value,
                    observed = measurement.value,
                    observedAt = measurement.observedAt,
                    measurementId = measurement.measurementId,
                ),
            ),
        )
    }

    private fun evaluateSymptom(node: WhenNode.Symptom): ConditionResult {
        if (node.symptom !in context.symptoms) return NO_MATCH
        return ConditionResult(
            fired = true,
            matches = listOf(
                SymptomMatch(
                    symptom = node.symptom,
                    observedAt = context.symptomsObservedAt ?: context.nowIso,
                    checkinId = context.symptomCheckinId,
                ),
            ),
        )
    }

    private companion object {
        val NO_MATCH = ConditionResult(fired = false, matches = emptyList())
    }
}
