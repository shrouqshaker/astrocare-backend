package com.yasmin.astrocare.data.health

import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.local.SyncStatus
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.local.entity.SymptomCheckInEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.domain.emergency.ChestPainTriage
import com.yasmin.astrocare.domain.health.MeasurementValidator
import com.yasmin.astrocare.domain.rule.ContractCatalog
import com.yasmin.astrocare.domain.rule.Insight
import com.yasmin.astrocare.domain.rule.OverallStatus
import com.yasmin.astrocare.domain.repository.SettingsRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A current rule alert projected for the screen; previewing never inserts it. */
data class DisplayAlert(
    val alert: AlertEntity,
    /** Only a stored matching alert can be acknowledged. */
    val savedAlertId: String?,
)

/** Result of one save + evaluation pass (the contract `evaluation.trigger` = on_save). */
data class EvaluationOutcome(
    val overallStatus: OverallStatus,
    /** New alerts AFTER group policy + dedupe, already persisted (pending sync). */
    val newAlerts: List<AlertEntity>,
    val insights: List<Insight>,
    /** Suppressions / insufficient-baseline / budget notes — surfaced, never silent. */
    val diagnostics: List<String>,
    /** Wall time of insert + evaluation + alert insert (the 5-second budget guard). */
    val elapsedMillis: Long,
    /** True when this pass persisted alerts (false for display-only passes). */
    val persistedAlerts: Boolean,
    /** Current causes for the UI, including rules whose alerts already exist. */
    val displayAlerts: List<DisplayAlert> = emptyList(),
)

data class PendingCounts(val measurements: Int, val checkIns: Int, val alerts: Int)

/**
 * The offline save pipeline: Room insert -> rule evaluation -> alert insert.
 *
 * - Room is the source of truth; every write happens locally first.
 * - Evaluation runs on Dispatchers.Default (never main) with the CONTRACT
 *   policies via [RuleSetRepository.buildContext]; alerts are persisted with
 *   `localSyncStatus = pending` (Android-only column, never serialized).
 * - The whole pass (insert + engine budget 1000 ms + alert insert) completes
 *   well within the 5-second offline alert requirement; [EvaluationOutcome]
 *   exposes the measured elapsed time.
 * - Trigger discipline: alerts are ONLY persisted from the save path
 *   (`evaluation.trigger = on_save_of_measurement_or_symptom_checkin`).
 *   [evaluateForDisplay] computes status/insights for the UI and never writes.
 */
@Singleton
class HealthRepository @Inject constructor(
    private val measurementDao: MeasurementDao,
    private val symptomCheckInDao: SymptomCheckInDao,
    private val alertDao: AlertDao,
    private val mapper: ContractMapper,
    private val ruleSetRepository: RuleSetRepository,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) {

    fun observeMeasurements(): Flow<List<MeasurementEntity>> = measurementDao.observeAll()

    fun observeCheckIns(): Flow<List<SymptomCheckInEntity>> = symptomCheckInDao.observeAll()

    fun observeAlerts(limit: Int): Flow<List<AlertEntity>> = alertDao.observeRecent(limit)

    /** Contract enums.json — metric defs/labels/units for the entry forms. */
    suspend fun catalog(): ContractCatalog = ruleSetRepository.contractCatalog()

    /**
     * Saves a user-entered measurement and evaluates immediately (contract
     * trigger: on_save). Rejects out-of-contract values BEFORE they reach Room.
     */
    suspend fun saveMeasurement(
        metric: String,
        values: Map<String, Double>,
        note: String?,
    ): EvaluationOutcome = withContext(Dispatchers.Default) {
        val catalog = ruleSetRepository.contractCatalog()
        val problems = MeasurementValidator.validate(catalog, metric, values)
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("\n"))
        }

        val settings = settingsRepository.snapshot()
        val nowMillis = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMillis)
        val valuesObject = JsonObject(values.map { (field, value) ->
            val type = catalog.metricDef(metric)?.fields?.get(field)?.type
            // integer contract fields must serialize as JSON integers (schema type=integer)
            field to (if (type == "integer") JsonPrimitive(value.toLong()) else JsonPrimitive(value))
        }.toMap())

        measurementDao.insertAll(
            listOf(
                MeasurementEntity(
                    id = UUID.randomUUID().toString(),
                    schemaVersion = AppDefaults.SCHEMA_VERSION,
                    astronautId = settings.astronautId,
                    metric = metric,
                    valuesJson = json.encodeToString(valuesObject),
                    measuredAt = nowIso,
                    utcOffsetMinutes = currentUtcOffsetMinutes(),
                    environment = settings.environment,
                    source = SOURCE_MANUAL,
                    isSynthetic = false,
                    note = note?.takeIf { it.isNotBlank() }?.take(MAX_NOTE_LENGTH),
                    createdAt = nowIso,
                    localSyncStatus = SyncStatus.PENDING,
                ),
            ),
        )
        evaluateAndPersist()
    }

    /**
     * Saves a symptom check-in (optionally with chest-pain triage data from the
     * offline protocol) and evaluates immediately (contract trigger: on_save).
     */
    suspend fun saveCheckIn(
        symptoms: List<String>,
        triage: ChestPainTriage? = null,
    ): EvaluationOutcome = withContext(Dispatchers.Default) {
        val catalog = ruleSetRepository.contractCatalog()
        val problems = validateSymptoms(catalog, symptoms)
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(problems.joinToString("\n"))
        }

        val settings = settingsRepository.snapshot()
        val nowMillis = System.currentTimeMillis()
        val nowIso = IsoTime.formatUtc(nowMillis)
        val triageJson = triage?.let { t ->
            json.encodeToString(
                JsonObject(
                    mapOf(
                        "protocolId" to JsonPrimitive(t.protocolId),
                        "answers" to kotlinx.serialization.json.JsonArray(
                            t.answers.map { a ->
                                JsonObject(
                                    mapOf(
                                        "questionId" to JsonPrimitive(a.questionId),
                                        "answer" to JsonPrimitive(a.answer.wire),
                                    ),
                                )
                            },
                        ),
                        "outcome" to JsonPrimitive(t.outcome.wire),
                    ),
                ),
            )
        }

        symptomCheckInDao.insertAll(
            listOf(
                SymptomCheckInEntity(
                    id = UUID.randomUUID().toString(),
                    schemaVersion = AppDefaults.SCHEMA_VERSION,
                    astronautId = settings.astronautId,
                    symptomsJson = json.encodeToString(
                        kotlinx.serialization.json.JsonArray(symptoms.map { JsonPrimitive(it) }),
                    ),
                    checkedAt = nowIso,
                    utcOffsetMinutes = currentUtcOffsetMinutes(),
                    environment = settings.environment,
                    source = SOURCE_MANUAL,
                    isSynthetic = false,
                    chestPainTriageJson = triageJson,
                    createdAt = nowIso,
                    localSyncStatus = SyncStatus.PENDING,
                ),
            ),
        )
        evaluateAndPersist()
    }

    /**
     * Full evaluation over the current Room state; persists new alerts after
     * group policy + dedupe. Called ONLY from save paths (trigger = on_save).
     */
    suspend fun evaluateAndPersist(): EvaluationOutcome = runEvaluation(persistAlerts = true)

    /**
     * Status + insights for DISPLAY (Overview). Never persists alerts — new
     * alerts may only be created by a save, per rules.v1.json evaluation.trigger.
     */
    suspend fun evaluateForDisplay(): EvaluationOutcome = runEvaluation(persistAlerts = false)

    /** Sets contract `acknowledgedAt` once (never overwrites an existing value). */
    suspend fun acknowledgeAlert(alertId: String) {
        alertDao.acknowledge(alertId, IsoTime.formatUtc(System.currentTimeMillis()))
    }

    /** Pending rows per table — derived from Room for Settings, never stored. */
    suspend fun pendingCounts(): PendingCounts = withContext(Dispatchers.IO) {
        PendingCounts(
            measurements = measurementDao.countPending(),
            checkIns = symptomCheckInDao.countPending(),
            alerts = alertDao.countPending(),
        )
    }

    private suspend fun runEvaluation(persistAlerts: Boolean): EvaluationOutcome =
        withContext(Dispatchers.Default) {
            val startedNanos = System.nanoTime()
            val settings = settingsRepository.snapshot()
            val nowMillis = System.currentTimeMillis()
            val nowIso = IsoTime.formatUtc(nowMillis)

            // fetch the baseline horizon (covers the 1440-min context window too)
            val horizonDays = ruleSetRepository.baselineWindowDays()
            val fromIso = IsoTime.formatUtc(nowMillis - horizonDays * DAY_MILLIS)
            val measurements = measurementDao.getFrom(fromIso).map(mapper::toMeasurementRecord)
            val checkIns = symptomCheckInDao.getFrom(fromIso).map(mapper::toCheckInRecord)

            val context = ruleSetRepository.buildContext(
                astronautId = settings.astronautId,
                nowMillis = nowMillis,
                nowIso = nowIso,
                measurements = measurements,
                checkIns = checkIns,
            )
            val storedAlerts = alertDao.getAll()
            val existing = storedAlerts.map(mapper::toExistingAlertRef)
            // Saving retains normal dedupe. A display-only pass keeps the
            // currently firing rule explanations, even when an alert was
            // already stored. The preview branch below NEVER inserts rows.
            val result = ruleSetRepository.evaluate(
                context,
                existing = if (persistAlerts) existing else emptyList(),
            )

            val rows = if (persistAlerts) {
                result.alerts.map { mapper.toEntity(it, localSyncStatus = SyncStatus.PENDING) }
            } else {
                emptyList()
            }
            if (rows.isNotEmpty()) alertDao.insertAll(rows)

            val displayAlerts = if (persistAlerts) {
                emptyList()
            } else {
                result.alerts.map { candidate ->
                    val directIndex = storedAlerts.indexOfFirst { it.id == candidate.id }
                    val matchingIndex = if (directIndex >= 0) {
                        directIndex
                    } else {
                        // Also handles stored legacy IDs. Match the same
                        // triggering record sets used by the engine's dedupe.
                        existing.indexOfFirst { reference ->
                            reference.ruleId == candidate.ruleId &&
                                    reference.measurementIds == candidate.triggeredBy.measurementIds.toSet() &&
                                    reference.symptomCheckinIds == candidate.triggeredBy.symptomCheckinIds.toSet()
                        }
                    }
                    val saved = storedAlerts.getOrNull(matchingIndex)
                    DisplayAlert(
                        alert = mapper.toEntity(candidate).copy(
                            acknowledgedAt = saved?.acknowledgedAt,
                        ),
                        savedAlertId = saved?.id,
                    )
                }
            }

            EvaluationOutcome(
                overallStatus = result.overallStatus,
                newAlerts = rows,
                insights = result.insights,
                diagnostics = result.diagnostics,
                elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000,
                persistedAlerts = persistAlerts,
                displayAlerts = displayAlerts,
            )
        }

    private fun validateSymptoms(catalog: ContractCatalog, symptoms: List<String>): List<String> {
        if (symptoms.isEmpty()) return listOf("select at least one symptom (or 'none')")
        val problems = mutableListOf<String>()
        for (symptom in symptoms) {
            if (symptom !in catalog.symptoms) problems += "unknown symptom '$symptom'"
        }
        if (NONE_SYMPTOM in symptoms && symptoms.size > 1) {
            problems += "'$NONE_SYMPTOM' must stand alone"
        }
        return problems
    }

    /** Device-local UTC offset in minutes for contract `utcOffsetMinutes`. */
    private fun currentUtcOffsetMinutes(): Int =
        java.time.ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val NONE_SYMPTOM = "none"
        private const val MAX_NOTE_LENGTH = 280
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
