package com.yasmin.astrocare.feature.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yasmin.astrocare.core.common.Format
import com.yasmin.astrocare.core.ui.CareCard
import com.yasmin.astrocare.core.ui.CareHeader
import com.yasmin.astrocare.core.ui.CareMessage
import com.yasmin.astrocare.core.ui.CareMetricBadge
import com.yasmin.astrocare.core.ui.CareSectionTitle
import com.yasmin.astrocare.core.ui.StatusPill
import com.yasmin.astrocare.domain.trend.FieldTrend
import com.yasmin.astrocare.domain.trend.TrendDirection
import com.yasmin.astrocare.domain.trend.TrendPoint
import com.yasmin.astrocare.ui.theme.StatusNormal
import com.yasmin.astrocare.ui.theme.StatusWarning

/**
 * Trends: one card per metric with a seven-day chart (gaps where a day has NO
 * readings — never zero-filled), latest/week-mean/baseline/direction stats and
 * the baseline reason when a baseline is insufficient.
 */
@Composable
fun TrendsScreen(modifier: Modifier = Modifier) {
    val viewModel: TrendsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    TrendsContent(state = state, modifier = modifier)
}

/** Stateless Trends body — Compose UI tests render it with a fake [TrendsState]. */
@Composable
fun TrendsContent(state: TrendsState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CareHeader(
                title = "Trends",
                subtitle = if (state.windowLabel.isNotBlank()) {
                    "Last 7 days · ${state.windowLabel}"
                } else {
                    "Recorded measurements"
                },
            )
        }

        state.error?.let { error ->
            item {
                CareMessage(
                    title = "Unable to load trends",
                    message = error,
                    tone = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.loading) {
            item {
                CareCard {
                    Text(
                        text = "Loading trends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        item {
            CareSectionTitle(
                title = "Recorded metrics",
                subtitle = "Seven-day patterns and baseline comparisons",
            )
        }

        items(state.cards, key = { it.metric }) { card ->
            MetricTrendCard(card)
        }

        item {
            CareMessage(
                title = "About these trends",
                message = "Demo thresholds are not medical advice. Direction and " +
                        "baseline comparisons are descriptive statistics, not diagnoses.",
            )
        }
    }
}

@Composable
private fun MetricTrendCard(card: TrendCard) {
    CareCard(
        tone = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CareMetricBadge(metric = card.metric)
            Text(
                text = card.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        if (card.hasNoData) {
            Text(
                text = "No data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            // Chart the primary field (first field of the metric).
            card.trend.fields.firstOrNull()?.let { field ->
                Text(
                    text = fieldLabel(field),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Seven-day mean · missing days remain gaps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SevenDayChart(points = field.points)
            }
        }

        HorizontalDivider()

        card.trend.fields.forEach { field ->
            FieldStats(field, showHeader = card.trend.fields.size > 1)
        }

        // Baseline availability — insufficient baselines are never silent.
        val baseline = card.trend.baseline
        if (!baseline.available) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Baseline unavailable (window ${baseline.windowDays} days, " +
                            "${baseline.sampleCount} sample(s), ${baseline.distinctDays} day(s)): " +
                            (baseline.reason ?: "insufficient data"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text(
                text = "Baseline: ${baseline.windowDays}-day window · " +
                        "${baseline.sampleCount} samples over ${baseline.distinctDays} day(s)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fieldLabel(field: FieldTrend): String =
    field.field.substringAfter('.', field.field)

@Composable
private fun FieldStats(field: FieldTrend, showHeader: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showHeader) {
            Text(
                text = fieldLabel(field),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        StatRow(
            label = "Latest",
            value = field.latest?.let { v ->
                Format.number(v) + " " + field.unit
            } ?: "No data",
            secondary = field.latestAt?.let { Format.shortDateTime(it) },
        )
        StatRow(
            label = "7-day mean",
            value = field.weekMean?.let { Format.number(it) } ?: "No data",
        )
        StatRow(
            label = "Baseline",
            value = buildString {
                append(field.baselineMean?.let { Format.number(it) } ?: "n/a")
                if (field.baselineSd != null) {
                    append(" ± ")
                    append(Format.number(field.baselineSd))
                }
            },
            secondary = field.deltaVsBaseline?.let { delta ->
                "vs baseline: ${if (delta >= 0) "+" else ""}${Format.number(delta)}"
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Direction",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(112.dp),
            )
            DirectionPill(field.direction)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, secondary: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DirectionPill(direction: TrendDirection) {
    val text = when (direction) {
        TrendDirection.up -> "Up"
        TrendDirection.down -> "Down"
        TrendDirection.stable -> "Stable"
        TrendDirection.insufficient_data -> "Insufficient data"
    }
    val color = when (direction) {
        TrendDirection.up -> StatusWarning
        TrendDirection.down -> MaterialTheme.colorScheme.primary
        TrendDirection.stable -> StatusNormal
        TrendDirection.insufficient_data -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusPill(
        text = text,
        color = color,
        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
    )
}

/**
 * Hand-drawn seven-day line chart. Days with count=0 are GAPS: the line breaks
 * (no interpolation across missing days) and the day label shows "–".
 */
@Composable
private fun SevenDayChart(points: List<TrendPoint>) {
    if (points.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary
    val gapLabel = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp),
        ) {
            if (points.size < 2) return@Canvas
            val present = points.mapNotNull { p -> p.mean }
            if (present.isEmpty()) return@Canvas

            val minValue = present.min()
            val maxValue = present.max()
            val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
            val pad = 12.dp.toPx()
            val usableHeight = size.height - pad * 2

            fun xAt(index: Int): Float =
                index * (size.width / (points.size - 1).toFloat())

            fun yAt(value: Double): Float =
                (pad + usableHeight * (1.0 - (value - minValue) / range)).toFloat()

            // Line segments only between CONSECUTIVE days that both have data —
            // gaps break the line (missing days are never zero-filled).
            for (i in 0 until points.size - 1) {
                val a = points[i].mean
                val b = points[i + 1].mean
                if (a != null && b != null) {
                    drawLine(
                        color = primary,
                        start = Offset(xAt(i), yAt(a)),
                        end = Offset(xAt(i + 1), yAt(b)),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            }
            // Dots on days that have data.
            points.forEachIndexed { index, point ->
                point.mean?.let { value ->
                    drawCircle(
                        color = dotColor,
                        radius = 5.dp.toPx(),
                        center = Offset(xAt(index), yAt(value)),
                    )
                }
            }
        }

        // Day labels — "–" marks a No data gap.
        Row(Modifier.fillMaxWidth()) {
            points.forEach { point ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (point.count == 0) "–" else shortDate(point.date),
                        style = MaterialTheme.typography.labelMedium,
                        color = gapLabel,
                    )
                    Text(
                        text = if (point.count == 0) {
                            "No data"
                        } else {
                            point.mean?.let { Format.number(it) } ?: ""
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (point.count == 0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** "2026-11-14" -> "14" */
private fun shortDate(isoLocalDate: String): String = isoLocalDate.takeLast(2)