package com.yasmin.astrocare.feature.overview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yasmin.astrocare.data.local.entity.AlertEntity
import com.yasmin.astrocare.ui.theme.AstroCareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for the stateless [OverviewContent] — loading, empty,
 * error, synthetic-data banner and alert acknowledgement. Runs on the JVM via
 * Robolectric (no emulator required).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverviewComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: OverviewState,
        onRefresh: () -> Unit = {},
        onAcknowledge: (String) -> Unit = {},
        onOpenEmergency: () -> Unit = {},
    ) {
        compose.setContent {
            AstroCareTheme {
                OverviewContent(
                    state = state,
                    onRefresh = onRefresh,
                    onAcknowledge = onAcknowledge,
                    onOpenEmergency = onOpenEmergency,
                )
            }
        }
    }

    private fun alert(id: String = "a-1") = AlertEntity(
        id = id,
        schemaVersion = "1.0",
        astronautId = "demo-astronaut-01",
        createdAt = "2026-11-13T10:00:00Z",
        level = "critical",
        category = "emergency",
        ruleId = "SYM-CHEST-C",
        ruleRevision = 1,
        ruleSetVersion = "1.0.0",
        title = "Chest pain reported",
        explanation = "Chest pain was reported in the latest check-in.",
        evidenceJson = "[]",
        recommendedAction = "Follow the chest-pain protocol now.",
        disclaimer = "Demo rule.",
        sourceJson = "{}",
        triggeredByJson = "{}",
        emergencyProtocolId = "chest-pain-v1",
        acknowledgedAt = null,
        isSynthetic = false,
    )

    @Test
    fun `loading state shows computing status`() {
        setContent(OverviewState(loading = true))
        compose.onNodeWithText("Computing local status", substring = true).assertIsDisplayed()
        // While loading with no alerts the UI shows a progress bar, not the empty card.
        compose.onNodeWithText("Alerts").assertIsDisplayed()
    }

    @Test
    fun `empty state explains unavailable status and offers the emergency check`() {
        var opened = false
        setContent(OverviewState(loading = false), onOpenEmergency = { opened = true })
        compose.onNodeWithText("Status unavailable", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No insights for the current data window.").assertIsDisplayed()
        compose.onNodeWithText("Open emergency check").performClick()
        assertTrue(opened)
    }

    @Test
    fun `error message is surfaced instead of being dropped`() {
        setContent(OverviewState(loading = false, errorMessage = "Evaluation failed: timeout"))
        compose.onNodeWithText("Evaluation failed: timeout").assertIsDisplayed()
    }

    @Test
    fun `synthetic data is clearly labeled`() {
        setContent(
            OverviewState(
                loading = false,
                hasSyntheticData = true,
                seedLabel = "SYNTHETIC DEMO DATA — NOT REAL ASTRONAUT DATA",
            ),
        )
        compose.onNodeWithText("SYNTHETIC DEMO DATA", substring = true).assertIsDisplayed()
    }

    @Test
    fun `alert card shows rule content and acknowledge invokes the callback`() {
        var acknowledged: String? = null
        setContent(
            OverviewState(loading = false, alerts = listOf(alert())),
            onAcknowledge = { acknowledged = it },
        )
        compose.onNodeWithText("Chest pain reported").assertIsDisplayed()
        compose.onNodeWithText("Acknowledge").performClick()
        compose.runOnIdle { assertEquals("a-1", acknowledged) }
    }

    @Test
    fun `latest reading without data renders No data`() {
        setContent(
            OverviewState(
                loading = false,
                latest = listOf(
                    LatestReading(
                        metric = "heart_rate",
                        label = "Heart rate",
                        valueText = null,
                        observedAt = null,
                        isSynthetic = false,
                    ),
                ),
            ),
        )
        compose.onNodeWithText("Heart rate").assertIsDisplayed()
        compose.onNodeWithText("No data").assertIsDisplayed()
    }
}
