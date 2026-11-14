package com.yasmin.astrocare.domain.rule

import com.yasmin.astrocare.bundledAsset
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.domain.rule.model.RuleSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared fixtures for Phase 4 rule-engine tests (loads real contract files). */
internal object RuleTestFixtures {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val rulesRaw: String by lazy { bundledAsset("contracts/rules.v1.json").readText(Charsets.UTF_8) }
    val enumsRaw: String by lazy { bundledAsset("contracts/enums.json").readText(Charsets.UTF_8) }

    val catalog: ContractCatalog by lazy { ContractCatalogLoader.load(enumsRaw) }
    val ruleSet: RuleSet by lazy { RuleSetLoader.load(rulesRaw, catalog) }

    fun engine(): RuleEngine = RuleEngine(ruleSet, catalog)

    fun parse(iso: String): Long = IsoTime.parseUtc(iso)

    /** Deterministic measurement id derived from the field key. */
    fun fieldId(field: String): String =
        "30000000-0000-4000-8000-%012d".format(field.hashCode().toUInt().toLong())

    const val CHECKIN_ID = "20000000-0000-4000-8000-000000000001"

    /**
     * Direct evaluation context: latest value per field observed at [now]
     * (single-entry history), optional latest check-in symptoms.
     */
    fun context(
        measurements: Map<String, Double>,
        symptoms: List<String> = emptyList(),
        now: String = "2026-11-13T18:00:00Z",
        astronautId: String = "demo-astronaut-01",
        withIds: Boolean = true,
        isSynthetic: Boolean = true,
    ): EvaluationContext {
        val nowMillis = parse(now)
        val ctxMeasurements = measurements.map { (field, value) ->
            field to ContextMeasurement(
                field = field,
                metric = field.substringBefore('.'),
                value = value,
                observedAt = now,
                observedAtMillis = nowMillis,
                measurementId = if (withIds) fieldId(field) else null,
            )
        }.toMap()

        return EvaluationContext(
            astronautId = astronautId,
            nowMillis = nowMillis,
            nowIso = now,
            measurements = ctxMeasurements,
            fieldHistory = ctxMeasurements.mapValues { listOf(it.value) },
            symptoms = symptoms,
            symptomCheckinId = if (symptoms.isEmpty()) null else CHECKIN_ID,
            symptomsObservedAt = if (symptoms.isEmpty()) null else now,
            isSynthetic = isSynthetic,
        )
    }

    // ------------------------------------------------------------------ vectors

    @Serializable
    data class VectorFile(
        val schemaVersion: String = "",
        val ruleSetVersion: String = "",
        val vectors: List<VectorDto> = emptyList(),
    )

    @Serializable
    data class VectorDto(
        val name: String,
        val now: String,
        val context: VectorContextDto,
        val expected: VectorExpectedDto,
    )

    @Serializable
    data class VectorContextDto(
        val measurements: Map<String, Double> = emptyMap(),
        val symptoms: List<String> = emptyList(),
    )

    @Serializable
    data class VectorExpectedDto(
        val status: String,
        val alerts: List<VectorAlertDto> = emptyList(),
    )

    @Serializable
    data class VectorAlertDto(val ruleId: String, val level: String)

    /** Reads the GATE file bundled at src/test/resources/contracts/. */
    fun loadVectors(): List<VectorDto> {
        val raw = RuleTestFixtures::class.java
            .getResourceAsStream("/contracts/rule-vectors.v1.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("rule-vectors.v1.json missing from app/src/test/resources/contracts/")
        return json.decodeFromString<VectorFile>(raw).vectors
    }

    fun contextOf(vector: VectorDto): EvaluationContext = context(
        measurements = vector.context.measurements,
        symptoms = vector.context.symptoms,
        now = vector.now,
    )
}
