package com.yasmin.astrocare.feature.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.yasmin.astrocare.core.notifications.ReminderNotifications
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderNotificationsPermissionTest {
    private lateinit var application: Application
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        manager = application.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
    }

    @Test
    fun `denied notification permission skips posting without crashing`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderNotifications.showReminder(application, "heart_rate", "Test reminder")
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `granted notification permission posts the reminder`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderNotifications.showReminder(application, "heart_rate", "Test reminder")
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
}
