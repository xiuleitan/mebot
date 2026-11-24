package com.txl.mebot.data.schedule

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.ScheduleCoverJobEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ScheduleCoverWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(TASK) ?: return Result.failure()
        val attemptId = inputData.getString(ATTEMPT) ?: return Result.failure()
        return try {
            val covers = (applicationContext as MebotApplication).container.scheduleCovers
            if (covers.execute(taskId, attemptId, runAttemptCount)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // 本地读写异常可恢复；主库中的 GENERATING 状态会阻止重复 POST。
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TASK = "schedule_cover_task"
        private const val ATTEMPT = "schedule_cover_attempt"
        fun enqueue(context: Context, job: ScheduleCoverJobEntity) {
            val request = OneTimeWorkRequestBuilder<ScheduleCoverWorker>()
                .setInputData(workDataOf(TASK to job.taskId, ATTEMPT to job.attemptId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "schedule-cover:${job.taskId}:${job.attemptId}", ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
