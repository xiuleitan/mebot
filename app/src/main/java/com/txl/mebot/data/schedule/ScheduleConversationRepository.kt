package com.txl.mebot.data.schedule

import androidx.room.withTransaction
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ScheduleConversationDraftEntity
import com.txl.mebot.data.local.SchedulePendingQuestionEntity
import java.time.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ConversationDraftConflict : IllegalStateException("草稿已改变，请重新打开会话后继续")

/** 会话可以先于任务存在；卡片仅通过逻辑任务找到同一个会话。 */
class ScheduleConversationRepository(
    private val database: MebotDatabase,
    private val schedules: ScheduleRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.conversationDao()
    private val scheduleDao = database.scheduleDao()

    /** 新建入口始终恢复唯一的未完成会话；升级后的旧草稿也在这里收敛为一份。 */
    suspend fun openCreation(): ScheduleConversationDraftEntity = database.withTransaction {
        val sessionId = dao.latestCreationSessionId() ?: schedules.openSession().id
        val draft = ensureDraft(sessionId)
        discardOtherCreationSessions(sessionId)
        draft
    }

    /** 清空消息、追问和输入，替换会话 ID；已有任务及其历史不参与删除。 */
    suspend fun clearCreation(): ScheduleConversationDraftEntity = database.withTransaction {
        val draft = ensureDraft(schedules.openSession().id)
        discardOtherCreationSessions(draft.sessionId)
        draft
    }

    suspend fun openConversation(sessionId: String): ScheduleConversationDraftEntity = database.withTransaction {
        requireNotNull(scheduleDao.session(sessionId)) { "会话不存在" }
        ensureDraft(sessionId)
    }

    suspend fun openForTask(taskId: String): ScheduleConversationDraftEntity = database.withTransaction {
        val task = requireNotNull(scheduleDao.task(taskId)) { "日程不存在" }
        openConversation(task.sessionId)
    }

    suspend fun openForOccurrence(occurrenceId: String): ScheduleConversationDraftEntity = database.withTransaction {
        val occurrence = requireNotNull(scheduleDao.occurrence(occurrenceId)) { "日程卡片不存在" }
        openForTask(occurrence.taskId)
    }

    suspend fun saveInput(sessionId: String, text: String, expectedRevision: Long): ScheduleConversationDraftEntity =
        database.withTransaction {
            val draft = openConversation(sessionId)
            if (draft.revision != expectedRevision) throw ConversationDraftConflict()
            if (draft.inputText == text) return@withTransaction draft
            if (dao.saveDraft(sessionId, text, expectedRevision, clock.millis()) != 1) throw ConversationDraftConflict()
            scheduleDao.touchSession(sessionId, clock.millis())
            requireNotNull(dao.draft(sessionId))
        }

    /** 消息入库与清空输入框是一个事务；重试同一消息不会清掉后来输入的草稿。 */
    suspend fun sendUserMessage(
        sessionId: String, messageId: String, text: String, expectedRevision: Long,
        occurrenceContextId: String? = null,
    ): ScheduleConversationDraftEntity = database.withTransaction {
        val content = text.trim()
        val draft = openConversation(sessionId)
        if (scheduleDao.message(messageId) != null) {
            val sent = scheduleDao.attachmentsForMessage(messageId).sortedBy { it.position }.map { it.assetId }
            schedules.appendMessage(sessionId, messageId, MessageRole.USER, content, sent, occurrenceContextId)
            return@withTransaction draft
        }
        if (draft.revision != expectedRevision) throw ConversationDraftConflict()
        require(draft.inputText.isEmpty() || draft.inputText == text) { "发送内容与当前草稿不同" }
        val media = database.scheduleMediaDao()
        val pending = media.attachments(sessionId).filter { it.link.pendingSend }.map { it.asset.id }
        require(content.isNotBlank() || pending.isNotEmpty())
        schedules.appendMessage(sessionId, messageId, MessageRole.USER, content, pending, occurrenceContextId)
        check(dao.saveDraft(sessionId, "", expectedRevision, clock.millis()) == 1)
        requireNotNull(dao.draft(sessionId))
    }

    /** 供后续模型结果接入使用；消息和追问同时保存，不在 UI 伪造助手回复。 */
    suspend fun recordQuestion(sessionId: String, questionId: String, question: String) = database.withTransaction {
        require(questionId.isNotBlank() && question.isNotBlank())
        openConversation(sessionId)
        val existing = dao.question(questionId)
        if (existing != null) {
            require(existing.sessionId == sessionId && existing.question == question.trim())
            return@withTransaction
        }
        schedules.appendMessage(sessionId, questionId, MessageRole.ASSISTANT, question.trim())
        dao.insertQuestion(SchedulePendingQuestionEntity(questionId, sessionId, question.trim(), clock.millis()))
    }

    suspend fun resolveQuestion(sessionId: String, questionId: String, answerMessageId: String) = database.withTransaction {
        val question = requireNotNull(dao.question(questionId))
        val answer = requireNotNull(scheduleDao.message(answerMessageId))
        require(question.sessionId == sessionId && answer.sessionId == sessionId && answer.role == MessageRole.USER)
        val prompt = requireNotNull(scheduleDao.message(questionId))
        require(answer.sequence > prompt.sequence) { "回答必须位于追问之后" }
        if (question.answeredAt != null) {
            require(question.answerMessageId == answerMessageId)
        } else {
            dao.answerQuestion(questionId, answerMessageId, clock.millis())
            scheduleDao.touchSession(sessionId, clock.millis())
        }
    }

    fun observeMessages(sessionId: String, limit: Int) = dao.observeMessages(sessionId, limit.coerceAtLeast(1))
        .map { it.asReversed() }.distinctUntilChanged()
    fun observePendingQuestions(sessionId: String) = dao.observePendingQuestions(sessionId).distinctUntilChanged()
    fun observeAwaitingAnalysis(sessionId: String) = dao.observeAwaitingAnalysis(sessionId).distinctUntilChanged()

    private suspend fun discardOtherCreationSessions(keepSessionId: String) {
        val media = dao.otherCreationMedia(keepSessionId)
        dao.deleteOtherCreationQuestions(keepSessionId)
        dao.deleteOtherCreationAttachmentReferences(keepSessionId)
        dao.deleteOtherCreationLocalAttachments(keepSessionId)
        dao.deleteOtherCreationMessages(keepSessionId)
        dao.deleteOtherCreationDrafts(keepSessionId)
        dao.deleteOtherCreationSessions(keepSessionId)
        for (asset in media) if (database.mediaDao().deleteIfUnused(asset.id) == 1) asset.relativePath?.let {
            database.mediaDao().queueCleanup(com.txl.mebot.data.local.MediaCleanupEntity(it))
        }
    }

    private suspend fun ensureDraft(sessionId: String): ScheduleConversationDraftEntity {
        dao.insertDraft(ScheduleConversationDraftEntity(sessionId, "", 0, clock.millis()))
        return requireNotNull(dao.draft(sessionId))
    }
}
