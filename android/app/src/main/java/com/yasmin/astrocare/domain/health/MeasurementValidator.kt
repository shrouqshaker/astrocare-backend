package com.yasmin.astrocare.domain.health

import com.yasmin.astrocare.domain.rule.ContractCatalog

/**
 * Entry-time validation for a user-saved measurement — the SAME contract rules
 * [com.yasmin.astrocare.domain.rule.ContextBuilder] applies (enums.json
 * min/max ranges, integer types and the metric's crossFieldRule such as
 * `systolic > diastolic`), so an invalid row can never enter Room and silently
 * poison evaluation. Returns human-readable problems; an empty list = valid.
 *
 * Nothing medical is hardcoded — every bound travels from the contract catalog.
 */
object MeasurementValidator {

    fun validate(
        catalog: ContractCatalog,
        metric: String,
        values: Map<String, Double>,
    ): List<String> {
        val metricDef = catalog.metricDef(metric)
            ?: return listOf("unknown metric '$metric' (not in contracts/enums.json)")
        if (values.isEmpty()) return listOf("no values entered for $metric")

        val problems = mutableListOf<String>()
        for ((field, fieldDef) in metricDef.fields) {
            val value = values[field]
            if (value == null) {
                problems += "missing value for '$field'"
                continue
            }
            if (value.isNaN() || value.isInfinite()) {
                problems += "'$field' must be a finite number"
                continue
            }
            if (value < fieldDef.min || value > fieldDef.max) {
                problems += "'$field' must be between ${trimZero(fieldDef.min)} and " +
                    "${trimZero(fieldDef.max)} ${fieldDef.unit}"
            }
            if (fieldDef.type == "integer" && value != value.toLong().toDouble()) {
                problems += "'$field' must be a whole number"
            }
        }
        for (key in values.keys) {
            if (key !in metricDef.fields) problems += "unknown field '$key' for metric '$metric'"
        }
        metricDef.crossField?.let { cross ->
            val left = values[cross.leftField]
            val right = values[cross.rightField]
            if (left != null && right != null && !cross.op.test(left, right)) {
                problems += "'${cross.leftField}' must be ${cross.op.symbol} '${cross.rightField}'"
            }
        }
        return problems
    }

    private fun trimZero(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
