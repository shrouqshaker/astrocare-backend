package com.yasmin.astrocare.domain.trend

import com.yasmin.astrocare.domain.rule.BaselineCalculator
import com.yasmin.astrocare.domain.rule.ContractCatalog
import com.yasmin.astrocare.domain.rule.ContextMeasurement
import com.yasmin.astrocare.domain.rule.MeasurementRecord
import com.yasmin.astrocare.domain.rule.model.TrendPolicy
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * Trend math for the Trends screen, following rules.v1.json `trendPolicy`
 * exactly — no invented thresholds:
 *
 *  - window: 7 inclusive local days ending "now" (localDate shifted by
 *    `utcOffsetMinutes`), one point per local day, missing days stay gaps;
 *  - weekMean: mean over days WITH data, rounded to `roundingDecimals`
 *    (trend-vectors.v1.json semantics — gaps are never zero-filled);
 *  - baseline per field: [BaselineCalculator] over `baseline.windowDays`
 *    minus the last `excludeLastHours`;
 *  - direction: mean of the last 3 days vs mean of the earlier 4 days;
 *    `insufficient_data` when either side has fewer than
 *    `minDaysWithDataPerSide` days with data, `stable` when the absolute
 *    difference is within `stableBand[field]` (band 0.0 for an unlisted
 *    field — the contract lists every field).
 *
 * Pure and deterministic: the clock is always an input; same inputs -> same
 * report. No I/O, no network, no rule evaluation here.
 */
class TrendCalculator(
    private val policy: TrendPolicy,
    private val catalog: ContractCatalog,
) {

    /**
     * Vector-gate aggregate over an ordered list of local days (oldest first).
     * `latest`/`latestAtDate` come from the newest day that has data.
     */
    fun aggregate(daily: List<DailyValues>): DailyAggregate {
        val withData = daily.filter { it.values.isNotEmpty() }
        val allValues = withData.flatMap { it.values }
        val newestDay = withData.lastOrNull()
        return DailyAggregate(
            dailyCount = daily.size,
            weekMean = if (allValues.isEmpty()) {
                null
            } else {
                BaselineCalculator.round(allValues.sum() / allValues.size, policy.roundingDecimals)
            },
            latest = newestDay?.values?.lastOrNull(),
            latestAtDate = newestDay?.date,
            missingDays = daily.count { it.values.isEmpty() },
        )
    }

    /**
     * Full TrendReport for the Trends screen (contracts/schemas/trend-report.schema.json).
     *
     * @param records measurement rows (any range — the 7-day window filters them)
     * @param insights display-only insights from the last evaluation (may be empty)
     */
    fun buildReport(
        astronautId: String,
        nowMillis: Long,
        nowIso: String,
        utcOffsetMinutes: Int,
        records: List<MeasurementRecord>,
        ruleSetVersion: String,
        insights: List<ReportInsight> = emptyList(),
    ): TrendReport {
        val localToday = localDate(nowMillis, utcOffsetMinutes)
        val windowFrom = localToday.minusDays(policy.windowDays - 1L)
        val windowDates = (0 until policy.windowDays).map { windowFrom.plusDays(it.toLong()).toString() }

        // readings grouped per field: window subset (7d) and full-history (baseline 14d)
        val windowByField = LinkedHashMap<String, MutableList<ContextMeasurement>>()
        val historyByField = LinkedHashMap<String, MutableList<ContextMeasurement>>()
        val historyByMetric = LinkedHashMap<String, MutableList<ContextMeasurement>>()
        for (record in records) {
            val day = localDate(record.observedAtMillis, utcOffsetMinutes).toString()
            val inWindow = day in windowDates
            for ((field, value) in record.values) {
                val fieldKey = "${record.metric}.$field"
                val cm = ContextMeasurement(
                    field = fieldKey,
                    metric = record.metric,
                    value = value,
                    observedAt = record.observedAt,
                    observedAtMillis = record.observedAtMillis,
                    measurementId = record.id,
                )
                historyByField.getOrPut(fieldKey) { mutableListOf() } += cm
                historyByMetric.getOrPut(record.metric) { mutableListOf() } += cm
                if (inWindow) windowByField.getOrPut(fieldKey) { mutableListOf() } += cm
            }
        }

        val metrics = catalog.metrics.map { (metricId, metricDef) ->
            MetricTrend(
                metric = metricId,
                baseline = metricBaseline(
                    readings = historyByMetric[metricId].orEmpty(),
                    nowMillis = nowMillis,
                ),
                fields = metricDef.fields.map { (field, fieldDef) ->
                    val fieldKey = "$metricId.$field"
                    buildFieldTrend(
                        fieldKey = fieldKey,
                        unit = fieldDef.unit,
                        windowReadings = windowByField[fieldKey].orEmpty(),
                        baselineReadings = historyByField[fieldKey].orEmpty(),
                        windowDates = windowDates,
                        utcOffsetMinutes = utcOffsetMinutes,
                        nowMillis = nowMillis,
                    )
                },
            )
        }

        return TrendReport(
            schemaVersion = catalog.schemaVersion,
            astronautId = astronautId,
            generatedAt = nowIso,
            generatedBy = GENERATED_BY_ANDROID_LOCAL,
            ruleSetVersion = ruleSetVersion,
            window = TrendWindow(
                from = windowFrom.toString(),
                to = localToday.toString(),
                days = policy.windowDays,
                utcOffsetMinutes = utcOffsetMinutes,
            ),
            metrics = metrics,
            insights = insights,
        )
    }

    private fun buildFieldTrend(
        fieldKey: String,
        unit: String,
        windowReadings: List<ContextMeasurement>,
        baselineReadings: List<ContextMeasurement>,
        windowDates: List<String>,
        utcOffsetMinutes: Int,
        nowMillis: Long,
    ): FieldTrend {
        val sorted = windowReadings.sortedWith(
            compareBy({ it.observedAtMillis }, { it.measurementId ?: "" }),
        )
        val byLocalDate = sorted.groupBy { localDate(it.observedAtMillis, utcOffsetMinutes).toString() }

        val points = windowDates.map { date ->
            val dayValues = byLocalDate[date].orEmpty().map { it.value }
            TrendPoint(
                date = date,
                count = dayValues.size,
                min = dayValues.minOrNull()?.let { BaselineCalculator.round(it, policy.roundingDecimals) },
                mean = if (dayValues.isEmpty()) {
                    null
                } else {
                    BaselineCalculator.round(dayValues.sum() / dayValues.size, policy.roundingDecimals)
                },
                max = dayValues.maxOrNull()?.let { BaselineCalculator.round(it, policy.roundingDecimals) },
            )
        }

        val newest = sorted.lastOrNull()
        val aggregate = aggregate(
            points.map { point -> DailyValues(point.date, byLocalDate[point.date].orEmpty().map { it.value }) },
        )

        val baselineStats = BaselineCalculator.calculate(
            readingsNewestFirst = baselineReadings.sortedWith(
                compareByDescending<ContextMeasurement> { it.observedAtMillis }.thenBy { it.measurementId ?: "" },
            ),
            policy = policy.baseline,
            nowMillis = nowMillis,
            roundingDecimals = policy.roundingDecimals,
        )

        val latest = newest?.value
        val baselineMean = if (baselineStats.sufficient) baselineStats.mean else null
        val delta = if (latest != null && baselineMean != null) {
            BaselineCalculator.round(latest - baselineMean, policy.roundingDecimals)
        } else {
            null
        }

        return FieldTrend(
            field = fieldKey,
            unit = unit,
            latest = latest,
            latestAt = newest?.observedAt,
            weekMean = aggregate.weekMean,
            baselineMean = baselineMean,
            baselineSd = if (baselineStats.sufficient) baselineStats.sd else null,
            deltaVsBaseline = delta,
            direction = direction(fieldKey, points),
            points = points,
        )
    }

    /**
     * Metric-level baseline block, pooled across all field readings of the
     * metric inside the baseline window. `sampleCount`/`distinctDays` describe
     * that pool; when the minimums are not met the first rejection reason is
     * surfaced (never silent).
     */
    private fun metricBaseline(
        readings: List<ContextMeasurement>,
        nowMillis: Long,
    ): BaselineInfo {
        if (readings.isEmpty()) {
            return BaselineInfo(
                available = false,
                windowDays = policy.baseline.windowDays,
                sampleCount = 0,
                distinctDays = 0,
                reason = "no readings in the baseline window " +
                    "(${policy.baseline.windowDays}d minus last ${policy.baseline.excludeLastHours}h)",
            )
        }
        val stats = BaselineCalculator.calculate(
            readingsNewestFirst = readings.sortedWith(
                compareByDescending<ContextMeasurement> { it.observedAtMillis }.thenBy { it.measurementId ?: "" },
            ),
            policy = policy.baseline,
            nowMillis = nowMillis,
            roundingDecimals = policy.roundingDecimals,
        )
        return BaselineInfo(
            available = stats.sufficient,
            windowDays = policy.baseline.windowDays,
            sampleCount = stats.samples,
            distinctDays = stats.distinctDays,
            reason = stats.insufficientReason,
        )
    }

    /**
     * trendPolicy.direction: "mean of last 3 days vs mean of the earlier 4 days".
     * Each day counts with equal weight (its mean); days without data do not
     * count toward either side's minimum.
     */
    private fun direction(fieldKey: String, points: List<TrendPoint>): TrendDirection {
        if (points.size != policy.windowDays) return TrendDirection.insufficient_data
        val split = points.size - LAST_DAYS
        val earlierDays = points.subList(0, split).filter { it.count > 0 }
        val recentDays = points.subList(split, points.size).filter { it.count > 0 }
        val minDays = policy.direction.minDaysWithDataPerSide
        if (earlierDays.size < minDays || recentDays.size < minDays) {
            return TrendDirection.insufficient_data
        }
        val earlierMean = earlierDays.mapNotNull { it.mean }.average()
        val recentMean = recentDays.mapNotNull { it.mean }.average()
        val diff = recentMean - earlierMean
        val band = policy.direction.stableBand[fieldKey] ?: 0.0
        return when {
            abs(diff) <= band -> TrendDirection.stable
            diff > 0 -> TrendDirection.up
            else -> TrendDirection.down
        }
    }

    companion object {
        const val GENERATED_BY_ANDROID_LOCAL = "android_local"
        private const val LAST_DAYS = 3

        /** Contract `localDate` for an instant at the given UTC offset. */
        fun localDate(millis: Long, utcOffsetMinutes: Int): LocalDate =
            Instant.ofEpochMilli(millis + utcOffsetMinutes * 60_000L)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
    }
}
