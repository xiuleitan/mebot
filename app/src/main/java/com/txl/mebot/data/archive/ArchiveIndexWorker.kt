package com.txl.mebot.data.archive

import android.content.Context
import androidx.work.*
import com.txl.mebot.MebotApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class ArchiveIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val engine = (applicationContext as MebotApplication).container.archiveSemantic
            if (withTimeout(8 * 60_000L) { engine.runQueue() }) Result.retry() else Result.success()
        } catch (_: TimeoutCancellationException) { Result.retry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
    companion object {
        const val WORK = "archive-semantic-index"
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArchiveIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            // 追加尾部任务保证最后一笔保存不会落在“Worker已扫完、仍未退出”的空隙。
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
