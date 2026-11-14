package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.ComparatorOp

/**
 * Parsed `contracts/enums.json` — the ONLY source of units, labels, display
 * strings and validity ranges used by the engine. Nothing medical is hardcoded:
 * every threshold/unit/label travels from the contract files.
 */
data class ContractCatalog(
    /** metric id -> definition. */
    val metrics: Map<String, MetricDef>,
    /** "heart_rate.bpm" -> "heart rate" (evidence labels, explanation templates). */
    val fieldLabels: Map<String, String>,
    /** raw unit -> display symbol ("percent" -> "%"). */
    val unitDisplay: Map<String, String>,
    val symptoms: List<String>,
    val defaultAstronautId: String,
    /** enums.json `schemaVersion` — carried into generated TrendReports. */
    val schemaVersion: String = "1.0",
    /** enums.json `environments` — Settings picker + emergency contact lookup. */
    val environments: List<EnvironmentDef> = emptyList(),
) {
    /** Raw contract unit for an evidence field, e.g. heart_rate.bpm -> "bpm". */
    fun unitFor(fieldKey: String): String {
        val metric = fieldKey.substringBefore('.', "")
        val field = fieldKey.substringAfter('.', "")
        return metrics[metric]?.fields?.get(field)?.unit
            ?: throw IllegalArgumentException("no unit defined for field '$fieldKey'")
    }

    fun displayUnit(unit: String): String = unitDisplay[unit] ?: unit

    fun labelFor(fieldKey: String): String = fieldLabels[fieldKey] ?: fieldKey

    fun metricDef(metricId: String): MetricDef? = metrics[metricId]
}

data class MetricDef(
    val label: String,
    val fields: Map<String, FieldDef>,
    /** e.g. blood_pressure: "systolic > diastolic" — parsed into [crossField]. */
    val crossFieldRule: String?,
    val crossField: CrossFieldRule?,
)

data class FieldDef(
    val unit: String,
    val min: Double,
    val max: Double,
    /** "integer" | "number". */
    val type: String,
)

data class CrossFieldRule(
    val leftField: String,
    val op: ComparatorOp,
    val rightField: String,
)

/** One enums.json environment (`iss` | `spacecraft` | `moon_base`). */
data class EnvironmentDef(val id: String, val label: String)
