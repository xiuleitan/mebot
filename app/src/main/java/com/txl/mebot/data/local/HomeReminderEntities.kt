package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.txl.mebot.data.dayima.DayimaReminderKind
import java.time.LocalDate

enum class HomeEventKind { SCHEDULE, FLASHNOTE, DAYIMA_PREPARE, DAYIMA_MISSING, SUBSCRIPTION }

/** 提醒及每日展示的本地历史；正式任务内容和完成状态仍以来源表为准。 */
@Entity(tableName = "home_events", indices = [Index(value = ["eventDate", "id"]), Index(value = ["kind", "sourceId"])])
data class HomeEventEntity(
    @PrimaryKey val id: String,
    val kind: HomeEventKind,
    val sourceId: String,
    val cycleKey: String?,
    val eventDate: LocalDate,
    val title: String,
    val body: String,
    val completedAt: Long? = null,
    val resolvedById: String? = null,
    val cancelled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
)

@Entity(tableName = "home_flashnote_days")
data class HomeFlashnoteDayEntity(@PrimaryKey val day: LocalDate, val seed: Long,
    val selectedNoteId: String?, val updatedAt: Long)

@Entity(tableName = "home_flashnote_pin", foreignKeys = [ForeignKey(
    entity = FlashnoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["noteId"])])
data class HomeFlashnotePinEntity(@PrimaryKey val id: Int = 1, val noteId: String, val updatedAt: Long)

enum class DayimaDeliveryState { PENDING, SCHEDULED, BLOCKED, POSTING, DELIVERED, FAILED, UNCERTAIN }

/** 每个真实周期与类型唯一；交付证明独立于开关和当前有效计划，不随重算清空。 */
@Entity(tableName = "dayima_reminder_plans", indices = [
    Index(value = ["periodId", "kind"], unique = true), Index(value = ["active", "triggerAt"]),
])
data class DayimaReminderPlanEntity(
    @PrimaryKey val id: String,
    val periodId: String,
    val periodDate: LocalDate,
    val periodRevision: Long,
    val kind: DayimaReminderKind,
    val predictedDate: LocalDate,
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val reminderDate: LocalDate,
    val minuteOfDay: Int,
    val triggerAt: Long,
    val zoneId: String,
    val dataVersion: Long,
    val ruleFingerprint: String,
    val generation: String,
    val active: Boolean = true,
    val deliveryState: DayimaDeliveryState = DayimaDeliveryState.PENDING,
    val attemptId: String? = null,
    val attemptStartedAt: Long? = null,
    val deliveredAt: Long? = null,
    val failure: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
