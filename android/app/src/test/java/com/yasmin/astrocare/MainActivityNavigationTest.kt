package com.yasmin.astrocare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `fresh launcher loads seed and opens all four real destination screens`() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("SYNTHETIC DEMO DATA — NOT REAL ASTRONAUT DATA").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Trends").performClick()
        compose.onNodeWithContentDescription("Trends").assertIsDisplayed()
        compose.onNodeWithContentDescription("Data").performClick()
        compose.onNodeWithContentDescription("Data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Overview").performClick()
        compose.onNodeWithContentDescription("Overview").assertIsDisplayed()
    }
}
