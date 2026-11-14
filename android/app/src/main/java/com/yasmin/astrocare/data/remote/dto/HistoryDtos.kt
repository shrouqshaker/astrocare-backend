package com.yasmin.astrocare.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * The three history collections returned by the backend. A collection reported
 * at the API limit is intentionally surfaced as limited: the API does not
 * provide a completeness guarantee when count == 1000.
 */
data class HistorySnapshot(
    val measurements: List<MeasurementDto>,
    val symptomCheckins: List<SymptomCheckInDto>,
    val alerts: List<AlertDto>,
    val limitedCollections: List<String> = emptyList(),
)

/** Common response envelope used by each history collection endpoint. */
@Serializable
data class HistoryResponseDto<T>(
    val items: List<T>,
    val count: Int,
)
