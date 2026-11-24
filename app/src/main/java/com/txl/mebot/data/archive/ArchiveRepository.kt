package com.txl.mebot.data.archive

import com.txl.mebot.data.local.*
import java.util.UUID
import org.json.JSONObject

/** 模型只能提供候选内容。目标由本地会话决定，确认后才事务保存正式档案。 */
class ArchiveRepository(private val storage: ArchiveStorage, private val now: () -> Long = System::currentTimeMillis) {
    private val dao get() = storage.dao

    suspend fun createSession(id: String = UUID.randomUUID().toString(), recordId: String? = null): String = storage.write {
        requireId(id)
        dao.session(id)?.let { existing -> require(existing.recordId == recordId); return@write id }
        val record = recordId?.let { requireNotNull(dao.record(it)).also { row -> check(row.deletedAt == null) } }
        val time = now()
        dao.insertSession(ArchiveSessionEntity(id, recordId, createdAt = time, updatedAt = time))
        dao.insertDraft(ArchiveDraftEntity(id, storage.encrypt(ArchiveCodec.draft(ArchiveDraftContent()), "draft:$id"), 0,
            baseRecordRevision = record?.revision, updatedAt = time))
        if (record != null) dao.recordAssets(record.id).forEach { link ->
            dao.insertSessionAsset(ArchiveSessionAssetEntity(id, link.assetId, link.position, false))
        }
        id
    }

    suspend fun records(limit: Int = 50, offset: Int = 0): List<ArchiveRecord> = storage.read {
        require(limit in 1..200 && offset >= 0)
        dao.records(limit, offset).map(storage::record)
    }
    suspend fun record(id: String): ArchiveRecord? = storage.read { dao.record(id)?.takeIf { it.deletedAt == null }?.let(storage::record) }
    suspend fun draft(sessionId: String): ArchiveDraft = storage.read { storage.draft(requireNotNull(dao.draft(sessionId))) }

    /** 首页只接收尚未开始对话的新档案输入；已有会话通过卡片或历史重新进入。 */
    suspend fun creationSession(preferred: String? = null): String = storage.write {
        val candidates = (listOfNotNull(preferred?.let { dao.session(it) }) + dao.unstartedSessions()).distinctBy { it.id }
        val existing = candidates.firstOrNull { session ->
            session.recordId == null && session.state == ArchiveSessionState.ACTIVE && dao.messages(session.id, Long.MAX_VALUE, 1).isEmpty() &&
                dao.draft(session.id)?.let { row -> row.activeRequestId == null && storage.draft(row).content.let { it.candidate == null && it.conflicts.isEmpty() } } == true
        }
        if (existing != null) return@write existing.id
        val id = UUID.randomUUID().toString(); val time = now()
        dao.insertSession(ArchiveSessionEntity(id, createdAt = time, updatedAt = time))
        dao.insertDraft(ArchiveDraftEntity(id, storage.encrypt(ArchiveCodec.draft(ArchiveDraftContent()), "draft:$id"), 0, updatedAt = time))
        id
    }

    /** 正式卡片始终回到首次建档的会话，不受历史分页或最近编辑次序影响。 */
    suspend fun conversationForRecord(id: String): String = storage.read {
        val record = requireNotNull(dao.record(id)).also { check(it.deletedAt == null) }
        val session = requireNotNull(dao.session(record.sourceSessionId))
        check(session.recordId == id && session.state == ArchiveSessionState.ACTIVE)
        session.id
    }

    suspend fun sessions(limit: Int = 50, offset: Int = 0): List<ArchiveSessionSummary> = storage.read {
        require(limit in 1..100 && offset >= 0)
        dao.sessions(limit, offset).map { session ->
            val record = session.recordId?.let { dao.record(it) }?.takeIf { it.deletedAt == null }?.let(storage::record)
            val draft = dao.draft(session.id)?.let(storage::draft)?.content
            val last = dao.messages(session.id, Long.MAX_VALUE, 1).firstOrNull()?.let { storage.decrypt(it.payload, "message:${it.id}").getString("text") }.orEmpty()
            ArchiveSessionSummary(session.id, session.recordId,
                record?.content?.title ?: draft?.candidate?.title?.takeIf(String::isNotBlank) ?: if (session.state == ArchiveSessionState.TARGET_DELETED) "已删除的档案" else "未完成的档案",
                (draft?.input?.takeIf(String::isNotBlank) ?: draft?.candidate?.body?.takeIf(String::isNotBlank) ?: last).take(160), session.state == ArchiveSessionState.TARGET_DELETED)
        }
    }

    suspend fun openSession(sessionId: String): ArchiveContext {
        storage.read { val session = requireNotNull(dao.session(sessionId)); dao.updateSession(session.copy(updatedAt = now())) }
        return context(sessionId)
    }

    suspend fun context(sessionId: String, before: Long = Long.MAX_VALUE, limit: Int = 50): ArchiveContext = storage.read {
        require(limit in 1..100)
        val session = requireNotNull(dao.session(sessionId))
        ArchiveContext(sessionId, session.recordId?.let { dao.record(it) }?.takeIf { it.deletedAt == null }?.let(storage::record),
            storage.draft(requireNotNull(dao.draft(sessionId))), dao.messages(sessionId, before, limit).asReversed().map { row ->
                ArchiveChatMessage(row.id, row.role, storage.decrypt(row.payload, "message:${row.id}").getString("text"),
                    dao.messageAssets(row.id).map { it.assetId }, row.sequence)
            }, dao.sessionAssets(sessionId).map { link ->
                val asset = requireNotNull(dao.asset(link.assetId))
                ArchiveLocalAsset(asset.id, ArchiveCodec.asset(storage.decrypt(asset.payload, "asset:${asset.id}")), link.pending)
            }, session.state == ArchiveSessionState.TARGET_DELETED)
    }

    /** 手动编辑允许标题暂时为空；正式确认仍校验必填项。原始消息一直留在会话中。 */
    suspend fun prepareManualEditor(sessionId: String, reloadSaved: Boolean = false): ArchiveDraft = storage.write {
        val session = storage.activeSession(sessionId)
        val old = requireNotNull(dao.draft(sessionId)); val draft = storage.draft(old).content
        if (!reloadSaved && draft.candidate != null) return@write storage.draft(old)
        val record = session.recordId?.let { requireNotNull(dao.record(it)) }
        val saved = record?.let(storage::content)
        val links = dao.sessionAssets(sessionId)
        if (reloadSaved && record != null) dao.recordAssets(record.id).filter { row -> links.none { it.assetId == row.assetId } }.forEach { row ->
            dao.insertSessionAsset(ArchiveSessionAssetEntity(sessionId, row.assetId, row.position, false))
        }
        val seed = draft.input.takeIf(String::isNotBlank) ?: dao.messages(sessionId, Long.MAX_VALUE, 100)
            .firstOrNull { it.role == MessageRole.USER }?.let { storage.decrypt(it.payload, "message:${it.id}").getString("text") }.orEmpty()
        val assets = if (saved == null) links.map { it.assetId } else (saved.assetIds + links.filter { it.pending }.map { it.assetId }).distinct()
        val firstImage = assets.firstOrNull { id -> dao.asset(id)?.let { ArchiveCodec.asset(storage.decrypt(it.payload, "asset:$id")).mimeType.startsWith("image/") } == true }
        val content = saved?.copy(assetIds = assets) ?: ArchiveContent(seed.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80),
            body = seed, rawInput = seed, links = draft.analysis.sourceLinks, assetIds = assets, coverAssetId = firstImage)
        val updated = old.copy(payload = storage.encrypt(ArchiveCodec.draft(draft.copy(candidate = content,
            conflicts = if (reloadSaved) emptyList() else draft.conflicts, analysis = ArchiveAnalysisMeta())), "draft:$sessionId"),
            revision = old.revision + 1, baseRecordRevision = record?.revision, activeRequestId = null, requestRecordRevision = null, updatedAt = now())
        dao.updateDraft(updated); storage.draft(updated)
    }

    suspend fun saveDraft(sessionId: String, expectedRevision: Long, content: ArchiveDraftContent): ArchiveDraft = storage.write {
        storage.activeSession(sessionId)
        validateDraft(content)
        val old = requireNotNull(dao.draft(sessionId)); checkRevision(old.revision, expectedRevision)
        val updated = old.copy(payload = storage.encrypt(ArchiveCodec.draft(content), "draft:$sessionId"),
            revision = old.revision + 1, activeRequestId = null, requestRecordRevision = null, updatedAt = now())
        dao.updateDraft(updated)
        val session = requireNotNull(dao.session(sessionId))
        dao.updateSession(session.copy(updatedAt = updated.updatedAt))
        storage.draft(updated)
    }

    suspend fun sendMessage(sessionId: String, messageId: String, expectedDraftRevision: Long, text: String, assetIds: List<String>): ArchiveChatMessage = storage.write {
        requireId(messageId); require(text.length <= 200_000 && (text.isNotBlank() || assetIds.isNotEmpty()))
        require(assetIds.distinct().size == assetIds.size && assetIds.size <= 100)
        dao.message(messageId)?.let { old ->
            require(old.sessionId == sessionId && old.role == MessageRole.USER) { "消息标识已使用" }
            require(storage.decrypt(old.payload, "message:$messageId").getString("text") == text && dao.messageAssets(messageId).map { it.assetId } == assetIds) { "消息标识已使用" }
            return@write ArchiveChatMessage(messageId, old.role, text, assetIds, old.sequence)
        }
        val session = storage.activeSession(sessionId)
        val old = requireNotNull(dao.draft(sessionId)); checkRevision(old.revision, expectedDraftRevision)
        val draft = storage.draft(old).content
        require(draft.input == text) { "待发送内容与本地草稿不一致" }
        val scoped = dao.sessionAssets(sessionId).associateBy { it.assetId }
        assetIds.forEach { require(scoped[it]?.pending == true) { "附件不属于本次输入" } }
        val time = now(); val sequence = dao.nextSequence(sessionId)
        dao.insertMessage(ArchiveMessageEntity(messageId, sessionId, sequence, MessageRole.USER,
            storage.encrypt(JSONObject().put("text", text), "message:$messageId"), time))
        dao.insertMessageAssets(assetIds.mapIndexed { index, assetId -> ArchiveMessageAssetEntity(messageId, sessionId, assetId, index.toLong()) })
        assetIds.forEach { dao.updateSessionAsset(scoped.getValue(it).copy(pending = false)) }
        dao.updateDraft(old.copy(payload = storage.encrypt(ArchiveCodec.draft(draft.copy(input = "")), "draft:$sessionId"),
            revision = old.revision + 1, activeRequestId = null, requestRecordRevision = null, updatedAt = time))
        dao.updateSession(session.copy(updatedAt = time))
        ArchiveChatMessage(messageId, MessageRole.USER, text, assetIds, sequence)
    }

    suspend fun beginAnalysis(sessionId: String, requestId: String, expectedDraftRevision: Long, sourceMessageId: String? = null): ArchiveAnalysisRequest = storage.write {
        requireId(requestId)
        val session = storage.activeSession(sessionId)
        val old = requireNotNull(dao.draft(sessionId)); checkRevision(old.revision, expectedDraftRevision)
        if (sourceMessageId != null) require(dao.latestUserMessage(sessionId)?.id == sourceMessageId) { "请求所属消息已变化" }
        val revision = session.recordId?.let { requireNotNull(dao.record(it)).revision }
        val previous = storage.draft(old).content
        val content = previous.copy(analysis = previous.analysis.copy(status = ArchiveAnalysisStatus.RUNNING, sourceMessageId = sourceMessageId,
            failure = null, imageCount = 0, omittedImages = 0, requestId = requestId))
        val updated = old.copy(payload = storage.encrypt(ArchiveCodec.draft(content), "draft:$sessionId"), revision = old.revision + 1,
            activeRequestId = requestId, requestRecordRevision = revision, updatedAt = now())
        dao.updateDraft(updated)
        ArchiveAnalysisRequest(sessionId, requestId, updated.revision, revision)
    }

    /** 返回 false 表示响应已过期；不会写入其他会话，也不会覆盖用户刚输入的草稿。 */
    suspend fun completeAnalysis(request: ArchiveAnalysisRequest, candidate: ArchiveContent?, conflicts: List<String>, reply: String,
        meta: ArchiveAnalysisMeta? = null, allowIncomplete: Boolean = false): Boolean = storage.write {
        val session = dao.session(request.sessionId) ?: return@write false
        val old = dao.draft(request.sessionId) ?: return@write false
        val current = session.recordId?.let { dao.record(it) }
        if (session.state != ArchiveSessionState.ACTIVE || current?.deletedAt != null || current?.revision != request.recordRevision ||
            old.activeRequestId != request.requestId || old.revision != request.draftRevision || old.requestRecordRevision != request.recordRevision) return@write false
        require(reply.length <= 200_000)
        val previous = storage.draft(old).content
        val content = previous.copy(candidate = candidate?.validated(allowBlankTitle = allowIncomplete), conflicts = conflicts,
            analysis = meta ?: previous.analysis.copy(status = if (candidate == null) ArchiveAnalysisStatus.NEEDS_INPUT else ArchiveAnalysisStatus.READY, failure = null))
        require(content.candidate?.assetIds.orEmpty().all { id -> dao.sessionAssets(request.sessionId).any { it.assetId == id } })
        validateDraft(content)
        val messageId = "analysis:${request.sessionId}:${request.requestId}:${request.draftRevision}"
        val time = now()
        dao.insertMessage(ArchiveMessageEntity(messageId, request.sessionId, dao.nextSequence(request.sessionId), MessageRole.ASSISTANT,
            storage.encrypt(JSONObject().put("text", reply), "message:$messageId"), time))
        dao.updateDraft(old.copy(payload = storage.encrypt(ArchiveCodec.draft(content), "draft:${request.sessionId}"),
            revision = old.revision + 1, baseRecordRevision = request.recordRevision, activeRequestId = null, requestRecordRevision = null, updatedAt = time))
        dao.updateSession(session.copy(updatedAt = time))
        true
    }

    suspend fun cancelAnalysis(sessionId: String, requestId: String, status: ArchiveAnalysisStatus = ArchiveAnalysisStatus.CANCELLED,
        failure: ArchiveAnalysisFailure? = null) = storage.write {
        val draft = requireNotNull(dao.draft(sessionId))
        if (draft.activeRequestId == requestId) {
            val content = storage.draft(draft).content
            dao.updateDraft(draft.copy(payload = storage.encrypt(ArchiveCodec.draft(content.copy(analysis = content.analysis.copy(status = status, failure = failure))), "draft:$sessionId"),
                activeRequestId = null, requestRecordRevision = null, revision = draft.revision + 1, updatedAt = now()))
        }
    }

    suspend fun latestUserMessage(sessionId: String): String? = storage.read { dao.latestUserMessage(sessionId)?.id }

    suspend fun confirm(sessionId: String, operationId: String, expectedDraftRevision: Long): ArchiveSaveReceipt = storage.write {
        requireId(operationId)
        dao.operation(operationId)?.let { old ->
            require(old.sessionId == sessionId && old.draftRevision == expectedDraftRevision && old.kind != ArchiveOperationKind.DELETE) { "保存标识已用于其他操作" }
            storage.decrypt(old.payload, "operation:$operationId") // 同时验证保存回执的密文完整性。
            return@write ArchiveSaveReceipt(operationId, old.recordId, old.resultRevision)
        }
        val session = storage.activeSession(sessionId)
        val draftRow = requireNotNull(dao.draft(sessionId)); checkRevision(draftRow.revision, expectedDraftRevision)
        check(draftRow.activeRequestId == null) { "请等待本次整理完成" }
        val draft = storage.draft(draftRow).content
        check(draft.conflicts.isEmpty()) { "请先解决档案中的待确认问题" }
        val candidate = requireNotNull(draft.candidate) { "还没有可保存的档案" }.validated()
        val scopedIds = dao.sessionAssets(sessionId).map { it.assetId }.toSet()
        require(candidate.assetIds.all { it in scopedIds }) { "候选附件不属于本会话" }
        val previous = session.recordId?.let { requireNotNull(dao.record(it)) }
        if (previous?.revision != draftRow.baseRecordRevision) throw ArchiveConflict()
        val id = previous?.id ?: UUID.randomUUID().toString()
        val changed = previous == null || storage.content(previous) != candidate
        val time = now(); val revision = (previous?.revision ?: 0L) + if (changed) 1 else 0
        val record = if (!changed) requireNotNull(previous) else ArchiveRecordEntity(id, previous?.sourceSessionId ?: sessionId,
            storage.encrypt(ArchiveCodec.content(candidate), "record:$id"), revision, previous?.createdAt ?: time, time)
        if (previous == null) {
            dao.insertRecord(record)
            check(dao.bindSession(sessionId, id, time) == 1)
        } else if (changed) dao.updateRecord(record)
        if (changed) {
            dao.clearRecordAssets(id)
            dao.insertRecordAssets(candidate.assetIds.mapIndexed { index, assetId -> ArchiveRecordAssetEntity(id, assetId, index.toLong()) })
            storage.enqueueIndex(record, time)
        }
        dao.sessionAssets(sessionId).filter { it.assetId in candidate.assetIds && it.pending }.forEach { dao.updateSessionAsset(it.copy(pending = false)) }
        dao.insertOperation(ArchiveOperationEntity(operationId, sessionId, id,
            if (previous == null) ArchiveOperationKind.CREATE else ArchiveOperationKind.UPDATE,
            expectedDraftRevision, revision, storage.encrypt(ArchiveCodec.content(candidate), "operation:$operationId"), time))
        dao.updateDraft(draftRow.copy(payload = storage.encrypt(ArchiveCodec.draft(draft.copy(candidate = null, conflicts = emptyList(), analysis = ArchiveAnalysisMeta())), "draft:$sessionId"),
            revision = draftRow.revision + 1, baseRecordRevision = revision, updatedAt = time))
        dao.updateSession(requireNotNull(dao.session(sessionId)).copy(updatedAt = time))
        ArchiveSaveReceipt(operationId, id, revision)
    }

    /** 删除保留绑定墓碑，旧会话与迟到响应均不能复活档案。历史记录按后续页面功能提供清理。 */
    suspend fun delete(sessionId: String, operationId: String, expectedRecordRevision: Long): ArchiveSaveReceipt = storage.write {
        requireId(operationId)
        dao.operation(operationId)?.let { old ->
            require(old.sessionId == sessionId && old.kind == ArchiveOperationKind.DELETE && old.draftRevision == expectedRecordRevision)
            storage.decrypt(old.payload, "operation:$operationId")
            return@write ArchiveSaveReceipt(operationId, old.recordId, old.resultRevision)
        }
        val session = storage.activeSession(sessionId)
        val record = requireNotNull(dao.record(requireNotNull(session.recordId)))
        checkRevision(record.revision, expectedRecordRevision)
        val time = now(); val revision = record.revision + 1
        dao.updateRecord(record.copy(payload = null, revision = revision, updatedAt = time, deletedAt = time))
        dao.clearRecordAssets(record.id); dao.clearEmbeddings(record.id); dao.deleteJob(record.id)
        dao.recordSessions(record.id).forEach { related ->
            dao.updateSession(related.copy(state = ArchiveSessionState.TARGET_DELETED, updatedAt = time))
            dao.draft(related.id)?.let { draft -> dao.updateDraft(draft.copy(
                payload = storage.encrypt(ArchiveCodec.draft(storage.draft(draft).content.copy(candidate = null, conflicts = emptyList(), analysis = ArchiveAnalysisMeta())), "draft:${related.id}"),
                revision = draft.revision + 1, activeRequestId = null, requestRecordRevision = null, updatedAt = time)) }
        }
        dao.insertOperation(ArchiveOperationEntity(operationId, sessionId, record.id, ArchiveOperationKind.DELETE, expectedRecordRevision, revision,
            storage.encrypt(JSONObject().put("deleted", true), "operation:$operationId"), time))
        ArchiveSaveReceipt(operationId, record.id, revision)
    }

    private fun requireId(id: String) { require(id.isNotBlank() && id.length <= 128) { "无效操作标识" } }
    private fun checkRevision(actual: Long, expected: Long) { if (actual != expected) throw ArchiveConflict() }
    private fun validateDraft(content: ArchiveDraftContent) {
        require(content.input.length <= 200_000 && content.conflicts.size <= 100 && content.conflicts.all { it.length <= 2_000 })
        content.candidate?.validated(allowBlankTitle = true)
        require(content.analysis.sourceLinks.size <= 100 && content.analysis.sourceLinks.all { it.url.length <= 4_000 && it.label.length <= 300 })
    }
}
