package com.yasmin.astrocare.domain.rule.model

/**
 * Thrown when `rules.v1.json` (or `enums.json`) is malformed or uses constructs
 * this engine does not support. The engine NEVER silently skips a rule — any
 * problem in any rule aborts loading with the full problem list.
 */
class RuleSetException(val problems: List<String>) : IllegalArgumentException(
    "Invalid rule set (${problems.size} problem(s)):\n- " + problems.joinToString("\n- "),
)

/**
 * Parsed, validated `rules.v1.json` (contracts/data/rules.v1.json).
 * Provenance fields (sources, actions, disclaimer, revision, ruleSetVersion)
 * are preserved verbatim and copied into every emitted alert.
 */
data class RuleSet(
    val schemaVersion: String,
    val ruleSetVersion: String,
    val effectiveFrom: String,
    val disclaimer: String,
    val evaluation: EvaluationPolicy,
    val statusPolicy: StatusPolicy,
    val trendPolicy: TrendPolicy,
    /** key = sourceRef (SRC-...). */
    val sources: Map<String, RuleSource>,
    /** key = actionRef (ACT-...). */
    val actions: Map<String, String>,
    val rules: List<Rule>,
    val demoOnly: Boolean,
) {
    fun source(ref: String): RuleSource =
        sources[ref] ?: throw RuleSetException(listOf("unknown sourceRef '$ref'"))

    fun action(ref: String): String =
        actions[ref] ?: throw RuleSetException(listOf("unknown actionRef '$ref'"))
}

data class EvaluationPolicy(
    val trigger: String,
    val contextPolicy: String,
    val contextWindowMinutes: Int,
    val groupPolicy: String,
    val dedupe: String,
    val maxEvaluationMillis: Int,
    val defaultExplanationTemplate: String,
    val evidenceOrder: String,
)

data class StatusPolicy(
    val minMetricsForNormal: Int,
    val note: String,
)

data class TrendPolicy(
    val windowDays: Int,
    val baseline: BaselinePolicy,
    val direction: DirectionPolicy,
    /** From `trendPolicy.rounding` ("... rounded to 2 decimals"). */
    val roundingDecimals: Int,
)

data class BaselinePolicy(
    val windowDays: Int,
    val excludeLastHours: Int,
    val minSamples: Int,
    val minDistinctDays: Int,
    val method: String,
)

data class DirectionPolicy(
    val compare: String,
    val minDaysWithDataPerSide: Int,
    val stableBand: Map<String, Double>,
)

data class RuleSource(
    val title: String,
    val url: String?,
    val verified: Boolean,
)

/** Comparison operators of the `when.field/op/value` grammar. */
enum class ComparatorOp(val symbol: String) {
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
    EQ("=="),
    ;

    fun test(observed: Double, threshold: Double): Boolean = when (this) {
        LT -> observed < threshold
        LE -> observed <= threshold
        GT -> observed > threshold
        GE -> observed >= threshold
        EQ -> observed == threshold
    }

    companion object {
        fun fromSymbol(symbol: String): ComparatorOp? = entries.firstOrNull { it.symbol == symbol }
    }
}

/** Grammar nodes of a rule's `when` block (exactly one form per node). */
sealed interface WhenNode {
    data class Field(val field: String, val op: ComparatorOp, val value: Double) : WhenNode
    data class Symptom(val symptom: String) : WhenNode
    data class AllOf(val children: List<WhenNode>) : WhenNode
    data class AnyOf(val children: List<WhenNode>) : WhenNode
}

/** The three rule types of rules.v1.json — as a sealed hierarchy, not strings. */
sealed class Rule {
    abstract val id: String
    abstract val revision: Int

    /** type=threshold, output=alert — produces alerts. */
    data class Threshold(
        override val id: String,
        override val revision: Int,
        /** `warning` | `critical` — the level always comes from the RULE. */
        val level: String,
        /** `vital` | `symptom` | `combination` | `emergency`. */
        val category: String,
        /** Group for the "highest level wins" policy. */
        val group: String,
        val metrics: List<String>,
        val title: String,
        val whenNode: WhenNode,
        val actionRef: String,
        val sourceRef: String,
        /** null = use evaluation.defaultExplanationTemplate. */
        val explanationTemplate: String?,
        val emergencyProtocolId: String?,
    ) : Rule()

    /** type=baseline_deviation, output=insight — never produces alerts. */
    data class BaselineDeviation(
        override val id: String,
        override val revision: Int,
        val severity: String,
        val metrics: List<String>,
        val field: String,
        val zThreshold: Double,
        val sdFloor: Double,
        val text: String,
    ) : Rule()

    /** type=window_average, output=insight — never produces alerts. */
    data class WindowAverage(
        override val id: String,
        override val revision: Int,
        val severity: String,
        val metrics: List<String>,
        val field: String,
        val lastN: Int,
        val op: ComparatorOp,
        val value: Double,
        val text: String,
    ) : Rule()
}

object AlertLevel {
    const val WARNING = "warning"
    const val CRITICAL = "critical"

    fun rank(level: String): Int = when (level) {
        CRITICAL -> 2
        WARNING -> 1
        else -> 0
    }
}

/** contracts/common.schema.json overallStatusValue — lowercase, contract literals. */
enum class OverallStatusValue { normal, warning, critical, insufficient_data }
