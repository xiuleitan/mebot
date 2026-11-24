package com.txl.mebot.data.trading

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.txl.mebot.MebotApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class TradingPerformanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val trading = (applicationContext as MebotApplication).container.trading
        // Binance can publish a late snapshot or omit the day for an empty wallet.
        if (trading.syncDailyPerformance() || runAttemptCount >= 2) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TradingException) {
        when (error.problem) {
            TradingProblem.NETWORK, TradingProblem.RATE_LIMIT -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val DailyWork = "trading-performance-daily"
        private const val CatchUpWork = "trading-performance-catch-up"
        private val Online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        internal fun schedule(context: Context, replaceCatchUp: Boolean) {
            val manager = WorkManager.getInstance(context)
            val now = System.currentTimeMillis()
            val today = tradingDay(now) * TRADING_DAY_MS
            // Give the exchange time to publish the previous UTC day's snapshot.
            val publishTime = today + TimeUnit.HOURS.toMillis(2)
            val next = if (now < publishTime) publishTime else publishTime + TRADING_DAY_MS
            val daily = PeriodicWorkRequestBuilder<TradingPerformanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Online)
                .setInitialDelay(next - now, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(DailyWork, ExistingPeriodicWorkPolicy.KEEP, daily)

            val catchUp = OneTimeWorkRequestBuilder<TradingPerformanceWorker>()
                .setConstraints(Online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            // The application also starts when WorkManager starts a job in a cold process.
            val policy = if (replaceCatchUp) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            manager.enqueueUniqueWork(CatchUpWork, policy, catchUp)
        }

        internal fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(DailyWork)
            manager.cancelUniqueWork(CatchUpWork)
        }
    }
}
