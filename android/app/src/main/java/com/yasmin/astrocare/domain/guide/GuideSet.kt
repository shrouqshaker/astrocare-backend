package com.yasmin.astrocare.domain.guide

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Thrown when the bundled guide set is malformed — fail loud, never render partial guides. */
class GuideSetException(val problems: List<String>) : IllegalArgumentException(
    "Invalid guide set (${problems.size} problem(s)):\n- " + problems.joinToString("\n- "),
)

/**
 * Parsed `contracts/data/guides.v1.json` — measurement how-to content and the
 * five bundled device illustrations (`deviceIllustration.assetName` maps to
 * `assets/<assetName>.png`, bundled, never downloaded).
 */
@Serializable
data class GuideSet(
    val schemaVersion: String,
    val guideSetVersion: String,
    val locale: String,
    val generatedFromRuleSetVersion: String,
    val guides: List<Guide>,
) {
    fun forMetric(metric: String): Guide? = guides.firstOrNull { it.metric == metric }
}

@Serializable
data class Guide(
    val id: String,
    val metric: String,
    val title: String,
    val deviceIllustration: DeviceIllustration,
    val howToMeasure: HowToMeasure,
    val whyItMatters: String,
    val referenceRange: ReferenceRange,
    val whatToDoIfAbnormal: WhatToDoIfAbnormal,
    val sources: List<GuideSource>,
    val disclaimer: String,
)

@Serializable
data class DeviceIllustration(
    /** e.g. "guide_heart_rate" — file is `assets/guide_heart_rate.png`. */
    val assetName: String,
    val altText: String,
)

@Serializable
data class HowToMeasure(val steps: List<String>)

@Serializable
data class ReferenceRange(
    val summary: String,
    val rows: List<RangeRow>,
    val note: String,
)

@Serializable
data class RangeRow(
    val field: String,
    val label: String,
    /** null = open-ended band (e.g. SpO2 has no upper bound). */
    val low: Double? = null,
    val high: Double? = null,
    val unit: String,
)

@Serializable
data class WhatToDoIfAbnormal(
    val warning: List<String>,
    val critical: List<String>,
)

@Serializable
data class GuideSource(
    val title: String,
    val url: String,
    val verified: Boolean,
)

/** Pure loader — parses and validates the frozen guide set (fail loud with every problem). */
object GuideSetLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    fun load(raw: String): GuideSet {
        val set = try {
            json.decodeFromString<GuideSet>(raw)
        } catch (e: SerializationException) {
            throw GuideSetException(listOf("not valid guide-set JSON: ${e.message}"))
        }

        val problems = buildList {
            if (set.guides.isEmpty()) add("guides must not be empty")
            val ids = set.guides.map { it.id }
            if (ids.size != ids.distinct().size) add("duplicate guide ids")
            val metrics = set.guides.map { it.metric }
            if (metrics.size != metrics.distinct().size) add("duplicate guide metrics")
            for (guide in set.guides) {
                if (guide.deviceIllustration.assetName.isBlank()) {
                    add("${guide.id}: deviceIllustration.assetName must not be blank")
                }
                if (guide.howToMeasure.steps.isEmpty()) add("${guide.id}: howToMeasure.steps must not be empty")
                if (guide.disclaimer.isBlank()) add("${guide.id}: disclaimer must not be blank")
                for (row in guide.referenceRange.rows) {
                    if (row.unit.isBlank()) add("${guide.id}: referenceRange row ${row.field} has no unit")
                }
            }
        }
        if (problems.isNotEmpty()) throw GuideSetException(problems)
        return set
    }
}
