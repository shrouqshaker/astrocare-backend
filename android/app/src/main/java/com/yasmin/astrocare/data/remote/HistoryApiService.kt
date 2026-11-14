package com.yasmin.astrocare.data.remote

import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.HistoryResponseDto
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url

/** Read-only history endpoints. Every URL is supplied as an absolute URL by [HistoryClient]. */
interface HistoryApiService {

    @GET
    suspend fun fetchMeasurements(
        @Url url: String,
        @Header("X-API-Key") apiKey: String,
        @Query("limit") limit: Int,
    ): HistoryResponseDto<MeasurementDto>

    @GET
    suspend fun fetchSymptomCheckins(
        @Url url: String,
        @Header("X-API-Key") apiKey: String,
        @Query("limit") limit: Int,
    ): HistoryResponseDto<SymptomCheckInDto>

    @GET
    suspend fun fetchAlerts(
        @Url url: String,
        @Header("X-API-Key") apiKey: String,
        @Query("limit") limit: Int,
    ): HistoryResponseDto<AlertDto>
}
