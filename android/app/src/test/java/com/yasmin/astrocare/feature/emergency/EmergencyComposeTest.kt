package com.yasmin.astrocare.feature.emergency

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yasmin.astrocare.domain.emergency.ChestPainProtocol
import com.yasmin.astrocare.domain.emergency.ProtocolQuestion
import com.yasmin.astrocare.domain.emergency.TriageAnswer
import com.yasmin.astrocare.domain.emergency.TriageOutcome
import com.yasmin.astrocare.ui.theme.AstroCareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for the offline chest-pain flow. The binding guarantee:
 * immediate safety instructions are visible BEFORE any question and stay
 * visible in every step (docs/DECISIONS.md §2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmergencyComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val protocol = ChestPainProtocol(
        schemaVersion = "1.0",
        protocolId = "chest-pain-v1",
        protocolVersion = "1.0.0",
        locale = "en",
        fullyOffline = true,
        disclaimer = "Demo protocol — not medical advice.",
        immediateSafetyInstructions = listOf(
            "Stop activity and sit down.",
            "Call medical support now.",
        ),
        contactByEnvironment = mapOf("iss" to "ISS flight surgeon"),
        maxQuestions = 5,
        questions = listOf(
            ProtocolQuestion("CP-Q1", "Is the pain crushing or pressure-like?", true),
            ProtocolQuestion("CP-Q2", "Does the pain spread to the arm or jaw?", true),
        ),
        outcomeRule = "any yes/unsure on CP-Q1..CP-Q4 -> emergency_now",
        outcomes = mapOf(
            "emergency_now" to "Emergency now: keep following safety steps.",
            "urgent_contact" to "Contact urgently: keep following safety steps.",
        ),
    )

    private fun setContent(
        state: EmergencyState,
        onAcknowledgeSafety: () -> Unit = {},
        onAnswer: (TriageAnswer) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        compose.setContent {
            AstroCareTheme {
                EmergencyContent(
                    state = state,
                    onBack = {},
                    onClose = onClose,
                    onAcknowledgeSafety = onAcknowledgeSafety,
                    onAnswer = onAnswer,
                    onReset = {},
                )
            }
        }
    }

    @Test
    fun `safety instructions are shown before any question`() {
        var acknowledged = false
        setContent(
            EmergencyState(
                loading = false,
                protocol = protocol,
                contactText = "ISS flight surgeon",
                step = EmergencyStep.SAFETY,
            ),
            onAcknowledgeSafety = { acknowledged = true },
        )
        compose.onNodeWithText("Do this now").assertIsDisplayed()
        compose.onNodeWithText("Stop activity and sit down.").assertIsDisplayed()
        compose.onNodeWithText("ISS flight surgeon").assertIsDisplayed()
        compose.onNodeWithText("Is the pain crushing", substring = true).assertDoesNotExist()
        compose.onNodeWithText("I understand — begin").performClick()
        compose.runOnIdle { assertTrue(acknowledged) }
    }

    @Test
    fun `question step keeps safety visible and routes answers`() {
        var answer: TriageAnswer? = null
        setContent(
            EmergencyState(
                loading = false,
                protocol = protocol,
                step = EmergencyStep.QUESTIONS,
                questionIndex = 0,
            ),
            onAnswer = { answer = it },
        )
        compose.onNodeWithText("Do this now").assertIsDisplayed()
        compose.onNodeWithText("Question 1 of 2").assertIsDisplayed()
        compose.onNodeWithText("Is the pain crushing or pressure-like?").assertIsDisplayed()
        compose.onNodeWithText("Yes").performClick()
        compose.runOnIdle { assertEquals(TriageAnswer.YES, answer) }
    }

    @Test
    fun `emergency outcome keeps safety steps visible`() {
        setContent(
            EmergencyState(
                loading = false,
                protocol = protocol,
                step = EmergencyStep.OUTCOME,
                outcome = TriageOutcome.EMERGENCY_NOW,
                checkInSaved = true,
            ),
        )
        compose.onNodeWithText("Do this now").assertIsDisplayed()
        compose.onNodeWithText("Emergency now").assertIsDisplayed()
        compose.onNodeWithText("Check-in saved on this device", substring = true).assertIsDisplayed()
    }

    @Test
    fun `failed save still shows the locally computed outcome and the error`() {
        setContent(
            EmergencyState(
                loading = false,
                protocol = protocol,
                step = EmergencyStep.OUTCOME,
                outcome = TriageOutcome.URGENT_CONTACT,
                checkInSaved = false,
                error = "Check-in could not be saved",
            ),
        )
        compose.onNodeWithText("Contact urgently").assertIsDisplayed()
        compose.onNodeWithText("Check-in could not be saved").assertIsDisplayed()
    }
}
