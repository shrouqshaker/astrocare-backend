package com.yasmin.astrocare.feature.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.yasmin.astrocare.domain.model.ReminderSettings
import com.yasmin.astrocare.domain.reminders.NextTriggerCalculator
import com.yasmin.astrocare.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * AlarmManager scheduling for measurement reminders.
 *
 * Guarantees (Phase 6 brief):
 *  - no duplicate alarms: every apply() CANCELS the previously scheduled set
 *    (tracked in private prefs) before setting the current one;
 *  - exact alarms via `setExactAndAllowWhileIdle` when permitted, otherwise
 *    the inexact `setAndAllowWhileIdle` fallback (Android 12+ runtime
 *    SCHEDULE_EXACT_ALARM);
 *  - disabled reminders and deleted reminders are cancelled;
 *  - next fire time comes from the pure [NextTriggerCalculator].
 *
 * Reboots wipe alarms: [BootReceiver] re-applies from settings.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /** Applies reminders now and on every settings.reminders change. */
    fun startObserving(scope: CoroutineScope) {
        settingsRepository.settings
            .map { it.reminders }
            .distinctUntilChanged()
            .onEach { apply(it) }
            .launchIn(scope)
    }

    /** Cancel-then-set: exactly one alarm per enabled reminder. */
    fun apply(reminders: List<ReminderSettings>) {
        val manager = alarmManager
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()
        for (id in previous) cancelOne(manager, id)

        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val scheduled = mutableSetOf<String>()
        for (reminder in reminders) {
            if (!reminder.enabled) continue
            val triggerAt = NextTriggerCalculator.nextTrigger(
                nowMillis = now,
                time = reminder.time,
                daysOfWeek = reminder.daysOfWeek,
                zone = zone,
            ) ?: continue
            if (canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent(reminder),
                )
            } else {
                // Fallback: inexact alarm — delivery may drift, never dropped.
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent(reminder),
                )
            }
            scheduled += reminder.id
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, scheduled).apply()
    }

    /** Android 12+ runtime exact-alarm permission status (Settings surfaces it). */
    fun canScheduleExactAlarms(): Boolean = canScheduleExactAlarms(context)

    private fun cancelOne(manager: AlarmManager, reminderId: String) {
        // Identity (action + data + class) matches regardless of extras.
        manager.cancel(
            PendingIntent.getBroadcast(
                context,
                reminderId.hashCode(),
                reminderIntent(reminderId, target = null, label = null),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun pendingIntent(reminder: ReminderSettings): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            reminderIntent(reminder.id, reminder.target, reminder.label),
            // UPDATE_CURRENT refreshes extras if this id was scheduled before.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun reminderIntent(id: String, target: String?, label: String?): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = "$ACTION_REMINDER/$id"
            data = Uri.parse("astrocare://reminder/$id")
            putExtra(EXTRA_REMINDER_ID, id)
            target?.let { putExtra(EXTRA_TARGET, it) }
            label?.let { putExtra(EXTRA_LABEL, it) }
        }

    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        fun canScheduleExactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        const val ACTION_REMINDER = "com.yasmin.astrocare.action.REMINDER"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_TARGET = "reminder_target"
        const val EXTRA_LABEL = "reminder_label"
        private const val KEY_SCHEDULED = "scheduled_ids"
        private const val PREFS_NAME = "astrocare_reminder_alarms"
    }
}
