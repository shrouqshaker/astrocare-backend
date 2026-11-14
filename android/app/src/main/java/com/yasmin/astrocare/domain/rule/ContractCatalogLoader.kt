package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.ComparatorOp
import com.yasmin.astrocare.domain.rule.model.RuleSetException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Loads `contracts/enums.json` into [ContractCatalog].
 * Every structural problem is collected and thrown as [RuleSetException] —
 * nothing is defaulted silently.
 */
object ContractCatalogLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val CROSS_FIELD = Regex("""^(\w+)\s*(<=|>=|<|>|==)\s*(\w+)$""")

    fun load(raw: String): ContractCatalog {
        val dto = try {
            json.decodeFromString<CatalogDto>(raw)
        } catch (e: SerializationException) {
            throw RuleSetException(listOf("enums.json is not parsable JSON: ${e.message}"))
        }

        val problems = mutableListOf<String>()

        if (dto.defaultAstronautId.isNullOrBlank()) {
            problems += "enums.json: defaultAstronautId missing"
        }
        if (dto.symptoms.isEmpty()) problems += "enums.json: symptoms list empty"
        if (dto.fieldLabels.isEmpty()) problems += "enums.json: fieldLabels empty"
        if (dto.unitDisplay.isEmpty()) problems += "enums.json: unitDisplay empty"
        if (dto.alertLevels != listOf("warning", "critical")) {
            problems += "enums.json: alertLevels must be [warning, critical] but was ${dto.alertLevels}"
        }
        if (dto.overallStatus.toSet() != setOf("normal", "warning", "critical", "insufficient_data")) {
            problems += "enums.json: overallStatus must be normal|warning|critical|insufficient_data"
        }

        val metrics = LinkedHashMap<String, MetricDef>()
        for ((metricId, metric) in dto.metrics) {
            if (metric.label.isNullOrBlank()) problems += "enums.json: metric '$metricId' has no label"
            if (metric.fields.isEmpty()) problems += "enums.json: metric '$metricId' has no fields"
            val fields = LinkedHashMap<String, FieldDef>()
            for ((fieldId, field) in metric.fields) {
                val unit = field.unit
                val min = field.min
                val max = field.max
                val type = field.type
                when {
                    unit.isNullOrBlank() -> problems += "enums.json: $metricId.$fieldId missing unit"
                    min == null -> problems += "enums.json: $metricId.$fieldId missing min"
                    max == null -> problems += "enums.json: $metricId.$fieldId missing max"
                    type.isNullOrBlank() -> problems += "enums.json: $metricId.$fieldId missing type"
                    min >= max -> problems += "enums.json: $metricId.$fieldId min >= max"
                    else -> fields[fieldId] = FieldDef(unit, min, max, type)
                }
            }

            var crossField: CrossFieldRule? = null
            val rule = metric.crossFieldRule
            if (rule != null) {
                val m = CROSS_FIELD.matchEntire(rule)
                if (m == null) {
                    problems += "enums.json: $metricId crossFieldRule '$rule' not parseable"
                } else {
                    val (left, opSymbol, right) = m.destructured
                    val op = ComparatorOp.fromSymbol(opSymbol)
                    if (op == null) {
                        problems += "enums.json: $metricId crossFieldRule unknown op '$opSymbol'"
                    } else if (left !in fields || right !in fields) {
                        problems += "enums.json: $metricId crossFieldRule references unknown field"
                    } else {
                        crossField = CrossFieldRule(left, op, right)
                    }
                }
            }

            metrics[metricId] = MetricDef(
                label = metric.label ?: "",
                fields = fields,
                crossFieldRule = rule,
                crossField = crossField,
            )
        }
        if (metrics.isEmpty()) problems += "enums.json: no metrics defined"

        // Evidence field keys must be labeled (schema fieldKey vocabulary).
        for (key in dto.fieldLabels.keys) {
            if ('.' !in key) problems += "enums.json: fieldLabel '$key' is not a fieldKey"
        }

        if (dto.environments.isEmpty()) problems += "enums.json: environments list empty"
        val environments = dto.environments.map { EnvironmentDef(it.id, it.label) }
        if (environments.size != environments.distinctBy { it.id }.size) {
            problems += "enums.json: duplicate environment ids"
        }

        if (problems.isNotEmpty()) throw RuleSetException(problems)

        return ContractCatalog(
            metrics = metrics,
            fieldLabels = dto.fieldLabels,
            unitDisplay = dto.unitDisplay,
            symptoms = dto.symptoms,
            defaultAstronautId = dto.defaultAstronautId!!,
            schemaVersion = dto.schemaVersion,
            environments = environments,
        )
    }

    @Serializable
    private data class CatalogDto(
        val schemaVersion: String = "1.0",
        val metrics: Map<String, MetricDto> = emptyMap(),
        val fieldLabels: Map<String, String> = emptyMap(),
        val unitDisplay: Map<String, String> = emptyMap(),
        val symptoms: List<String> = emptyList(),
        val environments: List<EnvironmentDto> = emptyList(),
        val alertLevels: List<String> = emptyList(),
        val overallStatus: List<String> = emptyList(),
        val defaultAstronautId: String? = null,
    )

    @Serializable
    private data class EnvironmentDto(
        val id: String = "",
        val label: String = "",
    )

    @Serializable
    private data class MetricDto(
        val label: String? = null,
        val fields: Map<String, FieldDto> = emptyMap(),
        val crossFieldRule: String? = null,
    )

    @Serializable
    private data class FieldDto(
        val unit: String? = null,
        val min: Double? = null,
        val max: Double? = null,
        val type: String? = null,
    )
}
