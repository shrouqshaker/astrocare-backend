package com.yasmin.astrocare.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Contract DTOs — field-for-field mirrors of the contracts/schemas JSON files
 * (additionalProperties:false; required fields have NO defaults so they are always
 * encoded; optional fields default to null and are omitted when absent).
 *
 * Android-only state (localSyncStatus/syncError) MUST NEVER appear on these types.
 */

@Serializable
data class MeasurementDto(
    val schemaVersion: String,
    val id: String,
    val astronautId: String,
    /** `heart_rate` | `blood_pressure` | `spo2` | `temperature` | `sleep`. */
    val metric: String,
    /** Metric-dependent object: `{bpm}` / `{systolic,diastolic}` / `{percent}` / `{celsius}` / `{hours,quality}`. */
    val values: JsonObject,
    /** Contract `utcDateTime`, verbatim. */
    val measuredAt: String,
    val utcOffsetMinutes: Int,
    /** `iss` | `spacecraft` | `moon_base`. */
    val environment: String,
    /** `manual` | `synthetic`. */
    val source: String,
    val isSynthetic: Boolean,
    /** Optional, ≤280 chars; omitted when null. */
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class SymptomCheckInDto(
    val schemaVersion: String,
    val id: String,
    val astronautId: String,
    /** 1..unique items of `chest_pain|dizziness|shortness_of_breath|none`. */
    val symptoms: List<String>,
    /** Contract `utcDateTime`, verbatim. */
    val checkedAt: String,
    val utcOffsetMinutes: Int,
    val environment: String,
    val source: String,
    val isSynthetic: Boolean,
    /** Optional; the triage outcome is check-in DATA, not an alert level (DECISIONS §2). */
    val chestPainTriage: ChestPainTriageDto? = null,
    val createdAt: String,
)

@Serializable
data class ChestPainTriageDto(
    val protocolId: String,
    /** Max 5 (CP-Q1..CP-Q5). */
    val answers: List<TriageAnswerDto>,
    val outcome: TriageOutcome,
)

@Serializable
data class TriageAnswerDto(
    val questionId: String,
    val answer: TriageAnswer,
)

@Serializable
enum class TriageAnswer {
    @SerialName("yes") YES,
    @SerialName("no") NO,
    @SerialName("unsure") UNSURE,
}

@Serializable
enum class TriageOutcome {
    @SerialName("emergency_now") EMERGENCY_NOW,
    @SerialName("urgent_contact") URGENT_CONTACT,
}

@Serializable
data class AlertDto(
    val schemaVersion: String,
    val id: String,
    val astronautId: String,
    /** Contract `utcDateTime`, verbatim. */
    val createdAt: String,
    /** `warning` | `critical` — produced by rule evaluation only, never by UI code. */
    val level: String,
    /** `vital` | `symptom` | `combination` | `emergency`. */
    val category: String,
    val ruleId: String,
    val ruleRevision: Int,
    val ruleSetVersion: String,
    val title: String,
    val explanation: String,
    /** 1..6 evidence items (contracts/common.schema.json#/$defs/evidence). */
    val evidence: List<EvidenceDto>,
    val recommendedAction: String,
    val disclaimer: String,
    val source: AlertSourceDto,
    val triggeredBy: TriggeredByDto,
    /** Present for emergency-protocol alerts (e.g. `chest-pain-v1`); omitted when null. */
    val emergencyProtocolId: String? = null,
    /** Contract `utcDateTime`; omitted when null. */
    val acknowledgedAt: String? = null,
    val isSynthetic: Boolean,
)

@Serializable
data class EvidenceDto(
    /** `fieldKey`: e.g. `heart_rate.bpm`, `symptom.chest_pain`. */
    val field: String,
    val observed: Double,
    /** `bpm|mmHg|percent|celsius|hours|score|flag`. */
    val unit: String,
    /** `<` | `<=` | `>` | `>=` | `==`. */
    val comparator: String,
    val threshold: Double,
    val observedAt: String,
    val measurementId: String? = null,
    val symptomCheckinId: String? = null,
    val baselineMean: Double? = null,
    val baselineSd: Double? = null,
    val zScore: Double? = null,
)

@Serializable
data class AlertSourceDto(
    val ref: String,
    val title: String,
    /** Omitted when null. */
    val url: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TriggeredByDto(
    @EncodeDefault val measurementIds: List<String> = emptyList(),
    @EncodeDefault val symptomCheckinIds: List<String> = emptyList(),
)
