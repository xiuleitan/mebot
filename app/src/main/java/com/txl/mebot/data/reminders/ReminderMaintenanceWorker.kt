package com.txl.mebot.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.txl.mebot.MebotApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ReminderMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val engine = (applicationContext as MebotApplication).container.reminders
        engine.reconcile()
        if (engine.state.value.calendarRegistrationFailed) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { Result.retry() }

    companion object {
        fun keepPeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("schedule-reminder-maintenance",
                ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReminderMaintenanceWorker>(12, TimeUnit.HOURS).build())
        }
        fun restore(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("schedule-reminder-restore", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderMaintenanceWorker>().build())
        }
    }
}

/** 开机及系统时间变化后重新核对日历登记。 */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            ReminderMaintenanceWorker.restore(context)
        }
    }
}
