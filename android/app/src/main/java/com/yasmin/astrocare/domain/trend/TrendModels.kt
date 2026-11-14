package com.yasmin.astrocare.domain.trend

import com.yasmin.astrocare.data.remote.dto.EvidenceDto

/** One local day's readings for a single field (contract `localDate` + values, oldest -> newest). */
data class DailyValues(val date: String, val values: List<Double>)

/**
 * Intermediate aggregates of `contracts/test-vectors/trend-vectors.v1.json`
 * (the Phase 5 trend gate). `weekMean` averages the values of days that HAVE
 * data — missing days are "No data" gaps and are never counted as zero.
 */
data class DailyAggregate(
    /** Number of days in the window (7 for the contract vectors). */
    val dailyCount: Int,
    /** Mean over days with data, rounded to trendPolicy rounding; null when no data. */
    val weekMean: Double?,
    /** Newest day's last value, or null when every day is a gap. */
    val latest: Double?,
    /** Contract `localDate` of [latest]. */
    val latestAtDate: String?,
    /** Days with an empty values list. */
    val missingDays: Int,
)

/** One of the 7 daily points of `TrendReport.metrics[].fields[].points`. */
data class TrendPoint(
    val date: String,
    val count: Int,
    val min: Double?,
    val mean: Double?,
    val max: Double?,
)

/** contracts/schemas/trend-report.schema.json direction enum — not an alert level. */
enum class TrendDirection { up, down, stable, insufficient_data }

/** Baseline block of TrendReport (trendPolicy.baseline via BaselineCalculator). */
data class BaselineInfo(
    val available: Boolean,
    val windowDays: Int,
    val sampleCount: Int,
    val distinctDays: Int,
    /** null when available, otherwise why the baseline was rejected — never silent. */
    val reason: String?,
)

data class FieldTrend(
    val field: String,
    /** Raw contract unit ("bpm") — display symbol comes from `unitDisplay`. */
    val unit: String,
    val latest: Double?,
    /** Contract `utcDateTime` of the newest reading, or null. */
    val latestAt: String?,
    val weekMean: Double?,
    val baselineMean: Double?,
    val baselineSd: Double?,
    /** latest - baselineMean (rounded), null when either side is missing. */
    val deltaVsBaseline: Double?,
    val direction: TrendDirection,
    /** Exactly 7 points, oldest -> newest; count=0 is a "No data" gap. */
    val points: List<TrendPoint>,
)

data class MetricTrend(
    val metric: String,
    val baseline: BaselineInfo,
    /** minItems=1 per schema — metrics without data still list their fields with null values. */
    val fields: List<FieldTrend>,
)

data class TrendWindow(
    /** Contract `localDate` of the first day (inclusive). */
    val from: String,
    /** Contract `localDate` of the last day (inclusive, = today). */
    val to: String,
    val days: Int,
    val utcOffsetMinutes: Int,
)

/** Insight row of TrendReport (schema `$defs.insight` — severity is `info|warning`, never an alert level). */
data class ReportInsight(
    val id: String,
    val ruleId: String,
    val ruleRevision: Int,
    val severity: String,
    val metric: String,
    val text: String,
    val evidence: List<EvidenceDto>,
    val generatedAt: String,
)

/** contracts/schemas/trend-report.schema.json — produced with `generatedBy = android_local`. */
data class TrendReport(
    val schemaVersion: String,
    val astronautId: String,
    val generatedAt: String,
    val generatedBy: String,
    val ruleSetVersion: String,
    val window: TrendWindow,
    val metrics: List<MetricTrend>,
    val insights: List<ReportInsight>,
)
