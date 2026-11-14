package com.yasmin.astrocare.data.sync

/** Pulls saved history only; never persists derived /overview or /trends data. */
interface HistoryPuller {
    suspend fun pull(baseUrl: String, apiKey: String, astronautId: String): HistoryPullResult
}

data class HistoryPullResult(
    val downloadedMeasurements: Int = 0,
    val downloadedCheckins: Int = 0,
    val downloadedAlerts: Int = 0,
    val insertedMeasurements: Int = 0,
    val insertedCheckins: Int = 0,
    val insertedAlerts: Int = 0,
    val conflicts: List<String> = emptyList(),
    val limitedCollections: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val downloaded: Int get() = downloadedMeasurements + downloadedCheckins + downloadedAlerts
    val inserted: Int get() = insertedMeasurements + insertedCheckins + insertedAlerts
    val complete: Boolean get() = conflicts.isEmpty() && limitedCollections.isEmpty() && warnings.isEmpty()
}
