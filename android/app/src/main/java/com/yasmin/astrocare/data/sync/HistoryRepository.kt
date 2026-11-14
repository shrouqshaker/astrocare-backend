package com.yasmin.astrocare.data.sync

import androidx.room.withTransaction
import com.yasmin.astrocare.data.local.AstroCareDatabase
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.remote.HistoryTransport
import com.yasmin.astrocare.data.remote.dto.AlertDto
import com.yasmin.astrocare.data.remote.dto.HistorySnapshot
import com.yasmin.astrocare.data.remote.dto.MeasurementDto
import com.yasmin.astrocare.data.remote.dto.SymptomCheckInDto
import com.yasmin.astrocare.domain.repository.SettingsRepository
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Pulls saved history and merges it into the offline Room source of truth.
 * Network I/O and response validation happen before the one Room transaction;
 * imported rows are marked synced and never pass through local save/evaluation.
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val db: AstroCareDatabase,
    private val mapper: ContractMapper,
    private val transport: HistoryTransport,
    private val settingsRepository: SettingsRepository,
) : HistoryPuller {

    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    override suspend fun pull(
        baseUrl: String,
        apiKey: String,
        astronautId: String,
    ): HistoryPullResult {
        // The transport returns all three collections in one response. Do not open
        // a Room transaction while the request is in flight.
        val snapshot = transport.fetch(baseUrl, apiKey, astronautId)
        HistoryValidator.validate(
            measurements = snapshot.measurements,
            checkins = snapshot.symptomCheckins,
            alerts = snapshot.alerts,
            astronautId = astronautId,
        )

        // Settings may have changed while the request was in flight. Refuse to
        // merge data fetched from the old identity/endpoint into the new account.
        ensureRequestStillMatchesSettings(baseUrl, apiKey, astronautId)

        val conflicts = mutableListOf<String>()
        val merged = db.withTransaction {
            val measurementResult = mergeMeasurements(snapshot.measurements, conflicts)
            val checkinResult = mergeCheckins(snapshot.symptomCheckins, conflicts)
            val alertResult = mergeAlerts(snapshot.alerts, conflicts)
            MergeResult(
                insertedMeasurements = measurementResult,
                insertedCheckins = checkinResult,
                insertedAlerts = alertResult,
            )
        }

        return HistoryPullResult(
            downloadedMeasurements = snapshot.measurements.size,
            downloadedCheckins = snapshot.symptomCheckins.size,
            downloadedAlerts = snapshot.alerts.size,
            insertedMeasurements = merged.insertedMeasurements,
            insertedCheckins = merged.insertedCheckins,
            insertedAlerts = merged.insertedAlerts,
            conflicts = conflicts.toList(),
            limitedCollections = snapshot.limitedCollections,
        )
    }

    private suspend fun ensureRequestStillMatchesSettings(baseUrl: String, apiKey: String, astronautId: String) {
        val current = settingsRepository.snapshot()
        if (!current.sync.enabled) {
            throw IOException("history pull discarded: sync is disabled")
        }
        if (current.sync.apiKey != apiKey) {
            throw IOException("history pull discarded: API key changed")
        }
        if (current.astronautId != astronautId) {
            throw IOException("history pull discarded: astronaut identity changed")
        }
        if (!sameEndpoint(current.sync.baseUrl, baseUrl)) {
            throw IOException("history pull discarded: sync endpoint changed")
        }
    }

    private suspend fun mergeMeasurements(
        incoming: List<MeasurementDto>,
        conflicts: MutableList<String>,
    ): Int {
        val dao = db.measurementDao()
        val toInsert = mutableListOf<MeasurementEntity>()
        for (remote in incoming) {
            val local = dao.findById(remote.id)
            if (local == null) {
                toInsert += mapper.toEntity(remote, localSyncStatus = SyncStatus.SYNCED)
            } else if (!sameImmutable(mapper.toDto(local), remote)) {
                conflicts += "measurements:${remote.id} immutable content differs"
            }
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        return toInsert.size
    }

    private suspend fun mergeCheckins(
        incoming: List<SymptomCheckInDto>,
        conflicts: MutableList<String>,
    ): Int {
        val dao = db.symptomCheckInDao()
        val toInsert = mutableListOf<SymptomCheckInEntity>()
        for (remote in incoming) {
            val local = dao.findById(remote.id)
            if (local == null) {
                toInsert += mapper.toEntity(remote, localSyncStatus = SyncStatus.SYNCED)
            } else if (!sameImmutable(mapper.toDto(local), remote)) {
                conflicts += "checkins:${remote.id} immutable content differs"
            }
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        return toInsert.size
    }

    private suspend fun mergeAlerts(
        incoming: List<AlertDto>,
        conflicts: MutableList<String>,
    ): Int {
        val dao = db.alertDao()
        val toInsert = mutableListOf<AlertEntity>()
        for (remote in incoming) {
            val local = dao.findById(remote.id)
            if (local == null) {
                toInsert += mapper.toEntity(remote, localSyncStatus = SyncStatus.SYNCED)
            } else if (!sameImmutable(mapper.toDto(local), remote)) {
                conflicts += "alerts:${remote.id} immutable content differs"
            } else if (remote.acknowledgedAt != null) {
                // The DAO conditionally applies the backend's first-accepted value:
                // local pending/rejected acknowledgements can never be regressed.
                dao.mergeServerAcknowledgement(remote.id, remote.acknowledgedAt)
            }
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        return toInsert.size
    }

    private fun sameImmutable(local: MeasurementDto, remote: MeasurementDto): Boolean =
        canonical(local) == canonical(remote)

    private fun sameImmutable(local: SymptomCheckInDto, remote: SymptomCheckInDto): Boolean =
        canonical(local) == canonical(remote)

    private fun sameImmutable(local: AlertDto, remote: AlertDto): Boolean =
        canonical(local.copy(acknowledgedAt = null)) == canonical(remote.copy(acknowledgedAt = null))

    private fun canonical(value: Any): String {
        val element: JsonElement = when (value) {
            is MeasurementDto -> canonicalJson.encodeToJsonElement(value)
            is SymptomCheckInDto -> canonicalJson.encodeToJsonElement(value)
            is AlertDto -> canonicalJson.encodeToJsonElement(value)
            else -> error("unsupported history value ${value::class}")
        }
        return canonicalElement(element)
    }

    /** Stable object-key ordering, numeric equivalence, and UUID case normalization. */
    private fun canonicalElement(element: JsonElement, fieldName: String? = null): String = when (element) {
        JsonNull -> "null"
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, child) ->
                quote(key) + ":" + canonicalElement(child, key)
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonicalElement(it, fieldName) }
        is JsonPrimitive -> if (element.isString) {
            val text = element.content
            val identityField = fieldName in setOf("id", "measurementId", "symptomCheckinId", "measurementIds", "symptomCheckinIds")
            quote(if (identityField && HistoryValidatorUuid.isUuid(text)) text.lowercase() else text)
        } else {
            normalizeNumberOrLiteral(element.content)
        }
    }

    private fun normalizeNumberOrLiteral(content: String): String =
        runCatching { BigDecimal(content).stripTrailingZeros().toPlainString() }
            .getOrElse { content }

    private fun quote(value: String): String = canonicalJson.encodeToString(JsonPrimitive(value))

    private fun sameEndpoint(first: String, second: String): Boolean {
        val a = endpoint(first) ?: return false
        val b = endpoint(second) ?: return false
        return a.scheme == b.scheme && a.host == b.host && a.port == b.port && a.path == b.path
    }

    /** Treat host and host/api/v1 as the same configured service endpoint. */
    private fun endpoint(raw: String): Endpoint? = runCatching {
        val uri = URI(raw.trim().removeSuffix("/"))
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        val host = uri.host?.lowercase() ?: return@runCatching null
        val path = uri.path.orEmpty().trimEnd('/').ifEmpty { "" }
            .removeSuffix("/api/v1").trimEnd('/')
        Endpoint(scheme, host, uri.port, path)
    }.getOrNull()

    private data class Endpoint(val scheme: String, val host: String, val port: Int, val path: String)

    private data class MergeResult(
        val insertedMeasurements: Int,
        val insertedCheckins: Int,
        val insertedAlerts: Int,
    )

    private object HistoryValidatorUuid {
        private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        fun isUuid(value: String): Boolean = uuid.matches(value)
    }
}
