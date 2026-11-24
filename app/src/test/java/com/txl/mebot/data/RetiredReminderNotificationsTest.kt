package com.txl.mebot.data

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.reminders.RetiredReminderNotifications
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RetiredReminderNotificationsTest {
    @Test fun onlyTheOldPersonalChannelsAndWakeAlarmsAreRemoved() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = app.getSystemService(NotificationManager::class.java)
        val retired = listOf("dayima_reminders_v1", "schedule_ringing_v1", "subscription_reminders_v1")
        val retained = setOf("trading_price_alerts", "trading_news")
        val alarms = app.getSystemService(AlarmManager::class.java)
        fun wake(action: String, receiver: String, uri: String): PendingIntent {
            val intent = Intent("com.txl.mebot.$action").setClassName(app.packageName, "com.txl.mebot.data.$receiver")
                .setData(Uri.parse(uri))
            return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                .also { alarms.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, it) }
        }

        for (channel in retired + retained) {
            notifications.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_DEFAULT))
            notifications.notify(channel, 1, Notification.Builder(app, channel).setContentTitle(channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        }
        wake("DAYIMA_REMINDER_WAKE", "dayima.DayimaReminderAlarmReceiver", "mebot://dayima-reminder/wake")
        wake("SUBSCRIPTION_REMINDER_WAKE", "subscriptions.SubscriptionReminderAlarmReceiver", "mebot://subscription-reminder/wake")
        val other = wake("OTHER_FIXTURE", "trading.Fixture", "mebot://unrelated")
        assertEquals(5, notifications.notificationChannels.size)
        assertEquals(5, notifications.activeNotifications.size)
        assertEquals(3, shadowOf(alarms).scheduledAlarms.size)

        repeat(2) { RetiredReminderNotifications(app).clear() }

        assertEquals(retained, notifications.notificationChannels.map { it.id }.toSet())
        assertEquals(retained, notifications.activeNotifications.map { it.notification.channelId }.toSet())
        assertEquals(listOf(other), shadowOf(alarms).scheduledAlarms.map { it.operation })
    }
}
