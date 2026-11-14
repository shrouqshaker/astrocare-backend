package com.yasmin.astrocare.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yasmin.astrocare.MainActivity
import com.yasmin.astrocare.R
import com.yasmin.astrocare.core.navigation.Routes

/**
 * Reminder notifications: one channel (created once at app start), and a tap
 * that opens Data Entry preselected for the reminder's metric target
 * (contract `target`: `all` opens Data Entry without a preselection).
 * Posting is a no-op while the POST_NOTIFICATIONS permission is missing.
 */
object ReminderNotifications {

    const val CHANNEL_ID = "reminders"
    private const val CHANNEL_NAME = "Measurement reminders"

    /** Idempotent — called from Application.onCreate and before each post. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminders to log measurements and check-ins"
        }
        manager.createNotificationChannel(channel)
    }

    /** True when reminders can be posted (Android 13+ runtime permission). */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun showReminder(context: Context, target: String, label: String) {
        if (!canPost(context)) return
        ensureChannel(context)

        val route = if (target == TARGET_ALL) {
            Routes.DATA_ENTRY
        } else {
            Routes.dataEntry(target)
        }
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            target.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("AstroCare reminder")
            .setContentText(notificationText(target, label))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(notificationText(target, label)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID_BASE + target.hashCode(),
                notification,
            )
        } catch (_: SecurityException) {
            // The user/OS may revoke permission after canPost(). Reminders keep
            // their schedule, but this occurrence must not crash the receiver.
        }
    }

    private fun notificationText(target: String, label: String): String = when {
        label.isNotBlank() -> label
        target == TARGET_ALL -> "Time to log your measurements and how you feel."
        else -> "Time to log ${target.replace('_', ' ')}."
    }

    /** Contract `target` value meaning "any metric" (settings.schema.json). */
    const val TARGET_ALL = "all"

    private const val NOTIFICATION_ID_BASE = 4200
}
