package com.yasmin.astrocare.core.di

import com.yasmin.astrocare.data.remote.HistoryClient
import com.yasmin.astrocare.data.remote.HistoryTransport
import com.yasmin.astrocare.data.rule.RuleSetRepository
import com.yasmin.astrocare.data.sync.ClientRuleSetVersionProvider
import com.yasmin.astrocare.data.sync.HistoryPuller
import com.yasmin.astrocare.data.sync.HistoryRepository
import com.yasmin.astrocare.data.sync.SyncClient
import com.yasmin.astrocare.data.sync.SyncDiagnosticsRepository
import com.yasmin.astrocare.data.sync.SyncDiagnosticsStore
import com.yasmin.astrocare.data.sync.SyncTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindingsModule {
    @Binds @Singleton abstract fun bindSyncTransport(impl: SyncClient): SyncTransport
    @Binds @Singleton abstract fun bindHistoryTransport(impl: HistoryClient): HistoryTransport
    @Binds @Singleton abstract fun bindHistoryPuller(impl: HistoryRepository): HistoryPuller
    @Binds @Singleton abstract fun bindSyncDiagnostics(impl: SyncDiagnosticsStore): SyncDiagnosticsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object SyncProvidersModule {
    @Provides @Singleton
    fun provideClientRuleSetVersionProvider(ruleSetRepository: RuleSetRepository): ClientRuleSetVersionProvider =
        ClientRuleSetVersionProvider { ruleSetRepository.ruleSetVersion() }
}
