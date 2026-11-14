package com.yasmin.astrocare.core.di

import android.content.Context
import androidx.room.Room
import com.yasmin.astrocare.data.local.AstroCareDatabase
import com.yasmin.astrocare.data.local.dao.AlertDao
import com.yasmin.astrocare.data.local.dao.MeasurementDao
import com.yasmin.astrocare.data.local.dao.SymptomCheckInDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AstroCareDatabase =
        Room.databaseBuilder(
            context,
            AstroCareDatabase::class.java,
            "astrocare.db",
        )
            // Offline-first: no allowMainThreadQueries, no network fallback.
            // v1 → v2: v1 held only the Phase-2 `toolchain_probe` row (schema shell,
            // since deleted); no released installs exist, so destructive migration
            // from v1 is intentional and documented (README "Known limitations").
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMeasurementDao(db: AstroCareDatabase): MeasurementDao = db.measurementDao()

    @Provides
    fun provideSymptomCheckInDao(db: AstroCareDatabase): SymptomCheckInDao = db.symptomCheckInDao()

    @Provides
    fun provideAlertDao(db: AstroCareDatabase): AlertDao = db.alertDao()
}
