package com.yasmin.astrocare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SymptomCheckInDao {

    @Query("SELECT COUNT(*) FROM symptom_checkins")
    suspend fun count(): Int

    /** Contract UUID lookup; UUID spelling is case-insensitive for sync dedupe. */
    @Query("SELECT * FROM symptom_checkins WHERE id = :id COLLATE NOCASE LIMIT 1")
    suspend fun findById(id: String): SymptomCheckInEntity?

    @Query("SELECT * FROM symptom_checkins ORDER BY checkedAt DESC")
    fun observeAll(): Flow<List<SymptomCheckInEntity>>

    @Query(
        "SELECT * FROM symptom_checkins WHERE checkedAt >= :fromIso AND checkedAt < :toIso " +
            "ORDER BY checkedAt ASC",
    )
    fun observeWindow(fromIso: String, toIso: String): Flow<List<SymptomCheckInEntity>>

    /** One-shot snapshot for rule evaluation (baseline-window rows, oldest first). */
    @Query("SELECT * FROM symptom_checkins WHERE checkedAt >= :fromIso ORDER BY checkedAt ASC")
    suspend fun getFrom(fromIso: String): List<SymptomCheckInEntity>

    @Query(
        "SELECT * FROM symptom_checkins WHERE localSyncStatus = 'pending' " +
            "ORDER BY checkedAt ASC LIMIT :limit",
    )
    suspend fun getPending(limit: Int): List<SymptomCheckInEntity>

    /** Settings diagnostics — derived from Room, never stored (settings.schema.json). */
    @Query("SELECT COUNT(*) FROM symptom_checkins WHERE localSyncStatus = 'pending'")
    suspend fun countPending(): Int

    /** Permanently rejected rows, newest first — read-only inspection in Settings. */
    @Query(
        "SELECT * FROM symptom_checkins WHERE localSyncStatus = 'rejected' " +
            "ORDER BY checkedAt DESC LIMIT :limit",
    )
    fun observeRejected(limit: Int): Flow<List<SymptomCheckInEntity>>

    @Query("UPDATE symptom_checkins SET localSyncStatus = :status, syncError = :error WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String, error: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SymptomCheckInEntity>)
}
