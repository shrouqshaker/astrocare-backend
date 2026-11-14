package com.yasmin.astrocare.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Shared Json + OkHttp instances.
 *
 * There is deliberately NO app-wide Retrofit provider: the sync base URL is
 * user-editable in Settings, so [com.yasmin.astrocare.data.sync.SyncClient]
 * builds Retrofit once against a placeholder root and passes the resolved
 * absolute endpoint URL per call (`@Url`). A fixed-base Retrofit provider here
 * would be dead code that silently ignores Settings.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * The app's single Json instance — used by Retrofit, the contract DTOs,
     * the seed importer and settings serialization.
     *
     *  - ignoreUnknownKeys: a newer backend can never crash the app;
     *  - explicitNulls=false: `"field": null` in responses == field absent;
     *  - isLenient=false: fails fast on malformed/missing required fields.
     * Required contract fields are constructor properties WITHOUT defaults, so
     * they are always encoded (kotlinx only omits defaulted properties when
     * encodeDefaults is false).
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Backend may add fields before the app ships an update.
        ignoreUnknownKeys = true
        explicitNulls = false
        // Fails fast on missing required contract fields (see ContractDtoTest).
        isLenient = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
