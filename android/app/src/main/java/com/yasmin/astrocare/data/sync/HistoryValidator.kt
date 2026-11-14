package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Structural contract checks for server history. This is deliberately pure and
 * runs before the repository opens its Room transaction, so malformed responses
 * cannot leave a partially merged database.
 */
internal object HistoryValidator {
    private val utcDateTime = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z$")
    private val astronaut = Regex("^[a-z0-9][a-z0-9-]{2,39}$")
    private val semver = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val ruleId = Regex("^[A-Z0-9]+(-[A-Z0-9]+)+$")
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val metrics = setOf("heart_rate", "blood_pressure", "spo2", "temperature", "sleep")
    private val environments = setOf("iss", "spacecraft", "moon_base")
    private val sources = setOf("manual", "synthetic")
    private val symptoms = setOf("chest_pain", "dizziness", "shortness_of_breath", "none")
    private val alertLevels = setOf("warning", "critical")
    private val alertCategories = setOf("vital", "symptom", "combination", "emergency")
    private val units = setOf("bpm", "mmHg", "percent", "celsius", "hours", "score", "flag")
    private val comparators = setOf("<", "<=", ">", ">=", "==")
    private val evidenceFields = setOf(
        "heart_rate.bpm",
        "blood_pressure.systolic",
        "blood_pressure.diastolic",
        "spo2.percent",
        "temperature.celsius",
        "sleep.hours",
        "sleep.quality",
        "symptom.chest_pain",
        "symptom.dizziness",
        "symptom.shortness_of_breath",
    )

    fun validate(
        measurements: List<MeasurementDto>,
        checkins: List<SymptomCheckInDto>,
        alerts: List<AlertDto>,
        astronautId: String,
    ) {
        require(astronaut.matches(astronautId)) { "invalid astronautId '$astronautId'" }
        ensureUniqueIds("measurements", measurements.map { it.id })
        ensureUniqueIds("checkins", checkins.map { it.id })
        ensureUniqueIds("alerts", alerts.map { it.id })
        measurements.forEachIndexed { index, value -> validateMeasurement(value, astronautId, index) }
        checkins.forEachIndexed { index, value -> validateCheckin(value, astronautId, index) }
        alerts.forEachIndexed { index, value -> validateAlert(value, astronautId, index) }
    }

    private fun validateMeasurement(value: MeasurementDto, astronautId: String, index: Int) {
        at("measurements[$index]") {
            common(value.schemaVersion, value.id, value.astronautId, value.measuredAt, value.createdAt, value.utcOffsetMinutes, astronautId)
            require(value.metric in metrics) { "unknown metric '${value.metric}'" }
            require(value.environment in environments) { "unknown environment '${value.environment}'" }
            require(value.source in sources) { "unknown source '${value.source}'" }
            require(value.note == null || value.note.length <= 280) { "note exceeds 280 characters" }
            validateValues(value.metric, value.values)
        }
    }

    private fun validateValues(metric: String, values: JsonObject) {
        val expected = when (metric) {
            "heart_rate" -> setOf("bpm")
            "blood_pressure" -> setOf("systolic", "diastolic")
            "spo2" -> setOf("percent")
            "temperature" -> setOf("celsius")
            "sleep" -> setOf("hours", "quality")
            else -> error("unknown metric '$metric'")
        }
        require(values.keys == expected) { "values keys for $metric must be $expected" }
        val ranges = when (metric) {
            "heart_rate" -> mapOf("bpm" to 20.0..250.0)
            "blood_pressure" -> mapOf("systolic" to 50.0..260.0, "diastolic" to 30.0..160.0)
            "spo2" -> mapOf("percent" to 50.0..100.0)
            "temperature" -> mapOf("celsius" to 30.0..43.0)
            "sleep" -> mapOf("hours" to 0.0..24.0, "quality" to 1.0..5.0)
            else -> emptyMap()
        }
        val numbers = values.mapValues { (field, element) ->
            val number = number(element, "values.$field")
            require(number.toDouble() in ranges.getValue(field)) {
                "values.$field is outside ${ranges.getValue(field)}"
            }
            if (metric == "heart_rate" || metric == "blood_pressure" || field == "quality") {
                require(number.stripTrailingZeros().scale() <= 0) { "values.$field must be an integer" }
            }
            number
        }
        if (metric == "blood_pressure") {
            require(numbers.getValue("systolic") > numbers.getValue("diastolic")) {
                "systolic must be greater than diastolic"
            }
        }
    }

    private fun validateCheckin(value: SymptomCheckInDto, astronautId: String, index: Int) {
        at("checkins[$index]") {
            common(value.schemaVersion, value.id, value.astronautId, value.checkedAt, value.createdAt, value.utcOffsetMinutes, astronautId)
            require(value.environment in environments) { "unknown environment '${value.environment}'" }
            require(value.source in sources) { "unknown source '${value.source}'" }
            require(value.symptoms.isNotEmpty()) { "symptoms must not be empty" }
            require(value.symptoms.all { it in symptoms }) { "unknown symptom code" }
            require(value.symptoms.size == value.symptoms.toSet().size) { "symptoms must be unique" }
            require("none" !in value.symptoms || value.symptoms.size == 1) { "none must stand alone" }
            value.chestPainTriage?.let { triage ->
                require(triage.answers.size <= 5) { "triage has more than 5 answers" }
            }
        }
    }

    private fun validateAlert(value: AlertDto, astronautId: String, index: Int) {
        at("alerts[$index]") {
            common(value.schemaVersion, value.id, value.astronautId, value.createdAt, value.createdAt, 0, astronautId)
            require(value.level in alertLevels) { "unknown alert level '${value.level}'" }
            require(value.category in alertCategories) { "unknown alert category '${value.category}'" }
            require(ruleId.matches(value.ruleId)) { "invalid ruleId '${value.ruleId}'" }
            require(value.ruleRevision >= 1) { "ruleRevision must be positive" }
            require(semver.matches(value.ruleSetVersion)) { "invalid ruleSetVersion '${value.ruleSetVersion}'" }
            require(value.title.length in 3..80) { "title length is invalid" }
            require(value.explanation.length in 10..400) { "explanation length is invalid" }
            require(value.recommendedAction.length in 10..400) { "recommendedAction length is invalid" }
            require(value.disclaimer.length in 20..400) { "disclaimer length is invalid" }
            require(value.evidence.size in 1..6) { "evidence count must be 1..6" }
            value.evidence.forEach { evidence ->
                require(evidence.field in evidenceFields) { "unknown evidence field '${evidence.field}'" }
                require(evidence.unit in units) { "unknown evidence unit '${evidence.unit}'" }
                require(evidence.comparator in comparators) { "unknown evidence comparator '${evidence.comparator}'" }
                require(evidence.observed.isFinite() && evidence.threshold.isFinite()) { "evidence numbers must be finite" }
                validateUtc(evidence.observedAt, "evidence.observedAt")
                evidence.measurementId?.let { validUuid(it, "evidence.measurementId") }
                evidence.symptomCheckinId?.let { validUuid(it, "evidence.symptomCheckinId") }
                require(evidence.baselineMean?.isFinite() != false) { "baselineMean must be finite" }
                require(evidence.baselineSd?.isFinite() != false) { "baselineSd must be finite" }
                require(evidence.zScore?.isFinite() != false) { "zScore must be finite" }
            }
            value.triggeredBy.measurementIds.forEach { validUuid(it, "triggeredBy.measurementIds") }
            value.triggeredBy.symptomCheckinIds.forEach { validUuid(it, "triggeredBy.symptomCheckinIds") }
            value.acknowledgedAt?.let { validateUtc(it, "acknowledgedAt") }
        }
    }

    private fun common(
        schemaVersion: String,
        id: String,
        rowAstronautId: String,
        primaryAt: String,
        createdAt: String,
        utcOffsetMinutes: Int,
        astronautId: String,
    ) {
        require(schemaVersion == AppDefaults.SCHEMA_VERSION) { "schemaVersion must be ${AppDefaults.SCHEMA_VERSION}" }
        validUuid(id, "id")
        require(rowAstronautId == astronautId) { "astronautId does not match current astronaut" }
        validateUtc(primaryAt, "utcDateTime")
        validateUtc(createdAt, "createdAt")
        require(utcOffsetMinutes in -840..840) { "utcOffsetMinutes is outside -840..840" }
    }

    private fun validateUtc(value: String, field: String) {
        require(utcDateTime.matches(value)) { "$field is not a contract UTC timestamp" }
        try {
            IsoTime.parseUtc(value)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("$field is not a valid UTC timestamp", e)
        }
    }

    private fun validUuid(value: String, field: String) {
        require(uuid.matches(value)) { "$field is not a valid UUID" }
        UUID.fromString(value)
    }

    private fun number(element: JsonElement, field: String): BigDecimal {
        require(element !is JsonNull) { "$field is null" }
        val primitive = element as? JsonPrimitive ?: error("$field must be a number")
        require(!primitive.isString) { "$field must be a number" }
        return runCatching { BigDecimal(primitive.content) }
            .getOrElse { throw IllegalArgumentException("$field must be a number") }
    }

    private fun ensureUniqueIds(collection: String, ids: List<String>) {
        val normalized = ids.map { it.lowercase() }
        require(normalized.size == normalized.toSet().size) { "$collection contains duplicate ids" }
    }

    private inline fun at(path: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$path: ${e.message}", e)
        }
    }
}
