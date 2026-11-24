package com.txl.mebot.ui.schedule

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import com.txl.mebot.R
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.data.local.ScheduleDisplayRecord
import com.txl.mebot.data.schedule.RecurrenceRule
import com.txl.mebot.data.schedule.asRule
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class ScheduleEntry(
    val id: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    @param:DrawableRes val image: Int? = null,
    val detail: String,
    val completed: Boolean = false,
    val sessionId: String? = null,
    val revision: Long = 0,
    val coverRelativePath: String? = null,
    val isRecurring: Boolean = false,
    val recurrence: RecurrenceRule? = null,
    val recurrenceStopped: Boolean = false,
    val attachmentCount: Int = 0,
    val coverSource: MediaSource? = null,
    val completedAt: Long? = null,
)

private val DateLabel = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val AccessibleDateLabel = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA)
private val TimeLabel = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

internal fun LocalDate.scheduleLabel(): String = format(DateLabel)
internal fun LocalDate.scheduleAccessibilityLabel(): String = format(AccessibleDateLabel)
internal fun LocalTime.scheduleLabel(): String = format(TimeLabel)

private val DisplayOrder = compareBy<ScheduleEntry> { it.completed }
    .thenByDescending { if (it.completed) it.completedAt else null }
    .thenBy { it.time == null }
    .thenBy { it.time }

// 未完成按提醒时间排列，已完成按实际完成时间倒序；相同条件保持稳定。
internal fun List<ScheduleEntry>.sortedForDisplay(): List<ScheduleEntry> = sortedWith(DisplayOrder)

internal fun ScheduleDisplayRecord.asEntry(): ScheduleEntry {
    val builtin = if (coverSource == MediaSource.BUILT_IN) when (coverBuiltinKey) {
        "meeting" -> R.drawable.schedule_meeting
        "medical" -> R.drawable.schedule_medical
        "review" -> R.drawable.schedule_review
        "fitness" -> R.drawable.schedule_fitness
        "dinner" -> R.drawable.schedule_dinner
        "groceries" -> R.drawable.schedule_groceries
        else -> null
    } else null
    return ScheduleEntry(
        id = occurrence.id, title = occurrence.title, date = occurrence.date, time = occurrence.time,
        image = builtin, detail = occurrence.detail, completed = occurrence.completedAt != null,
        completedAt = occurrence.completedAt,
        sessionId = sessionId, revision = occurrence.revision,
        isRecurring = isRecurring,
        recurrence = recurrence?.asRule(), recurrenceStopped = isRecurring && recurrenceStopped,
        attachmentCount = attachmentCount,
        coverSource = coverSource,
        coverRelativePath = if (coverSource == MediaSource.IMPORTED || coverSource == MediaSource.AI_GENERATED) coverRelativePath else null,
    )
}

// 只供 Android Studio @Preview 使用；不注入数据库或正式页面。
fun previewScheduleEntries(today: LocalDate): List<ScheduleEntry> = listOf(
    ScheduleEntry(
        id = "meeting", title = "团队会议", date = today, time = LocalTime.of(9, 0),
        image = R.drawable.schedule_meeting,
        detail = "对齐本周进度，讨论接下来的安排。\n带上需要一起确认的问题。",
    ),
    ScheduleEntry(
        id = "medical", title = "医院复查", date = today, time = LocalTime.of(11, 30),
        image = R.drawable.schedule_medical,
        detail = "提前准备好证件和上次的检查资料。",
    ),
    ScheduleEntry(
        id = "review", title = "项目复盘", date = today,
        image = R.drawable.schedule_review,
        detail = "整理近期的进展，记录做得好的地方和下一步想改进的事。\n今天找一个合适的时间完成即可。",
    ),
    ScheduleEntry(
        id = "fitness", title = "健身计划", date = today, time = LocalTime.of(16, 0),
        image = R.drawable.schedule_fitness,
        detail = "热身后开始今天的训练，记得带水和毛巾。",
    ),
    ScheduleEntry(
        id = "dinner", title = "家庭聚餐", date = today,
        image = R.drawable.schedule_dinner,
        detail = "和家人一起吃顿饭，留一点轻松聊天的时间。",
    ),
    ScheduleEntry(
        id = "groceries", title = "采购食材", date = today, time = LocalTime.of(20, 30),
        image = R.drawable.schedule_groceries,
        detail = "蔬菜、水果、鸡蛋和牛奶。\n出门前看一眼冰箱，避免重复购买。",
    ),
    ScheduleEntry(
        id = "past-review", title = "整理笔记", date = today.minusDays(2),
        image = R.drawable.schedule_review, detail = "把零散的笔记整理到一起。",
    ),
    ScheduleEntry(
        id = "past-fitness", title = "日常训练", date = today.minusDays(1), time = LocalTime.of(18, 0),
        image = R.drawable.schedule_fitness, detail = "给自己留一点运动的时间。",
    ),
    ScheduleEntry(
        id = "next-meeting", title = "方案讨论", date = today.plusDays(1), time = LocalTime.of(14, 0),
        image = R.drawable.schedule_meeting, detail = "一起确认方案的细节。",
    ),
    ScheduleEntry(
        id = "next-groceries", title = "补充日用品", date = today.plusDays(1),
        image = R.drawable.schedule_groceries, detail = "出发前列好购物清单。",
    ),
    ScheduleEntry(
        id = "future-dinner", title = "朋友小聚", date = today.plusDays(2), time = LocalTime.of(19, 0),
        image = R.drawable.schedule_dinner, detail = "约上朋友，慢慢吃一顿饭。",
    ),
)
