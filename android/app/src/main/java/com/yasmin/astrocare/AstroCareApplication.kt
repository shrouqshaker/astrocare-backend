package com.yasmin.astrocare

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yasmin.astrocare.core.notifications.ReminderNotifications
import com.yasmin.astrocare.data.seed.SeedImporter
import com.yasmin.astrocare.data.sync.SyncScheduler
import com.yasmin.astrocare.domain.repository.SettingsRepository
import com.yasmin.astrocare.feature.reminders.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Owns the Hilt dependency graph.
 *
 * On first launch it persists generated settings identity (deviceId) and imports
 * the frozen synthetic seed verbatim (idempotent — see [SeedImporter]); then it
 * starts the settings observers that keep WorkManager sync and reminder alarms
 * in lockstep with Settings (both survive reboots on their own: WorkManager
 * persists periodic work, [com.yasmin.astrocare.feature.reminders.BootReceiver]
 * re-applies alarms).
 *
 * Implements WorkManager's [Configuration.Provider] with the Hilt worker
 * factory (default initializer removed in the manifest).
 */
@HiltAndroidApp
class AstroCareApplication : Application(), Configuration.Provider {

    @Inject lateinit var seedImporter: SeedImporter
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var reminderScheduler: ReminderScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.ensureChannel(this)
        applicationScope.launch {
            runCatching { settingsRepository.ensureInitialized() }
                .onFailure { Log.e(TAG, "Settings init failed", it) }
            runCatching { seedImporter.ensureSeeded() }
                .onFailure { Log.e(TAG, "Seed import failed", it) }
            // Observers are idempotent and start only after identity exists.
            runCatching {
                syncScheduler.startObserving(applicationScope)
                reminderScheduler.startObserving(applicationScope)
            }.onFailure { Log.e(TAG, "Scheduler start failed", it) }
        }
    }

    private companion object {
        const val TAG = "AstroCareApp"
    }
}
