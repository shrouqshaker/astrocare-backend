package com.yasmin.astrocare.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yasmin.astrocare.core.common.AppDefaults
import com.yasmin.astrocare.core.notifications.ReminderNotifications
import com.yasmin.astrocare.data.contract.ContractProvenance
import com.yasmin.astrocare.data.contract.ProvenanceRepository
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.data.health.PendingCounts
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.data.sync.RejectedItem
import com.yasmin.astrocare.data.sync.RejectedItemsRepository
import com.yasmin.astrocare.data.sync.SyncScheduler
import com.yasmin.astrocare.data.remote.HistoryClient
import com.yasmin.astrocare.data.sync.SyncDiagnostics
import com.yasmin.astrocare.data.sync.SyncDiagnosticsRepository
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.ReminderSettings
import com.yasmin.astrocare.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val loading: Boolean = true,
    val settings: AppSettings? = null,
    val provenance: ContractProvenance? = null,
    val pending: PendingCounts? = null,
    /** Draft fields — committed with Save (single writer via DataStore edit). */
    val baseUrlDraft: String = "",
    val apiKeyDraft: String = "",
    val intervalDraft: Int = AppDefaults.DEFAULT_SYNC_INTERVAL_MINUTES,
    /** id -> label from enums.json `environments` (Settings picker). */
    val environmentOptions: List<Pair<String, String>> = emptyList(),
    /** Reminder targets: contract `all` + enums.json metric ids. */
    val reminderTargets: List<String> = emptyList(),
    /** Permanently rejected sync rows (read-only inspection; never retried). */
    val rejectedItems: List<RejectedItem> = emptyList(),
    /** Runtime permission state (re-checked on every composition). */
    val notificationsGranted: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    /** Feedback for the manual "Sync now" action. */
    val syncNowMessage: String? = null,
    val syncDiagnostics: SyncDiagnostics? = null,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * Settings state: app-local settings (DataStore, never synced), bundled
 * contract provenance (versions + synthetic seed label), Room-derived
 * pending-sync counts, reminder schedule editing and manual sync trigger.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val provenanceRepository: ProvenanceRepository,
    private val healthRepository: HealthRepository,
    private val ruleSetRepository: RuleSetRepository,
    private val syncScheduler: SyncScheduler,
    private val rejectedItemsRepository: RejectedItemsRepository,
    private val syncDiagnosticsRepository: SyncDiagnosticsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        loading = false,
                        settings = settings,
                        baseUrlDraft = if (it.baseUrlDraft.isBlank()) settings.sync.baseUrl else it.baseUrlDraft,
                        apiKeyDraft = if (it.apiKeyDraft.isBlank()) settings.sync.apiKey else it.apiKeyDraft,
                        intervalDraft = if (it.saved) it.intervalDraft else settings.sync.intervalMinutes,
                        saved = false,
                        syncDiagnostics = it.syncDiagnostics?.takeIf { d -> d.belongsTo(settings.sync.baseUrl, settings.astronautId) },
                    )
                }
            }
        }
        viewModelScope.launch {
            runCatching { provenanceRepository.load() }.fold(
                onSuccess = { p -> _state.update { it.copy(provenance = p) } },
                onFailure = { e -> _state.update { it.copy(error = e.message) } },
            )
        }
        viewModelScope.launch {
            runCatching { ruleSetRepository.contractCatalog() }.fold(
                onSuccess = { catalog ->
                    _state.update {
                        it.copy(
                            environmentOptions = catalog.environments.map { env -> env.id to env.label },
                            reminderTargets = listOf(ReminderNotifications.TARGET_ALL) +
                                catalog.metrics.keys.toList(),
                        )
                    }
                },
                onFailure = { e -> _state.update { it.copy(error = e.message) } },
            )
        }
        viewModelScope.launch {
            rejectedItemsRepository.observe().collect { items ->
                _state.update { it.copy(rejectedItems = items) }
            }
        }
        viewModelScope.launch {
            syncDiagnosticsRepository.diagnostics.collect { diagnostics ->
                _state.update { current ->
                    val settings = current.settings
                    current.copy(
                        syncDiagnostics = diagnostics.takeIf {
                            settings == null || it.belongsTo(settings.sync.baseUrl, settings.astronautId)
                        },
                        syncNowMessage = if (diagnostics.attemptedAt != null) null else current.syncNowMessage,
                    )
                }
                refreshPending()
            }
        }
        refreshPending()
        refreshPermissions()
    }

    /** Runtime permission state — called again whenever the screen recomposes. */
    fun refreshPermissions() {
        _state.update {
            it.copy(
                notificationsGranted = ReminderNotifications.canPost(context),
                exactAlarmsAllowed = com.yasmin.astrocare.feature.reminders.ReminderScheduler
                    .canScheduleExactAlarms(context),
            )
        }
    }

    fun refreshPending() {
        viewModelScope.launch {
            runCatching { healthRepository.pendingCounts() }.fold(
                onSuccess = { counts -> _state.update { it.copy(pending = counts) } },
                onFailure = { e -> _state.update { it.copy(error = e.message) } },
            )
        }
    }

    // ---------------------------------------------------------------- sync

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrlDraft = value, saved = false) }

    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKeyDraft = value, saved = false) }

    fun onIntervalChange(value: Int) = _state.update {
        it.copy(intervalDraft = value.coerceAtLeast(AppDefaults.MIN_SYNC_INTERVAL_MINUTES), saved = false)
    }

    fun onSyncEnabledChange(enabled: Boolean) = updateSettings { s ->
        s.copy(sync = s.sync.copy(enabled = enabled))
    }

    fun onWifiOnlyChange(wifiOnly: Boolean) = updateSettings { s ->
        s.copy(sync = s.sync.copy(wifiOnly = wifiOnly))
    }

    fun onEnvironmentChange(environment: String) = updateSettings { s ->
        s.copy(environment = environment)
    }

    /** Commits baseUrl + apiKey + interval drafts (Settings "Save" button). */
    fun saveConnectionSettings() {
        val state = _state.value
        val baseUrl = state.baseUrlDraft.trim()
        try {
            HistoryClient.canonicalBaseUrl(baseUrl)
        } catch (_: Exception) {
            _state.update { it.copy(error = "Base URL must be an HTTP(S) host or /api/v1 URL, without credentials, query or fragment.") }
            return
        }
        updateSettings { s ->
            s.copy(
                sync = s.sync.copy(
                    baseUrl = baseUrl.trimEnd('/'),
                    apiKey = state.apiKeyDraft.trim(),
                    intervalMinutes = state.intervalDraft
                        .coerceAtLeast(AppDefaults.MIN_SYNC_INTERVAL_MINUTES),
                ),
            )
        }
        _state.update { it.copy(saved = true, error = null) }
    }

    /** Manual one-shot sync (WorkManager; result shows via lastSyncResult). */
    fun syncNow() {
        val settings = _state.value.settings ?: return
        syncScheduler.syncNow(settings.sync.wifiOnly)
        _state.update { it.copy(syncNowMessage = "Upload + download requested — waiting for network.", error = null) }
    }

    fun dismissSyncNowMessage() = _state.update { it.copy(syncNowMessage = null) }

    // ----------------------------------------------------------- reminders

    /**
     * Adds one reminder schedule (settings.schema.json: max 12 total,
     * target in contract enum, time HH:mm, daysOfWeek ISO 1..7 non-empty,
     * label <= 60 chars). The alarm is applied by ReminderScheduler's settings
     * observer — single writer.
     */
    fun addReminder(target: String, time: String, daysOfWeek: Set<Int>, label: String) {
        val state = _state.value
        val error = validateReminder(
            state = state,
            target = target,
            time = time,
            daysOfWeek = daysOfWeek,
            label = label,
        )
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        val reminder = ReminderSettings(
            id = UUID.randomUUID().toString(),
            target = target,
            time = time.trim(),
            daysOfWeek = daysOfWeek.sorted(),
            enabled = true,
            label = label.trim(),
        )
        updateSettings { s -> s.copy(reminders = s.reminders + reminder) }
        _state.update { it.copy(error = null) }
    }

    fun setReminderEnabled(id: String, enabled: Boolean) = updateSettings { s ->
        s.copy(reminders = s.reminders.map { r -> if (r.id == id) r.copy(enabled = enabled) else r })
    }

    fun removeReminder(id: String) = updateSettings { s ->
        s.copy(reminders = s.reminders.filterNot { it.id == id })
    }

    private fun validateReminder(
        state: SettingsState,
        target: String,
        time: String,
        daysOfWeek: Set<Int>,
        label: String,
    ): String? = when {
        state.reminderTargets.isNotEmpty() && target !in state.reminderTargets ->
            "Unknown reminder target '$target'"
        !TIME_PATTERN.matches(time.trim()) ->
            "Time must be HH:mm (00:00-23:59)"
        daysOfWeek.isEmpty() || daysOfWeek.any { it !in 1..7 } ->
            "Pick at least one day of the week"
        label.trim().length > 60 ->
            "Label must be 60 characters or fewer"
        (state.settings?.reminders?.size ?: 0) >= MAX_REMINDERS ->
            "Maximum of $MAX_REMINDERS reminder schedules (settings.schema.json)"
        else -> null
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { settingsRepository.update(transform) }.fold(
                onFailure = { e -> _state.update { it.copy(error = e.message) } },
                onSuccess = { refreshPending() },
            )
        }
    }

    companion object {
        /** settings.schema.json reminders.maxItems. */
        const val MAX_REMINDERS = 12
        private val TIME_PATTERN = Regex("""^(?:[01]\d|2[0-3]):[0-5]\d$""")
    }
}
