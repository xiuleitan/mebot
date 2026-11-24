package com.txl.mebot.data.schedule

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.models.*
import java.io.ByteArrayInputStream
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 日程先提交，随后才调度装饰封面；失败、恢复及用户封面优先级均以主库为准。 */
class ScheduleCoverRepository(
    private val database: MebotDatabase,
    private val media: LocalMediaStore,
    private val generator: CoverImageGenerator,
    private val downloader: CoverImageDownloader = HttpsCoverImageDownloader(),
    private val clock: Clock = Clock.systemUTC(),
    private val enqueue: (ScheduleCoverJobEntity) -> Unit = {},
) {
    private val dao = database.scheduleCoverDao()
    // 唯一后台工作之外再限制进程内执行，避免恢复、重复调度同时发起收费请求。
    private val execution = Mutex()
    fun observe(sessionId: String) = dao.observeForSession(sessionId)

    /** 在新建日程的提交事务中登记；不在事务里调用外部服务或调度后台执行。 */
    suspend fun queueForTask(task: ScheduleTaskEntity) = database.withTransaction {
        if (task.coverAssetId == null && database.scheduleMediaDao().attachmentSummary(task.sessionId).imageCount == 0) {
            dao.insert(ScheduleCoverJobEntity(task.id, task.sessionId, UUID.randomUUID().toString(),
                coverPrompt(task.title), ScheduleCoverState.QUEUED, updatedAt = clock.millis()))
        }
    }

    suspend fun startForSession(sessionId: String) {
        dao.forSession(sessionId)?.takeIf { it.state in ActiveStates }?.let(enqueue)
    }

    /** 启动只恢复已登记的工作，不给旧任务批量生图，也不自动重试失败的收费请求。 */
    suspend fun resumePending() { dao.unfinished().forEach(enqueue) }

    suspend fun retry(sessionId: String) {
        val next = database.withTransaction {
            val previous = dao.forSession(sessionId) ?: return@withTransaction null
            if (previous.state != ScheduleCoverState.FAILED) return@withTransaction null
            if (!eligible(previous)) { skip(previous); return@withTransaction null }
            val reuseDownload = previous.canDownload(clock.millis())
            previous.copy(attemptId = UUID.randomUUID().toString(),
                prompt = if (reuseDownload) previous.prompt else coverPrompt(requireNotNull(database.scheduleDao().taskForSession(sessionId)).title),
                state = if (reuseDownload) ScheduleCoverState.DOWNLOADING else ScheduleCoverState.QUEUED,
                remoteUrl = previous.remoteUrl.takeIf { reuseDownload },
                generatedAt = previous.generatedAt.takeIf { reuseDownload },
                failure = null, updatedAt = clock.millis()).also { dao.update(it) }
        }
        next?.let(enqueue)
    }

    /** 用户显式重做 AI 封面；新图成功落地前保留当前封面，不批量重生旧图。 */
    suspend fun regenerate(sessionId: String) {
        val next = database.withTransaction {
            val task = database.scheduleDao().taskForSession(sessionId) ?: return@withTransaction null
            val currentAsset = task.coverAssetId?.let { database.mediaDao().asset(it) }
            if (task.deletedAt != null || currentAsset?.source != MediaSource.AI_GENERATED ||
                database.scheduleMediaDao().attachmentSummary(sessionId).imageCount > 0) return@withTransaction null
            val previous = dao.forSession(sessionId)
            if (previous?.state in ActiveStates) return@withTransaction null
            ScheduleCoverJobEntity(task.id, sessionId, UUID.randomUUID().toString(), coverPrompt(task.title),
                ScheduleCoverState.QUEUED, updatedAt = clock.millis()).also {
                if (previous == null) dao.insert(it) else dao.update(it)
            }
        }
        next?.let(enqueue)
    }

    /** true 仅表示可以安全重试下载；生成请求永不在这里自动重发。 */
    suspend fun execute(taskId: String, attemptId: String, downloadAttempt: Int = 0): Boolean = withContext(Dispatchers.IO) {
        execution.withLock {
            var job = begin(taskId, attemptId) ?: return@withLock false
            var downloaded = false
            try {
                if (job.state == ScheduleCoverState.GENERATING) {
                    val link = generator.generate(job.prompt)
                    require(isGeneratedImageUrl(link.url))
                    job = withContext(NonCancellable) {
                        database.withTransaction {
                            val latest = dao.job(taskId)?.takeIf { it.attemptId == attemptId && it.state == ScheduleCoverState.GENERATING }
                                ?: return@withTransaction null
                            if (!eligible(latest)) { skip(latest); return@withTransaction null }
                            latest.copy(state = ScheduleCoverState.DOWNLOADING, remoteUrl = link.url,
                                generatedAt = clock.millis(), generator = link.model, updatedAt = clock.millis()).also { dao.update(it) }
                        }
                    } ?: return@withLock false
                }
                val bytes = withTimeout(45_000) { downloader.download(requireNotNull(job.remoteUrl)) }
                downloaded = true
                media.importImage({ ByteArrayInputStream(bytes) }, MediaSource.AI_GENERATED,
                    generationPrompt = job.prompt, generator = job.generator, onImported = { asset ->
                        // 导入元数据、优先级复核、所有周期封面及成功状态在同一事务中提交。
                        val latest = dao.job(taskId)
                        if (latest?.attemptId == attemptId && latest.state == ScheduleCoverState.DOWNLOADING && eligible(latest)) {
                            require(asset.width == 768 && asset.height == 768) { "生成封面尺寸无效" }
                            val previousAsset = database.scheduleDao().taskForSession(job.sessionId)?.coverAssetId
                                ?.takeUnless { it == asset.id }?.let { database.mediaDao().asset(it) }
                            val images = database.scheduleMediaDao()
                            images.updateTaskCover(job.sessionId, asset.id, clock.millis())
                            images.updateVersionCovers(job.sessionId, asset.id)
                            images.updateOccurrenceCovers(job.sessionId, asset.id)
                            dao.update(latest.copy(state = ScheduleCoverState.READY, remoteUrl = null, failure = null, updatedAt = clock.millis()))
                            previousAsset?.let { previous ->
                                if (database.mediaDao().deleteIfUnused(previous.id) == 1) previous.relativePath?.let {
                                    database.mediaDao().queueCleanup(MediaCleanupEntity(it))
                                }
                            }
                        } else {
                            if (latest?.attemptId == attemptId && latest.state in ActiveStates) skip(latest)
                            if (database.mediaDao().deleteIfUnused(asset.id) == 1) asset.relativePath?.let {
                                database.mediaDao().queueCleanup(MediaCleanupEntity(it))
                            }
                        }
                    })
                false
            } catch (timeout: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) { interrupted(taskId, attemptId); throw timeout }
                recordFailure(taskId, attemptId, ScheduleCoverFailure.DOWNLOAD, downloadAttempt < 2)
            } catch (cancelled: CancellationException) {
                interrupted(taskId, attemptId)
                throw cancelled
            } catch (failure: ModelImageException) {
                recordFailure(taskId, attemptId, failure.kind.asCoverFailure(), false)
            } catch (_: IllegalArgumentException) {
                recordFailure(taskId, attemptId, ScheduleCoverFailure.INVALID_RESPONSE, false)
            } catch (_: Exception) {
                val downloading = job.state == ScheduleCoverState.DOWNLOADING && !downloaded
                recordFailure(taskId, attemptId, if (downloading) ScheduleCoverFailure.DOWNLOAD else ScheduleCoverFailure.LOCAL_SAVE,
                    downloading && downloadAttempt < 2)
            } finally {
                withContext(NonCancellable) { runCatching { media.cleanPendingDeletions() } }
            }
        }
    }

    private suspend fun begin(taskId: String, attemptId: String): ScheduleCoverJobEntity? = database.withTransaction {
        val job = dao.job(taskId)?.takeIf { it.attemptId == attemptId && it.state in ActiveStates } ?: return@withTransaction null
        if (!eligible(job)) { skip(job); return@withTransaction null }
        when (job.state) {
            ScheduleCoverState.GENERATING -> {
                // 上次进程可能在 POST 后终止，服务端是否计费无法确定，不自动再发一次。
                dao.update(job.copy(state = ScheduleCoverState.FAILED, failure = ScheduleCoverFailure.INTERRUPTED, updatedAt = clock.millis()))
                null
            }
            ScheduleCoverState.QUEUED -> job.copy(
                prompt = coverPrompt(requireNotNull(database.scheduleDao().taskForSession(job.sessionId)).title),
                state = ScheduleCoverState.GENERATING, failure = null, updatedAt = clock.millis(),
            ).also { dao.update(it) }
            ScheduleCoverState.DOWNLOADING -> if (job.canDownload(clock.millis())) job else {
                dao.update(job.copy(state = ScheduleCoverState.FAILED, remoteUrl = null, failure = ScheduleCoverFailure.DOWNLOAD, updatedAt = clock.millis()))
                null
            }
            else -> null
        }
    }

    private suspend fun eligible(job: ScheduleCoverJobEntity): Boolean {
        val task = database.scheduleDao().taskForSession(job.sessionId)
        val cover = task?.coverAssetId?.let { database.mediaDao().asset(it) }
        return task?.id == job.taskId && task.deletedAt == null && (task.coverAssetId == null || cover?.source == MediaSource.AI_GENERATED) &&
            database.scheduleMediaDao().attachmentSummary(job.sessionId).imageCount == 0
    }

    private suspend fun skip(job: ScheduleCoverJobEntity) {
        dao.update(job.copy(state = ScheduleCoverState.SKIPPED, remoteUrl = null, failure = null, updatedAt = clock.millis()))
    }

    private suspend fun recordFailure(taskId: String, attemptId: String, failure: ScheduleCoverFailure, retryDownload: Boolean): Boolean =
        withContext(NonCancellable) {
            database.withTransaction {
                val job = dao.job(taskId)?.takeIf { it.attemptId == attemptId && it.state in ActiveStates } ?: return@withTransaction false
                if (!eligible(job)) { skip(job); return@withTransaction false }
                val safeRetry = retryDownload && job.state == ScheduleCoverState.DOWNLOADING && job.canDownload(clock.millis())
                dao.update(job.copy(state = if (safeRetry) ScheduleCoverState.DOWNLOADING else ScheduleCoverState.FAILED,
                    remoteUrl = job.remoteUrl.takeUnless { failure == ScheduleCoverFailure.INVALID_RESPONSE },
                    failure = failure, updatedAt = clock.millis()))
                safeRetry
            }
        }

    private suspend fun interrupted(taskId: String, attemptId: String) = withContext(NonCancellable) {
        database.withTransaction {
            val job = dao.job(taskId)?.takeIf { it.attemptId == attemptId } ?: return@withTransaction
            if (job.state == ScheduleCoverState.GENERATING) dao.update(job.copy(state = ScheduleCoverState.FAILED,
                failure = ScheduleCoverFailure.INTERRUPTED, updatedAt = clock.millis()))
            // 已获得 URL 的下载可由 WorkManager 恢复，不重做生成。
        }
    }

    private companion object {
        val ActiveStates = setOf(ScheduleCoverState.QUEUED, ScheduleCoverState.GENERATING, ScheduleCoverState.DOWNLOADING)
        fun coverPrompt(title: String) = "生成一张正方形完整照片，活动主题：${title}。单一连续的写实摄影场景，自然光，主体完整居中，构图饱满，全画面清晰，前景与背景连贯，四周保留适度构图空间。整张图片均为活动相关内容，无上下分区、无拼接横条、无局部模糊、无渐变遮罩、无叠字留白。不包含文字、日期、数字、标志或水印，不推测用户附件内容。"
    }
}

internal fun ScheduleCoverJobEntity.canDownload(now: Long): Boolean = remoteUrl != null && generatedAt != null &&
    now - generatedAt in 0..(23L * 60 * 60 * 1000)

private fun ConnectionResultKind.asCoverFailure(): ScheduleCoverFailure = when (this) {
    ConnectionResultKind.INVALID_KEY, ConnectionResultKind.NO_PERMISSION, ConnectionResultKind.SERVICE_NOT_ENABLED -> ScheduleCoverFailure.AUTH
    ConnectionResultKind.BILLING -> ScheduleCoverFailure.BILLING
    ConnectionResultKind.RATE_LIMIT -> ScheduleCoverFailure.RATE_LIMIT
    ConnectionResultKind.DNS, ConnectionResultKind.NETWORK, ConnectionResultKind.TIMEOUT, ConnectionResultKind.TLS -> ScheduleCoverFailure.NETWORK
    ConnectionResultKind.ENDPOINT, ConnectionResultKind.MODEL_UNAVAILABLE, ConnectionResultKind.PARAMETERS -> ScheduleCoverFailure.CONFIGURATION
    ConnectionResultKind.BAD_RESPONSE -> ScheduleCoverFailure.INVALID_RESPONSE
    else -> ScheduleCoverFailure.SERVICE
}
