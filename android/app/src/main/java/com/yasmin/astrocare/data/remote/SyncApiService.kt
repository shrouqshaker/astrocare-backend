package com.yasmin.astrocare.data.remote

import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * `POST /api/v1/sync/batch` (contracts/API.md #2). The URL is passed
 * per-call ([Url]) because the base URL is user-editable in Settings; the
 * `X-API-Key` header is the demo-only key from Settings (never a secret —
 * see contracts/API.md "Security note").
 *
 * Non-2xx responses surface as `retrofit2.HttpException`; transport failures
 * as IOException — the caller classifies them (docs/DECISIONS.md §3).
 */
interface SyncApiService {

    @POST
    suspend fun syncBatch(
        @Url url: String,
        @Header("X-API-Key") apiKey: String,
        @Body body: SyncBatchRequestDto,
    ): SyncBatchResponseDto
}
