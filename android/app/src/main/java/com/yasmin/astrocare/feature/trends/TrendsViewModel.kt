package com.yasmin.astrocare.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yasmin.astrocare.core.common.IsoTime
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.data.local.entity.MeasurementEntity
import com.yasmin.astrocare.data.mapper.ContractMapper
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.domain.trend.MetricTrend
import com.yasmin.astrocare.domain.trend.TrendCalculator
import com.yasmin.astrocare.domain.trend.TrendReport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One metric card on Trends. */
data class TrendCard(
    val metric: String,
    val label: String,
    val trend: MetricTrend,
    /** True when the whole 7-day window has no readings ("No data" state). */
    val hasNoData: Boolean,
)

data class TrendsState(
    val loading: Boolean = true,
    val windowLabel: String = "",
    val cards: List<TrendCard> = emptyList(),
    val report: TrendReport? = null,
    val error: String? = null,
)

/**
 * Trends state: builds a contract TrendReport from Room rows with the bundled
 * trendPolicy (7-day window, 14-day baseline, direction band). Rebuilds on
 * every measurement change; all math runs off the main thread.
 */
@HiltViewModel
class TrendsViewModel @Inject constructor(
    healthRepository: HealthRepository,
    private val ruleSetRepository: RuleSetRepository,
    private val mapper: ContractMapper,
) : ViewModel() {

    private val _state = MutableStateFlow(TrendsState())
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    private var calculator: TrendCalculator? = null
    private var latestRows: List<MeasurementEntity> = emptyList()

    init {
        viewModelScope.launch {
            try {
                calculator = TrendCalculator(
                    policy = ruleSetRepository.trendPolicy(),
                    catalog = ruleSetRepository.contractCatalog(),
                )
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
                return@launch
            }
            healthRepository.observeMeasurements().collect { rows ->
                latestRows = rows
                rebuild()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { rebuild() }
    }

    private suspend fun rebuild() {
        val calc = calculator ?: return
        try {
            val catalog = ruleSetRepository.contractCatalog()
            val nowMillis = System.currentTimeMillis()
            val offset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(nowMillis))
                .totalSeconds / 60
            val records = latestRows.mapNotNull { row ->
                runCatching { mapper.toMeasurementRecord(row) }.getOrNull()
            }
            val report = calc.buildReport(
                astronautId = catalog.defaultAstronautId,
                nowMillis = nowMillis,
                nowIso = IsoTime.formatUtc(nowMillis),
                utcOffsetMinutes = offset,
                records = records,
                ruleSetVersion = ruleSetRepository.ruleSetVersion(),
                insights = emptyList(),
            )
            _state.update {
                it.copy(
                    loading = false,
                    windowLabel = "${report.window.from} to ${report.window.to}",
                    cards = report.metrics.map { metricTrend ->
                        TrendCard(
                            metric = metricTrend.metric,
                            label = catalog.metrics[metricTrend.metric]?.label ?: metricTrend.metric,
                            trend = metricTrend,
                            hasNoData = metricTrend.fields.all { field ->
                                field.points.all { point -> point.count == 0 }
                            },
                        )
                    },
                    report = report,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Trend build failed") }
        }
    }
}
