package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import com.yasmin.astrocare.data.remote.dto.SyncItemResultDto
import com.yasmin.astrocare.data.remote.dto.SyncItemStatus
import com.yasmin.astrocare.domain.model.LastSyncResult

/** Which local table a per-item result applies to. */
enum class SyncCollection { MEASUREMENTS, SYMPTOM_CHECKINS, ALERTS }

/**
 * One local row transition produced by a `created|duplicate|rejected` result:
 * `id` -> [SyncStatus] with the Android-only error text (null unless rejected).
 */
data class RowUpdate(
    val collection: SyncCollection,
    val id: String,
    val status: String,
    val error: String?,
)

/**
 * Maps a SyncBatchResponse to local row transitions (docs/DECISIONS.md §3):
 *
 *  - `created` / `duplicate` -> `localSyncStatus = "synced"` (rows are NEVER
 *    deleted — replaying an identical batch is expected to return duplicates);
 *  - `rejected` -> `localSyncStatus = "rejected"` plus the returned error
 *    (code + message) stored for inspection; rejected rows are excluded from
 *    future batches (getPending only selects `pending`) — no auto-retry of
 *    permanent validation errors;
 *  - items the server did NOT return a result for stay untouched (pending).
 *
 * Pure: no I/O — unit-testable on the JVM.
 */
object SyncResultPlanner {

    fun plan(response: SyncBatchResponseDto): List<RowUpdate> =
        planCollection(SyncCollection.MEASUREMENTS, response.results.measurements) +
            planCollection(SyncCollection.SYMPTOM_CHECKINS, response.results.symptomCheckins) +
            planCollection(SyncCollection.ALERTS, response.results.alerts)

    /** `partial` when anything was rejected, otherwise `success`. */
    fun lastSyncResult(response: SyncBatchResponseDto): LastSyncResult =
        if (response.summary.rejected > 0) LastSyncResult.partial else LastSyncResult.success

    private fun planCollection(
        collection: SyncCollection,
        results: List<SyncItemResultDto>,
    ): List<RowUpdate> = results.map { result ->
        when (result.status) {
            SyncItemStatus.CREATED, SyncItemStatus.DUPLICATE -> RowUpdate(
                collection = collection,
                id = result.id,
                status = SyncStatus.SYNCED,
                error = null,
            )

            SyncItemStatus.REJECTED -> RowUpdate(
                collection = collection,
                id = result.id,
                status = SyncStatus.REJECTED,
                error = storedError(result),
            )
        }
    }

    /** Android-only `syncError` text: "CODE: message" (details kept by the server). */
    private fun storedError(result: SyncItemResultDto): String {
        val error = result.error
        return if (error == null) {
            "REJECTED"
        } else {
            "${error.code.name}: ${error.message}"
        }
    }
}
