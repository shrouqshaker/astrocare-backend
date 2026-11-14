package com.yasmin.astrocare.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yasmin.astrocare.core.common.Format
import com.yasmin.astrocare.core.ui.CareCard
import com.yasmin.astrocare.core.ui.CareSectionTitle
import com.yasmin.astrocare.data.sync.SyncDiagnostics
import com.yasmin.astrocare.data.sync.SyncStage
import com.yasmin.astrocare.data.sync.TransferStatus

/** Stateless status content is also exercised by Compose tests. */
@Composable
fun SyncStatusContent(diagnostics: SyncDiagnostics?) {
    val tone = when (diagnostics?.stage) {
        SyncStage.FAILED, SyncStage.PARTIAL -> MaterialTheme.colorScheme.error
        SyncStage.COMPLETE -> MaterialTheme.colorScheme.primary
        SyncStage.UPLOADING, SyncStage.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    CareCard(tone = tone) {
        CareSectionTitle(
            title = "Two-way sync",
            subtitle = "Upload and download diagnostics for the selected local account.",
        )
        if (diagnostics == null || diagnostics.attemptedAt == null) {
            Text(
                text = "No upload + download attempt yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val summary = when (diagnostics.stage) {
                SyncStage.IDLE -> "Not started"
                SyncStage.UPLOADING -> "Uploading local data…"
                SyncStage.DOWNLOADING -> "Downloading saved server data…"
                SyncStage.COMPLETE -> "Upload + download completed"
                SyncStage.PARTIAL -> "Sync incomplete — see details below"
                SyncStage.FAILED -> "Sync did not finish"
                SyncStage.CANCELLED -> "Sync interrupted"
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Upload: ${transferLabel(diagnostics.uploadStatus)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Download: ${transferLabel(diagnostics.downloadStatus)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Last download: " +
                        (diagnostics.lastDownloadAt?.let { Format.shortDateTime(it) } ?: "never"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Received ${diagnostics.downloaded} records; added ${diagnostics.inserted} new records.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (diagnostics.pendingRemaining > 0) {
                Text(
                    text = "${diagnostics.pendingRemaining} local records still await upload.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (diagnostics.limitedCollections.isNotEmpty()) {
                Text(
                    text = "History limit reached (1000): ${diagnostics.limitedCollections.joinToString()}. " +
                            "The server may have more records; download completeness is not guaranteed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            diagnostics.conflicts.forEach { conflict ->
                Text(
                    text = "Content conflict: $conflict — local data kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            diagnostics.warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            diagnostics.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (diagnostics.stage == SyncStage.UPLOADING || diagnostics.stage == SyncStage.DOWNLOADING) {
                Text(
                    text = "Last recorded stage; an interrupted app can resume on the next sync.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = "Overview and Trends use local calculations with the downloaded records. " +
                    "Server history is merged; local records are never deleted by sync.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun transferLabel(status: TransferStatus): String = when (status) {
    TransferStatus.NOT_RUN -> "not run"
    TransferStatus.RUNNING -> "in progress"
    TransferStatus.NOTHING_PENDING -> "nothing pending"
    TransferStatus.SUCCESS -> "completed"
    TransferStatus.PARTIAL -> "incomplete"
    TransferStatus.FAILED -> "failed"
}