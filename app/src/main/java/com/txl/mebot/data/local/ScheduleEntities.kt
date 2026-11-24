package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.txl.mebot.data.schedule.RecurrencePattern
import java.time.LocalDate
import java.time.LocalTime

enum class MediaSource { BUILT_IN, IMPORTED, AI_GENERATED }
enum class MessageRole { USER, ASSISTANT }
enum class ReminderStatus { PENDING, SCHEDULED, DELIVERED, CANCELLED, MISSED }
enum class ScheduleAction { CREATE, UPDATE, COMPLETE, RESTORE, DELETE }

@Entity(tableName = "media_assets", indices = [Index(value = ["sha256"], unique = true)])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val source: MediaSource,
    val relativePath: String? = null,
    val builtinKey: String? = null,
    val originalName: String? = null,
    val mimeType: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val sha256: String? = null,
    val generationPrompt: String? = null,
    val generator: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "schedule_sessions", indices = [Index("updatedAt")])
data class ScheduleSessionEntity(@PrimaryKey val id: String, val createdAt: Long, val updatedAt: Long)

@Entity(
    tableName = "schedule_messages",
    foreignKeys = [ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"])],
    indices = [Index(value = ["sessionId", "sequence"], unique = true)],
)
data class ScheduleMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sequence: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val occurrenceContextId: String? = null,
)

/** UNIQUE(sessionId) 保证一个会话最多创建一项任务，包括一整项周期任务。 */
@Entity(
    tableName = "schedule_tasks",
    foreignKeys = [
        ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"]),
        ForeignKey(MediaAssetEntity::class, ["id"], ["coverAssetId"]),
    ],
    indices = [Index(value = ["sessionId"], unique = true), Index("coverAssetId"), Index("date")],
)
data class ScheduleTaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val time: LocalTime?,
    val coverAssetId: String?,
    val revision: Long = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "schedule_task_versions",
    primaryKeys = ["taskId", "revision"],
    foreignKeys = [
        ForeignKey(ScheduleTaskEntity::class, ["id"], ["taskId"]),
        ForeignKey(MediaAssetEntity::class, ["id"], ["coverAssetId"]),
    ],
    indices = [Index(value = ["effectiveFrom", "date"]), Index("coverAssetId")],
)
data class ScheduleTaskVersionEntity(
    val taskId: String,
    val revision: Long,
    val effectiveFrom: LocalDate,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val time: LocalTime?,
    val coverAssetId: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "schedule_recurrences",
    primaryKeys = ["taskId", "taskRevision"],
    foreignKeys = [ForeignKey(ScheduleTaskVersionEntity::class, ["taskId", "revision"], ["taskId", "taskRevision"])],
)
data class ScheduleRecurrenceEntity(
    val taskId: String,
    val taskRevision: Long,
    val pattern: RecurrencePattern,
    val intervalDays: Int,
    val weekDayMask: Int,
    val monthDay: Int?,
    val endDate: LocalDate?,
    val enabled: Boolean,
)

@Entity(
    tableName = "schedule_reminder_leads",
    primaryKeys = ["taskId", "taskRevision", "minutesBefore"],
    foreignKeys = [ForeignKey(ScheduleTaskVersionEntity::class, ["taskId", "revision"], ["taskId", "taskRevision"])],
)
data class ScheduleReminderLeadEntity(val taskId: String, val taskRevision: Long, val minutesBefore: Int)

/** 卡片的持久快照；单次修改使用独立提醒配置，删除时物理移除。 */
@Entity(
    tableName = "schedule_occurrences",
    foreignKeys = [
        ForeignKey(ScheduleTaskEntity::class, ["id"], ["taskId"]),
        ForeignKey(ScheduleTaskVersionEntity::class, ["taskId", "revision"], ["taskId", "taskRevision"]),
        ForeignKey(MediaAssetEntity::class, ["id"], ["coverAssetId"]),
    ],
    indices = [
        Index(value = ["taskId", "occurrenceKey"], unique = true),
        Index(value = ["taskId", "taskRevision"]),
        Index(value = ["date", "completedAt", "time"]),
        Index("coverAssetId"),
    ],
)
data class ScheduleOccurrenceEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val occurrenceKey: String,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val time: LocalTime?,
    val coverAssetId: String?,
    val taskRevision: Long,
    val revision: Long = 1,
    val completedAt: Long? = null,
    val suppressedByRule: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isException: Boolean = false,
)

/** 周期的排除规则，不保留被删除卡片的标题、详情或提醒。 */
@Entity(tableName = "schedule_exclusions", primaryKeys = ["taskId", "occurrenceKey"],
    foreignKeys = [ForeignKey(ScheduleTaskEntity::class, ["id"], ["taskId"])])
data class ScheduleExclusionEntity(val taskId: String, val occurrenceKey: String)

@Entity(tableName = "schedule_occurrence_leads", primaryKeys = ["occurrenceId", "minutesBefore"],
    foreignKeys = [ForeignKey(ScheduleOccurrenceEntity::class, ["id"], ["occurrenceId"])])
data class ScheduleOccurrenceLeadEntity(val occurrenceId: String, val minutesBefore: Int)

/** 仅保存待清理路径，任务删除提交后继续清理，不保留业务或会话快照。 */
@Entity(tableName = "media_cleanup")
data class MediaCleanupEntity(@PrimaryKey val relativePath: String)

@Entity(
    tableName = "schedule_reminders",
    foreignKeys = [ForeignKey(ScheduleOccurrenceEntity::class, ["id"], ["occurrenceId"])],
    indices = [
        Index(value = ["occurrenceId", "occurrenceRevision", "minutesBefore"], unique = true),
        Index(value = ["status", "triggerAt"]),
    ],
)
data class ScheduleReminderEntity(
    @PrimaryKey val id: String,
    val occurrenceId: String,
    val occurrenceRevision: Long,
    val minutesBefore: Int,
    val triggerAt: Long,
    val zoneId: String,
    val status: ReminderStatus,
    val deliveredAt: Long? = null,
    val createdAt: Long,
)

/** 附件在任务创建前即可随会话保存；整项删除时清理关联及无人引用的本地副本。 */
@Entity(
    tableName = "schedule_attachments",
    primaryKeys = ["messageId", "assetId"],
    foreignKeys = [
        ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"]),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"]),
        ForeignKey(ScheduleMessageEntity::class, ["id"], ["messageId"]),
    ],
    indices = [Index(value = ["sessionId", "addedAt"]), Index("assetId")],
)
data class ScheduleAttachmentEntity(
    val sessionId: String,
    val assetId: String,
    val messageId: String,
    val addedAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val position: Int = 0,
    val displayName: String? = null,
)

/** 请求 ID 去重与操作留痕，快照仅用于追溯，当前业务状态仍以结构化表为准。 */
@Entity(
    tableName = "schedule_operations",
    foreignKeys = [
        ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"]),
        ForeignKey(ScheduleTaskEntity::class, ["id"], ["taskId"]),
    ],
    indices = [Index(value = ["sessionId", "sequence"], unique = true), Index("taskId")],
)
data class ScheduleOperationEntity(
    @PrimaryKey val requestId: String,
    val sessionId: String,
    val taskId: String,
    val sequence: Long,
    val action: ScheduleAction,
    val fingerprint: String,
    val beforeSnapshot: String?,
    val afterSnapshot: String,
    val createdAt: Long,
)

data class ScheduleDateCount(val date: LocalDate, val count: Int)
