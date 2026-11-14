package com.yasmin.astrocare.feature.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yasmin.astrocare.core.notifications.ReminderNotifications
import com.yasmin.astrocare.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when a reminder alarm triggers: posts the reminder notification
 * (tap opens Data Entry preselected for the metric) and RE-ARMS the recurring
 * schedule. No network, no evaluation — pure local notification.
 *
 * Re-arming is essential: `setExactAndAllowWhileIdle` is a ONE-SHOT alarm.
 * Without this step a daily/weekly reminder would fire once and never again
 * until the app process restarted or Settings changed (a warm process is
 * common, so relying on Application.onCreate is not enough).
 *
 * [ReminderScheduler.apply] is cancel-then-set for the whole schedule, so it
 * is safe to call from here: the just-fired alarm is already delivered
 * (cancel is a no-op) and every enabled reminder gets its next occurrence.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        // Alarms are sent with action "$ACTION_REMINDER/$id".
        if (intent.action?.startsWith(ReminderScheduler.ACTION_REMINDER) != true) return
        val target = intent.getStringExtra(ReminderScheduler.EXTRA_TARGET)
            ?: ReminderNotifications.TARGET_ALL
        val label = intent.getStringExtra(ReminderScheduler.EXTRA_LABEL).orEmpty()
        ReminderNotifications.showReminder(context, target, label)

        // Go async for the short DataStore read + AlarmManager re-arm.
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
}
