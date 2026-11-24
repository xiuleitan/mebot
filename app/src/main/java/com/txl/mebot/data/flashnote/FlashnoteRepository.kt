package com.txl.mebot.data.flashnote

import androidx.room.withTransaction
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.data.local.FlashnoteAttachmentEntity
import com.txl.mebot.data.local.FlashnoteDraftAttachmentEntity
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.data.local.MediaCleanupEntity
import com.txl.mebot.data.local.FlashnoteCompletionEntity
import com.txl.mebot.data.local.FlashnoteCompletionArchiveEntity
import com.txl.mebot.data.local.MebotDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.flow.distinctUntilChanged

class FlashnoteNotFound : IllegalStateException("闪念已不存在，请刷新列表")
class FlashnoteConflict : IllegalStateException("闪念已改变，请重新查看后操作")
class FlashnoteDraftNotFound : IllegalStateException("草稿已提交或取消，请重新打开")
class FlashnoteDraftConflict : IllegalStateException("草稿已改变，已保留当前输入，请重新读取")

/** 所有业务操作在本地主库完成；不依赖模型、网络或页面生命周期。 */
class FlashnoteRepository(
    private val database: MebotDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val dayZone: () -> ZoneId = ZoneId::systemDefault,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.flashnoteDao()
    private val attachments = database.flashnoteMediaDao()
    private val statistics = database.flashnoteStatisticsDao()

    suspend fun note(id: String) = dao.note(id)
    suspend fun notes(status: FlashnoteStatus) = dao.notes(status)
    fun observeNote(id: String) = dao.observeNote(id).distinctUntilChanged()
    fun observeNotes(status: FlashnoteStatus) = dao.observeNotes(status).distinctUntilChanged()
    fun observeCounts() = dao.observeCounts().distinctUntilChanged()
    suspend fun draft(key: String) = dao.draft(key)
    fun observeDraft(key: String) = dao.observeDraft(key).distinctUntilChanged()
    fun observeCards(status: FlashnoteStatus) = attachments.observeCards(status).distinctUntilChanged()
    fun observeNoteAttachments(id: String) = attachments.observeNoteAttachments(id).distinctUntilChanged()
    suspend fun draftAttachments(key: String) = attachments.draftAttachments(key)
    suspend fun noteAttachments(id: String) = attachments.noteAttachments(id)
    fun observeCompletionDays(fromDay: Long, untilDay: Long) = statistics.observeDays(fromDay, untilDay).distinctUntilChanged()
    fun observeHistoryBounds() = statistics.observeBounds().distinctUntilChanged()

    /** AI 读取当前版本的一份快照；已有编辑草稿时不与用户争抢标题。 */
    suspend fun titleSnapshot(id: String, expectedRevision: Long): FlashnoteTitleSnapshot = database.withTransaction {
        val note = dao.note(id) ?: throw FlashnoteNotFound()
        if (note.revision != expectedRevision || dao.editingDraft(id) != null) throw FlashnoteConflict()
        FlashnoteTitleSnapshot(note, attachments.noteAttachments(id))
    }

    /** 唯一允许 AI 写入的字段是标题；迟到结果不覆盖编辑、完成／恢复、附件变化或删除。 */
    suspend fun applyGeneratedTitle(snapshot: FlashnoteTitleSnapshot, generated: String): FlashnoteEntity = database.withTransaction {
        val title = validatedFlashnoteTitle(generated)
        val note = dao.note(snapshot.note.id) ?: throw FlashnoteNotFound()
        if (note != snapshot.note || attachments.noteAttachments(note.id) != snapshot.attachments || dao.editingDraft(note.id) != null)
            throw FlashnoteConflict()
        if (note.title == title) return@withTransaction note
        if (dao.updateTitle(note.id, title, clock.millis(), note.revision) != 1) throw FlashnoteConflict()
        requireNotNull(dao.note(note.id))
    }

    /** 草稿标识在提交期间保持稳定，保存按钮重试不能重新打开一个新草稿。 */
    suspend fun openCreation(): FlashnoteDraftEntity = database.withTransaction {
        dao.creationDrafts().firstOrNull() ?: FlashnoteDraftEntity(
            key = unusedId(), updatedAt = clock.millis(),
        ).also { dao.insertDraft(it) }
    }

    suspend fun openEditing(noteId: String): FlashnoteDraftEntity = database.withTransaction {
        val note = dao.note(noteId) ?: throw FlashnoteNotFound()
        dao.editingDraft(noteId) ?: FlashnoteDraftEntity(
            key = unusedId(), title = note.title, content = note.content,
            editingNoteId = note.id, baseRevision = note.revision, updatedAt = clock.millis(),
            attachmentSignature = signature(attachments.noteAttachments(noteId)),
        ).also { draft ->
            dao.insertDraft(draft)
            attachments.noteAttachments(noteId).forEach {
                attachments.insertDraft(FlashnoteDraftAttachmentEntity(draft.key, it.asset.id, it.displayName, it.position))
            }
        }
    }

    /** 导入文件及关联由 LocalMediaStore 放在同一事务；版本变化使旧提交不能漏掉新附件。 */
    suspend fun attach(draft: FlashnoteDraftEntity, asset: MediaAssetEntity, name: String = asset.originalName ?: "附件"): FlashnoteDraftEntity = database.withTransaction {
        val stored = dao.draft(draft.key) ?: throw FlashnoteDraftNotFound()
        if (stored != draft) throw FlashnoteDraftConflict()
        requireNotNull(database.mediaDao().asset(asset.id))
        val current = attachments.draftAttachments(draft.key)
        if (current.any { it.asset.id == asset.id }) return@withTransaction stored
        attachments.insertDraft(FlashnoteDraftAttachmentEntity(draft.key, asset.id, name, (current.maxOfOrNull { it.position } ?: 0) + 1))
        updateAttachmentSnapshot(stored)
    }

    suspend fun removeAttachment(draft: FlashnoteDraftEntity, assetId: String): FlashnoteDraftEntity = database.withTransaction {
        val stored = dao.draft(draft.key) ?: throw FlashnoteDraftNotFound()
        if (stored != draft) throw FlashnoteDraftConflict()
        if (attachments.removeDraft(draft.key, assetId) == 0) return@withTransaction stored
        updateAttachmentSnapshot(stored).also { cleanUnused(listOf(assetId)) }
    }

    private suspend fun updateAttachmentSnapshot(draft: FlashnoteDraftEntity): FlashnoteDraftEntity {
        if (dao.updateAttachmentSignature(draft.key, signature(attachments.draftAttachments(draft.key)), clock.millis(), draft.revision) != 1) throw FlashnoteDraftConflict()
        return requireNotNull(dao.draft(draft.key))
    }

    /** 草稿可以为空；迟到保存只更新仍存在的同版本草稿，绝不重新插入。 */
    suspend fun saveInput(key: String, title: String, content: String, expectedRevision: Long): FlashnoteDraftEntity {
        require(expectedRevision >= 0) { "草稿版本无效" }
        return database.withTransaction {
            val draft = dao.draft(key) ?: throw FlashnoteDraftNotFound()
            if (draft.title == title && draft.content == content) return@withTransaction draft
            if (draft.revision != expectedRevision) throw FlashnoteDraftConflict()
            if (dao.updateDraft(key, title, content, clock.millis(), expectedRevision) != 1) throw FlashnoteDraftConflict()
            requireNotNull(dao.draft(key))
        }
    }

    /** 使用读取／保存后返回的草稿快照提交，正式写入和草稿清理在同一事务中完成。 */
    suspend fun save(draft: FlashnoteDraftEntity): FlashnoteEntity {
        require(draft.key.isNotBlank() && draft.revision >= 0) { "草稿标识或版本无效" }
        return database.withTransaction {
            val stored = dao.draft(draft.key)
            if (stored == null) return@withTransaction committedResult(draft)
            if (stored != draft) throw FlashnoteDraftConflict()
            val media = attachments.draftAttachments(stored.key)
            if (signature(media) != stored.attachmentSignature) throw FlashnoteDraftConflict()
            val text = validatedText(stored, media)
            val oldMedia = stored.editingNoteId?.let { attachments.noteAttachments(it) }.orEmpty()
            val note = if (stored.editingNoteId == null) {
                if (dao.note(stored.key) != null) throw FlashnoteConflict()
                val now = clock.millis()
                FlashnoteEntity(id = stored.key, title = text.title, content = text.content,
                    createdAt = now, updatedAt = now).also { dao.insertNote(it) }
            } else {
                val original = dao.note(stored.editingNoteId) ?: throw FlashnoteNotFound()
                if (stored.baseRevision != original.revision) throw FlashnoteConflict()
                if (original.title == text.title && original.content == text.content && signature(oldMedia) == stored.attachmentSignature) original
                else {
                    if (dao.updateContent(original.id, text.title, text.content, clock.millis(), original.revision) != 1) {
                        throw FlashnoteConflict()
                    }
                    requireNotNull(dao.note(original.id))
                }
            }
            attachments.clearNote(note.id)
            media.forEach { attachments.insertNote(FlashnoteAttachmentEntity(note.id, it.asset.id, it.displayName, it.position)) }
            if (dao.deleteDraftIfRevision(stored.key, stored.revision) != 1) throw FlashnoteDraftConflict()
            cleanUnused(oldMedia.map { it.asset.id })
            note
        }
    }

    suspend fun discardDraft(key: String, expectedRevision: Long): Boolean {
        require(expectedRevision >= 0) { "草稿版本无效" }
        return database.withTransaction {
            val draft = dao.draft(key) ?: return@withTransaction false
            val oldAssets = attachments.draftAttachments(key).map { it.asset.id }
            if (draft.revision != expectedRevision || dao.deleteDraftIfRevision(key, expectedRevision) != 1) {
                throw FlashnoteDraftConflict()
            }
            cleanUnused(oldAssets)
            true
        }
    }

    suspend fun complete(id: String, expectedRevision: Long): FlashnoteEntity =
        setStatus(id, FlashnoteStatus.COMPLETED, expectedRevision)

    suspend fun restore(id: String, expectedRevision: Long): FlashnoteEntity =
        setStatus(id, FlashnoteStatus.PENDING, expectedRevision)

    /** 显式目标状态保证重复请求不翻转状态，也不刷新首次完成时间。 */
    private suspend fun setStatus(id: String, status: FlashnoteStatus, expectedRevision: Long): FlashnoteEntity {
        require(expectedRevision > 0) { "闪念版本无效" }
        return database.withTransaction {
            val original = dao.note(id) ?: throw FlashnoteNotFound()
            if (original.status == status) return@withTransaction original
            if (original.revision != expectedRevision) throw FlashnoteConflict()
            val now = clock.millis()
            if (dao.updateStatus(id, status, if (status == FlashnoteStatus.COMPLETED) now else null, now, expectedRevision) != 1) {
                throw FlashnoteConflict()
            }
            if (status == FlashnoteStatus.COMPLETED) statistics.insert(FlashnoteCompletionEntity(id, completionDay(now)))
            else statistics.remove(id)
            if (status == FlashnoteStatus.COMPLETED) database.home().clearFlashnotePin(id)
            requireNotNull(dao.note(id))
        }
    }

    /** 重复删除返回已不存在；旧版本不能删除后来编辑或改变状态的事项。 */
    suspend fun delete(id: String, expectedRevision: Long): Boolean {
        require(expectedRevision > 0) { "闪念版本无效" }
        return database.withTransaction {
            val original = dao.note(id) ?: return@withTransaction false
            val oldAssets = attachments.noteAttachments(id).map { it.asset.id } +
                dao.editingDraft(id)?.let { attachments.draftAttachments(it.key).map { item -> item.asset.id } }.orEmpty()
            if (original.revision != expectedRevision) throw FlashnoteConflict()
            if (original.status == FlashnoteStatus.COMPLETED) {
                val day = statistics.completion(id)?.day ?: original.completedAt?.let(::completionDay)
                if (day != null) {
                    statistics.ensureArchive(FlashnoteCompletionArchiveEntity(day, 0))
                    statistics.incrementArchive(day)
                }
            }
            if (dao.deleteNoteIfRevision(id, expectedRevision) != 1) throw FlashnoteConflict()
            cleanUnused(oldAssets)
            true
        }
    }

    /** 已提交草稿的重试只核对当前记录，不回放旧正文，也不重建已删除事项。 */
    private suspend fun committedResult(draft: FlashnoteDraftEntity): FlashnoteEntity {
        val target = draft.editingNoteId ?: draft.key
        val note = dao.note(target) ?: throw if (draft.editingNoteId == null) FlashnoteDraftNotFound() else FlashnoteNotFound()
        val media = attachments.noteAttachments(target)
        val text = validatedText(draft, media)
        if (signature(media) != draft.attachmentSignature || note.title != text.title || note.content != text.content ||
            draft.editingNoteId != null && (draft.baseRevision == null || note.revision < draft.baseRevision)) {
            throw FlashnoteConflict()
        }
        return note
    }

    private suspend fun unusedId(): String {
        val id = nextId()
        check(id.isNotBlank() && dao.note(id) == null && dao.draft(id) == null) { "草稿标识冲突，请重试" }
        return id
    }

    private fun validatedText(draft: FlashnoteDraftEntity, media: List<FlashnoteAttachment>): FlashnoteText {
        require(draft.content.isNotBlank() || media.isNotEmpty()) { "请先输入想做的事情或添加附件" }
        val title = draft.title.trim().ifEmpty { draft.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: if (media.any { it.isImage }) "图片闪念" else media.first().displayName }
        return FlashnoteText(title, draft.content)
    }

    private fun signature(media: List<FlashnoteAttachment>): String = if (media.isEmpty()) "" else
        MessageDigest.getInstance("SHA-256").digest(media.joinToString("\n") { it.asset.id }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun completionDay(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(dayZone()).toLocalDate().toEpochDay()

    private suspend fun cleanUnused(ids: List<String>) {
        for (id in ids.distinct()) {
            val asset = database.mediaDao().asset(id) ?: continue
            if (database.mediaDao().deleteIfUnused(id) == 1) asset.relativePath?.let { database.mediaDao().queueCleanup(MediaCleanupEntity(it)) }
        }
    }

    private data class FlashnoteText(val title: String, val content: String)
}
