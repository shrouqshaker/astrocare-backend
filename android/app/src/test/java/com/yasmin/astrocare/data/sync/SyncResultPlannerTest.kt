package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import com.yasmin.astrocare.data.remote.dto.SyncErrorCode
import com.yasmin.astrocare.data.remote.dto.SyncErrorDto
import com.yasmin.astrocare.data.remote.dto.SyncItemResultDto
import com.yasmin.astrocare.data.remote.dto.SyncItemStatus
import com.yasmin.astrocare.data.remote.dto.SyncResultsDto
import com.yasmin.astrocare.data.remote.dto.SyncSummaryDto
import com.yasmin.astrocare.domain.model.LastSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Maps backend per-item statuses to local rows (created/duplicate -> synced, rejected -> rejected + error). */
class SyncResultPlannerTest {

    private fun response(
        measurements: List<SyncItemResultDto> = emptyList(),
        checkins: List<SyncItemResultDto> = emptyList(),
        alerts: List<SyncItemResultDto> = emptyList(),
        rejected: Int = 0,
    ) = SyncBatchResponseDto(
        schemaVersion = "1.0",
        batchId = "b",
        receivedAt = "2026-11-14T06:00:00Z",
        serverTime = "2026-11-14T06:00:00Z",
        summary = SyncSummaryDto(created = 1, duplicates = 1, rejected = rejected),
        results = SyncResultsDto(
            measurements = measurements,
            symptomCheckins = checkins,
            alerts = alerts,
        ),
        latestRuleSetVersion = "1.0.0",
    )

    @Test
    fun `created and duplicate both mark the row synced with no error`() {
        val updates = SyncResultPlanner.plan(
            response(
                measurements = listOf(
                    SyncItemResultDto("m-1", SyncItemStatus.CREATED),
                    SyncItemResultDto("m-2", SyncItemStatus.DUPLICATE),
                ),
            ),
        )
        assertEquals(2, updates.size)
        assertEquals(SyncStatus.SYNCED, updates[0].status)
        assertEquals(SyncStatus.SYNCED, updates[1].status)
        assertNull(updates[0].error)
        assertNull(updates[1].error)
    }

    @Test
    fun `rejected stores code and message for inspection`() {
        val updates = SyncResultPlanner.plan(
            response(
                measurements = listOf(
                    SyncItemResultDto(
                        "m-1",
                        SyncItemStatus.REJECTED,
                        SyncErrorDto(SyncErrorCode.CROSS_FIELD_INVALID, "systolic <= diastolic"),
                    ),
                ),
                rejected = 1,
            ),
        )
        assertEquals(SyncStatus.REJECTED, updates.single().status)
        assertEquals("CROSS_FIELD_INVALID: systolic <= diastolic", updates.single().error)
    }

    @Test
    fun `rejected without an error object still stores a marker`() {
        val updates = SyncResultPlanner.plan(
            response(measurements = listOf(SyncItemResultDto("m-1", SyncItemStatus.REJECTED))),
        )
        assertEquals("REJECTED", updates.single().error)
    }

    @Test
    fun `collections are routed to their own tables`() {
        val updates = SyncResultPlanner.plan(
            response(
                measurements = listOf(SyncItemResultDto("m-1", SyncItemStatus.CREATED)),
                checkins = listOf(SyncItemResultDto("c-1", SyncItemStatus.CREATED)),
                alerts = listOf(SyncItemResultDto("a-1", SyncItemStatus.CREATED)),
            ),
        )
        assertEquals(
            listOf(SyncCollection.MEASUREMENTS, SyncCollection.SYMPTOM_CHECKINS, SyncCollection.ALERTS),
            updates.map { it.collection },
        )
    }

    @Test
    fun `lastSyncResult is partial only when something was rejected`() {
        assertEquals(LastSyncResult.success, SyncResultPlanner.lastSyncResult(response()))
        assertEquals(
            LastSyncResult.partial,
            SyncResultPlanner.lastSyncResult(response(rejected = 1)),
        )
    }
}
