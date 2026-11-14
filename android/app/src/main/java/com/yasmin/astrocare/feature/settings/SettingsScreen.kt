package com.yasmin.astrocare.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yasmin.astrocare.core.common.Format
import com.yasmin.astrocare.core.notifications.ReminderNotifications
import com.yasmin.astrocare.core.ui.CareCard
import com.yasmin.astrocare.core.ui.CareHeader
import com.yasmin.astrocare.core.ui.CareMessage
import com.yasmin.astrocare.core.ui.CareSectionTitle
import com.yasmin.astrocare.data.contract.ContractProvenance
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.ReminderSettings
import kotlin.math.roundToInt

/**
 * Settings: local identity and environment, optional sync, reminders, runtime
 * permissions, pending/rejected status, and expandable contract provenance.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()

    var addScheduleExpanded by rememberSaveable { mutableStateOf(false) }
    var addTarget by rememberSaveable { mutableStateOf(ReminderNotifications.TARGET_ALL) }
    var addTime by rememberSaveable { mutableStateOf("08:00") }
    var addDaysDraft by rememberSaveable { mutableStateOf("1,2,3,4,5") }
    var addLabel by rememberSaveable { mutableStateOf("") }
    var showRejectionDetails by rememberSaveable { mutableStateOf(false) }

    val addDays = addDaysDraft
        .split(',')
        .mapNotNull { it.toIntOrNull() }
        .filter { it in 1..7 }
        .toSet()

    // Re-check runtime permissions when the screen appears.
    LaunchedEffect(Unit) { viewModel.refreshPermissions() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CareHeader(title = "Settings")
        }

        if (state.loading) {
            item {
                CareMessage(
                    title = "Loading settings",
                    message = "Reading local settings and contract information…",
                )
            }
        }

        state.error?.let { error ->
            item {
                CareMessage(
                    title = "Settings error",
                    message = error,
                    tone = MaterialTheme.colorScheme.error,
                )
            }
        }

        val settings: AppSettings? = state.settings

        item {
            CareCard {
                CareSectionTitle(title = "Astronaut & environment")
                InfoRow("Astronaut ID", settings?.astronautId ?: "—")
                InfoRow("Device ID", settings?.deviceId?.takeIf { it.isNotBlank() } ?: "—")
                InfoRow("Language", settings?.language ?: "en")
                Text(
                    text = "Environment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.environmentOptions.isEmpty()) {
                    Text(
                        text = "No environment options loaded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.environmentOptions.forEach { (id, label) ->
                            val selected = id == settings?.environment
                            OutlinedButton(onClick = { viewModel.onEnvironmentChange(id) }) {
                                Text(
                                    text = label,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else null,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            CareCard {
                CareSectionTitle(title = "Sync")
                if (settings == null) {
                    Text(
                        text = if (state.loading) {
                            "Sync settings are loading…"
                        } else {
                            "Sync settings are not available."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SettingSwitchRow(
                        label = "Sync enabled",
                        checked = settings.sync.enabled,
                        onCheckedChange = viewModel::onSyncEnabledChange,
                    )
                    SettingSwitchRow(
                        label = "Wi-Fi only",
                        checked = settings.sync.wifiOnly,
                        onCheckedChange = viewModel::onWifiOnlyChange,
                    )
                    OutlinedTextField(
                        value = state.baseUrlDraft,
                        onValueChange = viewModel::onBaseUrlChange,
                        label = { Text("Base URL") },
                        supportingText = { Text("Upload and download saved history") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.apiKeyDraft,
                        onValueChange = viewModel::onApiKeyChange,
                        label = { Text("API key (demo)") },
                        supportingText = { Text("Stored locally; not a real secret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Sync interval: ${state.intervalDraft} min",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.intervalDraft.toFloat(),
                        onValueChange = { viewModel.onIntervalChange(it.roundToInt()) },
                        valueRange = 15f..1440f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = viewModel::saveConnectionSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save")
                    }
                    val lastSync = settings.sync.lastSyncAt?.let { Format.shortDateTime(it) } ?: "never"
                    Text(
                        text = if (state.saved) {
                            "Saved. Last sync: $lastSync (${settings.sync.lastSyncResult.name})"
                        } else {
                            "Last sync: $lastSync (${settings.sync.lastSyncResult.name})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.saved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    HorizontalDivider()
                    state.syncNowMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::syncNow,
                        enabled = settings.sync.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sync now")
                    }
                    if (!settings.sync.enabled) {
                        Text(
                            text = "Enable sync to upload pending rows and download server history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Keep the stateless upload/download diagnostics available exactly once.
        item { SyncStatusContent(state.syncDiagnostics) }

        item {
            val context = LocalContext.current
            CareCard {
                CareSectionTitle(title = "Notifications & alarms")
                if (!state.notificationsGranted) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { viewModel.refreshPermissions() }
                    Text(
                        text = "Notification permission is not granted; reminders cannot be displayed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enable notifications")
                    }
                } else {
                    Text(
                        text = "Notifications: allowed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.exactAlarmsAllowed) {
                    Text(
                        text = "Exact alarms are not permitted; reminders may be slightly delayed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                runCatching { context.startActivity(intent) }
                                    .onFailure { viewModel.refreshPermissions() }
                            } else {
                                viewModel.refreshPermissions()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Allow exact alarms")
                    }
                } else {
                    Text(
                        text = "Exact alarms: allowed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            CareCard {
                CareSectionTitle(title = "Pending sync")
                val pending = state.pending
                if (pending == null) {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    InfoRow("Measurements", pending.measurements.toString())
                    InfoRow("Check-ins", pending.checkIns.toString())
                    InfoRow("Alerts", pending.alerts.toString())
                    Text(
                        text = "Counts come from the local database and are never sent as data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            val rejected = state.rejectedItems
            CareCard(tone = if (rejected.isNotEmpty()) MaterialTheme.colorScheme.error else null) {
                CareSectionTitle(title = "Rejected sync items")
                if (rejected.isEmpty()) {
                    Text(
                        text = "No rejected items.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rejected.take(MAX_REJECTED_SHOWN).forEachIndexed { index, rejectedItem ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${rejectedItem.collection.displayName}: ${rejectedItem.label}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = rejectedItem.error ?: "No error details returned",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = rejectedItem.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < minOf(rejected.size, MAX_REJECTED_SHOWN) - 1) {
                            HorizontalDivider()
                        }
                    }
                    if (rejected.size > MAX_REJECTED_SHOWN) {
                        Text(
                            text = "Showing $MAX_REJECTED_SHOWN of ${rejected.size} rejected rows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showRejectionDetails = !showRejectionDetails },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showRejectionDetails) "Hide details" else "Details")
                }
                if (showRejectionDetails) {
                    Text(
                        text = "Permanent validation errors are never retried automatically. " +
                                "The rows stay on this device for inspection and are excluded " +
                                "from future sync batches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            CareCard {
                CareSectionTitle(
                    title = "Reminders",
                    subtitle = "Schedules open Data Entry when tapped.",
                )
                val reminders = settings?.reminders.orEmpty()
                if (reminders.isEmpty()) {
                    Text(
                        text = "No reminder schedules configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    reminders.forEachIndexed { index, reminder ->
                        ReminderEditorRow(
                            reminder = reminder,
                            onToggle = { viewModel.setReminderEnabled(reminder.id, it) },
                            onRemove = { viewModel.removeReminder(reminder.id) },
                        )
                        if (index < reminders.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }

                HorizontalDivider()
                if (!addScheduleExpanded) {
                    OutlinedButton(
                        onClick = { addScheduleExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add reminder")
                    }
                } else {
                    CareSectionTitle(title = "Add schedule")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.reminderTargets.forEach { target ->
                            val selected = target == addTarget
                            OutlinedButton(onClick = { addTarget = target }) {
                                Text(
                                    text = target.replace('_', ' '),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else null,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DAY_LABELS.forEach { (isoDay, letter) ->
                            val selected = isoDay in addDays
                            OutlinedButton(
                                onClick = {
                                    val nextDays = if (selected) addDays - isoDay else addDays + isoDay
                                    addDaysDraft = nextDays.sorted().joinToString(",")
                                },
                            ) {
                                Text(
                                    text = letter,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (selected) FontWeight.SemiBold else null,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = addTime,
                        onValueChange = { addTime = it },
                        label = { Text("Time (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = addLabel,
                        onValueChange = { addLabel = it },
                        label = { Text("Label (optional, max 60 chars)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.addReminder(addTarget, addTime, addDays, addLabel)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add reminder")
                    }
                    OutlinedButton(
                        onClick = { addScheduleExpanded = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel")
                    }
                    Text(
                        text = "Up to ${SettingsViewModel.MAX_REMINDERS} schedules. Alarms are restored after reboot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Provenance and the medical/seed safety disclaimer live at the bottom.
        state.provenance?.let { provenance ->
            item { ProvenanceCard(provenance) }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProvenanceCard(provenance: ContractProvenance) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CareCard {
        CareSectionTitle(
            title = "Data & safety details",
            subtitle = "Contract versions, seed metadata, and the safety notice.",
        )
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (expanded) "Hide details" else "Show details")
        }
        if (expanded) {
            CareSectionTitle(title = "Contract provenance")
            InfoRow("Contract", "${provenance.contractVersion} · schema ${provenance.schemaVersion}")
            InfoRow("Rules", "${provenance.ruleSetVersion} (effective ${provenance.ruleEffectiveFrom})")
            InfoRow("Guides", provenance.guideSetVersion)
            InfoRow("Protocol", "${provenance.protocolId} ${provenance.protocolVersion}")
            HorizontalDivider()
            InfoRow("Seed", provenance.seedVersion)
            InfoRow("Seed label", provenance.seedLabel)
            InfoRow("Seed generated", provenance.seedGeneratedAt)
            InfoRow("Seed data end", provenance.seedEndDate)
            InfoRow("Seed astronaut", provenance.seedAstronautId)
            InfoRow("Seed environment", provenance.seedEnvironment)
            HorizontalDivider()
            Text(
                text = provenance.disclaimer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReminderEditorRow(
    reminder: ReminderSettings,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = reminder.label.ifBlank { reminder.target.replace('_', ' ') },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "${reminder.time} · days ${reminder.daysOfWeek.joinToString()} · " +
                    reminder.target.replace('_', ' '),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = reminder.enabled,
                onCheckedChange = onToggle,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove reminder ${reminder.label.ifBlank { reminder.target }}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ISO day-of-week letters: 1=Monday … 7=Sunday (settings.schema.json). */
private val DAY_LABELS = listOf(
    1 to "M",
    2 to "T",
    3 to "W",
    4 to "T",
    5 to "F",
    6 to "S",
    7 to "S",
)

/** Rejected rows are capped in the UI; the full list stays inspectable in Room. */
private const val MAX_REJECTED_SHOWN = 20
