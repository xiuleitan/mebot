package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Insert suspend fun insert(item: SubscriptionEntity)
    @Update suspend fun update(item: SubscriptionEntity): Int
    @Query("SELECT * FROM subscriptions WHERE id = :id") suspend fun subscription(id: String): SubscriptionEntity?
    @Query("""SELECT * FROM subscriptions WHERE status IN ('ACTIVE', 'CANCEL_AT_PERIOD_END')
        ORDER BY nextBillingDate IS NULL, nextBillingDate, createdAt, id""")
    fun observeOngoing(): Flow<List<SubscriptionEntity>>
    @Query("SELECT assetId FROM subscription_images WHERE subscriptionId = :id ORDER BY position, assetId")
    suspend fun imageIds(id: String): List<String>
    @Query("SELECT assetId FROM subscription_images WHERE subscriptionId = :id ORDER BY position, assetId")
    fun observeImageIds(id: String): Flow<List<String>>
    @Query("DELETE FROM subscription_images WHERE subscriptionId = :id") suspend fun clearImages(id: String)
    @Insert suspend fun insertImages(items: List<SubscriptionImageEntity>)
    @Query("SELECT * FROM subscriptions ORDER BY createdAt, id") suspend fun all(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions ORDER BY createdAt, id") fun observeAll(): Flow<List<SubscriptionEntity>>
    @Query("""SELECT EXISTS(SELECT 1 FROM subscriptions WHERE coverAssetId = :id)
        OR EXISTS(SELECT 1 FROM subscription_images WHERE assetId = :id)
        OR EXISTS(SELECT 1 FROM subscription_conversation_media WHERE assetId = :id)
        OR EXISTS(SELECT 1 FROM subscription_changes WHERE beforeCoverAssetId = :id OR afterCoverAssetId = :id)""")
    suspend fun referencesMedia(id: String): Boolean
    @Query("SELECT * FROM subscriptions WHERE id = :id") fun observeSubscription(id: String): Flow<SubscriptionEntity?>
    @Insert suspend fun insertChange(item: SubscriptionChangeEntity)
    @Query("SELECT * FROM subscription_changes WHERE subscriptionId = :id ORDER BY revision DESC LIMIT :limit OFFSET :offset")
    suspend fun changes(id: String, limit: Int, offset: Int = 0): List<SubscriptionChangeEntity>
    @Query("SELECT * FROM subscription_changes WHERE subscriptionId = :id AND revision < :before ORDER BY revision DESC LIMIT :limit")
    suspend fun changesBefore(id: String, before: Long, limit: Int): List<SubscriptionChangeEntity>
    @Query("SELECT * FROM subscription_changes WHERE subscriptionId = :id ORDER BY revision DESC LIMIT :limit")
    fun observeChanges(id: String, limit: Int): Flow<List<SubscriptionChangeEntity>>

    @Insert suspend fun insertOperation(item: SubscriptionOperationEntity)
    @Update suspend fun updateOperation(item: SubscriptionOperationEntity): Int
    @Query("SELECT * FROM subscription_operations WHERE id = :id") suspend fun operation(id: String): SubscriptionOperationEntity?
    @Query("SELECT * FROM subscription_operations WHERE conversationId = :id AND state = 'PENDING' ORDER BY createdAt, id")
    fun observePendingOperations(id: String): Flow<List<SubscriptionOperationEntity>>

    @Insert suspend fun insertPayment(item: SubscriptionPaymentEntity)
    @Query("""UPDATE subscription_payments SET amountMinor = :amount, currencyCode = :currency, paidOn = :date,
        notes = :notes, operationId = :operationId WHERE id = :id AND operationId = :expectedOperationId""")
    suspend fun correctPayment(id: String, expectedOperationId: String, amount: Long, currency: String,
        date: LocalDate, notes: String, operationId: String): Int
    @Query("SELECT * FROM subscription_payments WHERE id = :id") suspend fun payment(id: String): SubscriptionPaymentEntity?
    @Query("SELECT * FROM subscription_payments WHERE subscriptionId = :id ORDER BY paidOn DESC, confirmedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun payments(id: String, limit: Int, offset: Int = 0): List<SubscriptionPaymentEntity>
    @Query("SELECT * FROM subscription_payments WHERE paidOn >= :start AND paidOn < :end ORDER BY paidOn, id")
    suspend fun paymentsBetween(start: LocalDate, end: LocalDate): List<SubscriptionPaymentEntity>
    @Query("SELECT * FROM subscription_payments WHERE subscriptionId = :id AND billingEventId = :eventId")
    suspend fun paymentForEvent(id: String, eventId: String): SubscriptionPaymentEntity?
    @Query("""SELECT p.*, o.kind AS operationKind FROM subscription_payments p JOIN subscription_operations o ON o.id = p.operationId
        WHERE p.subscriptionId = :id AND p.paidOn >= :start AND p.paidOn < :end
        ORDER BY p.paidOn DESC, p.confirmedAt DESC, p.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun paymentRows(id: String, start: LocalDate, end: LocalDate, limit: Int, offset: Int): List<SubscriptionPaymentRow>
    @Query("""SELECT p.id, p.subscriptionId, p.billingEventId, p.amountMinor, p.currencyCode, p.paidOn, o.kind AS operationKind
        FROM subscription_payments p JOIN subscription_operations o ON o.id = p.operationId
        WHERE (:subscriptionId IS NULL OR p.subscriptionId = :subscriptionId)""")
    suspend fun paymentFacts(subscriptionId: String? = null): List<SubscriptionPaymentFact>
    @Query("""SELECT c.subscriptionId, c.revision, c.kind, c.createdAt, c.afterJson,
        CASE WHEN c.kind IN ('RECORD_PAYMENT', 'CORRECT_PAYMENT', 'VOID_PAYMENT') THEN o.payloadJson ELSE NULL END AS paymentPayload
        FROM subscription_changes c JOIN subscription_operations o ON o.id = c.operationId
        WHERE (:subscriptionId IS NULL OR c.subscriptionId = :subscriptionId)
        AND (c.subscriptionId > :afterSubscription OR (c.subscriptionId = :afterSubscription AND c.revision > :afterRevision))
        ORDER BY c.subscriptionId, c.revision LIMIT :limit""")
    suspend fun billingHistoryPage(subscriptionId: String?, afterSubscription: String, afterRevision: Long, limit: Int): List<SubscriptionBillingHistoryRow>
}

data class SubscriptionPaymentRow(@Embedded val payment: SubscriptionPaymentEntity, val operationKind: SubscriptionOperationKind) {
    val voided: Boolean get() = operationKind == SubscriptionOperationKind.VOID_PAYMENT
}
data class SubscriptionPaymentFact(val id: String, val subscriptionId: String, val billingEventId: String,
    val amountMinor: Long, val currencyCode: String, val paidOn: LocalDate, val operationKind: SubscriptionOperationKind) {
    val voided: Boolean get() = operationKind == SubscriptionOperationKind.VOID_PAYMENT
}
data class SubscriptionBillingHistoryRow(val subscriptionId: String, val revision: Long, val kind: SubscriptionOperationKind,
    val createdAt: Long, val afterJson: String, val paymentPayload: String?)

@Dao
interface SubscriptionConversationDao {
    @Insert suspend fun insert(item: SubscriptionConversationEntity)
    @Query("SELECT * FROM subscription_conversations WHERE id = :id") suspend fun conversation(id: String): SubscriptionConversationEntity?
    @Query("SELECT * FROM subscription_conversations WHERE id = :id") fun observeConversation(id: String): Flow<SubscriptionConversationEntity?>
    @Query("SELECT s.* FROM subscriptions s JOIN subscription_conversations c ON c.subscriptionId = s.id WHERE c.id = :id")
    fun observeChatRecord(id: String): Flow<SubscriptionEntity?>
    @Query("""SELECT c.* FROM subscription_conversations c JOIN subscription_drafts d ON d.conversationId = c.id
        WHERE c.subscriptionId IS NULL AND instr(d.fieldsJson, '"manualEditor"') = 0 ORDER BY c.updatedAt DESC, c.id DESC LIMIT 1""")
    suspend fun latestCreationChat(): SubscriptionConversationEntity?
    @Query("""SELECT c.*, s.title AS subscriptionTitle, d.inputText AS draftText,
        (SELECT content FROM subscription_messages WHERE conversationId = c.id ORDER BY sequence DESC LIMIT 1) AS lastText
        FROM subscription_conversations c JOIN subscription_drafts d ON d.conversationId = c.id
        LEFT JOIN subscriptions s ON s.id = c.subscriptionId ORDER BY c.updatedAt DESC, c.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun chatRows(limit: Int, offset: Int): List<SubscriptionChatRow>
    @Query("SELECT * FROM subscription_conversations WHERE subscriptionId = :id")
    suspend fun forSubscription(id: String): SubscriptionConversationEntity?
    @Query("SELECT * FROM subscription_conversations ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun conversations(limit: Int, offset: Int = 0): List<SubscriptionConversationEntity>
    @Query("""SELECT c.* FROM subscription_conversations c JOIN subscription_drafts d ON d.conversationId = c.id
        WHERE c.subscriptionId IS NULL AND instr(d.fieldsJson, '"manualEditor"') > 0
        ORDER BY c.updatedAt DESC, c.id LIMIT 1""")
    suspend fun latestManualCreation(): SubscriptionConversationEntity?
    @Query("UPDATE subscription_conversations SET subscriptionId = :subscriptionId, updatedAt = :now WHERE id = :id AND subscriptionId IS NULL")
    suspend fun bind(id: String, subscriptionId: String, now: Long): Int
    @Query("UPDATE subscription_conversations SET updatedAt = :now WHERE id = :id") suspend fun touch(id: String, now: Long)

    @Insert suspend fun insertDraft(item: SubscriptionDraftEntity)
    @Query("SELECT * FROM subscription_drafts WHERE conversationId = :id") suspend fun draft(id: String): SubscriptionDraftEntity?
    @Query("SELECT * FROM subscription_drafts WHERE conversationId = :id") fun observeDraft(id: String): Flow<SubscriptionDraftEntity?>
    @Query("""UPDATE subscription_drafts SET inputText = :text, fieldsJson = :fields, revision = revision + 1,
        updatedAt = :now WHERE conversationId = :id AND revision = :expected""")
    suspend fun saveDraft(id: String, text: String, fields: String, expected: Long, now: Long): Int

    @Insert suspend fun insertMessage(item: SubscriptionMessageEntity)
    @Query("SELECT * FROM subscription_messages WHERE id = :id") suspend fun message(id: String): SubscriptionMessageEntity?
    @Query("SELECT * FROM subscription_messages WHERE conversationId = :id AND role = 'USER' ORDER BY sequence DESC LIMIT 1")
    suspend fun latestUserMessage(id: String): SubscriptionMessageEntity?
    @Query("SELECT * FROM subscription_messages WHERE conversationId = :id AND sequence < :before ORDER BY sequence DESC LIMIT :limit")
    suspend fun messagesBefore(id: String, before: Long, limit: Int): List<SubscriptionMessageEntity>
    @Query("SELECT * FROM subscription_messages WHERE conversationId = :id ORDER BY sequence LIMIT 1")
    suspend fun firstMessage(id: String): SubscriptionMessageEntity?
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM subscription_messages WHERE conversationId = :id")
    suspend fun nextSequence(id: String): Long
    @Query("SELECT * FROM subscription_messages WHERE conversationId = :id ORDER BY sequence DESC LIMIT :limit OFFSET :offset")
    suspend fun messages(id: String, limit: Int, offset: Int = 0): List<SubscriptionMessageEntity>
    @Query("SELECT * FROM subscription_messages WHERE conversationId = :id ORDER BY sequence DESC LIMIT :limit")
    fun observeMessages(id: String, limit: Int): Flow<List<SubscriptionMessageEntity>>

    @Insert suspend fun insertMedia(item: SubscriptionConversationMediaEntity)
    @Query("SELECT * FROM subscription_conversation_media WHERE conversationId = :id ORDER BY position, assetId")
    suspend fun mediaLinks(id: String): List<SubscriptionConversationMediaEntity>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM subscription_conversation_media WHERE conversationId = :id")
    suspend fun nextMediaPosition(id: String): Long
    @Query("""SELECT a.*, m.conversationId AS link_conversationId, m.assetId AS link_assetId,
        m.displayName AS link_displayName, m.position AS link_position, m.pendingSend AS link_pendingSend,
        m.createdAt AS link_createdAt FROM media_assets a JOIN subscription_conversation_media m ON a.id = m.assetId
        WHERE m.conversationId = :id ORDER BY m.position, m.assetId""")
    fun observeMedia(id: String): Flow<List<SubscriptionLocalAttachment>>
    @Query("UPDATE subscription_conversation_media SET pendingSend = :pending WHERE conversationId = :id AND assetId = :assetId")
    suspend fun markPending(id: String, assetId: String, pending: Boolean)
    @Query("""DELETE FROM subscription_conversation_media WHERE conversationId = :id AND assetId = :assetId
        AND NOT EXISTS(SELECT 1 FROM subscription_message_media WHERE conversationId = :id AND assetId = :assetId)""")
    suspend fun removeUnsentMedia(id: String, assetId: String): Int
    @Insert suspend fun insertMessageMedia(items: List<SubscriptionMessageMediaEntity>)
    @Query("SELECT * FROM subscription_message_media WHERE messageId = :id ORDER BY position, assetId")
    suspend fun messageMedia(id: String): List<SubscriptionMessageMediaEntity>
    @Query("SELECT * FROM subscription_message_media WHERE conversationId = :id AND messageId IN (:messageIds) ORDER BY position, assetId")
    suspend fun messageMediaFor(id: String, messageIds: List<String>): List<SubscriptionMessageMediaEntity>
    /** 只加入上次确认保存后用户发送的图片，避免把已从图库删除的旧聊天图片再次加回。 */
    @Query("""SELECT cm.assetId FROM subscription_conversation_media cm WHERE cm.conversationId = :id
        AND EXISTS(SELECT 1 FROM subscription_message_media mm JOIN subscription_messages m ON m.id = mm.messageId
        WHERE mm.conversationId = :id AND mm.assetId = cm.assetId AND m.sequence > COALESCE(
        (SELECT MAX(source.sequence) FROM subscription_operations o JOIN subscription_messages source ON source.id = o.sourceMessageId
        WHERE o.conversationId = :id AND o.state = 'APPLIED'), 0)) ORDER BY cm.position, cm.assetId""")
    suspend fun newImageIds(id: String): List<String>
}

data class SubscriptionChatRow(@Embedded val conversation: SubscriptionConversationEntity, val subscriptionTitle: String?,
    val draftText: String, val lastText: String?)
