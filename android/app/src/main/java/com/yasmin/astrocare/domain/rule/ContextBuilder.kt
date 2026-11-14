package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.BaselinePolicy
import com.yasmin.astrocare.domain.rule.model.EvaluationPolicy

/** Domain-side measurement row (entity-free, so the builder is unit-testable). */
data class MeasurementRecord(
    val id: String,
    val metric: String,
    val values: Map<String, Double>,
    /** Contract utcDateTime verbatim. */
    val observedAt: String,
    val observedAtMillis: Long,
    val isSynthetic: Boolean,
)

/** Domain-side symptom check-in row. */
data class SymptomCheckInRecord(
    val id: String,
    val symptoms: List<String>,
    val checkedAt: String,
    val checkedAtMillis: Long,
    val isSynthetic: Boolean,
)

/**
 * Applies the contract `evaluation.contextPolicy` to raw rows:
 *
 *  - "latest valid measurement per metric ... only if within
 *    contextWindowMinutes" — validity = every present value inside the
 *    enums.json min/max range (plus the metric's crossFieldRule, e.g.
 *    systolic > diastolic); invalid rows fall back to the next-latest valid one;
 *  - "latest symptom check-in ... within contextWindowMinutes" (boundary
 *    inclusive at exactly contextWindowMinutes);
 *  - per-field history for insight rules spans the trend baseline window
 *    (newest first, INCLUDING the last 24h — baseline exclusion happens in
 *    [BaselineCalculator]).
 *
 * Deterministic regardless of DAO order: rows sort by (time, id).
 */
class ContextBuilder(
    private val catalog: ContractCatalog,
    private val evaluationPolicy: EvaluationPolicy,
    private val baselinePolicy: BaselinePolicy,
) {

    fun build(
        astronautId: String,
        nowMillis: Long,
        nowIso: String,
        measurements: List<MeasurementRecord>,
        checkIns: List<SymptomCheckInRecord>,
    ): EvaluationContext {
        val contextFrom = nowMillis - evaluationPolicy.contextWindowMinutes * 60_000L
        val historyFrom = nowMillis - baselinePolicy.windowDays * DAY_MILLIS

        val sorted = measurements.sortedWith(compareBy({ it.observedAtMillis }, { it.id }))

        // Validity applies within the (longer) baseline window so history can hold
        // readings beyond the 1440-minute context window; the context window then
        // selects the subset used by threshold rules.
        val inHistory = sorted.filter { it.observedAtMillis in historyFrom..nowMillis }
        val valid = inHistory.filter { isValid(it) }
        val inWindow = valid.filter { it.observedAtMillis in contextFrom..nowMillis }

        val latestPerMetric = inWindow
            .groupBy { it.metric }
            .mapValues { (_, rows) -> rows.maxBy { it.observedAtMillis } }

        val contextMeasurements = LinkedHashMap<String, ContextMeasurement>()
        for (record in latestPerMetric.values) {
            for ((field, value) in record.values) {
                // contract fieldKey = "<metric>.<field>" (enums.json fieldLabels vocabulary)
                val fieldKey = "${record.metric}.$field"
                contextMeasurements[fieldKey] = ContextMeasurement(
                    field = fieldKey,
                    metric = record.metric,
                    value = value,
                    observedAt = record.observedAt,
                    observedAtMillis = record.observedAtMillis,
                    measurementId = record.id,
                )
            }
        }

        val fieldHistory = valid
            .flatMap { record ->
                record.values.map { (field, value) ->
                    val fieldKey = "${record.metric}.$field"
                    fieldKey to ContextMeasurement(
                        field = fieldKey,
                        metric = record.metric,
                        value = value,
                        observedAt = record.observedAt,
                        observedAtMillis = record.observedAtMillis,
                        measurementId = record.id,
                    )
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) ->
                rows.sortedWith(
                    compareByDescending<ContextMeasurement> { it.observedAtMillis }
                        .thenBy { it.measurementId ?: "" },
                )
            }

        val checkin = checkIns
            .filter { it.checkedAtMillis in contextFrom..nowMillis }
            .sortedWith(compareBy({ it.checkedAtMillis }, { it.id }))
            .lastOrNull()

        val contributing = buildList {
            addAll(latestPerMetric.values.map { it.isSynthetic })
            checkin?.let { add(it.isSynthetic) }
        }
        val isSynthetic = contributing.isNotEmpty() && contributing.all { it }

        return EvaluationContext(
            astronautId = astronautId,
            nowMillis = nowMillis,
            nowIso = nowIso,
            measurements = contextMeasurements,
            fieldHistory = fieldHistory,
            symptoms = checkin?.symptoms ?: emptyList(),
            symptomCheckinId = checkin?.id,
            symptomsObservedAt = checkin?.checkedAt,
            isSynthetic = isSynthetic,
        )
    }

    private fun isValid(record: MeasurementRecord): Boolean {
        if (record.values.isEmpty()) return false
        val def = catalog.metricDef(record.metric) ?: return false
        for ((field, value) in record.values) {
            val fieldDef = def.fields[field] ?: return false
            if (value.isNaN() || value.isInfinite()) return false
            if (value < fieldDef.min || value > fieldDef.max) return false
        }
        def.crossField?.let { cross ->
            val left = record.values[cross.leftField] ?: return@let
            val right = record.values[cross.rightField] ?: return@let
            if (!cross.op.test(left, right)) return false
        }
        return true
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
