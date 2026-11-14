package com.yasmin.astrocare.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "astrocare_settings",
)

/**
 * Cross-cutting singleton providers. Feature-specific wiring lives in its own module.
 *
 * Json conventions for contract DTOs live with [NetworkModule.provideJson]
 * (single binding: ignoreUnknownKeys=true, explicitNulls=false, strict).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
