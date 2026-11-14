package com.yasmin.astrocare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yasmin.astrocare.data.local.SyncStatus

/**
 * Room row for a contract Measurement (contracts/schemas/measurement.schema.json).
 *
 * Contract fields are stored with their EXACT names/values; timestamps are kept as
 * the original UTC ISO-8601 strings so seed data and DTOs round-trip byte-for-byte
 * (canonical `...Z` strings also sort chronologically, which the queries rely on).
 * [valuesJson] holds the metric-dependent `values` object verbatim.
 *
 * [localSyncStatus] and [syncError] are ANDROID-ONLY and are never serialized
 * into any payload sent to the backend.
 */
@Entity(
    tableName = "measurements",
    indices = [
        Index(value = ["measuredAt"]),
        Index(value = ["metric"]),
        Index(value = ["localSyncStatus"]),
    ],
)
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val schemaVersion: String,
    val astronautId: String,
    val metric: String,
    /** Contract `values` object as JSON, e.g. `{"bpm":68}` / `{"systolic":120,"diastolic":80}`. */
    val valuesJson: String,
    /** Contract `utcDateTime` — verbatim, e.g. `2026-10-31T07:15:00Z`. */
    val measuredAt: String,
    val utcOffsetMinutes: Int,
    val environment: String,
    val source: String,
    val isSynthetic: Boolean,
    val note: String?,
    val createdAt: String,
    // ---- Android-only (never in payloads) ----
    val localSyncStatus: String = SyncStatus.PENDING,
    val syncError: String? = null,
)
