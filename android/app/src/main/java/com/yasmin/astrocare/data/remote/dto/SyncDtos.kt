package com.yasmin.astrocare.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sync envelope DTOs — contracts/schemas/sync-request.schema.json and
 * sync-response.schema.json for `POST /api/v1/sync/batch`.
 *
 * Invariants (docs/DECISIONS.md §3):
 *  - `batchId` is a UUID kept STABLE across retries of the same pending set;
 *  - `items` carries three arrays of max 100 items each;
 *  - per-item `created|duplicate|rejected` results drive local `localSyncStatus`;
 *  - rejected items keep their `error` for inspection and are never auto-retried.
 */

@Serializable
data class SyncBatchRequestDto(
    val schemaVersion: String,
    val batchId: String,
    val astronautId: String,
    val deviceId: String,
    /** Time this attempt was sent (regenerated per attempt; batchId stays stable). */
    val sentAt: String,
    val clientRuleSetVersion: String,
    val items: SyncItemsDto,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SyncItemsDto(
    // The backend envelope schema REQUIRES all three arrays (even when empty).
    // encodeDefaults=false would omit these empty defaults and the whole batch
    // would be rejected with 400 VALIDATION_ERROR.
    @EncodeDefault val measurements: List<MeasurementDto> = emptyList(),
    @EncodeDefault val symptomCheckins: List<SymptomCheckInDto> = emptyList(),
    @EncodeDefault val alerts: List<AlertDto> = emptyList(),
) {
    val totalCount: Int get() = measurements.size + symptomCheckins.size + alerts.size
}

@Serializable
data class SyncBatchResponseDto(
    val schemaVersion: String,
    val batchId: String,
    val receivedAt: String,
    val serverTime: String,
    val summary: SyncSummaryDto,
    val results: SyncResultsDto,
    val latestRuleSetVersion: String,
)

@Serializable
data class SyncSummaryDto(
    val created: Int,
    val duplicates: Int,
    val rejected: Int,
)

@Serializable
data class SyncResultsDto(
    val measurements: List<SyncItemResultDto> = emptyList(),
    val symptomCheckins: List<SyncItemResultDto> = emptyList(),
    val alerts: List<SyncItemResultDto> = emptyList(),
)

@Serializable
data class SyncItemResultDto(
    val id: String,
    val status: SyncItemStatus,
    /** Null unless status == rejected. */
    val error: SyncErrorDto? = null,
)

@Serializable
enum class SyncItemStatus {
    @SerialName("created") CREATED,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("rejected") REJECTED,
}

/** Permanent validation failures — must NOT be retried automatically. */
@Serializable
enum class SyncErrorCode {
    @SerialName("VALIDATION_ERROR") VALIDATION_ERROR,
    @SerialName("CROSS_FIELD_INVALID") CROSS_FIELD_INVALID,
    @SerialName("ASTRONAUT_MISMATCH") ASTRONAUT_MISMATCH,
    @SerialName("UNSUPPORTED_SCHEMA_VERSION") UNSUPPORTED_SCHEMA_VERSION,
}

@Serializable
data class SyncErrorDto(
    val code: SyncErrorCode,
    val message: String,
    val details: List<SyncErrorDetailDto>? = null,
)

@Serializable
data class SyncErrorDetailDto(
    val path: String = "",
    val message: String = "",
)
