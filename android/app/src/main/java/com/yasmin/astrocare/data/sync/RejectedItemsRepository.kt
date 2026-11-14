package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Which table a rejected row belongs to (display only). */
enum class RejectedCollection(val displayName: String) {
    MEASUREMENT("Measurement"),
    CHECK_IN("Check-in"),
    ALERT("Alert"),
}

/**
 * One row the backend permanently rejected (`sync-result` status `rejected`).
 * [error] is the stored "CODE: message" text — null only if the server omitted
 * the error object (then [SyncResultPlanner] stores "REJECTED").
 */
data class RejectedItem(
    val collection: RejectedCollection,
    val id: String,
    val label: String,
    val error: String?,
)

/**
 * Read-only inspection feed for Settings ("Rejected sync items").
 *
 * Rejected rows are NOT retried automatically: the backend classified them as
 * permanent validation errors (`VALIDATION_ERROR`, `CROSS_FIELD_INVALID`, …).
 * They stay in Room untouched so the user can see exactly what was refused.
 * This repository only observes; it never mutates or deletes rows.
 */
@Singleton
class RejectedItemsRepository @Inject constructor(
    private val measurementDao: MeasurementDao,
    private val symptomCheckInDao: SymptomCheckInDao,
    private val alertDao: AlertDao,
) {
    fun observe(limit: Int = MAX_ITEMS_PER_COLLECTION): Flow<List<RejectedItem>> = combine(
        measurementDao.observeRejected(limit),
        symptomCheckInDao.observeRejected(limit),
        alertDao.observeRejected(limit),
    ) { measurements, checkIns, alerts ->
        buildList {
            measurements.forEach { row ->
                add(
                    RejectedItem(
                        collection = RejectedCollection.MEASUREMENT,
                        id = row.id,
                        label = "${row.metric} · ${row.measuredAt}",
                        error = row.syncError,
                    ),
                )
            }
            checkIns.forEach { row ->
                add(
                    RejectedItem(
                        collection = RejectedCollection.CHECK_IN,
                        id = row.id,
                        label = "Check-in · ${row.checkedAt}",
                        error = row.syncError,
                    ),
                )
            }
            alerts.forEach { row ->
                add(
                    RejectedItem(
                        collection = RejectedCollection.ALERT,
                        id = row.id,
                        label = "${row.title} · ${row.createdAt}",
                        error = row.syncError,
                    ),
                )
            }
        }
    }

    companion object {
        /** Same per-collection cap as the sync batch planner. */
        const val MAX_ITEMS_PER_COLLECTION = 100
    }
}
