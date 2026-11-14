package com.yasmin.astrocare.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yasmin.astrocare.data.sync.SyncDiagnostics
import com.yasmin.astrocare.data.sync.SyncStage
import com.yasmin.astrocare.data.sync.TransferStatus
import com.yasmin.astrocare.ui.theme.AstroCareTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose-only coverage for the stateless sync diagnostics content. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncStatusComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `no attempt state is explicit`() {
        setContent(null)
        compose.onNodeWithText("Two-way sync").assertIsDisplayed()
        compose.onNodeWithText("No upload + download attempt yet.").assertIsDisplayed()
    }

    @Test
    fun `upload success and download failure labels are both shown`() {
        setContent(
            SyncDiagnostics(
                attemptedAt = "2026-11-14T06:00:00Z",
                stage = SyncStage.FAILED,
                uploadStatus = TransferStatus.SUCCESS,
                downloadStatus = TransferStatus.FAILED,
                error = "Server unavailable",
            ),
        )
        compose.onNodeWithText("Sync did not finish").assertIsDisplayed()
        compose.onNodeWithText("Upload: completed").assertIsDisplayed()
        compose.onNodeWithText("Download: failed").assertIsDisplayed()
        compose.onNodeWithText("Server unavailable").assertIsDisplayed()
    }

    @Test
    fun `history cap warning is surfaced`() {
        setContent(
            SyncDiagnostics(
                attemptedAt = "2026-11-14T06:00:00Z",
                stage = SyncStage.PARTIAL,
                uploadStatus = TransferStatus.NOTHING_PENDING,
                downloadStatus = TransferStatus.PARTIAL,
                limitedCollections = listOf("measurements", "checkIns"),
            ),
        )
        compose.onNodeWithText(
            "History limit reached (1000): measurements, checkIns.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `conflict explicitly says local data was kept`() {
        setContent(
            SyncDiagnostics(
                attemptedAt = "2026-11-14T06:00:00Z",
                stage = SyncStage.PARTIAL,
                conflicts = listOf("measurement m-1"),
            ),
        )
        compose.onNodeWithText("Content conflict: measurement m-1 — local data kept.").assertIsDisplayed()
    }

    @Test
    fun `complete upload and download summary is rendered`() {
        setContent(
            SyncDiagnostics(
                attemptedAt = "2026-11-14T06:00:00Z",
                stage = SyncStage.COMPLETE,
                uploadStatus = TransferStatus.SUCCESS,
                downloadStatus = TransferStatus.SUCCESS,
                downloaded = 4,
                inserted = 2,
            ),
        )
        compose.onNodeWithText("Upload + download completed").assertIsDisplayed()
        compose.onNodeWithText("Upload: completed").assertIsDisplayed()
        compose.onNodeWithText("Download: completed").assertIsDisplayed()
        compose.onNodeWithText("Received 4 records; added 2 new records.").assertIsDisplayed()
    }

    private fun setContent(diagnostics: SyncDiagnostics?) {
        compose.setContent {
            AstroCareTheme {
                SyncStatusContent(diagnostics)
            }
        }
    }
}
