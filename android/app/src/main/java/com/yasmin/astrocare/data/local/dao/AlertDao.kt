package com.yasmin.astrocare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yasmin.astrocare.data.local.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Query("SELECT COUNT(*) FROM alerts")
    suspend fun count(): Int

    /** Contract UUID lookup; UUID spelling is case-insensitive for sync dedupe. */
    @Query("SELECT * FROM alerts WHERE id = :id COLLATE NOCASE LIMIT 1")
    suspend fun findById(id: String): AlertEntity?

    @Query("SELECT * FROM alerts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    /** Newest-first feed for Overview (dedupe by rule + identical triggeredBy happens before insert). */
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AlertEntity>>

    /** All alerts for one rule — used by the rules-engine dedupe check (rules.v1.json evaluation.dedupe). */
    @Query("SELECT * FROM alerts WHERE ruleId = :ruleId ORDER BY createdAt DESC")
    suspend fun getByRuleId(ruleId: String): List<AlertEntity>

    /** Every stored alert — dedupe input for the next evaluation (ruleId + identical triggeredBy). */
    @Query("SELECT * FROM alerts")
    suspend fun getAll(): List<AlertEntity>

    /**
     * Contract `acknowledgedAt` (utcDateTime) — set once, never overwritten.
     * A synced alert is re-queued (`pending`) so the backend receives the first
     * acknowledgement through the normal sync batch (backend updates the ack on
     * a duplicate alert). A rejected alert stays rejected — it was never stored
     * server-side, so re-uploading it would only fail again.
     */
    @Query(
        "UPDATE alerts SET acknowledgedAt = :at, " +
            "localSyncStatus = CASE WHEN localSyncStatus = 'rejected' THEN localSyncStatus ELSE 'pending' END, " +
            "syncError = CASE WHEN localSyncStatus = 'rejected' THEN syncError ELSE NULL END " +
            "WHERE id = :id AND acknowledgedAt IS NULL",
    )
    suspend fun acknowledge(id: String, at: String)

    /**
     * Applies the server's non-null acknowledgement without regressing a dirty
     * local acknowledgement. A synced row may adopt the backend's first-accepted
     * timestamp; pending/rejected rows retain their local lifecycle state.
     */
    @Query(
        "UPDATE alerts SET acknowledgedAt = :serverAcknowledgedAt, " +
            "localSyncStatus = CASE WHEN localSyncStatus = 'synced' THEN 'synced' ELSE localSyncStatus END " +
            "WHERE id = :id COLLATE NOCASE " +
            "AND :serverAcknowledgedAt IS NOT NULL " +
            "AND (acknowledgedAt IS NULL OR localSyncStatus = 'synced')",
    )
    suspend fun mergeServerAcknowledgement(id: String, serverAcknowledgedAt: String)

    @Query(
        "SELECT * FROM alerts WHERE localSyncStatus = 'pending' " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun getPending(limit: Int): List<AlertEntity>

    /** Settings diagnostics — derived from Room, never stored (settings.schema.json). */
    @Query("SELECT COUNT(*) FROM alerts WHERE localSyncStatus = 'pending'")
    suspend fun countPending(): Int

    /** Permanently rejected rows, newest first — read-only inspection in Settings. */
    @Query(
        "SELECT * FROM alerts WHERE localSyncStatus = 'rejected' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    fun observeRejected(limit: Int): Flow<List<AlertEntity>>

    @Query("UPDATE alerts SET localSyncStatus = :status, syncError = :error WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String, error: String?)

    /**
     * Completes an upload only if the acknowledgement sent by that upload is
     * still the row's current value and the row is still pending. This prevents
     * an older POST response from clearing a newer local acknowledgement.
     */
    @Query(
        "UPDATE alerts SET localSyncStatus = :status, syncError = :error " +
            "WHERE id = :id COLLATE NOCASE " +
            "AND acknowledgedAt IS :uploadedAcknowledgedAt " +
            "AND localSyncStatus = 'pending'",
    )
    suspend fun updateSyncStatusIfAcknowledgementUnchanged(
        id: String,
        uploadedAcknowledgedAt: String?,
        status: String,
        error: String?,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<AlertEntity>)
}
