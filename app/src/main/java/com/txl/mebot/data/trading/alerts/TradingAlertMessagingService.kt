package com.txl.mebot.data.trading.alerts

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.trading.news.TradingNewsNotifications
import com.txl.mebot.data.trading.news.TradingNewsPushType
import com.txl.mebot.data.trading.news.TradingNewsRegistrationWorker
import com.txl.mebot.data.trading.news.tradingNewsPush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class TradingAlertMessagingService : FirebaseMessagingService() {
    private val app get() = application as MebotApplication
    private val container get() = app.container

    override fun onNewToken(token: String) {
        val work = OneTimeWorkRequestBuilder<TradingAlertTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("trading-alert-token", ExistingWorkPolicy.REPLACE, work)
        TradingNewsRegistrationWorker.enqueue(this, replaceToken = true)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val now = System.currentTimeMillis()
        val data = message.data
        if (data["type"] == TradingPushCheckType) {
            container.tradingAlerts.receivePushCheck(data["device_id"], data["check_id"])
            return
        }
        if (data["type"] == TradingNewsPushType) {
            val news = tradingNewsPush(data, now) ?: return
            val story = persistPush { container.tradingNews.accept(news, now) } ?: return
            TradingNewsNotifications.post(this, story)
            return
        }
        val event = tradingAlertEvent(data, now) ?: return
        val received = app.tradingAlertVisibility.snapshot()
        val notice = persistPush { container.tradingAlerts.claim(event, data["message"]) } ?: return
        if (!TradingAlertNotifications.canNotify(this)) return
        val current = app.tradingAlertVisibility.snapshot()
        if (received.acknowledgedBy(current)) {
            TradingAlertNotifications.notice(this, notice)
            return
        }
        if (!current.visible && message.priority != RemoteMessage.PRIORITY_HIGH) {
            TradingAlertNotifications.notice(this, notice)
            return
        }
        try {
            ContextCompat.startForegroundService(this,
                TradingAlertRingingService.intent(this, notice, received))
        } catch (_: RuntimeException) {
            TradingAlertNotifications.notice(this, notice)
        }
    }

    private fun <T> persistPush(store: suspend () -> T?): T? = runBlocking {
        withTimeoutOrNull(3_500L) {
            try { store() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}

class TradingAlertTokenWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MebotApplication).container.tradingAlerts
        return try {
            repository.registerRefreshedToken()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ProbeAlertException) {
            if (error.retryable && runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (_: Exception) { Result.failure() }
    }
}
