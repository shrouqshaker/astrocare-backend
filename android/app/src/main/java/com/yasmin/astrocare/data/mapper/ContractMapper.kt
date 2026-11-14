package com.yasmin.astrocare.data.mapper

import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.AlertSourceDto
import com.yasmin.astrocare.data.remote.dto.ChestPainTriageDto
import com.yasmin.astrocare.data.remote.dto.EvidenceDto
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import com.yasmin.astrocare.data.remote.dto.TriggeredByDto
import com.yasmin.astrocare.domain.rule.ExistingAlertRef
import com.yasmin.astrocare.domain.rule.MeasurementRecord
import com.yasmin.astrocare.domain.rule.SymptomCheckInRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Converts between Room rows and contract DTOs.
 *
 * The one invariant this class exists to protect: Android-only columns
 * ([SyncStatus] values and `syncError`) live ONLY on entities and are dropped
 * when producing DTOs, so they can never leak into a payload
 * (verified by ContractDtoTest).
 */
@Singleton
class ContractMapper @Inject constructor(
    private val json: Json,
) {

    // ---------------------------------------------------------------- measurement

    fun toDto(e: MeasurementEntity): MeasurementDto = MeasurementDto(
        schemaVersion = e.schemaVersion,
        id = e.id,
        astronautId = e.astronautId,
        metric = e.metric,
        values = json.parseToJsonElement(e.valuesJson) as JsonObject,
        measuredAt = e.measuredAt,
        utcOffsetMinutes = e.utcOffsetMinutes,
        environment = e.environment,
        source = e.source,
        isSynthetic = e.isSynthetic,
        note = e.note,
        createdAt = e.createdAt,
    )

    fun toEntity(
        d: MeasurementDto,
        localSyncStatus: String = SyncStatus.PENDING,
        syncError: String? = null,
    ): MeasurementEntity = MeasurementEntity(
        id = d.id,
        schemaVersion = d.schemaVersion,
        astronautId = d.astronautId,
        metric = d.metric,
        valuesJson = json.encodeToString(d.values),
        measuredAt = d.measuredAt,
        utcOffsetMinutes = d.utcOffsetMinutes,
        environment = d.environment,
        source = d.source,
        isSynthetic = d.isSynthetic,
        note = d.note,
        createdAt = d.createdAt,
        localSyncStatus = localSyncStatus,
        syncError = syncError,
    )

    // ---------------------------------------------------------------- symptom check-in

    fun toDto(e: SymptomCheckInEntity): SymptomCheckInDto = SymptomCheckInDto(
        schemaVersion = e.schemaVersion,
        id = e.id,
        astronautId = e.astronautId,
        symptoms = json.decodeFromString(e.symptomsJson),
        checkedAt = e.checkedAt,
        utcOffsetMinutes = e.utcOffsetMinutes,
        environment = e.environment,
        source = e.source,
        isSynthetic = e.isSynthetic,
        chestPainTriage = e.chestPainTriageJson?.let { json.decodeFromString(it) },
        createdAt = e.createdAt,
    )

    fun toEntity(
        d: SymptomCheckInDto,
        localSyncStatus: String = SyncStatus.PENDING,
        syncError: String? = null,
    ): SymptomCheckInEntity = SymptomCheckInEntity(
        id = d.id,
        schemaVersion = d.schemaVersion,
        astronautId = d.astronautId,
        symptomsJson = json.encodeToString(d.symptoms),
        checkedAt = d.checkedAt,
        utcOffsetMinutes = d.utcOffsetMinutes,
        environment = d.environment,
        source = d.source,
        isSynthetic = d.isSynthetic,
        chestPainTriageJson = d.chestPainTriage?.let { json.encodeToString(it) },
        createdAt = d.createdAt,
        localSyncStatus = localSyncStatus,
        syncError = syncError,
    )

    // ---------------------------------------------------------------- alert

    fun toDto(e: AlertEntity): AlertDto = AlertDto(
        schemaVersion = e.schemaVersion,
        id = e.id,
        astronautId = e.astronautId,
        createdAt = e.createdAt,
        level = e.level,
        category = e.category,
        ruleId = e.ruleId,
        ruleRevision = e.ruleRevision,
        ruleSetVersion = e.ruleSetVersion,
        title = e.title,
        explanation = e.explanation,
        evidence = json.decodeFromString(e.evidenceJson),
        recommendedAction = e.recommendedAction,
        disclaimer = e.disclaimer,
        source = json.decodeFromString(e.sourceJson),
        triggeredBy = json.decodeFromString(e.triggeredByJson),
        emergencyProtocolId = e.emergencyProtocolId,
        acknowledgedAt = e.acknowledgedAt,
        isSynthetic = e.isSynthetic,
    )

    fun toEntity(
        d: AlertDto,
        localSyncStatus: String = SyncStatus.PENDING,
        syncError: String? = null,
    ): AlertEntity = AlertEntity(
        id = d.id,
        schemaVersion = d.schemaVersion,
        astronautId = d.astronautId,
        createdAt = d.createdAt,
        level = d.level,
        category = d.category,
        ruleId = d.ruleId,
        ruleRevision = d.ruleRevision,
        ruleSetVersion = d.ruleSetVersion,
        title = d.title,
        explanation = d.explanation,
        evidenceJson = json.encodeToString(d.evidence),
        recommendedAction = d.recommendedAction,
        disclaimer = d.disclaimer,
        sourceJson = json.encodeToString(d.source),
        triggeredByJson = json.encodeToString(d.triggeredBy),
        emergencyProtocolId = d.emergencyProtocolId,
        acknowledgedAt = d.acknowledgedAt,
        isSynthetic = d.isSynthetic,
        localSyncStatus = localSyncStatus,
        syncError = syncError,
    )

    // ---------------------------------------------------------------- helpers

    /** Values object of a measurement, parsed for rule evaluation / charting. */
    fun parseValues(e: MeasurementEntity): JsonObject =
        json.parseToJsonElement(e.valuesJson) as JsonObject

    fun parseSymptoms(e: SymptomCheckInEntity): List<String> =
        json.decodeFromString(e.symptomsJson)

    fun parseTriage(e: SymptomCheckInEntity): ChestPainTriageDto? =
        e.chestPainTriageJson?.let { json.decodeFromString(it) }

    fun parseEvidence(e: AlertEntity): List<EvidenceDto> =
        json.decodeFromString(e.evidenceJson)

    fun parseTriggeredBy(e: AlertEntity): TriggeredByDto =
        json.decodeFromString(e.triggeredByJson)

    fun parseSource(e: AlertEntity): AlertSourceDto =
        json.decodeFromString(e.sourceJson)

    // ---------------------------------------------------------------- domain records

    /**
     * Room row -> domain measurement row consumed by
     * [com.yasmin.astrocare.domain.rule.ContextBuilder]. Non-numeric value
     * entries are dropped (the builder rejects empty/out-of-range rows anyway);
     * an unparsable timestamp propagates loudly rather than being skipped.
     */
    fun toMeasurementRecord(e: MeasurementEntity): MeasurementRecord = MeasurementRecord(
        id = e.id,
        metric = e.metric,
        values = parseValues(e).mapNotNull { (field, element) ->
            (element as? JsonPrimitive)?.doubleOrNull?.let { field to it }
        }.toMap(),
        observedAt = e.measuredAt,
        observedAtMillis = IsoTime.parseUtc(e.measuredAt),
        isSynthetic = e.isSynthetic,
    )

    fun toCheckInRecord(e: SymptomCheckInEntity): SymptomCheckInRecord = SymptomCheckInRecord(
        id = e.id,
        symptoms = parseSymptoms(e),
        checkedAt = e.checkedAt,
        checkedAtMillis = IsoTime.parseUtc(e.checkedAt),
        isSynthetic = e.isSynthetic,
    )

    /** Dedupe view of a stored alert for the rule engine (no Room types in domain). */
    fun toExistingAlertRef(e: AlertEntity): ExistingAlertRef {
        val triggered = parseTriggeredBy(e)
        return ExistingAlertRef(
            ruleId = e.ruleId,
            measurementIds = triggered.measurementIds.toSet(),
            symptomCheckinIds = triggered.symptomCheckinIds.toSet(),
        )
    }
}
