package com.txl.mebot.data.schedule

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduleEditorConflict : IllegalStateException("日程已被修改，请读取最新记录后再保存")
data class ScheduleEditorSnapshot(val draft: ScheduleEditorDraftEntity, val attachments: List<ScheduleEditorAttachment>, val conflict: Boolean)

/** 手动编辑使用独立本地草稿；表单与媒体只有在最终事务提交时才影响原日程。 */
class ScheduleEditorRepository(context: Context, private val database: MebotDatabase,
    private val schedules: ScheduleRepository, private val attachments: ScheduleAttachmentRepository,
    private val media: LocalMediaStore, private val clock: Clock = Clock.systemUTC()) {
    private val resolver = context.applicationContext.contentResolver
    private val dao = database.scheduleEditor()

    suspend fun open(id: String): ScheduleEditorSnapshot = withContext(Dispatchers.IO) { database.withTransaction {
        val occurrence = requireNotNull(database.scheduleDao().occurrence(id)) { "这条日程已不存在" }
        val task = requireNotNull(database.scheduleDao().task(occurrence.taskId)) { "这条日程已不存在" }
        require(task.deletedAt == null && !occurrence.suppressedByRule) { "这条日程已取消" }
        val draft = dao.draft(id) ?: ScheduleEditorDraftEntity(id, UUID.randomUUID().toString(), task.sessionId,
            occurrence.revision, task.revision, attachmentFingerprint(task.sessionId), occurrence.title, occurrence.detail,
            occurrence.date, occurrence.time, database.scheduleDao().recurrence(task.id, task.revision) != null ||
                database.scheduleDao().recurrence(task.id, occurrence.taskRevision) != null, updatedAt = clock.millis()).also {
            dao.insert(it)
            for (item in database.scheduleMediaDao().attachments(task.sessionId))
                dao.attach(ScheduleEditorAttachmentEntity(id, item.asset.id, item.link.position))
        }
        ScheduleEditorSnapshot(draft, dao.attachments(id), !matchesSource(draft))
    } }

    suspend fun update(value: ScheduleEditorDraftEntity): ScheduleEditorDraftEntity = withContext(Dispatchers.IO) { database.withTransaction {
        val old = requireNotNull(dao.draft(value.occurrenceId)) { "编辑草稿已关闭" }
        check(old.requestId == value.requestId && old.revision == value.revision) { "编辑草稿已更新，请重新打开" }
        old.copy(title = value.title, detail = value.detail, date = value.date, time = value.time,
            revision = old.revision + 1, updatedAt = clock.millis()).also { dao.update(it) }
    } }

    suspend fun importImage(id: String, requestId: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择相册中的图片" }
        val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.take(200) ?: "日程图片"
        media.importDocument({ requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" } }, name,
            resolver.getType(uri), requireImage = true) { asset -> attach(id, requestId, asset.id) }
    }

    suspend fun attach(id: String, requestId: String, assetId: String) = database.withTransaction {
        check(dao.draft(id)?.requestId == requestId) { "编辑草稿已关闭" }
        val asset = requireNotNull(database.mediaDao().asset(assetId))
        require(asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0) { "请选择图片" }
        dao.attach(ScheduleEditorAttachmentEntity(id, assetId, (dao.attachments(id).maxOfOrNull { it.link.position } ?: 0) + 1))
    }

    suspend fun remove(id: String, requestId: String, assetId: String) = withContext(Dispatchers.IO) { database.withTransaction {
        check(dao.draft(id)?.requestId == requestId) { "编辑草稿已关闭" }
        val item = dao.attachments(id).firstOrNull { it.asset.id == assetId } ?: return@withTransaction
        dao.remove(id, assetId)
        release(item.asset)
    } }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) { database.withTransaction {
        val items = dao.attachments(id)
        dao.delete(id)
        items.forEach { release(it.asset) }
    } }

    suspend fun save(id: String, requestId: String): ScheduleOccurrenceEntity = withContext(Dispatchers.IO) { database.withTransaction {
        val draft = requireNotNull(dao.draft(id)) { "编辑草稿已关闭" }
        check(draft.requestId == requestId)
        if (!matchesSource(draft)) throw ScheduleEditorConflict()
        val original = requireNotNull(database.scheduleDao().occurrence(id))
        val base = schedules.occurrenceDraft(original).copy(recurrence = null)
        val patch = SchedulePatch(ScheduleDraft(draft.title, draft.detail, draft.date, draft.time), setOf("title", "detail", "date", "time"))
        // 写入附件前校验表单，失败时整个事务仍回到原状态。
        val changed = patch.applyTo(base).resolved(original.date, original.title)
        val desired = dao.attachments(id)
        val desiredIds = desired.map { it.asset.id }.toSet()
        val current = database.scheduleMediaDao().attachments(draft.sessionId)
        for (item in current) if (item.asset.id !in desiredIds) attachments.remove(draft.sessionId, item.asset.id)
        val currentIds = current.map { it.asset.id }.toSet()
        for (item in desired) if (item.asset.id !in currentIds) attachments.attach(draft.sessionId, item.asset, asPending = false)
        if (draft.isRecurring) schedules.editOccurrence(draft.sessionId, id, patch, requestId, draft.expectedRevision)
        else {
            val cover = requireNotNull(schedules.taskForSession(draft.sessionId)).coverAssetId
            schedules.updateTask(draft.sessionId, requestId, changed.copy(coverAssetId = cover), draft.expectedTaskRevision,
                changedFields = patch.fields)
        }
        dao.delete(id)
        requireNotNull(database.scheduleDao().occurrence(id))
    } }

    suspend fun cleanMedia() = media.cleanPendingDeletions()

    private suspend fun matchesSource(draft: ScheduleEditorDraftEntity): Boolean {
        val occurrence = database.scheduleDao().occurrence(draft.occurrenceId) ?: return false
        val task = schedules.taskForSession(draft.sessionId) ?: return false
        return occurrence.taskId == task.id && !occurrence.suppressedByRule && occurrence.revision == draft.expectedRevision &&
            task.revision == draft.expectedTaskRevision && attachmentFingerprint(draft.sessionId) == draft.attachmentFingerprint
    }

    private suspend fun attachmentFingerprint(session: String) = database.scheduleMediaDao().attachments(session)
        .joinToString("|") { "${it.asset.id}:${it.link.isCover}:${it.link.position}" }

    private suspend fun release(asset: MediaAssetEntity) {
        if (database.mediaDao().deleteIfUnused(asset.id) == 1) asset.relativePath?.let { database.mediaDao().queueCleanup(MediaCleanupEntity(it)) }
    }
}
