package com.txl.mebot.data.dayima

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

data class DayimaChatSnapshot(val session: DayimaChatSessionEntity, val draft: DayimaChatDraftEntity,
    val messages: List<DayimaChatMessageEntity>, val operation: DayimaChatOperationEntity?,
    val images: List<DayimaImage> = emptyList(), val messageImages: Map<String, List<DayimaImage>> = emptyMap())

/** 独立聊天、输入和请求状态统一持久化；清聊天不会删除业务事实。 */
class DayimaConversationRepository(private val database: MebotDatabase, private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val dao = database.dayimaDao()

    suspend fun open(id: String? = null, new: Boolean = false, recover: Boolean = true): DayimaChatSnapshot = withContext(Dispatchers.IO) {
        database.withTransaction {
            val selected = if (new) null else id?.let { dao.session(it) } ?: dao.latestActiveSession()
            val session = selected ?: DayimaChatSessionEntity(UUID.randomUUID().toString(), createdAt = clock.millis(), updatedAt = clock.millis()).also {
                dao.insertSession(it); dao.insertDraft(DayimaChatDraftEntity(it.id, updatedAt = clock.millis()))
            }
            for (other in dao.activeSessions().filter { it.id != session.id }) {
                dao.interruptChatOperations(other.id, clock.millis())
                check(dao.updateSession(other.id, other.title, DayimaChatStatus.CLOSED, clock.millis(), other.revision) == 1)
            }
            if (recover) dao.interruptChatOperations(session.id, clock.millis())
            touch(session.id, active = true)
            snapshotInside(session.id) ?: throw DayimaChatChanged()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(id: String) = dao.observeSession(id).mapLatest { snapshot(id) }
    fun observeSessions(limit: Int = 30) = dao.observeSessions(limit)
    suspend fun snapshot(id: String): DayimaChatSnapshot? = withContext(Dispatchers.IO) { database.withTransaction { snapshotInside(id) } }
    private suspend fun snapshotInside(id: String): DayimaChatSnapshot? {
        val session = dao.session(id) ?: return null
        val draft = dao.draft(id) ?: DayimaChatDraftEntity(id, updatedAt = clock.millis()).also { dao.insertDraft(it) }
        val messages = dao.messages(id, 40)
        return DayimaChatSnapshot(session, draft, messages, dao.latestChatOperation(id), database.dayimaMediaDao().draftImages(id),
            messageImages(id, messages.map { it.id }))
    }

    suspend fun messageImages(id: String, messageIds: List<String>) = database.dayimaMediaDao().messageImages(id, messageIds)
        .groupBy { it.messageId }.mapValues { (_, images) -> images.map { it.image() } }

    suspend fun older(id: String, before: Long) = withContext(Dispatchers.IO) { dao.messages(id, 40, before) }
    suspend fun sentMessage(id: String, messageId: String): DayimaChatMessageEntity? = withContext(Dispatchers.IO) {
        dao.chatMessage(messageId)?.takeIf { it.sessionId == id && it.role == DayimaMessageRole.USER }
    }
    suspend fun saveInput(id: String, text: String, expectedRevision: Long): DayimaChatDraftEntity = withContext(Dispatchers.IO) {
        require(text.length <= 6000)
        database.withTransaction {
            active(id)
            val draft = dao.draft(id) ?: throw DayimaChatChanged()
            if (draft.revision != expectedRevision) throw DayimaChatChanged()
            if (draft.inputText != text) {
                if (dao.updateDraft(id, text, clock.millis(), expectedRevision) != 1) throw DayimaChatChanged()
                touch(id)
            }
            requireNotNull(dao.draft(id))
        }
    }

    suspend fun send(id: String, text: String, messageId: String, draftRevision: Long): DayimaChatMessageEntity = withContext(Dispatchers.IO) {
        require(text.length <= 6000)
        database.withTransaction {
            active(id)
            dao.chatMessage(messageId)?.let { existing ->
                if (existing.sessionId != id || existing.role != DayimaMessageRole.USER || existing.content != text) throw DayimaChatChanged()
                return@withTransaction existing
            }
            val draft = dao.draft(id) ?: throw DayimaChatChanged()
            if (draft.revision != draftRevision || draft.inputText != text) throw DayimaChatChanged()
            val images = database.dayimaMediaDao().draftLinks(id)
            require(text.isNotBlank() || images.isNotEmpty())
            val previous = dao.latestChatOperation(id)
            if (previous?.state == DayimaOperationState.PENDING) throw DayimaChatChanged()
            val context = JSONObject().put("zone", zone().id)
            val imageSources = mutableListOf<String>()
            if (previous?.state == DayimaOperationState.WAITING_FOR_INPUT) {
                context.put("clarification_of", previous.requestMessageId)
                val prior = JSONObject(previous.resultJson ?: "{}").optJSONArray("media_sources")
                if (prior != null) for (index in 0 until prior.length()) imageSources += prior.getString(index)
                updateOperation(previous, DayimaOperationState.CANCELLED)
            }
            val now = clock.millis()
            val message = DayimaChatMessageEntity(messageId, id, dao.lastMessageSequence(id) + 1, DayimaMessageRole.USER, text, now)
            dao.insertMessage(message)
            images.forEach { image -> database.dayimaMediaDao().insertMessage(DayimaMessageMediaEntity(
                messageId, image.assetId, image.displayName, image.position, now)) }
            database.dayimaMediaDao().clearDraft(id)
            if (images.isNotEmpty()) imageSources += messageId
            context.put("media_sources", JSONArray(imageSources.distinct()))
            dao.insertOperation(DayimaChatOperationEntity(messageId, id, resultJson = context.toString(), createdAt = now, updatedAt = now))
            if (dao.updateDraft(id, "", now, draftRevision) != 1) throw DayimaChatChanged()
            val session = active(id)
            check(dao.updateSession(id, session.title.ifBlank { text.lineSequence().first().take(32).ifBlank { "图片记录" } }, session.status, now, session.revision) == 1)
            message
        }
    }

    suspend fun interrupt(id: String) = withContext(Dispatchers.IO) { database.withTransaction {
        if (dao.session(id) != null) { dao.interruptChatOperations(id, clock.millis()); touch(id) }
    } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { database.withTransaction {
        val assets = database.dayimaMediaDao().sessionAssetIds(id)
        dao.session(id)?.let { if (dao.deleteSession(id, it.revision) != 1) throw DayimaChatChanged() }
        releaseDayimaImages(database, assets)
    } }

    internal suspend fun active(id: String): DayimaChatSessionEntity = dao.session(id)?.takeIf { it.status == DayimaChatStatus.ACTIVE } ?: throw DayimaChatChanged()
    internal suspend fun touch(id: String, active: Boolean = false) {
        val session = dao.session(id) ?: throw DayimaChatChanged()
        if (dao.updateSession(id, session.title, if (active) DayimaChatStatus.ACTIVE else session.status, clock.millis(), session.revision) != 1) throw DayimaChatChanged()
    }
    internal suspend fun updateOperation(op: DayimaChatOperationEntity, state: DayimaOperationState,
        action: DayimaOperationAction? = op.action, question: String? = op.question, result: String? = op.resultJson,
        failure: String? = null, attempt: String? = null) {
        if (dao.updateOperation(op.requestMessageId, state, action, question, result, failure, attempt, clock.millis(), op.revision) != 1) throw DayimaChatChanged()
        touch(op.sessionId)
    }
}
