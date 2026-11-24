package com.txl.mebot.data.flashnote

import android.content.Context
import androidx.work.*
import com.txl.mebot.MebotApplication
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FlashnoteTitlePhase { WAITING, GENERATING, FAILED }
data class FlashnoteTitleState(val phase: FlashnoteTitlePhase, val failure: FlashnoteTitleFailure? = null) {
    val busy: Boolean get() = phase != FlashnoteTitlePhase.FAILED
}

interface FlashnoteTitleWork {
    suspend fun enqueue(id: String, revision: Long)
    fun observe(notes: Map<String, Long>): Flow<Map<String, FlashnoteTitleState>>
}

/** 任务只持久化记录 ID 和版本；网络工作独立于页面，正文、图片和密钥不进入 WorkManager Data。 */
class AndroidFlashnoteTitleWork(context: Context) : FlashnoteTitleWork {
    private val appContext = context.applicationContext
    private val manager by lazy { WorkManager.getInstance(appContext) }

    override suspend fun enqueue(id: String, revision: Long) {
        require(id.isNotBlank() && revision > 0)
        val operation = manager.enqueueUniqueWork(flashnoteTitleWorkName(id, revision), ExistingWorkPolicy.KEEP,
            flashnoteTitleWorkRequest(id, revision))
        suspendCancellableCoroutine { continuation ->
            val result = operation.result
            result.addListener({
                if (continuation.isActive) {
                    try { result.get(); continuation.resume(Unit) }
                    catch (_: Exception) { continuation.resumeWithException(FlashnoteTitleException(FlashnoteTitleFailure.LOCAL_SAVE)) }
                }
            }, Executor { it.run() })
        }
    }

    override fun observe(notes: Map<String, Long>): Flow<Map<String, FlashnoteTitleState>> = flow {
        if (notes.isEmpty()) emit(emptyMap())
        else emitAll(combine(notes.map { (id, revision) ->
            manager.getWorkInfosForUniqueWorkFlow(flashnoteTitleWorkName(id, revision)).map { work ->
                val info = work.firstOrNull()
                val state = when (info?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> FlashnoteTitleState(FlashnoteTitlePhase.WAITING)
                    WorkInfo.State.RUNNING -> FlashnoteTitleState(FlashnoteTitlePhase.GENERATING)
                    WorkInfo.State.FAILED -> FlashnoteTitleState(FlashnoteTitlePhase.FAILED,
                        runCatching { FlashnoteTitleFailure.valueOf(info.outputData.getString(TITLE_FAILURE).orEmpty()) }.getOrDefault(FlashnoteTitleFailure.LOCAL_SAVE))
                    WorkInfo.State.CANCELLED -> FlashnoteTitleState(FlashnoteTitlePhase.FAILED, FlashnoteTitleFailure.INTERRUPTED)
                    else -> null
                }
                id to state
            }
        }) { values -> values.mapNotNull { (id, state) -> state?.let { id to it } }.toMap() })
    }.distinctUntilChanged()
}

internal fun flashnoteTitleWorkName(id: String, revision: Long) = "flashnote-title:$id:$revision"

internal fun flashnoteTitleWorkRequest(id: String, revision: Long) = OneTimeWorkRequestBuilder<FlashnoteTitleWorker>()
    .setInputData(workDataOf(TITLE_NOTE to id, TITLE_REVISION to revision))
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()

class FlashnoteTitleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(TITLE_NOTE) ?: return failure(FlashnoteTitleFailure.LOCAL_SAVE)
        val revision = inputData.getLong(TITLE_REVISION, 0)
        if (revision <= 0) return failure(FlashnoteTitleFailure.LOCAL_SAVE)
        return try {
            val container = (applicationContext as MebotApplication).container
            FlashnoteTitleProcessor(container.flashnotes, QwenFlashnoteTitleGenerator(container.modelSettings),
                prepareImages = { flashnoteTitleImages(applicationContext.filesDir, it) }).generate(id, revision)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: FlashnoteTitleException) { failure(error.failure) }
        catch (_: Exception) { failure(FlashnoteTitleFailure.LOCAL_SAVE) }
    }

    private fun failure(reason: FlashnoteTitleFailure) = Result.failure(workDataOf(TITLE_FAILURE to reason.name))
}

private const val TITLE_NOTE = "flashnote_title_note"
private const val TITLE_REVISION = "flashnote_title_revision"
private const val TITLE_FAILURE = "flashnote_title_failure"
