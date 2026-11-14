package com.yasmin.astrocare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yasmin.astrocare.data.local.SyncStatus

/**
 * Room row for a contract SymptomCheckin (contracts/schemas/symptom-checkin.schema.json).
 *
 * [symptomsJson] is the JSON array of symptom codes; [chestPainTriageJson] is the
 * optional chest-pain triage object (`protocolId`, `answers`, `outcome`) or null —
 * the triage outcome is DATA on the check-in, never an alert level
 * (see docs/DECISIONS.md §2).
 *
 * [localSyncStatus] and [syncError] are ANDROID-ONLY, never serialized into payloads.
 */
@Entity(
    tableName = "symptom_checkins",
    indices = [
        Index(value = ["checkedAt"]),
        Index(value = ["localSyncStatus"]),
    ],
)
data class SymptomCheckInEntity(
    @PrimaryKey val id: String,
    val schemaVersion: String,
    val astronautId: String,
    /** JSON array, e.g. `["dizziness"]` (unique items; `["none"]` must stand alone). */
    val symptomsJson: String,
    /** Contract `utcDateTime` — verbatim. */
    val checkedAt: String,
    val utcOffsetMinutes: Int,
    val environment: String,
    val source: String,
    val isSynthetic: Boolean,
    /** JSON object or null — parsed via [com.yasmin.astrocare.data.mapper.ContractMapper]. */
    val chestPainTriageJson: String?,
    val createdAt: String,
    // ---- Android-only (never in payloads) ----
    val localSyncStatus: String = SyncStatus.PENDING,
    val syncError: String? = null,
)
