package com.yasmin.astrocare.domain.repository

import com.yasmin.astrocare.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/** Read/modify app-local settings (DataStore-backed, never synced). */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Current values (first emission). */
    suspend fun snapshot(): AppSettings

    /** Atomically transform settings (single writer via DataStore edit). */
    suspend fun update(transform: (AppSettings) -> AppSettings)

    /** Persist generated identity fields (deviceId) if missing. Idempotent. */
    suspend fun ensureInitialized()
}
