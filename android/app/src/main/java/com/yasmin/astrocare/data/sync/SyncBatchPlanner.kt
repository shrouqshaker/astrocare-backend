package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncItemsDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Builds the `POST /api/v1/sync/batch` envelope (contracts/schemas/
 * sync-request.schema.json) from local pending rows.
 *
 * Binding rules (docs/DECISIONS.md §3):
 *  - `batchId` is a UUIDv5-style name-based UUID over
 *    `astronautId + sorted "collection:id"` — the SAME pending set always
 *    produces the SAME batchId (stable across retries and process restarts);
 *    a changed set is a new batch.
 *  - `sentAt` is regenerated per attempt (caller passes the attempt time).
 *  - At most 100 items per collection (schema `maxItems: 100`).
 *  - The payload contains contract fields ONLY: `id`, never `clientId`, and
 *    never the Android-only `localSyncStatus`/`syncError` columns.
 *
 * Pure: no I/O, no Android — unit-testable on the JVM.
 */
object SyncBatchPlanner {

    const val MAX_ITEMS_PER_COLLECTION = 100

    /** Fixed UUID namespace (DNS) for the name-based batch id. */
    private val BATCH_NAMESPACE: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    fun plan(
        schemaVersion: String,
        astronautId: String,
        deviceId: String,
        sentAt: String,
        clientRuleSetVersion: String,
        measurements: List<MeasurementDto>,
        symptomCheckins: List<SymptomCheckInDto>,
        alerts: List<AlertDto>,
    ): SyncBatchRequestDto {
        // Deterministic composition: sort by id, cap at the schema maximum.
        val measurementItems = measurements.sortedBy { it.id }.take(MAX_ITEMS_PER_COLLECTION)
        val checkinItems = symptomCheckins.sortedBy { it.id }.take(MAX_ITEMS_PER_COLLECTION)
        val alertItems = alerts.sortedBy { it.id }.take(MAX_ITEMS_PER_COLLECTION)

        return SyncBatchRequestDto(
            schemaVersion = schemaVersion,
            batchId = batchId(
                astronautId = astronautId,
                measurementIds = measurementItems.map { it.id },
                checkinIds = checkinItems.map { it.id },
                alertIds = alertItems.map { it.id },
            ),
            astronautId = astronautId,
            deviceId = deviceId,
            sentAt = sentAt,
            clientRuleSetVersion = clientRuleSetVersion,
            items = SyncItemsDto(
                measurements = measurementItems,
                symptomCheckins = checkinItems,
                alerts = alertItems,
            ),
        )
    }

    /**
     * UUIDv5 (SHA-1, version/variant bits set) over the ordered id set.
     * Order-insensitive: ids are sorted per collection and collections are
     * sorted as `collection:id` strings before hashing.
     */
    fun batchId(
        astronautId: String,
        measurementIds: List<String>,
        checkinIds: List<String>,
        alertIds: List<String>,
    ): String {
        val entries = buildList {
            measurementIds.forEach { add("measurements:$it") }
            checkinIds.forEach { add("symptomCheckins:$it") }
            alertIds.forEach { add("alerts:$it") }
        }.sorted()
        val name = "astrocare-sync|$astronautId|" + entries.joinToString(",")
        return uuidV5(name).toString()
    }

    private fun uuidV5(name: String): UUID {
        val namespaceBytes = ByteBuffer.allocate(16)
            .putLong(BATCH_NAMESPACE.mostSignificantBits)
            .putLong(BATCH_NAMESPACE.leastSignificantBits)
            .array()
        val input = namespaceBytes + name.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-1").digest(input)
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte() // version 5
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        val msb = ByteBuffer.wrap(hash, 0, 8).long
        val lsb = ByteBuffer.wrap(hash, 8, 8).long
        return UUID(msb, lsb)
    }
}
