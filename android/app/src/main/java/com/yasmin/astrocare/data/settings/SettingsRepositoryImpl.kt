package com.yasmin.astrocare.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.LastSyncResult
import com.yasmin.astrocare.domain.model.ReminderSettings
import com.yasmin.astrocare.domain.model.SyncSettings
import com.yasmin.astrocare.domain.repository.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DataStore-backed settings. Keys mirror the settings.schema.json block; the
 * pending sync COUNT is never stored here — it is derived from Room
 * (`localSyncStatus = 'pending'`), per the schema header.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override val settings: Flow<AppSettings> =
        dataStore.data.map { it.toAppSettings() }

    override suspend fun snapshot(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toAppSettings())
            prefs[KEY_ASTRONAUT_ID] = next.astronautId
            // Never clear a generated deviceId.
            if (next.deviceId.isNotBlank()) prefs[KEY_DEVICE_ID] = next.deviceId
            prefs[KEY_ENVIRONMENT] = next.environment
            prefs[KEY_LANGUAGE] = next.language
            prefs[KEY_LOADED_RULE_SET_VERSION] = next.loadedRuleSetVersion
            prefs[KEY_REMINDERS] = json.encodeToString(next.reminders)
            prefs[KEY_SYNC_ENABLED] = next.sync.enabled
            prefs[KEY_SYNC_BASE_URL] = next.sync.baseUrl
            prefs[KEY_SYNC_API_KEY] = next.sync.apiKey
            prefs[KEY_SYNC_WIFI_ONLY] = next.sync.wifiOnly
            prefs[KEY_SYNC_INTERVAL] = next.sync.intervalMinutes
                .coerceAtLeast(AppDefaults.MIN_SYNC_INTERVAL_MINUTES)
            // Preferences cannot hold null — remove the key to represent "never synced".
            if (next.sync.lastSyncAt != null) {
                prefs[KEY_SYNC_LAST_AT] = next.sync.lastSyncAt
            } else {
                prefs.remove(KEY_SYNC_LAST_AT)
            }
            prefs[KEY_SYNC_LAST_RESULT] = next.sync.lastSyncResult.name
        }
    }

    override suspend fun ensureInitialized() {
        dataStore.edit { prefs ->
            if (prefs[KEY_DEVICE_ID].isNullOrBlank()) {
                prefs[KEY_DEVICE_ID] = UUID.randomUUID().toString()
            }
            if (prefs[KEY_ASTRONAUT_ID] == null) {
                prefs[KEY_ASTRONAUT_ID] = AppDefaults.DEFAULT_ASTRONAUT_ID
            }
        }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        schemaVersion = AppDefaults.SCHEMA_VERSION,
        astronautId = this[KEY_ASTRONAUT_ID] ?: AppDefaults.DEFAULT_ASTRONAUT_ID,
        deviceId = this[KEY_DEVICE_ID] ?: "",
        environment = this[KEY_ENVIRONMENT] ?: AppDefaults.DEFAULT_ENVIRONMENT,
        language = this[KEY_LANGUAGE] ?: AppDefaults.DEFAULT_LANGUAGE,
        loadedRuleSetVersion = this[KEY_LOADED_RULE_SET_VERSION]
            ?: AppDefaults.DEFAULT_RULE_SET_VERSION,
        reminders = this[KEY_REMINDERS]?.let {
            runCatching { json.decodeFromString<List<ReminderSettings>>(it) }.getOrNull()
        } ?: emptyList(),
        sync = SyncSettings(
            enabled = this[KEY_SYNC_ENABLED] ?: true,
            baseUrl = this[KEY_SYNC_BASE_URL] ?: AppDefaults.DEFAULT_BASE_URL,
            apiKey = this[KEY_SYNC_API_KEY] ?: AppDefaults.DEFAULT_API_KEY,
            wifiOnly = this[KEY_SYNC_WIFI_ONLY] ?: false,
            intervalMinutes = this[KEY_SYNC_INTERVAL]
                ?: AppDefaults.DEFAULT_SYNC_INTERVAL_MINUTES,
            lastSyncAt = this[KEY_SYNC_LAST_AT],
            lastSyncResult = this[KEY_SYNC_LAST_RESULT]
                ?.let { name -> LastSyncResult.entries.firstOrNull { it.name == name } }
                ?: LastSyncResult.never,
        ),
    )

    private companion object {
        val KEY_ASTRONAUT_ID = stringPreferencesKey("astronaut_id")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_ENVIRONMENT = stringPreferencesKey("environment")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_LOADED_RULE_SET_VERSION = stringPreferencesKey("loaded_rule_set_version")
        val KEY_REMINDERS = stringPreferencesKey("reminders_json")
        val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val KEY_SYNC_BASE_URL = stringPreferencesKey("sync_base_url")
        val KEY_SYNC_API_KEY = stringPreferencesKey("sync_api_key")
        val KEY_SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val KEY_SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val KEY_SYNC_LAST_AT = stringPreferencesKey("sync_last_at")
        val KEY_SYNC_LAST_RESULT = stringPreferencesKey("sync_last_result")
    }
}
