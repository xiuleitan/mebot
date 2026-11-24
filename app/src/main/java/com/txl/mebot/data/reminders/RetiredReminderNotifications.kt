package com.txl.mebot.data.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** 撤销旧版个人提醒。交易与新闻有各自的渠道及闹钟。 */
internal class RetiredReminderNotifications(context: Context) {
    private val app = context.applicationContext
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val alarms = app.getSystemService(AlarmManager::class.java)

    fun clear() {
        for (notification in runCatching { notifications.activeNotifications }.getOrDefault(emptyArray())) {
            if (notification.notification.channelId in channels) runCatching {
                notifications.cancel(notification.tag, notification.id)
            }
        }
        channels.forEach(notifications::deleteNotificationChannel)
        cancelAlarm("DAYIMA_REMINDER_WAKE", "dayima.DayimaReminderAlarmReceiver", "mebot://dayima-reminder/wake")
        cancelAlarm("SUBSCRIPTION_REMINDER_WAKE", "subscriptions.SubscriptionReminderAlarmReceiver", "mebot://subscription-reminder/wake")
    }

    private fun cancelAlarm(action: String, receiver: String, data: String) {
        val intent = Intent("com.txl.mebot.$action")
            .setClassName(app.packageName, "com.txl.mebot.data.$receiver")
            .setData(data.toUri())
        val pending = PendingIntent.getBroadcast(app, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    companion object {
        private val channels = setOf("dayima_reminders_v1", "schedule_ringing_v1", "subscription_reminders_v1")
        const val EXTRA_DAYIMA_EVENT = "dayima_reminder_event"
        const val EXTRA_SUBSCRIPTION = "subscription_reminder_id"
    }
}
