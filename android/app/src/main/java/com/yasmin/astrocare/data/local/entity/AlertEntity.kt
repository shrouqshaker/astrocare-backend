package com.yasmin.astrocare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yasmin.astrocare.data.local.SyncStatus

/**
 * Room row for a contract Alert (contracts/schemas/alert.schema.json).
 *
 * The alert [level] column ALWAYS carries the value produced by rule evaluation
 * (`warning|critical` per rules.v1.json) — UI code never hardcodes levels
 * (docs/DECISIONS.md §2). Structured sub-objects ([evidenceJson], [sourceJson],
 * [triggeredByJson]) are stored as contract-shaped JSON for byte-exact payloads.
 *
 * [localSyncStatus] and [syncError] are ANDROID-ONLY, never serialized into payloads.
 */
@Entity(
    tableName = "alerts",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["ruleId"]),
        Index(value = ["localSyncStatus"]),
    ],
)
data class AlertEntity(
    @PrimaryKey val id: String,
    val schemaVersion: String,
    val astronautId: String,
    /** Contract `utcDateTime` — verbatim. */
    val createdAt: String,
    /** `warning` | `critical` — from the firing rule only. */
    val level: String,
    /** `vital` | `symptom` | `combination` | `emergency`. */
    val category: String,
    val ruleId: String,
    val ruleRevision: Int,
    val ruleSetVersion: String,
    val title: String,
    val explanation: String,
    /** JSON array of evidence objects (1..6 items). */
    val evidenceJson: String,
    val recommendedAction: String,
    val disclaimer: String,
    /** JSON object `{ref, title, url?}`. */
    val sourceJson: String,
    /** JSON object `{measurementIds[], symptomCheckinIds[]}`. */
    val triggeredByJson: String,
    val emergencyProtocolId: String?,
    /** Contract `utcDateTime` or null — set when the user acknowledges the alert. */
    val acknowledgedAt: String?,
    val isSynthetic: Boolean,
    // ---- Android-only (never in payloads) ----
    val localSyncStatus: String = SyncStatus.PENDING,
    val syncError: String? = null,
)
