package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.domain.rule.model.AlertLevel
import com.yasmin.astrocare.domain.rule.model.ComparatorOp
import com.yasmin.astrocare.domain.rule.model.EvaluationPolicy
import com.yasmin.astrocare.domain.rule.model.Rule
import com.yasmin.astrocare.domain.rule.model.RuleSet
import com.yasmin.astrocare.domain.rule.model.RuleSetException
import com.yasmin.astrocare.domain.rule.model.RuleSource
import com.yasmin.astrocare.domain.rule.model.StatusPolicy
import com.yasmin.astrocare.domain.rule.model.TrendPolicy
import com.yasmin.astrocare.domain.rule.model.WhenNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Loads and validates `contracts/data/rules.v1.json` into a [RuleSet].
 *
 * Contract fidelity rules:
 *  - the complete `when` grammar is parsed: field/op/value, symptom, all, any
 *    (nested arbitrarily, at most [MAX_EVIDENCE] leaves so evidence fits the
 *    schema's 1..6 constraint);
 *  - every structural problem is collected and thrown as [RuleSetException]
 *    with the offending rule id — rules are NEVER silently skipped;
 *  - provenance (sources, actions, disclaimer, revision, ruleSetVersion) is
 *    preserved verbatim.
 *
 * No threshold exists anywhere in this file: values come from the JSON only.
 */
object RuleSetLoader {

    private const val MAX_EVIDENCE = 6
    private const val MAX_WHEN_DEPTH = 10

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
    private val RULE_ID = Regex("""^[A-Z0-9]+(-[A-Z0-9]+)+$""")
    private val ROUNDING = Regex("""rounded to (\d+) decimals""")
    private val TEMPLATE_PLACEHOLDER = Regex("""\{([^{}]+)\}""")
    private val ALLOWED_PLACEHOLDER =
        Regex("""^(ruleId|e\d+\.(label|observed|threshold|unit|comparator|baselineMean|zScore))$""")

    private val LEVELS = setOf(AlertLevel.WARNING, AlertLevel.CRITICAL)
    private val CATEGORIES = setOf("vital", "symptom", "combination", "emergency")
    private val SEVERITIES = setOf("info", "warning")

    fun load(raw: String, catalog: ContractCatalog): RuleSet {
        val dto = try {
            json.decodeFromString<RuleSetDto>(raw)
        } catch (e: SerializationException) {
            throw RuleSetException(listOf("rules.v1.json is not parsable JSON: ${e.message}"))
        }

        val problems = mutableListOf<String>()

        val schemaVersion = dto.schemaVersion
        if (schemaVersion != "1.0") problems += "schemaVersion must be '1.0' (was $schemaVersion)"

        val ruleSetVersion = dto.ruleSetVersion
        if (ruleSetVersion == null || !SEMVER.matches(ruleSetVersion)) {
            problems += "ruleSetVersion must be semver (was $ruleSetVersion)"
        }
        if (dto.effectiveFrom.isNullOrBlank()) problems += "effectiveFrom missing"
        val disclaimer = dto.disclaimer
        when {
            disclaimer.isNullOrBlank() -> problems += "disclaimer missing"
            disclaimer.length !in 20..400 -> problems += "disclaimer length ${disclaimer.length} not in 20..400"
        }

        val evaluation = dto.evaluation
        if (evaluation == null) {
            problems += "evaluation block missing"
        } else {
            if (evaluation.contextWindowMinutes <= 0) problems += "evaluation.contextWindowMinutes must be > 0"
            if (evaluation.maxEvaluationMillis <= 0) problems += "evaluation.maxEvaluationMillis must be > 0"
            if (evaluation.defaultExplanationTemplate.isNullOrBlank()) {
                problems += "evaluation.defaultExplanationTemplate missing"
            }
            if (evaluation.contextPolicy.isNullOrBlank()) problems += "evaluation.contextPolicy missing"
            if (evaluation.groupPolicy.isNullOrBlank()) problems += "evaluation.groupPolicy missing"
            if (evaluation.dedupe.isNullOrBlank()) problems += "evaluation.dedupe missing"
            if (evaluation.evidenceOrder.isNullOrBlank()) problems += "evaluation.evidenceOrder missing"
            if (evaluation.trigger.isNullOrBlank()) problems += "evaluation.trigger missing"
        }

        val statusPolicy = dto.statusPolicy
        if (statusPolicy == null) {
            problems += "statusPolicy block missing"
        } else if (statusPolicy.minMetricsForNormal == null || statusPolicy.minMetricsForNormal !in 0..5) {
            problems += "statusPolicy.minMetricsForNormal must be 0..5"
        }

        val trendPolicyDto = dto.trendPolicy
        var trendPolicy: TrendPolicy? = null
        if (trendPolicyDto == null) {
            problems += "trendPolicy block missing"
        } else {
            val baseline = trendPolicyDto.baseline
            val direction = trendPolicyDto.direction
            when {
                baseline == null -> problems += "trendPolicy.baseline missing"
                baseline.windowDays == null || baseline.windowDays <= 0 ->
                    problems += "trendPolicy.baseline.windowDays must be > 0"
                baseline.excludeLastHours == null || baseline.excludeLastHours < 0 ->
                    problems += "trendPolicy.baseline.excludeLastHours must be >= 0"
                baseline.minSamples == null || baseline.minSamples < 2 ->
                    problems += "trendPolicy.baseline.minSamples must be >= 2"
                baseline.minDistinctDays == null || baseline.minDistinctDays < 1 ->
                    problems += "trendPolicy.baseline.minDistinctDays must be >= 1"
                baseline.method.isNullOrBlank() -> problems += "trendPolicy.baseline.method missing"
                direction == null -> problems += "trendPolicy.direction missing"
                direction.minDaysWithDataPerSide == null || direction.minDaysWithDataPerSide < 1 ->
                    problems += "trendPolicy.direction.minDaysWithDataPerSide must be >= 1"
                direction.compare.isNullOrBlank() -> problems += "trendPolicy.direction.compare missing"
                else -> {
                    val rounding = ROUNDING.find(trendPolicyDto.rounding ?: "")
                    if (rounding == null) {
                        problems += "trendPolicy.rounding '${trendPolicyDto.rounding}' not parseable"
                    } else {
                        trendPolicy = TrendPolicy(
                            windowDays = trendPolicyDto.windowDays ?: 0,
                            baseline = com.yasmin.astrocare.domain.rule.model.BaselinePolicy(
                                windowDays = baseline.windowDays,
                                excludeLastHours = baseline.excludeLastHours,
                                minSamples = baseline.minSamples,
                                minDistinctDays = baseline.minDistinctDays,
                                method = baseline.method,
                            ),
                            direction = com.yasmin.astrocare.domain.rule.model.DirectionPolicy(
                                compare = direction.compare,
                                minDaysWithDataPerSide = direction.minDaysWithDataPerSide,
                                stableBand = direction.stableBand,
                            ),
                            roundingDecimals = rounding.groupValues[1].toInt(),
                        )
                        if (trendPolicyDto.windowDays == null || trendPolicyDto.windowDays <= 0) {
                            problems += "trendPolicy.windowDays must be > 0"
                        }
                    }
                }
            }
        }

        val sources = LinkedHashMap<String, RuleSource>()
        for ((ref, src) in dto.sources) {
            if (src.title.isNullOrBlank()) problems += "source '$ref' has no title"
            else sources[ref] = RuleSource(title = src.title, url = src.url, verified = src.verified)
        }
        if (sources.isEmpty()) problems += "no sources defined"

        val actions = LinkedHashMap<String, String>()
        for ((ref, text) in dto.actions) {
            when {
                text.isBlank() -> problems += "action '$ref' is blank"
                text.length !in 10..400 -> problems += "action '$ref' length ${text.length} not in 10..400"
                else -> actions[ref] = text
            }
        }
        if (actions.isEmpty()) problems += "no actions defined"

        if (dto.rules.isEmpty()) problems += "rules list empty"

        val rules = mutableListOf<Rule>()
        val seenIds = HashSet<String>()
        for (rawRule in dto.rules) {
            val id = rawRule.id
            if (id == null) {
                problems += "rule without id: $rawRule"
                continue
            }
            val at = "rule $id"
            if (!RULE_ID.matches(id)) problems += "$at: id does not match ${RULE_ID.pattern}"
            if (!seenIds.add(id)) problems += "$at: duplicate id"
            val revision = rawRule.revision
            if (revision == null || revision < 1) problems += "$at: revision must be >= 1"

            val rule = when (rawRule.type to rawRule.output) {
                "threshold" to "alert" -> parseThreshold(rawRule, at, catalog, sources, actions, problems)
                "baseline_deviation" to "insight" ->
                    parseBaselineDeviation(rawRule, at, catalog, problems)
                "window_average" to "insight" -> parseWindowAverage(rawRule, at, catalog, problems)
                else -> {
                    problems += "$at: unsupported type/output '${rawRule.type}'/'${rawRule.output}'"
                    null
                }
            }
            if (rule != null && revision != null) rules += rule
        }

        if (problems.isNotEmpty()) throw RuleSetException(problems)

        return RuleSet(
            schemaVersion = schemaVersion!!,
            ruleSetVersion = ruleSetVersion!!,
            effectiveFrom = dto.effectiveFrom!!,
            disclaimer = disclaimer!!,
            evaluation = EvaluationPolicy(
                trigger = evaluation!!.trigger!!,
                contextPolicy = evaluation.contextPolicy!!,
                contextWindowMinutes = evaluation.contextWindowMinutes,
                groupPolicy = evaluation.groupPolicy!!,
                dedupe = evaluation.dedupe!!,
                maxEvaluationMillis = evaluation.maxEvaluationMillis,
                defaultExplanationTemplate = evaluation.defaultExplanationTemplate!!,
                evidenceOrder = evaluation.evidenceOrder!!,
            ),
            statusPolicy = StatusPolicy(
                minMetricsForNormal = statusPolicy!!.minMetricsForNormal!!,
                note = statusPolicy.note ?: "",
            ),
            trendPolicy = trendPolicy!!,
            sources = sources,
            actions = actions,
            rules = rules,
            demoOnly = dto.demoOnly,
        )
    }

    // ------------------------------------------------------------------ rule parsers

    private fun parseThreshold(
        raw: RuleDto,
        at: String,
        catalog: ContractCatalog,
        sources: Map<String, RuleSource>,
        actions: Map<String, String>,
        problems: MutableList<String>,
    ): Rule.Threshold? {
        var ok = true
        val level = raw.level
        if (level == null || level !in LEVELS) { problems += "$at: level must be warning|critical (was $level)"; ok = false }
        val category = raw.category
        if (category == null || category !in CATEGORIES) { problems += "$at: category must be vital|symptom|combination|emergency (was $category)"; ok = false }
        val group = raw.group
        if (group.isNullOrBlank()) { problems += "$at: group missing"; ok = false }
        val title = raw.title
        when {
            title.isNullOrBlank() -> { problems += "$at: title missing"; ok = false }
            title.length !in 3..80 -> { problems += "$at: title length ${title.length} not in 3..80"; ok = false }
        }
        val actionRef = raw.actionRef
        if (actionRef == null || actionRef !in actions) { problems += "$at: actionRef not found in actions"; ok = false }
        val sourceRef = raw.sourceRef
        if (sourceRef == null || sourceRef !in sources) { problems += "$at: sourceRef not found in sources"; ok = false }
        val whenObject = raw.whenNode
        if (whenObject == null) {
            problems += "$at: when block missing"
            ok = false
        }
        for (metricId in raw.metrics) {
            if (catalog.metricDef(metricId) == null) { problems += "$at: unknown metric '$metricId'"; ok = false }
        }
        raw.explanationTemplate?.let { template ->
            validatePlaceholders(template, "$at: explanationTemplate", problems)?.let { ok = false }
        }

        val whenNode = whenObject?.let { parseWhen(it, at, 0, problems) }
        if (whenObject != null && whenNode == null) ok = false
        if (whenNode != null && countLeaves(whenNode) > MAX_EVIDENCE) {
            problems += "$at: when has ${countLeaves(whenNode)} leaves, evidence max is $MAX_EVIDENCE"
            ok = false
        }
        whenNode?.let { node ->
            val fields = mutableListOf<String>()
            val symptoms = mutableListOf<String>()
            collectLeaves(node, fields, symptoms)
            for (field in fields) {
                if (field !in catalog.fieldLabels) {
                    problems += "$at: when references unknown field '$field'"
                    ok = false
                }
            }
            for (symptom in symptoms) {
                if ("symptom.$symptom" !in catalog.fieldLabels) {
                    problems += "$at: when references unknown symptom '$symptom'"
                    ok = false
                }
            }
        }

        if (!ok) return null
        return Rule.Threshold(
            id = raw.id!!,
            revision = raw.revision!!,
            level = level!!,
            category = category!!,
            group = group!!,
            metrics = raw.metrics,
            title = title!!,
            whenNode = whenNode!!,
            actionRef = actionRef!!,
            sourceRef = sourceRef!!,
            explanationTemplate = raw.explanationTemplate,
            emergencyProtocolId = raw.emergencyProtocolId,
        )
    }

    private fun parseBaselineDeviation(
        raw: RuleDto,
        at: String,
        catalog: ContractCatalog,
        problems: MutableList<String>,
    ): Rule.BaselineDeviation? {
        var ok = true
        if (raw.severity == null || raw.severity !in SEVERITIES) { problems += "$at: severity must be info|warning"; ok = false }
        val field = raw.field
        if (field == null || !isLabeledField(catalog, field)) { problems += "$at: field '$field' not in enums fieldLabels"; ok = false }
        val zThreshold = raw.zThreshold
        if (zThreshold == null || zThreshold <= 0) { problems += "$at: zThreshold must be > 0"; ok = false }
        val sdFloor = raw.sdFloor
        if (sdFloor == null || sdFloor <= 0) { problems += "$at: sdFloor must be > 0"; ok = false }
        if (raw.text.isNullOrBlank()) { problems += "$at: text missing"; ok = false }
        validateMetrics(raw.metrics, catalog, at, problems)?.let { ok = false }

        if (!ok) return null
        return Rule.BaselineDeviation(
            id = raw.id!!,
            revision = raw.revision!!,
            severity = raw.severity!!,
            metrics = raw.metrics,
            field = field!!,
            zThreshold = zThreshold!!,
            sdFloor = sdFloor!!,
            text = raw.text!!,
        )
    }

    private fun parseWindowAverage(
        raw: RuleDto,
        at: String,
        catalog: ContractCatalog,
        problems: MutableList<String>,
    ): Rule.WindowAverage? {
        var ok = true
        if (raw.severity == null || raw.severity !in SEVERITIES) { problems += "$at: severity must be info|warning"; ok = false }
        val field = raw.field
        if (field == null || !isLabeledField(catalog, field)) { problems += "$at: field '$field' not in enums fieldLabels"; ok = false }
        val lastN = raw.lastN
        if (lastN == null || lastN < 1) { problems += "$at: lastN must be >= 1"; ok = false }
        val opSymbol = raw.op
        val op = opSymbol?.let { ComparatorOp.fromSymbol(it) }
        if (op == null) { problems += "$at: op '$opSymbol' not one of < <= > >= =="; ok = false }
        if (raw.value == null) { problems += "$at: value missing"; ok = false }
        if (raw.text.isNullOrBlank()) { problems += "$at: text missing"; ok = false }
        validateMetrics(raw.metrics, catalog, at, problems)?.let { ok = false }

        if (!ok) return null
        return Rule.WindowAverage(
            id = raw.id!!,
            revision = raw.revision!!,
            severity = raw.severity!!,
            metrics = raw.metrics,
            field = field!!,
            lastN = lastN!!,
            op = op!!,
            value = raw.value!!,
            text = raw.text!!,
        )
    }

    private fun validateMetrics(
        metrics: List<String>,
        catalog: ContractCatalog,
        at: String,
        problems: MutableList<String>,
    ): Any? {
        for (metricId in metrics) {
            if (catalog.metricDef(metricId) == null) {
                problems += "$at: unknown metric '$metricId'"
                return problems
            }
        }
        return null
    }

    private fun isLabeledField(catalog: ContractCatalog, field: String): Boolean =
        field in catalog.fieldLabels && !field.startsWith("symptom.")

    private fun validatePlaceholders(template: String, at: String, problems: MutableList<String>): Any? {
        for (match in TEMPLATE_PLACEHOLDER.findAll(template)) {
            val name = match.groupValues[1]
            if (!ALLOWED_PLACEHOLDER.matches(name)) {
                problems += "$at: unsupported placeholder '{$name}'"
                return problems
            }
        }
        return null
    }

    // ------------------------------------------------------------------ when grammar

    private val WHEN_FORMS = setOf("field", "symptom", "all", "any")

    private fun parseWhen(
        node: JsonObject,
        at: String,
        depth: Int,
        problems: MutableList<String>,
    ): WhenNode? {
        if (depth > MAX_WHEN_DEPTH) {
            problems += "$at: when nested deeper than $MAX_WHEN_DEPTH"
            return null
        }
        val forms = node.keys.filter { it in WHEN_FORMS }
        if (forms.isEmpty()) {
            problems += "$at: when has no recognized form (field|symptom|all|any)"
            return null
        }
        if (forms.size > 1) {
            problems += "$at: when has multiple forms $forms — exactly one required"
            return null
        }

        return when (val form = forms.single()) {
            "field" -> {
                val extra = node.keys - setOf("field", "op", "value")
                if (extra.isNotEmpty()) {
                    problems += "$at: field form has unexpected keys $extra"
                    return null
                }
                val field = (node["field"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (field == null) {
                    problems += "$at: field form needs a string 'field'"
                    return null
                }
                val opSymbol = (node["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val op = opSymbol?.let { ComparatorOp.fromSymbol(it) }
                if (op == null) {
                    problems += "$at: field form needs op in < <= > >= == (was '$opSymbol')"
                    return null
                }
                val value = (node["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (value == null) {
                    problems += "$at: field form needs a numeric 'value'"
                    return null
                }
                WhenNode.Field(field = field, op = op, value = value)
            }

            "symptom" -> {
                if (node.keys != setOf("symptom")) {
                    problems += "$at: symptom form must contain only 'symptom'"
                    return null
                }
                val symptom = (node["symptom"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (symptom == null) {
                    problems += "$at: symptom form needs a string 'symptom'"
                    return null
                }
                WhenNode.Symptom(symptom = symptom)
            }

            else -> {
                if (node.keys != setOf(form)) {
                    problems += "$at: $form form must contain only '$form'"
                    return null
                }
                val array = node[form] as? JsonArray
                if (array == null || array.isEmpty()) {
                    problems += "$at: '$form' must be a non-empty array"
                    return null
                }
                val children = array.map { element ->
                    val child = element as? JsonObject
                    if (child == null) {
                        problems += "$at: '$form' children must be objects"
                        null
                    } else {
                        parseWhen(child, at, depth + 1, problems)
                    }
                }
                if (children.any { it == null }) return null
                val kids = children.filterNotNull()
                if (form == "all") WhenNode.AllOf(kids) else WhenNode.AnyOf(kids)
            }
        }
    }

    private fun countLeaves(node: WhenNode): Int = when (node) {
        is WhenNode.Field -> 1
        is WhenNode.Symptom -> 1
        is WhenNode.AllOf -> node.children.sumOf { countLeaves(it) }
        is WhenNode.AnyOf -> node.children.sumOf { countLeaves(it) }
    }

    private fun collectLeaves(
        node: WhenNode,
        fields: MutableList<String>,
        symptoms: MutableList<String>,
    ) {
        when (node) {
            is WhenNode.Field -> fields += node.field
            is WhenNode.Symptom -> symptoms += node.symptom
            is WhenNode.AllOf -> node.children.forEach { collectLeaves(it, fields, symptoms) }
            is WhenNode.AnyOf -> node.children.forEach { collectLeaves(it, fields, symptoms) }
        }
    }

    // ------------------------------------------------------------------ DTOs

    @Serializable
    private data class RuleSetDto(
        val schemaVersion: String? = null,
        val ruleSetVersion: String? = null,
        val effectiveFrom: String? = null,
        val disclaimer: String? = null,
        val evaluation: EvaluationDto? = null,
        val statusPolicy: StatusPolicyDto? = null,
        val trendPolicy: TrendPolicyDto? = null,
        val sources: Map<String, SourceDto> = emptyMap(),
        val actions: Map<String, String> = emptyMap(),
        val rules: List<RuleDto> = emptyList(),
        val demoOnly: Boolean = false,
    )

    @Serializable
    private data class EvaluationDto(
        val trigger: String? = null,
        val contextPolicy: String? = null,
        val contextWindowMinutes: Int = 0,
        val groupPolicy: String? = null,
        val dedupe: String? = null,
        val maxEvaluationMillis: Int = 0,
        val defaultExplanationTemplate: String? = null,
        val templatePlaceholders: List<String> = emptyList(),
        val evidenceOrder: String? = null,
    )

    @Serializable
    private data class StatusPolicyDto(
        val minMetricsForNormal: Int? = null,
        val note: String? = null,
    )

    @Serializable
    private data class TrendPolicyDto(
        val windowDays: Int? = null,
        val baseline: BaselineDto? = null,
        val direction: DirectionDto? = null,
        val rounding: String? = null,
    )

    @Serializable
    private data class BaselineDto(
        val windowDays: Int? = null,
        val excludeLastHours: Int? = null,
        val minSamples: Int? = null,
        val minDistinctDays: Int? = null,
        val method: String? = null,
    )

    @Serializable
    private data class DirectionDto(
        val compare: String? = null,
        val minDaysWithDataPerSide: Int? = null,
        val stableBand: Map<String, Double> = emptyMap(),
    )

    @Serializable
    private data class SourceDto(
        val title: String? = null,
        val url: String? = null,
        val verified: Boolean = false,
    )

    @Serializable
    private data class RuleDto(
        val id: String? = null,
        val revision: Int? = null,
        val type: String? = null,
        val output: String? = null,
        val level: String? = null,
        val category: String? = null,
        val group: String? = null,
        val metrics: List<String> = emptyList(),
        val title: String? = null,
        @SerialName("when") val whenNode: JsonObject? = null,
        val actionRef: String? = null,
        val sourceRef: String? = null,
        val explanationTemplate: String? = null,
        val emergencyProtocolId: String? = null,
        val severity: String? = null,
        val field: String? = null,
        val zThreshold: Double? = null,
        val sdFloor: Double? = null,
        val lastN: Int? = null,
        val op: String? = null,
        val value: Double? = null,
        val text: String? = null,
    )
}
