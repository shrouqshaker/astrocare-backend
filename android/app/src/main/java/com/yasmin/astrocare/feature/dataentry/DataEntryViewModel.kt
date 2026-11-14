package com.yasmin.astrocare.feature.dataentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yasmin.astrocare.data.guide.GuideRepository
import com.yasmin.astrocare.data.health.EvaluationOutcome
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.domain.guide.Guide
import com.yasmin.astrocare.domain.health.MeasurementValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One form field, described entirely from contracts/enums.json. */
data class FieldFormDef(
    val field: String,
    val label: String,
    val unit: String,
    val unitDisplay: String,
    val min: Double,
    val max: Double,
    val type: String,
)

data class MetricForm(
    val inputs: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val note: String = "",
)

data class SaveUiResult(
    val outcome: EvaluationOutcome,
    val message: String,
)

data class DataEntryState(
    val loading: Boolean = true,
    /** metric id -> label (contracts/enums.json), in contract order. */
    val metricOptions: List<Pair<String, String>> = emptyList(),
    /** metric id -> ordered form fields (enums.json). */
    val fieldDefs: Map<String, List<FieldFormDef>> = emptyMap(),
    val symptomOptions: List<String> = emptyList(),
    val selectedMetric: String = "heart_rate",
    val forms: Map<String, MetricForm> = emptyMap(),
    val saving: Boolean = false,
    val saveResult: SaveUiResult? = null,
    val saveError: String? = null,
    val symptoms: Set<String> = emptySet(),
    val checkInSaving: Boolean = false,
    val checkInResult: String? = null,
    val checkInError: String? = null,
    val guide: Guide? = null,
)

/**
 * Data Entry state: measurement forms (validated against contracts/enums.json
 * BEFORE reaching Room) and the symptom check-in. Every save runs the offline
 * evaluation pipeline and surfaces overall status + any new alerts.
 */
@HiltViewModel
class DataEntryViewModel @Inject constructor(
    private val healthRepository: HealthRepository,
    private val guideRepository: GuideRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DataEntryState())
    val state: StateFlow<DataEntryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val catalog = healthRepository.catalog()
                val guideSet = guideRepository.guideSet()
                val defs = catalog.metrics.mapValues { (metricId, metricDef) ->
                    metricDef.fields.map { (field, fieldDef) ->
                        FieldFormDef(
                            field = field,
                            label = catalog.labelFor("$metricId.$field"),
                            unit = fieldDef.unit,
                            unitDisplay = catalog.displayUnit(fieldDef.unit),
                            min = fieldDef.min,
                            max = fieldDef.max,
                            type = fieldDef.type,
                        )
                    }
                }
                _state.update {
                    it.copy(
                        loading = false,
                        metricOptions = catalog.metrics.map { (id, def) -> id to def.label },
                        fieldDefs = defs,
                        symptomOptions = catalog.symptoms,
                        guide = guideSet.forMetric(it.selectedMetric),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, saveError = e.message) }
            }
        }
    }

    fun selectMetric(metric: String) {
        if (metric == _state.value.selectedMetric && _state.value.guide != null) return
        _state.update {
            it.copy(selectedMetric = metric, saveResult = null, saveError = null)
        }
        viewModelScope.launch {
            val guide = runCatching { guideRepository.guideForMetric(metric) }.getOrNull()
            _state.update { it.copy(guide = guide) }
        }
    }

    fun onFieldChange(field: String, value: String) {
        _state.update { state ->
            val metric = state.selectedMetric
            val form = state.forms[metric] ?: MetricForm()
            state.copy(
                forms = state.forms + (metric to form.copy(
                    inputs = form.inputs + (field to value),
                    errors = form.errors - field,
                )),
                saveResult = null,
                saveError = null,
            )
        }
    }

    fun onNoteChange(note: String) {
        _state.update { state ->
            val metric = state.selectedMetric
            val form = state.forms[metric] ?: MetricForm()
            state.copy(forms = state.forms + (metric to form.copy(note = note)))
        }
    }

    /** Parses inputs, validates against contract bounds, saves, evaluates. */
    fun saveMeasurement() {
        val snapshot = _state.value
        val metric = snapshot.selectedMetric
        val form = snapshot.forms[metric] ?: MetricForm()

        viewModelScope.launch {
            val catalog = healthRepository.catalog()

            val parseErrors = LinkedHashMap<String, String>()
            val values = LinkedHashMap<String, Double>()
            val fieldDefs = snapshot.fieldDefs[metric].orEmpty()
            for (def in fieldDefs) {
                val text = form.inputs[def.field].orEmpty().trim()
                if (text.isEmpty()) {
                    parseErrors[def.field] = "Required"
                    continue
                }
                val parsed = text.toDoubleOrNull()
                if (parsed == null) {
                    parseErrors[def.field] = "Enter a number"
                } else {
                    values[def.field] = parsed
                }
            }
            val contractProblems = if (parseErrors.isEmpty()) {
                MeasurementValidator.validate(catalog, metric, values)
            } else {
                emptyList()
            }
            if (parseErrors.isNotEmpty() || contractProblems.isNotEmpty()) {
                _state.update {
                    val formNow = it.forms[metric] ?: MetricForm()
                    it.copy(
                        forms = it.forms + (metric to formNow.copy(errors = parseErrors)),
                        saveError = contractProblems.joinToString("\n").ifBlank { null },
                    )
                }
                return@launch
            }

            _state.update { it.copy(saving = true, saveError = null, saveResult = null) }
            try {
                val outcome = healthRepository.saveMeasurement(
                    metric = metric,
                    values = values,
                    note = form.note,
                )
                _state.update {
                    it.copy(
                        saving = false,
                        saveResult = SaveUiResult(
                            outcome = outcome,
                            message = buildSaveMessage(outcome),
                        ),
                        forms = it.forms + (metric to MetricForm()),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, saveError = e.message ?: "Save failed") }
            }
        }
    }

    /** Toggles a symptom chip; "none" is exclusive (contract rule). */
    fun toggleSymptom(symptom: String) {
        _state.update { state ->
            val next = if (symptom in state.symptoms) {
                state.symptoms - symptom
            } else if (symptom == NONE_SYMPTOM) {
                setOf(NONE_SYMPTOM)
            } else {
                (state.symptoms - NONE_SYMPTOM) + symptom
            }
            state.copy(symptoms = next, checkInResult = null, checkInError = null)
        }
    }

    /** Plain symptom check-in — chest-pain triage is saved by the emergency flow. */
    fun saveCheckIn() {
        val symptoms = _state.value.symptoms.toList()
        _state.update { it.copy(checkInSaving = true, checkInError = null, checkInResult = null) }
        viewModelScope.launch {
            try {
                val outcome = healthRepository.saveCheckIn(symptoms = symptoms)
                _state.update {
                    it.copy(
                        checkInSaving = false,
                        checkInResult = buildSaveMessage(outcome),
                        symptoms = emptySet(),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(checkInSaving = false, checkInError = e.message ?: "Save failed")
                }
            }
        }
    }

    fun dismissResults() {
        _state.update {
            it.copy(saveResult = null, saveError = null, checkInResult = null, checkInError = null)
        }
    }

    private fun buildSaveMessage(outcome: EvaluationOutcome): String = buildString {
        append("Saved — status: ${outcome.overallStatus.status.name}")
        if (outcome.newAlerts.isEmpty()) {
            append(" · no new alerts")
        } else {
            append(" · ${outcome.newAlerts.size} new alert(s): ")
            append(outcome.newAlerts.joinToString { "${it.title} (${it.level})" })
        }
        append(" · evaluated in ${outcome.elapsedMillis} ms")
    }

    private companion object {
        const val NONE_SYMPTOM = "none"
    }
}
