package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class SubscriptionDraftConflict : IllegalStateException("输入草稿已改变，请重新读取后再继续")

class SubscriptionConversationRepository(private val storage: SubscriptionStorage, private val clock: Clock = Clock.systemUTC()) {
    private val dao = storage.conversations

    /** 每次明确新建均生成独立 ID，保留之前未完成的草稿与聊天。 */
    suspend fun newConversation(id: String = UUID.randomUUID().toString()): SubscriptionConversationEntity = storage.database.withTransaction {
        requireSubscriptionId(id)
        dao.conversation(id)?.let { return@withTransaction it }
        val now = clock.millis()
        SubscriptionConversationEntity(id, createdAt = now, updatedAt = now).also {
            dao.insert(it)
            dao.insertDraft(SubscriptionDraftEntity(id, updatedAt = now))
        }
    }

    /** 手动保存的订阅首次进入聊天时建立专属会话；以后始终恢复同一份。 */
    suspend fun forSubscription(id: String): SubscriptionConversationEntity = storage.database.withTransaction {
        requireNotNull(storage.records.subscription(id)) { "订阅不存在" }
        dao.forSubscription(id)?.let { return@withTransaction it }
        val session = newConversation()
        check(dao.bind(session.id, id, clock.millis()) == 1)
        requireNotNull(dao.conversation(session.id))
    }

    suspend fun saveDraft(id: String, inputText: String, fieldsJson: String, expectedRevision: Long): SubscriptionDraftEntity =
        storage.database.withTransaction {
            require(inputText.length <= 100_000 && fieldsJson.length <= 100_000) { "输入内容过长" }
            val canonical = canonicalSubscriptionJson(JSONObject(fieldsJson))
            val draft = requireNotNull(dao.draft(id)) { "草稿不存在" }
            if (draft.revision != expectedRevision) throw SubscriptionDraftConflict()
            require(draft.formatVersion == 1) { "不支持的草稿格式，已保留原始内容" }
            if (draft.inputText == inputText && draft.fieldsJson == canonical) return@withTransaction draft
            if (dao.saveDraft(id, inputText, canonical, expectedRevision, clock.millis()) != 1) throw SubscriptionDraftConflict()
            dao.touch(id, clock.millis())
            requireNotNull(dao.draft(id))
        }

    /** 消息与附件引用成功入库后才清空本次输入；同一消息重试不能清掉后来写的草稿。 */
    suspend fun sendUserMessage(id: String, messageId: String, text: String, assetIds: List<String>, expectedRevision: Long): SubscriptionMessageEntity =
        storage.database.withTransaction {
            repeatedMessage(id, messageId, MessageRole.USER, text.trim(), assetIds)?.let { return@withTransaction it }
            val draft = requireNotNull(dao.draft(id)) { "草稿不存在" }
            if (draft.revision != expectedRevision) throw SubscriptionDraftConflict()
            require(draft.inputText.trim() == text.trim()) { "发送内容与当前输入草稿不同" }
            val item = append(id, messageId, MessageRole.USER, text.trim(), assetIds)
            if (dao.saveDraft(id, "", draft.fieldsJson, expectedRevision, clock.millis()) != 1) throw SubscriptionDraftConflict()
            item
        }

    suspend fun appendAssistantMessage(id: String, messageId: String, text: String): SubscriptionMessageEntity = storage.database.withTransaction {
        repeatedMessage(id, messageId, MessageRole.ASSISTANT, text.trim(), emptyList())
            ?: append(id, messageId, MessageRole.ASSISTANT, text.trim(), emptyList())
    }

    fun observeDraft(id: String) = dao.observeDraft(id)
    fun observeMessages(id: String, limit: Int = 50) = dao.observeMessages(id, limit.coerceIn(1, 200)).map { it.asReversed() }
    suspend fun messages(id: String, limit: Int = 50, offset: Int = 0) = dao.messages(id, limit.coerceIn(1, 200), offset.coerceAtLeast(0)).asReversed()

    private suspend fun repeatedMessage(id: String, messageId: String, role: MessageRole, content: String,
        assetIds: List<String>): SubscriptionMessageEntity? {
        requireSubscriptionId(messageId)
        val old = dao.message(messageId) ?: return null
        require(old.conversationId == id && old.role == role && old.content == content &&
            dao.messageMedia(messageId).map { it.assetId } == assetIds) { "消息 ID 已用于其他内容或会话" }
        return old
    }

    private suspend fun append(id: String, messageId: String, role: MessageRole, content: String,
        assetIds: List<String>): SubscriptionMessageEntity {
        requireNotNull(dao.conversation(id)) { "会话不存在" }
        require(content.length <= 100_000 && (content.isNotBlank() || assetIds.isNotEmpty())) { "请输入内容或添加图片" }
        require(assetIds.size <= 20 && assetIds.distinct().size == assetIds.size) { "附件数量过多或重复" }
        val links = dao.mediaLinks(id).associateBy { it.assetId }
        val attached = assetIds.map { requireNotNull(links[it]) { "附件不属于当前会话" } }
        val now = clock.millis()
        val item = SubscriptionMessageEntity(messageId, id, dao.nextSequence(id), role, content, now)
        dao.insertMessage(item)
        dao.insertMessageMedia(attached.mapIndexed { position, link ->
            SubscriptionMessageMediaEntity(messageId, id, link.assetId, link.displayName, position.toLong())
        })
        attached.forEach { dao.markPending(id, it.assetId, false) }
        dao.touch(id, now)
        return item
    }
}
