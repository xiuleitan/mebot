package com.txl.mebot.data.trading.news

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.trading.alerts.ProbeAlertException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class TradingNewsRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MebotApplication).container.tradingAlerts
        val sendingReady = try {
            repository.registerNewsDevice()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProbeAlertException) {
            if (!error.retryable && error.httpStatus != 404) return Result.failure()
            false
        } catch (_: Exception) {
            return Result.failure()
        }
        return when {
            sendingReady -> Result.success()
            runAttemptCount < 8 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        internal fun enqueue(context: Context, replaceToken: Boolean = false) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = OneTimeWorkRequestBuilder<TradingNewsRegistrationWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            val policy = if (replaceToken) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork("trading-news-registration", policy, work)
        }
    }
}
