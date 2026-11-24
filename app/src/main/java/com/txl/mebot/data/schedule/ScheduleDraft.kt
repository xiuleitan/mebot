package com.txl.mebot.data.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

internal const val ScheduleTitleLimit = 30
internal const val DefaultScheduleLeadMinutes = 10

enum class RecurrencePattern { DAILY, WEEKLY, MONTHLY, INTERVAL }

/** 一项任务的重复规则；具体发生记录另存，不反复调用模型生成。 */
data class RecurrenceRule(
    val pattern: RecurrencePattern,
    val intervalDays: Int = 1,
    val weekDays: Set<DayOfWeek> = emptySet(),
    val monthDay: Int? = null,
    val endDate: LocalDate? = null,
    val enabled: Boolean = true,
) {
    internal fun validate(startDate: LocalDate) {
        require(endDate == null || !endDate.isBefore(startDate)) { "结束日期不能早于开始日期" }
        require(intervalDays >= 1) { "间隔天数必须大于零" }
        require(pattern == RecurrencePattern.INTERVAL || intervalDays == 1)
        require(if (pattern == RecurrencePattern.WEEKLY) weekDays.isNotEmpty() else weekDays.isEmpty())
        require(if (pattern == RecurrencePattern.MONTHLY) monthDay in 1..31 else monthDay == null)
    }

    fun includes(startDate: LocalDate, date: LocalDate): Boolean {
        if (!enabled || date < startDate || (endDate != null && date > endDate)) return false
        return when (pattern) {
            RecurrencePattern.DAILY -> true
            RecurrencePattern.WEEKLY -> date.dayOfWeek in weekDays
            RecurrencePattern.MONTHLY -> date.dayOfMonth == monthDay
            RecurrencePattern.INTERVAL -> ChronoUnit.DAYS.between(startDate, date) % intervalDays == 0L
        }
    }

    internal fun nextOnOrAfter(startDate: LocalDate, from: LocalDate): LocalDate? {
        if (!enabled) return null
        val first = maxOf(startDate, from)
        val next = if (pattern == RecurrencePattern.INTERVAL) {
            val elapsed = ChronoUnit.DAYS.between(startDate, first)
            startDate.plusDays(((elapsed + intervalDays - 1) / intervalDays) * intervalDays)
        } else {
            (0L..62L).asSequence().map { first.plusDays(it) }.firstOrNull { includes(startDate, it) }
        }
        return next?.takeIf { includes(startDate, it) }
    }
}

/** null 日期在执行本地写入时解析为设备当天；null 时间表示不设具体时刻。 */
data class ScheduleDraft(
    val title: String,
    val detail: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val recurrence: RecurrenceRule? = null,
    // null 采用默认策略：具体时间任务提前 10 分钟及准点提醒，仅日期任务不提醒。
    // 显式空列表关闭提醒；0 为准点，正数为提前分钟数。
    val reminderMinutes: List<Int>? = null,
    val coverAssetId: String? = null,
) {
    internal fun resolved(today: LocalDate, existingTitle: String? = null): ScheduleDraft {
        val resolvedDate = date ?: today
        val normalizedTitle = title.trim()
        val titleLength = normalizedTitle.codePointCount(0, normalizedTitle.length)
        // 保留旧标题的兼容路径；当前上限也已覆盖旧版 9～10 字标题，不截断历史数据。
        val unchangedLegacyTitle = normalizedTitle == existingTitle && titleLength <= 10
        require(normalizedTitle.isNotEmpty() && (titleLength <= ScheduleTitleLimit || unchangedLegacyTitle)) {
            "标题必须为 1 至 $ScheduleTitleLimit 个字符"
        }
        require(time == null || (time.second == 0 && time.nano == 0)) { "日程时间精确到分钟" }
        val leads = (reminderMinutes ?: if (time == null) emptyList() else listOf(DefaultScheduleLeadMinutes, 0))
            .distinct().sortedDescending()
        require(leads.all { it >= 0 }) { "提前提醒分钟数不能为负数" }
        require(time != null || leads.isEmpty()) { "仅日期日程不设置定时提醒" }
        recurrence?.validate(resolvedDate)
        return copy(title = normalizedTitle, date = resolvedDate, reminderMinutes = leads)
    }
}
