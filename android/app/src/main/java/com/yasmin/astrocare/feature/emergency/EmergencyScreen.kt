package com.yasmin.astrocare.feature.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yasmin.astrocare.core.ui.CareBulletStep
import com.yasmin.astrocare.core.ui.CareCard
import com.yasmin.astrocare.core.ui.CareMessage
import com.yasmin.astrocare.core.ui.CareSectionTitle
import com.yasmin.astrocare.domain.emergency.ChestPainProtocol
import com.yasmin.astrocare.domain.emergency.TriageAnswer
import com.yasmin.astrocare.domain.emergency.TriageOutcome
import com.yasmin.astrocare.ui.theme.StatusCritical
import com.yasmin.astrocare.ui.theme.StatusWarning

/**
 * Offline chest-pain flow: immediate safety instructions FIRST (visible in
 * every step), then at most `maxQuestions` questions, then the outcome with
 * the safety text still on screen. No network, no LLM — only the bundled
 * protocol asset.
 */
@Composable
fun EmergencyScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: EmergencyViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    EmergencyContent(
        state = state,
        onBack = viewModel::back,
        onClose = onClose,
        onAcknowledgeSafety = viewModel::acknowledgeSafety,
        onAnswer = viewModel::answerCurrent,
        onReset = viewModel::reset,
        modifier = modifier,
    )
}

/**
 * Stateless emergency flow — safety instructions are rendered before and in
 * every step. Compose UI tests render it with a fake [EmergencyState].
 */
@Composable
fun EmergencyContent(
    state: EmergencyState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onAcknowledgeSafety: () -> Unit,
    onAnswer: (TriageAnswer) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Chest-pain check",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .imePadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { error ->
                item {
                    CareMessage(
                        title = "Could not complete the last step",
                        message = error,
                        tone = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.loading) {
                item {
                    CareCard(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            val protocol = state.protocol
            if (protocol != null) {
                item {
                    CareSectionTitle(
                        title = "Immediate safety guidance",
                        subtitle = "Read this before answering any question.",
                    )
                }
                item {
                    SafetyCard(
                        instructions = protocol.immediateSafetyInstructions,
                        contactText = state.contactText,
                        disclaimer = protocol.disclaimer,
                    )
                }
            }

            when (state.step) {
                EmergencyStep.SAFETY -> item {
                    CareCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Read the safety steps above first. You can answer " +
                                    "${protocol?.questions?.size ?: 0} short questions next — " +
                                    "everything runs on this device.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onAcknowledgeSafety,
                            enabled = protocol != null && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("I understand — begin")
                        }
                        OutlinedButton(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Close")
                        }
                    }
                }

                EmergencyStep.QUESTIONS -> {
                    val question = protocol?.questions?.getOrNull(state.questionIndex)
                    if (question != null) {
                        item {
                            CareSectionTitle(
                                title = "Questions",
                                subtitle = "Answer each question to calculate the offline result.",
                            )
                        }
                        item {
                            QuestionCard(
                                questionNumber = state.questionIndex + 1,
                                questionCount = protocol.questions.size,
                                questionText = question.text,
                                onYes = { onAnswer(TriageAnswer.YES) },
                                onNo = { onAnswer(TriageAnswer.NO) },
                                onUnsure = { onAnswer(TriageAnswer.UNSURE) },
                                onBack = onBack,
                            )
                        }
                    }
                }

                EmergencyStep.OUTCOME -> {
                    val outcome = state.outcome
                    item {
                        CareSectionTitle(
                            title = "Result",
                            subtitle = "This result was computed on this device from the protocol answers.",
                        )
                    }
                    item {
                        CareCard(modifier = Modifier.fillMaxWidth()) {
                            OutcomeCard(outcome = outcome, protocol = protocol)
                            if (state.contactText.isNotBlank()) {
                                Text(
                                    text = "Contact: ${state.contactText}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (state.checkInSaved) {
                                Text(
                                    text = "Check-in saved on this device (pending sync).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "The check-in could not be saved, but the outcome " +
                                            "above was computed on this device. Keep following the " +
                                            "safety steps.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = protocol?.outcomes?.get(outcome?.wire) ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = onClose,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusCritical,
                                ),
                            ) {
                                Text("Done — keep following safety steps")
                            }
                            OutlinedButton(
                                onClick = onReset,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Start over")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyCard(
    instructions: List<String>,
    contactText: String,
    disclaimer: String,
) {
    CareCard(
        modifier = Modifier.fillMaxWidth(),
        tone = StatusCritical,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = StatusCritical,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Do this now",
                style = MaterialTheme.typography.titleLarge,
                color = StatusCritical,
                fontWeight = FontWeight.Bold,
            )
        }
        instructions.forEachIndexed { index, instruction ->
            CareBulletStep(index = index + 1, text = instruction)
        }
        if (contactText.isNotBlank()) {
            Text(
                text = contactText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = disclaimer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuestionCard(
    questionNumber: Int,
    questionCount: Int,
    questionText: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onUnsure: () -> Unit,
    onBack: () -> Unit,
) {
    CareCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Question $questionNumber of $questionCount",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = questionText,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) {
            Text("Yes")
        }
        Button(onClick = onNo, modifier = Modifier.fillMaxWidth()) {
            Text("No")
        }
        Button(onClick = onUnsure, modifier = Modifier.fillMaxWidth()) {
            Text("Unsure")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun OutcomeCard(
    outcome: TriageOutcome?,
    protocol: ChestPainProtocol?,
) {
    val isEmergency = outcome == TriageOutcome.EMERGENCY_NOW
    val accent = if (isEmergency) StatusCritical else StatusWarning
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isEmergency) Icons.Filled.Warning else Icons.Filled.Favorite,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (isEmergency) "Emergency now" else "Contact urgently",
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
    if (outcome != null) {
        Text(
            text = protocol?.outcomes?.get(outcome.wire).orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
        )
    } else {
        Text(
            text = "Outcome could not be computed — keep following the safety steps " +
                    "above and contact medical support.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}