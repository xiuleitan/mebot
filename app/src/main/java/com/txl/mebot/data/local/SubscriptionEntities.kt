package com.txl.mebot.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class SubscriptionStatus { ACTIVE, CANCEL_AT_PERIOD_END, ARCHIVED, DELETED }
enum class SubscriptionCycleUnit { NONE, DAY, WEEK, MONTH, YEAR }
enum class SubscriptionOperationKind { CREATE, UPDATE, RECORD_PAYMENT, ADVANCE, STOP_RENEWAL, RESUME_RENEWAL, ARCHIVE, RESTORE, DELETE, CORRECT_PAYMENT, VOID_PAYMENT, RENEW_TERM }
enum class SubscriptionOperationState { PENDING, APPLIED, CANCELLED, FAILED }

/** 金额以币种的最小货币单位保存；预计扣费日期不代表已付款。 */
@Entity(tableName = "subscriptions", foreignKeys = [
    ForeignKey(MediaAssetEntity::class, ["id"], ["coverAssetId"]),
], indices = [Index("coverAssetId"), Index(value = ["status", "nextBillingDate", "id"])])
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val cycleUnit: SubscriptionCycleUnit,
    val cycleInterval: Int,
    val anchorDate: LocalDate?,
    val anchorAtMonthEnd: Boolean,
    val nextBillingDate: LocalDate?,
    val validUntil: LocalDate?,
    val autoRenew: Boolean,
    val status: SubscriptionStatus,
    val account: String?,
    val paymentMethod: String?,
    val notes: String,
    val coverAssetId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
)

/** 未保存的会话不绑定订阅；唯一索引和条件绑定共同保证一对一。 */
@Entity(tableName = "subscription_conversations", foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
], indices = [Index(value = ["subscriptionId"], unique = true), Index("updatedAt")])
data class SubscriptionConversationEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 草稿 JSON 只保存待确认字段，不替代正式订阅；格式版本随草稿一起保存。 */
@Entity(tableName = "subscription_drafts", foreignKeys = [
    ForeignKey(SubscriptionConversationEntity::class, ["id"], ["conversationId"]),
])
data class SubscriptionDraftEntity(
    @PrimaryKey val conversationId: String,
    val inputText: String = "",
    val fieldsJson: String = "{}",
    val formatVersion: Int = 1,
    val revision: Long = 0,
    val updatedAt: Long,
)

@Entity(tableName = "subscription_messages", foreignKeys = [
    ForeignKey(SubscriptionConversationEntity::class, ["id"], ["conversationId"]),
], indices = [Index(value = ["conversationId", "sequence"], unique = true),
    Index(value = ["id", "conversationId"], unique = true)])
data class SubscriptionMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sequence: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
)

/** 待确认和已执行操作均持久化；同一请求号不能用于不同内容。 */
@Entity(tableName = "subscription_operations", foreignKeys = [
    ForeignKey(SubscriptionConversationEntity::class, ["id"], ["conversationId"]),
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
    ForeignKey(SubscriptionMessageEntity::class, ["id", "conversationId"], ["sourceMessageId", "conversationId"]),
], indices = [Index(value = ["conversationId", "state"]), Index("subscriptionId"),
    Index(value = ["sourceMessageId", "conversationId"])])
data class SubscriptionOperationEntity(
    @PrimaryKey val id: String,
    val conversationId: String?,
    val subscriptionId: String?,
    val sourceMessageId: String?,
    val kind: SubscriptionOperationKind,
    val state: SubscriptionOperationState,
    val payloadJson: String,
    val expectedRevision: Long?,
    val resultId: String? = null,
    val failure: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 追加快照，不随当前价格、日期或归档状态变化而改写。 */
@Entity(tableName = "subscription_changes", foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
    ForeignKey(SubscriptionOperationEntity::class, ["id"], ["operationId"]),
    ForeignKey(MediaAssetEntity::class, ["id"], ["beforeCoverAssetId"]),
    ForeignKey(MediaAssetEntity::class, ["id"], ["afterCoverAssetId"]),
], indices = [Index(value = ["subscriptionId", "revision"], unique = true),
    Index(value = ["operationId"], unique = true), Index("beforeCoverAssetId"), Index("afterCoverAssetId")])
data class SubscriptionChangeEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val operationId: String,
    val kind: SubscriptionOperationKind,
    val previousRevision: Long?,
    val revision: Long,
    val beforeJson: String?,
    val afterJson: String,
    val beforeCoverAssetId: String?,
    val afterCoverAssetId: String?,
    val createdAt: Long,
)

/** 仅在明确确认扣费时写入。历史金额、币种、日期与稳定扣费事件 ID 均独立保存。 */
@Entity(tableName = "subscription_payments", foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
    ForeignKey(SubscriptionOperationEntity::class, ["id"], ["operationId"]),
], indices = [Index(value = ["subscriptionId", "billingEventId"], unique = true),
    Index(value = ["operationId"], unique = true), Index(value = ["paidOn", "currencyCode"])])
data class SubscriptionPaymentEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val billingEventId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val paidOn: LocalDate,
    val notes: String,
    val operationId: String,
    val confirmedAt: Long,
)

/** 会话持有本地附件，消息再引用附件；清空未发送输入不能删掉已发出的原图。 */
@Entity(tableName = "subscription_conversation_media", primaryKeys = ["conversationId", "assetId"], foreignKeys = [
    ForeignKey(SubscriptionConversationEntity::class, ["id"], ["conversationId"]),
    ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"]),
], indices = [Index("assetId"), Index(value = ["conversationId", "position"])])
data class SubscriptionConversationMediaEntity(
    val conversationId: String,
    val assetId: String,
    val displayName: String,
    val position: Long,
    val pendingSend: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "subscription_message_media", primaryKeys = ["messageId", "assetId"], foreignKeys = [
    ForeignKey(SubscriptionMessageEntity::class, ["id", "conversationId"], ["messageId", "conversationId"]),
    ForeignKey(SubscriptionConversationMediaEntity::class, ["conversationId", "assetId"], ["conversationId", "assetId"]),
], indices = [Index(value = ["messageId", "conversationId"]), Index(value = ["conversationId", "assetId"])])
data class SubscriptionMessageMediaEntity(
    val messageId: String,
    val conversationId: String,
    val assetId: String,
    val displayName: String,
    val position: Long,
)

data class SubscriptionLocalAttachment(
    @Embedded val asset: MediaAssetEntity,
    @Embedded(prefix = "link_") val link: SubscriptionConversationMediaEntity,
)
