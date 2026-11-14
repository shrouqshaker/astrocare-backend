package com.yasmin.astrocare.feature.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yasmin.astrocare.data.emergency.ChestPainProtocolRepository
import com.yasmin.astrocare.data.health.HealthRepository
import com.yasmin.astrocare.domain.emergency.AnsweredQuestion
import com.yasmin.astrocare.domain.emergency.ChestPainProtocol
import com.yasmin.astrocare.domain.emergency.ChestPainTriage
import com.yasmin.astrocare.domain.emergency.TriageAnswer
import com.yasmin.astrocare.domain.emergency.TriageOutcome
import com.yasmin.astrocare.domain.emergency.outcomeFor
import com.yasmin.astrocare.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EmergencyStep { SAFETY, QUESTIONS, OUTCOME }

data class EmergencyState(
    val loading: Boolean = true,
    val protocol: ChestPainProtocol? = null,
    /** Contact line for the configured environment (iss | spacecraft | moon_base). */
    val contactText: String = "",
    val step: EmergencyStep = EmergencyStep.SAFETY,
    val questionIndex: Int = 0,
    val answers: Map<String, TriageAnswer> = emptyMap(),
    val outcome: TriageOutcome? = null,
    val checkInSaved: Boolean = false,
    val error: String? = null,
)

/**
 * Chest-pain emergency flow — FULLY OFFLINE (protocol asset only, no network,
 * no LLM). Order is binding: immediate safety instructions FIRST (step
 * [EmergencyStep.SAFETY], no question gating), then at most
 * `maxQuestions` questions, then the outcome.
 *
 * The outcome is DATA on the saved check-in (`chestPainTriage`), never an
 * alert level (docs/DECISIONS.md §2): threshold/symptom alerts still come from
 * the normal rule engine when the check-in is saved.
 */
@HiltViewModel
class EmergencyViewModel @Inject constructor(
    private val protocolRepository: ChestPainProtocolRepository,
    private val healthRepository: HealthRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EmergencyState())
    val state: StateFlow<EmergencyState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val protocol = protocolRepository.protocol()
                val environment = settingsRepository.snapshot().environment
                _state.update {
                    it.copy(
                        loading = false,
                        protocol = protocol,
                        contactText = protocol.contactByEnvironment[environment]
                            ?: protocol.contactByEnvironment.values.firstOrNull().orEmpty(),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Protocol failed to load") }
            }
        }
    }

    /** Safety screen -> first question. Safety text stays visible throughout. */
    fun acknowledgeSafety() {
        _state.update { it.copy(step = EmergencyStep.QUESTIONS, questionIndex = 0) }
    }

    fun answerCurrent(answer: TriageAnswer) {
        val state = _state.value
        val protocol = state.protocol ?: return
        val question = protocol.questions.getOrNull(state.questionIndex) ?: return
        val answers = state.answers + (question.id to answer)
        val nextIndex = state.questionIndex + 1
        if (nextIndex >= protocol.questions.size) {
            _state.update { it.copy(answers = answers) }
            submit(answers, protocol)
        } else {
            _state.update { it.copy(answers = answers, questionIndex = nextIndex) }
        }
    }

    fun back() {
        _state.update { state ->
            if (state.step == EmergencyStep.OUTCOME) {
                // outcome is final — safety stays visible, back restarts questions
                state.copy(step = EmergencyStep.SAFETY, questionIndex = 0, answers = emptyMap(), outcome = null)
            } else if (state.questionIndex > 0) {
                val protocol = state.protocol
                val previous = protocol?.questions?.getOrNull(state.questionIndex - 1)
                state.copy(
                    questionIndex = state.questionIndex - 1,
                    answers = previous?.let { state.answers - it.id } ?: state.answers,
                )
            } else {
                state.copy(step = EmergencyStep.SAFETY)
            }
        }
    }

    /**
     * Computes the contract outcome (red-flag yes/unsure -> emergency_now),
     * saves the check-in with `chestPainTriage` and runs the normal evaluation
     * (symptom rules may fire — their level comes from the RULE, not triage).
     */
    private fun submit(answers: Map<String, TriageAnswer>, protocol: ChestPainProtocol) {
        viewModelScope.launch {
            try {
                val answered = protocol.questions.mapNotNull { q ->
                    answers[q.id]?.let { AnsweredQuestion(q.id, it) }
                }
                val outcome = protocol.outcomeFor(answered)
                val triage = ChestPainTriage(
                    protocolId = protocol.protocolId,
                    answers = answered,
                    outcome = outcome,
                )
                healthRepository.saveCheckIn(symptoms = listOf(CHEST_PAIN_SYMPTOM), triage = triage)
                _state.update {
                    it.copy(step = EmergencyStep.OUTCOME, outcome = outcome, checkInSaved = true, error = null)
                }
            } catch (e: Exception) {
                // Never hide safety: show the outcome computed locally even if the
                // check-in write failed, and surface the error alongside it.
                val outcome = protocol.outcomeFor(
                    protocol.questions.mapNotNull { q -> answers[q.id]?.let { AnsweredQuestion(q.id, it) } },
                )
                _state.update {
                    it.copy(
                        step = EmergencyStep.OUTCOME,
                        outcome = outcome,
                        checkInSaved = false,
                        error = e.message ?: "Check-in could not be saved",
                    )
                }
            }
        }
    }

    fun reset() {
        _state.update {
            it.copy(step = EmergencyStep.SAFETY, questionIndex = 0, answers = emptyMap(), outcome = null, error = null)
        }
    }

    private companion object {
        const val CHEST_PAIN_SYMPTOM = "chest_pain"
    }
}
