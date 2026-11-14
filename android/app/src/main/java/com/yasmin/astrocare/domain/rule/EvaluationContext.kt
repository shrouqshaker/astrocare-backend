package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.EvidenceDto
import com.yasmin.astrocare.domain.rule.model.OverallStatusValue

/**
 * Input to one rule evaluation — built from Room data by [ContextBuilder]
 * (contract `evaluation.contextPolicy`) or directly by the vector tests.
 *
 * Everything here is already windowed/validated: [measurements] holds the LATEST
 * valid measurement per field within `contextWindowMinutes`; [fieldHistory] holds
 * per-field readings (newest first) within the trend baseline window so insight
 * rules can compute baselines and window averages.
 */
data class EvaluationContext(
    val astronautId: String,
    val nowMillis: Long,
    /** Contract utcDateTime of the evaluation instant. */
    val nowIso: String,
    /** fieldKey -> latest valid in-window reading. */
    val measurements: Map<String, ContextMeasurement>,
    /** fieldKey -> readings newest-first within the baseline window (incl. recent). */
    val fieldHistory: Map<String, List<ContextMeasurement>>,
    /** Latest in-window symptom check-in's symptoms (may contain "none"). */
    val symptoms: List<String>,
    val symptomCheckinId: String?,
    val symptomsObservedAt: String?,
    /** True when every contributing record is contract-synthetic demo data. */
    val isSynthetic: Boolean,
) {
    /** Distinct metrics present (0..5) — feeds statusPolicy.minMetricsForNormal. */
    val metricsInContext: Int
        get() = measurements.keys.map { it.substringBefore('.') }.distinct().size
}

data class ContextMeasurement(
    val field: String,
    val metric: String,
    val value: Double,
    /** Contract utcDateTime verbatim. */
    val observedAt: String,
    val observedAtMillis: Long,
    val measurementId: String?,
)

/** A stored alert reduced to what dedupe needs (domain-side, no Room types). */
data class ExistingAlertRef(
    val ruleId: String,
    val measurementIds: Set<String>,
    val symptomCheckinIds: Set<String>,
)

data class OverallStatus(
    val status: OverallStatusValue,
    val computedAt: String,
    val firingRuleIds: List<String>,
    val metricsInContext: Int,
    val ruleSetVersion: String,
)

/** An insight that fired (output=insight rules — never affect overall status). */
data class Insight(
    val ruleId: String,
    val ruleRevision: Int,
    val severity: String,
    val text: String,
    val field: String,
    val evidence: EvidenceDto?,
)

data class EvaluationResult(
    val overallStatus: OverallStatus,
    /** New alerts after group policy AND dedupe, sorted by ruleId. */
    val alerts: List<AlertDto>,
    val insights: List<Insight>,
    /** Suppressions, insufficient-baseline notes, budget overruns — never silent. */
    val diagnostics: List<String>,
)
