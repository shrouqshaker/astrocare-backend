package com.yasmin.astrocare.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity

/**
 * Room database — the offline source of truth for all user data.
 *
 * Schema v1 was the Phase-2 toolchain shell (single `toolchain_probe` table, since
 * deleted). v2 creates the real contract tables. No released installs exist, and the
 * only pre-v2 data is that throwaway probe row, so the database uses destructive
 * migration from v1 (see [com.yasmin.astrocare.core.di.DatabaseModule]) rather than
 * carrying a migration for a table nobody keeps.
 *
 * `localSyncStatus`/`syncError` are Android-only columns and are NEVER included in
 * payloads (docs/DECISIONS.md §3).
 */
@Database(
    entities = [
        MeasurementEntity::class,
        SymptomCheckInEntity::class,
        AlertEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AstroCareDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun symptomCheckInDao(): SymptomCheckInDao
    abstract fun alertDao(): AlertDao
}
