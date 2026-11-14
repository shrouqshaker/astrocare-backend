package com.yasmin.astrocare.feature.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.data.sync.FakeSettingsRepository
import com.yasmin.astrocare.domain.model.AppSettings
import com.yasmin.astrocare.domain.model.ReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AlarmManager integration via Robolectric: one alarm per enabled reminder,
 * disabled reminders never get an alarm, and re-applying the schedule is
 * cancel-then-set (no duplicate alarms) — the property the re-arm in
 * [ReminderReceiver] relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduler = ReminderScheduler(context, FakeSettingsRepository(AppSettings(deviceId = "d")))
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun scheduledCount(): Int = shadowOf(alarmManager()).scheduledAlarms.size

    private fun reminder(id: String, enabled: Boolean = true) = ReminderSettings(
        id = id,
        target = "heart_rate",
        time = "08:00",
        daysOfWeek = listOf(1, 2, 3, 4, 5, 6, 7),
        enabled = enabled,
        label = "Morning",
    )

    @Test
    fun `one alarm per enabled reminder and none for disabled ones`() {
        scheduler.apply(listOf(reminder("r-1"), reminder("r-2", enabled = false)))
        assertEquals(1, scheduledCount())
    }

    @Test
    fun `re-applying the schedule replaces alarms instead of duplicating`() {
        val reminders = listOf(reminder("r-1"))
        scheduler.apply(reminders)
        scheduler.apply(reminders)
        scheduler.apply(reminders)
        assertEquals(1, scheduledCount())
    }

    @Test
    fun `disabling or deleting every reminder cancels the alarms`() {
        scheduler.apply(listOf(reminder("r-1")))
        assertEquals(1, scheduledCount())
        scheduler.apply(emptyList())
        assertEquals(0, scheduledCount())
    }
}
