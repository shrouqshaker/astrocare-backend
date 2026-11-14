package com.yasmin.astrocare.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yasmin.astrocare.core.common.Format
import com.yasmin.astrocare.data.contract.ProvenanceRepository
import com.yasmin.astrocare.data.health.DisplayAlert
import com.yasmin.astrocare.data.health.EvaluationOutcome
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.domain.rule.ContractCatalog
import com.yasmin.astrocare.domain.rule.Insight
import com.yasmin.astrocare.domain.rule.OverallStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One row of the "latest readings" grid — valueText null means "No data". */
data class LatestReading(
    val metric: String,
    val label: String,
    /** e.g. "72 bpm", "120/80 mmHg", "7.4 h · 4/5"; null = no rows yet. */
    val valueText: String?,
    /** Contract utcDateTime of the newest row, or null. */
    val observedAt: String?,
    /** True when the newest row is contract-synthetic demo data. */
    val isSynthetic: Boolean,
)

data class OverviewState(
    val loading: Boolean = true,
    /** Display-only evaluation — alerts persist on save only (trigger=on_save). */
    val status: OverallStatus? = null,
    val latest: List<LatestReading> = emptyList(),
    val alerts: List<AlertEntity> = emptyList(),
    val insights: List<Insight> = emptyList(),
    /** Suppressions / budget notes — surfaced, never silent. */
    val diagnostics: List<String> = emptyList(),
    /** True when any newest row is contract-synthetic demo data. */
    val hasSyntheticData: Boolean = false,
    /** Seed label from synthetic-seed.v1.json ("SYNTHETIC DEMO DATA — ..."). */
    val seedLabel: String = "",
    /** Contract disclaimer (enums.json) — shown in the footer. */
    val disclaimer: String = "",
    val errorMessage: String? = null,
    /** Current rule causes; stored history stays separately in alerts. */
    val currentAlerts: List<DisplayAlert> = emptyList(),
    val evaluating: Boolean = true,
)

/**
 * Overview state: current overall status (display evaluation), latest reading
 * per metric, the stored alert feed, insights and diagnostics. Room Flows
 * drive the UI; evaluation runs off the main thread (RuleSetRepository).
 */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val healthRepository: HealthRepository,
    private val ruleSetRepository: RuleSetRepository,
    private val mapper: ContractMapper,
    provenanceRepository: ProvenanceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                healthRepository.observeMeasurements(),
                healthRepository.observeAlerts(ALERT_FEED_LIMIT),
                healthRepository.observeCheckIns(),
            ) { measurements, alerts, _ -> measurements to alerts }
                .collectLatest { (measurements, alerts) ->
                    val latest = buildLatestReadings(measurements)
                    _state.update {
                        it.copy(
                            loading = false,
                            latest = latest,
                            alerts = alerts,
                            hasSyntheticData = measurements.any { row -> row.isSynthetic },
                        )
                    }
                    // Pull updates can change current status/insights as well as the grid.
                    // This evaluation is display-only; it never persists new alerts.
                    refreshStatus()
                }
        }
        viewModelScope.launch {
            runCatching { provenanceRepository.load() }.onSuccess { provenance ->
                _state.update {
                    it.copy(seedLabel = provenance.seedLabel, disclaimer = provenance.disclaimer)
                }
            }
        }
    }

    /** Re-runs the display evaluation (refresh action). */
    fun refresh() {
        viewModelScope.launch { refreshStatus() }
    }

    /** Contract `acknowledgedAt` — set once, never deletes the alert. */
    fun acknowledge(alertId: String) {
        viewModelScope.launch {
            try {
                healthRepository.acknowledgeAlert(alertId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "Unable to acknowledge alert") }
            }
        }
    }

    private suspend fun refreshStatus() {
        _state.update { it.copy(evaluating = true) }
        try {
            val outcome: EvaluationOutcome = healthRepository.evaluateForDisplay()
            _state.update {
                it.copy(
                    status = outcome.overallStatus,
                    insights = outcome.insights,
                    diagnostics = outcome.diagnostics,
                    currentAlerts = outcome.displayAlerts,
                    evaluating = false,
                    errorMessage = null,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    status = null,
                    currentAlerts = emptyList(),
                    evaluating = false,
                    errorMessage = e.message ?: "Evaluation failed",
                )
            }
        }
    }

    private suspend fun buildLatestReadings(rows: List<MeasurementEntity>): List<LatestReading> {
        val catalog: ContractCatalog = ruleSetRepository.contractCatalog()
        return catalog.metrics.map { (metricId, metricDef) ->
            val newest = rows.filter { it.metric == metricId }.maxByOrNull { it.measuredAt }
                ?: return@map LatestReading(metricId, metricDef.label, null, null, false)
            LatestReading(
                metric = metricId,
                label = metricDef.label,
                valueText = formatMetricValue(catalog, metricId, newest),
                observedAt = newest.measuredAt,
                isSynthetic = newest.isSynthetic,
            )
        }
    }

    private fun formatMetricValue(
        catalog: ContractCatalog,
        metric: String,
        row: MeasurementEntity,
    ): String? {
        val metricDef = catalog.metricDef(metric) ?: return null
        val values = runCatching { mapper.parseValues(row) }.getOrNull() ?: return null
        val parts = metricDef.fields.map { (field, fieldDef) ->
            val raw = values[field] ?: return null
            val number = (raw as? JsonPrimitive)?.doubleOrNull ?: return null
            val display = Format.number(number)
            if (metric == "blood_pressure") {
                display // pair rendered once below: "120/80 mmHg"
            } else {
                "$display ${catalog.displayUnit(fieldDef.unit)}"
            }
        }
        if (parts.isEmpty()) return null
        return if (metric == "blood_pressure") {
            parts.joinToString("/") + " " +
                    catalog.displayUnit(metricDef.fields.values.first().unit)
        } else {
            parts.joinToString(" · ")
        }
    }

    private companion object {
        const val ALERT_FEED_LIMIT = 20
    }
}
