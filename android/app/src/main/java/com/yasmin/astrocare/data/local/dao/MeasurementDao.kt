package com.yasmin.astrocare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    /** First-launch seed gate + Settings diagnostics. */
    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    /** Contract UUID lookup; UUID spelling is case-insensitive for sync dedupe. */
    @Query("SELECT * FROM measurements WHERE id = :id COLLATE NOCASE LIMIT 1")
    suspend fun findById(id: String): MeasurementEntity?

    @Query("SELECT * FROM measurements ORDER BY measuredAt DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    /**
     * Chart window query. ISO-8601 UTC strings with a fixed `Z` format sort
     * chronologically as text, so range comparisons on the string are exact.
     * Missing days in the window are rendered as "No data" — never interpolated.
     */
    @Query(
        "SELECT * FROM measurements WHERE metric = :metric " +
            "AND measuredAt >= :fromIso AND measuredAt < :toIso ORDER BY measuredAt ASC",
    )
    fun observeMetricWindow(metric: String, fromIso: String, toIso: String): Flow<List<MeasurementEntity>>

    /** One-shot snapshot for rule evaluation (baseline-window rows, oldest first). */
    @Query("SELECT * FROM measurements WHERE measuredAt >= :fromIso ORDER BY measuredAt ASC")
    suspend fun getFrom(fromIso: String): List<MeasurementEntity>

    /** Rows waiting for the next sync batch, oldest first (stable batch composition). */
    @Query("SELECT * FROM measurements WHERE localSyncStatus = 'pending' ORDER BY measuredAt ASC LIMIT :limit")
    suspend fun getPending(limit: Int): List<MeasurementEntity>

    /** Settings diagnostics — derived from Room, never stored (settings.schema.json). */
    @Query("SELECT COUNT(*) FROM measurements WHERE localSyncStatus = 'pending'")
    suspend fun countPending(): Int

    /**
     * Permanently rejected rows, newest first — read-only inspection in Settings.
     * Rejected rows are never selected by [getPending] and are never auto-retried.
     */
    @Query(
        "SELECT * FROM measurements WHERE localSyncStatus = 'rejected' " +
            "ORDER BY measuredAt DESC LIMIT :limit",
    )
    fun observeRejected(limit: Int): Flow<List<MeasurementEntity>>

    @Query("UPDATE measurements SET localSyncStatus = :status, syncError = :error WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String, error: String?)

    /** Contract id is the primary key — re-imports can never duplicate or overwrite. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<MeasurementEntity>)
}
