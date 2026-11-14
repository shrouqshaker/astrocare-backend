package com.yasmin.astrocare.feature.dataentry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yasmin.astrocare.core.common.Format
import com.yasmin.astrocare.core.ui.CareCard
import com.yasmin.astrocare.core.ui.CareHeader
import com.yasmin.astrocare.core.ui.CareMetricBadge
import com.yasmin.astrocare.core.ui.CarePhoto
import com.yasmin.astrocare.core.ui.CareSectionTitle
import com.yasmin.astrocare.domain.guide.Guide

/**
 * Data Entry: metric picker, contract-described form fields, the bundled
 * text-first how-to guide, and the symptom check-in. The chest-pain protocol
 * shortcut remains visible here at all times.
 */
@Composable
fun DataEntryScreen(
    metric: String?,
    onOpenEmergency: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DataEntryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(metric) { metric?.let(viewModel::selectMetric) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CareHeader(
                title = "Data Entry",
                subtitle = "Record a measurement or a symptom check-in.",
            )
        }

        if (state.loading) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            CareCard {
                CareSectionTitle(
                    title = "Choose a measurement",
                    subtitle = "Select the metric you want to record.",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.metricOptions.forEach { (id, label) ->
                        val selected = id == state.selectedMetric
                        OutlinedButton(
                            onClick = { viewModel.selectMetric(id) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ),
                        ) {
                            Text(
                                text = label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        state.saveResult?.let { result ->
            item {
                ResultCard(
                    message = result.message,
                    onDismiss = viewModel::dismissResults,
                )
            }
        }
        state.saveError?.let { message ->
            item {
                ErrorBanner(message)
            }
        }

        val selectedMetric = state.selectedMetric
        val form = state.forms[selectedMetric] ?: MetricForm()
        val fieldDefs = state.fieldDefs[selectedMetric].orEmpty()

        item {
            MeasurementFormCard(
                metricLabel = state.metricOptions.firstOrNull { it.first == selectedMetric }?.second
                    ?: selectedMetric,
                metric = selectedMetric,
                fieldDefs = fieldDefs,
                form = form,
                saving = state.saving,
                onFieldChange = viewModel::onFieldChange,
                onNoteChange = viewModel::onNoteChange,
                onSave = viewModel::saveMeasurement,
            )
        }

        state.guide?.let { guide ->
            item {
                GuideCard(guide)
            }
        }

        item {
            CareSectionTitle(
                title = "Symptom check-in",
                subtitle = "Record the symptoms you are experiencing.",
            )
        }

        // Chest-pain shortcut — always visible.
        item {
            EmergencyShortcut(onOpenEmergency = onOpenEmergency)
        }

        state.checkInResult?.let { message ->
            item {
                ResultCard(
                    message = message,
                    onDismiss = viewModel::dismissResults,
                )
            }
        }
        state.checkInError?.let { message ->
            item {
                ErrorBanner(message)
            }
        }

        item {
            SymptomCheckInCard(
                symptomOptions = state.symptomOptions,
                selectedSymptoms = state.symptoms,
                saving = state.checkInSaving,
                onToggleSymptom = viewModel::toggleSymptom,
                onSave = viewModel::saveCheckIn,
            )
        }
        item {
            CarePhoto(
                assetName = "nasa_crew_health",
                description = "Astronaut Jack Hathaway handling research sample-processing hardware inside the ISS Destiny laboratory",
                credit = "NASA / Jessica Meir",
            )
        }
    }
}

@Composable
private fun MeasurementFormCard(
    metricLabel: String,
    metric: String,
    fieldDefs: List<FieldFormDef>,
    form: MetricForm,
    saving: Boolean,
    onFieldChange: (String, String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    CareCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CareMetricBadge(metric = metric)
            CareSectionTitle(
                title = metricLabel,
                subtitle = "Enter the values required for this measurement.",
                modifier = Modifier.weight(1f),
            )
        }

        fieldDefs.forEach { definition ->
            val error = form.errors[definition.field]
            OutlinedTextField(
                value = form.inputs[definition.field] ?: "",
                onValueChange = { onFieldChange(definition.field, it) },
                label = {
                    Text(
                        buildString {
                            append(definition.label)
                            if (definition.unitDisplay.isNotBlank()) {
                                append(" (")
                                append(definition.unitDisplay)
                                append(")")
                            }
                        },
                    )
                },
                isError = error != null,
                supportingText = {
                    if (error != null) {
                        Text(error)
                    } else {
                        Text("Allowed: ${definition.min}–${definition.max}")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = form.note,
            onValueChange = onNoteChange,
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        ) {
            Text(if (saving) "Saving…" else "Save & evaluate")
        }
    }
}

@Composable
private fun GuideCard(guide: Guide) {
    var detailsExpanded by rememberSaveable(guide.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // A quiet header, without another large metric badge or subtitle.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Measurement guide",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = guide.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GuideSectionLabel("How to measure")
                guide.howToMeasure.steps.forEachIndexed { index, step ->
                    GuideStepRow(number = index + 1, text = step)
                }
            }

            // This is a soft section within one card, not a stack of boxed cards.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GuideSectionLabel("App alert boundaries")
                    if (guide.referenceRange.summary.isNotBlank()) {
                        Text(
                            text = guide.referenceRange.summary,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    guide.referenceRange.rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        // Each field is identified separately (e.g. systolic and
                        // diastolic), instead of repeating a long generic label.
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = guideRangeFieldLabel(row.field, row.label),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = guideRangeValue(row.low, row.high, row.unit),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 19.sp,
                                    lineHeight = 27.sp,
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            if (guide.whyItMatters.isNotBlank() ||
                guide.referenceRange.note.isNotBlank() ||
                guide.referenceRange.rows.isNotEmpty()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                    ) {
                        Text(if (detailsExpanded) "Hide guide details" else "More about this guide")
                    }
                    if (detailsExpanded) {
                        if (guide.whyItMatters.isNotBlank()) {
                            GuideSectionLabel("Why it matters")
                            GuideBodyText(guide.whyItMatters)
                        }
                        if (guide.referenceRange.note.isNotBlank()) {
                            GuideSectionLabel("Range notes")
                            GuideBodyText(guide.referenceRange.note)
                        }
                        // Keep the source row labels available without repeating
                        // the same caution under every numeric field above.
                        guide.referenceRange.rows.map { it.label }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .forEach { label -> GuideBodyText(label) }
                    }
                }
            }

            if (guide.disclaimer.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = guide.disclaimer,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GuideSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = 16.sp,
            lineHeight = 23.sp,
        ),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun GuideStepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        )
    }
}

@Composable
private fun GuideBodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 23.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun guideRangeFieldLabel(field: String, fallback: String): String = when (field) {
    "heart_rate.bpm" -> "Heart rate"
    "blood_pressure.systolic" -> "Systolic pressure"
    "blood_pressure.diastolic" -> "Diastolic pressure"
    "spo2.percent" -> "Oxygen saturation"
    "temperature.celsius" -> "Body temperature"
    "sleep.hours" -> "Sleep duration"
    "sleep.quality" -> "Sleep quality"
    else -> fallback
}

private fun guideRangeValue(low: Double?, high: Double?, unit: String): String {
    val bounds = when {
        low != null && high != null -> "${Format.number(low)}–${Format.number(high)}"
        low != null -> "≥ ${Format.number(low)}"
        high != null -> "≤ ${Format.number(high)}"
        else -> "Not specified"
    }
    if (low == null && high == null) return bounds
    val symbol = when (unit) {
        "percent" -> "%"
        "celsius" -> "°C"
        "hours" -> "h"
        "score" -> "/5"
        else -> unit
    }
    return when {
        symbol.isBlank() -> bounds
        symbol == "/5" -> "$bounds/5"
        else -> "$bounds $symbol"
    }
}

@Composable
private fun EmergencyShortcut(onOpenEmergency: () -> Unit) {
    CareCard(tone = MaterialTheme.colorScheme.error) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Chest pain?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "Open the offline chest-pain check for immediate safety guidance before anything else.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onOpenEmergency,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text("Open emergency check")
        }
    }
}

@Composable
private fun SymptomCheckInCard(
    symptomOptions: List<String>,
    selectedSymptoms: Set<String>,
    saving: Boolean,
    onToggleSymptom: (String) -> Unit,
    onSave: () -> Unit,
) {
    CareCard {
        CareSectionTitle(
            title = "How are you feeling?",
            subtitle = "Select all that apply.",
        )
        symptomOptions.forEach { symptom ->
            val selected = symptom in selectedSymptoms
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSymptom(symptom) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = prettySymptom(symptom),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(
            onClick = onSave,
            enabled = !saving && selectedSymptoms.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        ) {
            Text(if (saving) "Saving…" else "Save check-in")
        }
        if (selectedSymptoms.isEmpty()) {
            Text(
                text = "Select at least one symptom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun prettySymptom(code: String): String =
    code.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

@Composable
private fun ResultCard(message: String, onDismiss: () -> Unit) {
    CareCard(tone = MaterialTheme.colorScheme.tertiary) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    CareCard(tone = MaterialTheme.colorScheme.error) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
