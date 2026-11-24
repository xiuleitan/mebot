package com.txl.mebot.data.dayima

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 定期同步经期预测和首页提醒。 */
class DayimaReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val engine = (applicationContext as MebotApplication).container.dayimaReminders
            engine.reconcile()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        const val PERIODIC_WORK = "dayima-reminder-maintenance"
        const val RESTORE_WORK = "dayima-reminder-restore"
        fun keepPeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DayimaReminderWorker>(12, TimeUnit.HOURS).build())
        }
        fun restore(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(RESTORE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DayimaReminderWorker>().build())
        }
    }
}

class DayimaReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) DayimaReminderWorker.restore(context)
    }
}
