package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime

/** 日期使用主库已有的 epoch day 转换，避免随时区移动。 */
@Entity(tableName = "dayima_profile")
data class DayimaProfileEntity(
    @PrimaryKey val id: Int = 1,
    val birthDate: LocalDate? = null,
    val updatedAt: Long,
    val revision: Long = 1,
) {
    init { require(id == 1) }
}

@Entity(tableName = "dayima_periods", indices = [Index(value = ["startDate"], unique = true)])
data class DayimaPeriodEntity(
    @PrimaryKey val id: String,
    val startDate: LocalDate,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

/** 删除经期记录只解除备注关联，保留独立保存的文字。 */
@Entity(
    tableName = "dayima_notes",
    foreignKeys = [ForeignKey(DayimaPeriodEntity::class, ["id"], ["periodId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("periodId"), Index(value = ["occurredDate", "id"])],
)
data class DayimaNoteEntity(
    @PrimaryKey val id: String,
    val occurredDate: LocalDate,
    val content: String,
    val periodId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

enum class DayimaSpanReason { MISSING_RECORDS, SPECIAL_PHASE }

/** 日期与版本保留标记时的快照。后续统计需校验端点仍相邻、日期及版本仍匹配。 */
@Entity(
    tableName = "dayima_span_markers",
    foreignKeys = [
        ForeignKey(DayimaPeriodEntity::class, ["id"], ["startPeriodId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(DayimaPeriodEntity::class, ["id"], ["endPeriodId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["startPeriodId", "endPeriodId"], unique = true), Index("endPeriodId")],
)
data class DayimaSpanMarkerEntity(
    @PrimaryKey val id: String,
    val startPeriodId: String?,
    val endPeriodId: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startPeriodRevision: Long,
    val endPeriodRevision: Long,
    val reason: DayimaSpanReason,
    val detail: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    @ColumnInfo(defaultValue = "0") val needsReview: Boolean = false,
)

enum class DayimaChatStatus { ACTIVE, CLOSED }
enum class DayimaMessageRole { USER, ASSISTANT }
enum class DayimaOperationState { PENDING, WAITING_FOR_INPUT, SUCCEEDED, FAILED, CANCELLED }
enum class DayimaOperationAction { RECORD, EDIT, DELETE, NOTE, QUERY, REMINDER, CONSULT, BATCH }

/** 各次聊天独立保存，不保存跨会话记忆或共享上下文。 */
@Entity(tableName = "dayima_chat_sessions", indices = [Index(value = ["status", "updatedAt", "id"])])
data class DayimaChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val status: DayimaChatStatus = DayimaChatStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

@Entity(
    tableName = "dayima_chat_messages",
    foreignKeys = [ForeignKey(DayimaChatSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "sequence"], unique = true), Index(value = ["sessionId", "id"], unique = true)],
)
data class DayimaChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sequence: Long,
    val role: DayimaMessageRole,
    val content: String,
    val createdAt: Long,
)

@Entity(
    tableName = "dayima_chat_drafts",
    foreignKeys = [ForeignKey(DayimaChatSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class DayimaChatDraftEntity(
    @PrimaryKey val sessionId: String,
    val inputText: String = "",
    val revision: Long = 0,
    val updatedAt: Long,
)

/** 每条请求一个操作状态；追问随请求保存。复合外键防止请求归属另一会话。 */
@Entity(
    tableName = "dayima_chat_operations",
    foreignKeys = [ForeignKey(
        DayimaChatMessageEntity::class, ["sessionId", "id"], ["sessionId", "requestMessageId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "requestMessageId"]), Index(value = ["sessionId", "state", "createdAt"])],
)
data class DayimaChatOperationEntity(
    @PrimaryKey val requestMessageId: String,
    val sessionId: String,
    val state: DayimaOperationState = DayimaOperationState.PENDING,
    val action: DayimaOperationAction? = null,
    val question: String? = null,
    // 执行回执用于展示；正式经期、备注等业务表仍为事实来源。
    val resultJson: String? = null,
    val failure: String? = null,
    val attemptId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

/** 经期本地提醒规则；计划和投递事实由独立后台执行器维护。 */
@Entity(tableName = "dayima_reminder_settings")
data class DayimaReminderSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val prepareEnabled: Boolean = true,
    val missingRecordEnabled: Boolean = true,
    val reminderTime: LocalTime = LocalTime.NOON,
    val prepareDaysBefore: Int = 1,
    val updatedAt: Long,
    val revision: Long = 1,
) {
    init { require(id == 1) }
}
