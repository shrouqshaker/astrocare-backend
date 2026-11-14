package com.yasmin.astrocare.data.sync

import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.data.remote.SyncApiService
import com.yasmin.astrocare.data.remote.dto.SyncBatchRequestDto
import com.yasmin.astrocare.data.remote.dto.SyncBatchResponseDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Network boundary of [SyncEngine] — an interface so the engine can be
 * unit-tested with a fake transport (no sockets on the JVM).
 */
interface SyncTransport {
    /** POSTs one batch; throws IOException/HttpException on failure. */
    suspend fun post(
        baseUrl: String,
        apiKey: String,
        request: SyncBatchRequestDto,
    ): SyncBatchResponseDto
}

/**
 * Retrofit client for the sync endpoint with a DYNAMIC base URL: Retrofit is
 * built once against a placeholder root (required to be a valid URL) and the
 * absolute endpoint URL is assembled from the user-configured Settings
 * `baseUrl` on every call (`<baseUrl>/api/v1/sync/batch`).
 */
@Singleton
class SyncClient @Inject constructor(
    json: Json,
    okHttpClient: OkHttpClient,
) : SyncTransport {

    private val service: SyncApiService = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SyncApiService::class.java)

    override suspend fun post(
        baseUrl: String,
        apiKey: String,
        request: SyncBatchRequestDto,
    ): SyncBatchResponseDto {
        val url = endpointUrl(baseUrl)
        return service.syncBatch(url = url, apiKey = apiKey, body = request)
    }

    companion object {
        /** Retrofit requires a syntactically valid root; never actually used. */
        const val PLACEHOLDER_BASE_URL = "http://localhost/"

        /**
         * Resolves the sync endpoint from the user-configured base URL:
         *  - `http://host:3000`      -> `http://host:3000/api/v1/sync/batch`
         *  - `https://host/api/v1`   -> `https://host/api/v1/sync/batch`
         *  - `https://host/api/v1/`  -> same (trailing slash ignored)
         * The `/api/v1` prefix is never duplicated, whatever form the user
         * saved (the schema example is host-only, while the demo default
         * already includes the prefix).
         */
        fun endpointUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw IOException("Invalid base URL (must start with http:// or https://): $baseUrl")
            }
            val root = if (trimmed.endsWith(AppDefaults.API_PREFIX)) {
                trimmed.removeSuffix(AppDefaults.API_PREFIX)
            } else {
                trimmed
            }
            return "$root${AppDefaults.API_PREFIX}/sync/batch"
        }
    }
}
