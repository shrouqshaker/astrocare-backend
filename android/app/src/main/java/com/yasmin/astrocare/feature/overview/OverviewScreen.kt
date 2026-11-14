package com.yasmin.astrocare.feature.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yasmin.astrocare.core.common.Format
import com.yasmin.astrocare.core.ui.CareBulletStep
import com.yasmin.astrocare.core.ui.StatusPill
import com.yasmin.astrocare.data.health.DisplayAlert
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.domain.rule.Insight
import com.yasmin.astrocare.feature.trends.TrendsState
import com.yasmin.astrocare.feature.trends.TrendsViewModel
import com.yasmin.astrocare.ui.theme.StatusCritical
import com.yasmin.astrocare.ui.theme.StatusInsufficient
import com.yasmin.astrocare.ui.theme.StatusNormal
import com.yasmin.astrocare.ui.theme.StatusWarning

private val NATIVE_METRICS = listOf(
    "heart_rate",
    "blood_pressure",
    "spo2",
    "temperature",
    "sleep",
)

@Composable
fun OverviewScreen(
    onOpenEmergency: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: OverviewViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val trendsViewModel: TrendsViewModel = hiltViewModel()
    val trendsState by trendsViewModel.state.collectAsState()

    OverviewContent(
        state = state,
        onRefresh = {
            viewModel.refresh()
            trendsViewModel.refresh()
        },
        onAcknowledge = viewModel::acknowledge,
        onOpenEmergency = onOpenEmergency,
        modifier = modifier,
        trendsState = trendsState,
    )
}

@Composable
fun OverviewContent(
    state: OverviewState,
    onRefresh: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onOpenEmergency: () -> Unit,
    modifier: Modifier = Modifier,
    trendsState: TrendsState = TrendsState(loading = false),
) {
    val currentSavedIds = state.currentAlerts.mapNotNull { it.savedAlertId }.toSet()
    val previousAlerts = state.alerts.filterNot { it.id in currentSavedIds }
    val readings = state.latest
        .filter { it.metric in NATIVE_METRICS }
        .sortedBy { NATIVE_METRICS.indexOf(it.metric) }
    val linkedFields = trendsState.cards.flatMap { card ->
        card.trend.fields.map { it.field }
    }.toSet()
    val otherInsights = state.insights.filterNot { it.field in linkedFields }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { OverviewHeader(onRefresh = onRefresh) }
        item { CurrentHealthCard(state, onAcknowledge) }
        item { EmergencyCard(onOpenEmergency = onOpenEmergency) }

        state.errorMessage?.let { message ->
            item { ErrorCard(message) }
        }

        item { OverviewSectionTitle("Latest readings") }
        when {
            state.loading && readings.isEmpty() -> item {
                LoadingCard("Loading latest readings…")
            }
            readings.isEmpty() -> item {
                EmptyCard("No measurements yet.")
            }
            else -> {
                items(
                    items = readings.chunked(2),
                    key = { row -> "readings:${row.joinToString(":") { it.metric }}" },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { reading ->
                            ReadingCard(
                                reading = reading,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }


        if (otherInsights.isNotEmpty()) {
            item { OverviewSectionTitle("Other insights") }
            itemsIndexed(
                items = otherInsights,
                key = { index, insight -> insightKey(otherInsights, index, insight) },
            ) { index, insight ->
                InsightCard(
                    insight = insight,
                    stableKey = insightKey(otherInsights, index, insight),
                )
            }
        }

        item { PreviousAlertsCard(previousAlerts, onAcknowledge) }
        if (state.diagnostics.isNotEmpty()) {
            item { DiagnosticsCard(state.diagnostics) }
        }
        item { SafetyInformationCard(state.disclaimer) }
    }
}

@Composable
private fun OverviewHeader(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Overview",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun CurrentHealthCard(state: OverviewState, onAcknowledge: (String) -> Unit) {
    val statusName = if (state.evaluating) null else state.status?.status?.name?.lowercase()
    val statusText = if (state.evaluating) "Updating" else statusName?.let(::statusLabel) ?: "Unavailable"
    val tone = statusName?.let(::statusColor) ?: MaterialTheme.colorScheme.outlineVariant
    val currentAlerts = state.currentAlerts.sortedBy { severityOrder(it.alert.level) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(text = statusText, color = tone)
            }

            when {
                state.evaluating -> {
                    Text(
                        text = "Updating current status…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.status == null -> {
                    Text(
                        text = "Unable to assess current status.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                statusName == "normal" -> {
                    Text(
                        text = "No rule alerts in the current data window.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                statusName == "insufficient_data" -> {
                    Text(
                        text = "Not enough recent readings to assess status.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                currentAlerts.isEmpty() -> {
                    Text(
                        text = "No current rule details are available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    currentAlerts.forEachIndexed { index, displayAlert ->
                        if (index > 0) HorizontalDivider()
                        CurrentAlertDetails(
                            displayAlert = displayAlert,
                            onAcknowledge = onAcknowledge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentAlertDetails(
    displayAlert: DisplayAlert,
    onAcknowledge: (String) -> Unit,
) {
    val alert = displayAlert.alert
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(
                text = alert.level,
                color = statusColor(alert.level.lowercase()),
            )
            Spacer(Modifier.width(8.dp))
            if (alert.isSynthetic) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Demo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = alert.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = "Reason: ${alert.explanation}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Action: ${alert.recommendedAction}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AcknowledgeControl(
            alert = alert,
            savedAlertId = displayAlert.savedAlertId,
            onAcknowledge = onAcknowledge,
        )
    }
}

@Composable
private fun AcknowledgeControl(
    alert: AlertEntity,
    savedAlertId: String?,
    onAcknowledge: (String) -> Unit,
) {
    when {
        savedAlertId == null -> {
            TextButton(onClick = {}, enabled = false) {
                Text("Acknowledge")
            }
            Text(
                text = "Not yet recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        alert.acknowledgedAt != null -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Acknowledged",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            TextButton(onClick = { onAcknowledge(savedAlertId) }) {
                Text("Acknowledge")
            }
        }
    }
}

@Composable
private fun TrendsHeader(trendsState: TrendsState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        OverviewSectionTitle("Trends")
        val window = trendsState.report?.window
        val range = when {
            window != null -> "${Format.dayLabel(window.from)} – ${Format.dayLabel(window.to)}"
            trendsState.windowLabel.isNotBlank() -> trendsState.windowLabel
            else -> ""
        }
        if (range.isNotBlank()) {
            Text(
                text = range,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmergencyCard(onOpenEmergency: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(23.dp),
                )
                Text(
                    text = "Emergency",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = onOpenEmergency,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open emergency check")
            }
        }
    }
}

@Composable
private fun ReadingCard(reading: LatestReading, modifier: Modifier = Modifier) {
    val accent = metricAccent(reading.metric)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.62f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = reading.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(7.dp))
                MetricGlyph(metric = reading.metric, tint = accent)
            }
            if (reading.valueText == null) {
                Text(
                    text = "No data",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ReadingValue(reading.valueText)
            }
            Spacer(Modifier.weight(1f))
            if (reading.isSynthetic) {
                Text(
                    text = "Demo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reading.observedAt?.let { observedAt ->
                Text(
                    text = Format.shortDateTime(observedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricGlyph(metric: String, tint: Color) {
    val glyph = when (metric) {
        "heart_rate" -> "HR"
        "blood_pressure" -> "BP"
        "spo2" -> "O₂"
        "temperature" -> "°"
        "sleep" -> "Zz"
        else -> "+"
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

/** Preserve every formatted part while placing the unit below the number. */
@Composable
private fun ReadingValue(valueText: String) {
    val components = valueText.split(" · ")
    val primary = components.firstOrNull().orEmpty()
    val separatorIndex = primary.indexOf(' ')
    val number = if (separatorIndex >= 0) primary.substring(0, separatorIndex) else primary
    val unit = if (separatorIndex >= 0) primary.substring(separatorIndex + 1) else ""
    Text(
        text = number,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = 26.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
    val details = (listOf(unit).filter { it.isNotBlank() } + components.drop(1))
        .joinToString(" · ")
    if (details.isNotBlank()) {
        Text(
            text = details,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviousAlertsCard(
    alerts: List<AlertEntity>,
    onAcknowledge: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Previous alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                if (alerts.isEmpty()) {
                    Text(
                        text = "No previous alerts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    alerts.forEachIndexed { index, alert ->
                        if (index > 0) HorizontalDivider()
                        HistoricalAlertDetails(alert, onAcknowledge)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricalAlertDetails(
    alert: AlertEntity,
    onAcknowledge: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(text = alert.level, color = statusColor(alert.level.lowercase()))
            Spacer(Modifier.width(8.dp))
            Text(
                text = Format.shortDateTime(alert.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (alert.isSynthetic) {
                Text(
                    text = "Demo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = alert.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = alert.explanation, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Action: ${alert.recommendedAction}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (alert.acknowledgedAt == null) {
            TextButton(onClick = { onAcknowledge(alert.id) }) {
                Text("Acknowledge")
            }
        } else {
            Text(
                text = "Acknowledged ${Format.shortDateTime(alert.acknowledgedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    StatusPill(text = text, color = color)
}

@Composable
private fun OverviewSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InsightCard(insight: Insight, stableKey: String) {
    var detailsExpanded by rememberSaveable(stableKey) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = insight.severity,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(insight.text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Hide details" else "Details")
            }
            if (detailsExpanded) {
                Text(
                    text = "Rule: ${insight.ruleId} · Field: ${insight.field}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "Diagnostics (${diagnostics.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide diagnostics" else "Show diagnostics")
            }
            if (expanded) {
                diagnostics.forEachIndexed { index, line ->
                    CareBulletStep(index = index + 1, text = line)
                }
            }
        }
    }
}

@Composable
private fun SafetyInformationCard(disclaimer: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val disclaimerText = disclaimer.ifBlank {
        "AstroCare provides general decision support only. It does not diagnose " +
                "or treat any condition and does not replace a flight surgeon or " +
                "qualified medical professional."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Safety information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Decision support only. Not a medical diagnosis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide safety information" else "Show safety information")
            }
            if (expanded) {
                Text(
                    text = disclaimerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "critical" -> "Critical"
    "warning" -> "Warning"
    "normal" -> "Normal"
    "insufficient_data" -> "Insufficient data"
    else -> "Unavailable"
}

private fun statusColor(status: String): Color = when (status) {
    "critical" -> StatusCritical
    "warning" -> StatusWarning
    "normal" -> StatusNormal
    "insufficient_data" -> StatusInsufficient
    else -> StatusInsufficient
}

private fun severityOrder(level: String): Int = when (level.lowercase()) {
    "critical" -> 0
    "warning" -> 1
    else -> 2
}

@Composable
private fun metricAccent(metric: String): Color {
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (metric) {
        "heart_rate", "spo2" -> if (darkSurface) Color(0xFF69DBE5) else Color(0xFF087E8B)
        else -> if (darkSurface) Color(0xFFB2A5F7) else Color(0xFF6654A8)
    }
}

private fun insightKey(insights: List<Insight>, index: Int, insight: Insight): String {
    val base = "${insight.ruleId}:${insight.field}"
    val duplicateCount = insights.count { it.ruleId == insight.ruleId && it.field == insight.field }
    return if (duplicateCount > 1) "$base:$index" else base
}
