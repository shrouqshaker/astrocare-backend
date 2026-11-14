package com.yasmin.astrocare.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class SyncStage { IDLE, UPLOADING, DOWNLOADING, COMPLETE, PARTIAL, FAILED, CANCELLED }

@Serializable
enum class TransferStatus { NOT_RUN, RUNNING, NOTHING_PENDING, SUCCESS, PARTIAL, FAILED }

/** Android-only diagnostics. No API keys, health values or remote payloads are stored here. */
@Serializable
data class SyncDiagnostics(
    val baseUrl: String = "",
    val astronautId: String = "",
    val attemptedAt: String? = null,
    val finishedAt: String? = null,
    val lastDownloadAt: String? = null,
    val stage: SyncStage = SyncStage.IDLE,
    val uploadStatus: TransferStatus = TransferStatus.NOT_RUN,
    val downloadStatus: TransferStatus = TransferStatus.NOT_RUN,
    val created: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val downloaded: Int = 0,
    val inserted: Int = 0,
    val pendingRemaining: Int = 0,
    val conflicts: List<String> = emptyList(),
    val limitedCollections: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
) {
    fun belongsTo(url: String, id: String): Boolean =
        normalizeBase(baseUrl) == normalizeBase(url) && astronautId == id

    companion object {
        private fun normalizeBase(url: String): String = url.trim().trimEnd('/').removeSuffix("/api/v1")
    }
}

interface SyncDiagnosticsRepository {
    val diagnostics: Flow<SyncDiagnostics>
    suspend fun snapshot(): SyncDiagnostics
    suspend fun update(transform: (SyncDiagnostics) -> SyncDiagnostics)
}
