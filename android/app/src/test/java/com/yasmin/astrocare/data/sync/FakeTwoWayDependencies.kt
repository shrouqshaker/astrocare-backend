package com.yasmin.astrocare.data.sync

import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSyncDiagnosticsRepository : SyncDiagnosticsRepository {
    override val diagnostics = MutableStateFlow(SyncDiagnostics())
    override suspend fun snapshot() = diagnostics.value
    override suspend fun update(transform: (SyncDiagnostics) -> SyncDiagnostics) {
        diagnostics.value = transform(diagnostics.value)
    }
}

internal class FakeHistoryPuller(
    private val action: suspend () -> HistoryPullResult = { HistoryPullResult() },
) : HistoryPuller {
    var calls = 0
    val baseUrls = mutableListOf<String>()
    val apiKeys = mutableListOf<String>()
    val astronautIds = mutableListOf<String>()
    override suspend fun pull(baseUrl: String, apiKey: String, astronautId: String): HistoryPullResult {
        calls++
        baseUrls += baseUrl
        apiKeys += apiKey
        astronautIds += astronautId
        return action()
    }
}
