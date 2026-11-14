package com.yasmin.astrocare.feature.trends

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yasmin.astrocare.domain.trend.BaselineInfo
import com.yasmin.astrocare.domain.trend.MetricTrend
import com.yasmin.astrocare.ui.theme.AstroCareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for Trends: the 7-day window renders gaps as "No data"
 * (never zero-filled) and the demo-threshold disclaimer is always visible.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrendsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(label: String, metric: String, hasNoData: Boolean) = TrendCard(
        metric = metric,
        label = label,
        trend = MetricTrend(
            metric = metric,
            baseline = BaselineInfo(
                available = false,
                windowDays = 14,
                sampleCount = 0,
                distinctDays = 0,
                reason = "insufficient data",
            ),
            fields = emptyList(),
        ),
        hasNoData = hasNoData,
    )

    @Test
    fun `empty window shows the title and the disclaimer`() {
        compose.setContent {
            AstroCareTheme {
                TrendsContent(TrendsState(loading = false, windowLabel = "2026-11-08 → 2026-11-14"))
            }
        }
        compose.onNodeWithText("Trends").assertIsDisplayed()
        compose.onNodeWithText("Last 7 days", substring = true).assertIsDisplayed()
        compose.onNodeWithText("not medical advice", substring = true).assertIsDisplayed()
    }

    @Test
    fun `metric without readings renders No data instead of zeros`() {
        compose.setContent {
            AstroCareTheme {
                TrendsContent(
                    TrendsState(
                        loading = false,
                        cards = listOf(card("Heart rate", "heart_rate", hasNoData = true)),
                    ),
                )
            }
        }
        compose.onNodeWithText("Heart rate").assertIsDisplayed()
        compose.onNodeWithText("No data").assertIsDisplayed()
    }
}
