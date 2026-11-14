package com.yasmin.astrocare.data.seed

import android.content.Context
import android.util.Log
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import com.yasmin.astrocare.data.mapper.ContractMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Result of a seed run — used for logging and unit tests. */
internal data class SeedImportResult(
    val alreadySeeded: Boolean,
    val label: String?,
    val measurements: Int,
    val symptomCheckins: Int,
    val alerts: Int,
)

/**
 * Imports the frozen synthetic seed exactly once, verbatim.
 *
 * Idempotency: gated on `measurementDao.count() > 0` AND primary-key `IGNORE`
 * inserts, so repeated launches (or process restarts mid-import) never duplicate.
 *
 * Sync status of imported rows: `synced` — the backend ships the same frozen seed
 * (same ids), so these demo rows are never uploaded; this also keeps the first real
 * sync batch small (docs/DECISIONS.md §8).
 */
@Singleton
class SeedImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val measurementDao: MeasurementDao,
    private val symptomCheckInDao: SymptomCheckInDao,
    private val alertDao: AlertDao,
    private val mapper: ContractMapper,
    private val json: Json,
) {
    internal suspend fun ensureSeeded(): SeedImportResult = withContext(Dispatchers.IO) {
        if (measurementDao.count() > 0) {
            return@withContext SeedImportResult(
                alreadySeeded = true,
                label = null,
                measurements = 0,
                symptomCheckins = 0,
                alerts = 0,
            )
        }

        val raw = context.assets.open(SEED_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val seed = parseSeedFile(json, raw)

        val measurementRows = seed.measurements.map {
            mapper.toEntity(it, localSyncStatus = SyncStatus.SYNCED)
        }
        val checkInRows = seed.symptomCheckins.map {
            mapper.toEntity(it, localSyncStatus = SyncStatus.SYNCED)
        }
        val alertRows = seed.alerts.map {
            mapper.toEntity(it, localSyncStatus = SyncStatus.SYNCED)
        }

        measurementDao.insertAll(measurementRows)
        symptomCheckInDao.insertAll(checkInRows)
        alertDao.insertAll(alertRows)

        Log.i(TAG, "Seed imported: ${measurementRows.size} measurements, " +
            "${checkInRows.size} check-ins, ${alertRows.size} alerts (${seed.label})")

        SeedImportResult(
            alreadySeeded = false,
            label = seed.label,
            measurements = measurementRows.size,
            symptomCheckins = checkInRows.size,
            alerts = alertRows.size,
        )
    }

    companion object {
        private const val TAG = "SeedImporter"
        const val SEED_ASSET = "contracts/synthetic-seed.v1.json"
    }
}
