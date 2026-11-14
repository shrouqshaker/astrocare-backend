package com.yasmin.astrocare.domain.model

import com.yasmin.astrocare.core.common.AppDefaults
import kotlinx.serialization.Serializable

/**
 * App-local settings — mirrors contracts/schemas/settings.schema.json.
 * Android-local only (DataStore); never synced (settings.schema.json header).
 * Pending sync count is derived from the DB, never stored.
 */
data class AppSettings(
    val schemaVersion: String = "1.0",
    val astronautId: String = AppDefaults.DEFAULT_ASTRONAUT_ID,
    /** Generated once on first launch; stable identity for sync deviceId. */
    val deviceId: String = "",
    val environment: String = AppDefaults.DEFAULT_ENVIRONMENT,
    /** `en` | `ar` (UI ships English only for v1 — README). */
    val language: String = "en",
    /** Rule set currently loaded by the app (rules.v1.json → 1.0.0). */
    val loadedRuleSetVersion: String = AppDefaults.DEFAULT_RULE_SET_VERSION,
    val reminders: List<ReminderSettings> = emptyList(),
    val sync: SyncSettings = SyncSettings(),
)

@Serializable
data class SyncSettings(
    val enabled: Boolean = true,
    val baseUrl: String = AppDefaults.DEFAULT_BASE_URL,
    /** Demo-only default; user-editable in Settings (docs/DECISIONS.md §4). */
    val apiKey: String = AppDefaults.DEFAULT_API_KEY,
    val wifiOnly: Boolean = false,
    val intervalMinutes: Int = AppDefaults.DEFAULT_SYNC_INTERVAL_MINUTES,
    /** Contract utcDateTime or null. */
    val lastSyncAt: String? = null,
    val lastSyncResult: LastSyncResult = LastSyncResult.never,
)

/** Stored verbatim lowercase in DataStore. */
enum class LastSyncResult { never, success, partial, failed }

/** One reminder schedule (max 12 per metric) — activated in Phase 6. */
@Serializable
data class ReminderSettings(
    val id: String,
    /** `heart_rate` | `blood_pressure` | `spo2` | `temperature` | `sleep` | `checkin`. */
    val target: String,
    /** Local time `HH:mm`. */
    val time: String,
    /** ISO day-of-week 1..7 (Monday..Sunday). */
    val daysOfWeek: List<Int>,
    val enabled: Boolean = true,
    val label: String = "",
)
