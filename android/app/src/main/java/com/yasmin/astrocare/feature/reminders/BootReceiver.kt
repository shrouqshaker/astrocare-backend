package com.yasmin.astrocare.feature.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yasmin.astrocare.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores reminder alarms after reboot, app update, or a manual time/timezone
 * change (alarms do not survive reboots, and wall-clock alarms shift when the
 * system clock moves; WorkManager periodic sync survives on its own).
 * Re-applies the schedule straight from settings — nothing is stored in the
 * receiver itself.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.snapshot()
                reminderScheduler.apply(settings.reminders)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
