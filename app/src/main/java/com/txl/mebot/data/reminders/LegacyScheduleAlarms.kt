package com.txl.mebot.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** 升级后撤销旧版本为日程登记的应用闹钟。 */
internal class LegacyScheduleAlarms(context: Context) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    fun cancel(id: String) {
        val intent = Intent("com.txl.mebot.SCHEDULE_ALARM")
            .setClassName(app.packageName, "com.txl.mebot.data.reminders.ScheduleAlarmReceiver")
            .setData("mebot://schedule-reminder/$id".toUri())
        val pending = PendingIntent.getBroadcast(app, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    companion object {
        const val EXTRA_OCCURRENCE = "schedule_occurrence_id"
    }
}
