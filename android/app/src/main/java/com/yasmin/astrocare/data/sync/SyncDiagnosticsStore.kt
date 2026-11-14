package com.yasmin.astrocare.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.syncDiagnosticsDataStore by preferencesDataStore(name = "sync_diagnostics")

/** A separate local preferences file; does not change settings.schema.json or Room schema. */
@Singleton
class SyncDiagnosticsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : SyncDiagnosticsRepository {
    private val key = stringPreferencesKey("last_two_way_attempt")

    override val diagnostics = context.syncDiagnosticsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences -> decode(preferences[key]) }

    override suspend fun snapshot(): SyncDiagnostics = diagnostics.first()

    override suspend fun update(transform: (SyncDiagnostics) -> SyncDiagnostics) {
        context.syncDiagnosticsDataStore.edit { preferences ->
            preferences[key] = json.encodeToString(transform(decode(preferences[key])))
        }
    }

    private fun decode(raw: String?): SyncDiagnostics = if (raw == null) SyncDiagnostics() else {
        runCatching { json.decodeFromString<SyncDiagnostics>(raw) }.getOrDefault(SyncDiagnostics())
    }
}
