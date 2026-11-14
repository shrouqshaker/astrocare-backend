package com.yasmin.astrocare.data.remote

import com.yasmin.astrocare.data.remote.dto.HistoryResponseDto
import com.yasmin.astrocare.data.remote.dto.HistorySnapshot
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

/**
 * Network boundary for the read-only history pull. This transport has no write
 * operation by design; callers receive either a complete snapshot or an error.
 */
interface HistoryTransport {
    suspend fun fetch(baseUrl: String, apiKey: String, astronautId: String): HistorySnapshot
}

@Singleton
class HistoryClient @Inject constructor(
    json: Json,
    okHttpClient: OkHttpClient,
) : HistoryTransport {

    private val service: HistoryApiService = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HistoryApiService::class.java)

    override suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        astronautId: String,
    ): HistorySnapshot = coroutineScope {
        val base = canonicalBaseUrl(baseUrl)
        requireSafeAstronautId(astronautId)

        // Start all three requests before awaiting any result. coroutineScope
        // propagates failures and cancellation rather than converting them into
        // partial snapshots or warnings.
        val measurements = async {
            service.fetchMeasurements(
                url = endpoint(base, astronautId, COLLECTION_MEASUREMENTS),
                apiKey = apiKey,
                limit = HISTORY_LIMIT,
            )
        }
        val symptomCheckins = async {
            service.fetchSymptomCheckins(
                url = endpoint(base, astronautId, COLLECTION_SYMPTOM_CHECKINS),
                apiKey = apiKey,
                limit = HISTORY_LIMIT,
            )
        }
        val alerts = async {
            service.fetchAlerts(
                url = endpoint(base, astronautId, COLLECTION_ALERTS),
                apiKey = apiKey,
                limit = HISTORY_LIMIT,
            )
        }

        // Await all children before constructing the result. No response is
        // silently discarded when another collection fails.
        val measurementResponse = measurements.await()
        val symptomCheckinResponse = symptomCheckins.await()
        val alertResponse = alerts.await()

        validateResponse(COLLECTION_MEASUREMENTS, measurementResponse) { it.id }
        validateResponse(COLLECTION_SYMPTOM_CHECKINS, symptomCheckinResponse) { it.id }
        validateResponse(COLLECTION_ALERTS, alertResponse) { it.id }

        val limitedCollections = buildList {
            if (measurementResponse.items.size == HISTORY_LIMIT) add(COLLECTION_MEASUREMENTS)
            if (symptomCheckinResponse.items.size == HISTORY_LIMIT) add(COLLECTION_SYMPTOM_CHECKINS)
            if (alertResponse.items.size == HISTORY_LIMIT) add(COLLECTION_ALERTS)
        }

        HistorySnapshot(
            measurements = measurementResponse.items,
            symptomCheckins = symptomCheckinResponse.items,
            alerts = alertResponse.items,
            limitedCollections = limitedCollections,
        )
    }

    private fun endpoint(base: HttpUrl, astronautId: String, collection: String): String =
        base.newBuilder()
            .addPathSegment("astronauts")
            // The identifier has already passed the narrow contract regex;
            // addPathSegment still performs the final URL encoding.
            .addPathSegment(astronautId)
            .addPathSegment(collection)
            .build()
            .toString()

    private fun <T> validateResponse(
        collection: String,
        response: HistoryResponseDto<T>,
        idOf: (T) -> String,
    ) {
        if (response.count < 0) {
            throw IllegalStateException("$collection response count must be non-negative")
        }
        if (response.count != response.items.size) {
            throw IllegalStateException(
                "$collection response count ${response.count} does not match " +
                    "items size ${response.items.size}",
            )
        }
        if (response.items.size > HISTORY_LIMIT) {
            throw IllegalStateException(
                "$collection response contains ${response.items.size} items; maximum is $HISTORY_LIMIT",
            )
        }

        val ids = HashSet<String>(response.items.size)
        response.items.forEach { item ->
            val normalizedId = idOf(item).lowercase(Locale.ROOT)
            if (!ids.add(normalizedId)) {
                throw IllegalStateException("$collection response contains duplicate id: ${idOf(item)}")
            }
        }
    }

    companion object {
        /** Retrofit requires a valid base URL even though every request is absolute. */
        const val PLACEHOLDER_BASE_URL = "http://localhost/"
        const val HISTORY_LIMIT = 1000

        private const val COLLECTION_MEASUREMENTS = "measurements"
        private const val COLLECTION_SYMPTOM_CHECKINS = "symptom-checkins"
        private const val COLLECTION_ALERTS = "alerts"
        private const val API_PREFIX = "/api/v1"
        private val ASTRONAUT_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{2,39}$")

        /**
         * Accept only a host root or the exact `/api/v1` prefix. HttpUrl performs
         * canonical parsing; pathSegments are additionally checked so a setting
         * cannot accidentally redirect history calls to another path.
         */
        fun canonicalBaseUrl(baseUrl: String): HttpUrl {
            val parsed = baseUrl.trim().toHttpUrlOrNull()
                ?: throw IOException("Invalid history base URL: $baseUrl")
            if (parsed.scheme != "http" && parsed.scheme != "https") {
                throw IOException("History base URL must use http or https: $baseUrl")
            }
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw IOException("History base URL must not contain userinfo")
            }
            if (parsed.query != null || parsed.fragment != null) {
                throw IOException("History base URL must not contain a query or fragment")
            }

            val isRoot = parsed.encodedPath == "/"
            val isApiPrefix = parsed.encodedPath == API_PREFIX ||
                parsed.encodedPath == "$API_PREFIX/"
            val nonEmptySegments = parsed.pathSegments.filter { it.isNotEmpty() }
            val segmentsAreAllowed = when {
                isRoot -> nonEmptySegments.isEmpty()
                isApiPrefix -> nonEmptySegments == listOf("api", "v1")
                else -> false
            }
            if (!segmentsAreAllowed) {
                throw IOException(
                    "History base URL path must be / or $API_PREFIX (optionally trailing slash): $baseUrl",
                )
            }

            // Normalize the optional prefix to a trailing-slash path before
            // appending path segments through HttpUrl.Builder.
            return parsed.newBuilder()
                .encodedPath("$API_PREFIX/")
                .build()
        }

        private fun requireSafeAstronautId(astronautId: String) {
            if (!ASTRONAUT_ID_REGEX.matches(astronautId)) {
                throw IOException("Invalid astronaut id for history URL")
            }
        }
    }
}
