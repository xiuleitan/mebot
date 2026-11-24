package com.txl.mebot.data.schedule

/** 字段存在才修改；null 时间明确表示改为仅日期，其他字段不受影响。 */
data class SchedulePatch(val values: ScheduleDraft, val fields: Set<String>) {
    init { require(fields.isNotEmpty() && fields.all { it in EditableScheduleFields }) }

    fun applyTo(base: ScheduleDraft): ScheduleDraft {
        val updatedTime = if ("time" in fields) values.time else base.time
        val reminders = when {
            "reminder_minutes" in fields -> values.reminderMinutes
            "time" in fields && updatedTime == null -> emptyList()
            "time" in fields && base.time == null -> null
            else -> base.reminderMinutes
        }
        return base.copy(
            title = if ("title" in fields) values.title else base.title,
            detail = if ("detail" in fields) values.detail else base.detail,
            date = if ("date" in fields) values.date else base.date,
            time = updatedTime,
            recurrence = if ("recurrence" in fields) values.recurrence else base.recurrence,
            reminderMinutes = reminders,
        )
    }
}

internal val EditableScheduleFields = setOf("title", "detail", "date", "time", "recurrence", "reminder_minutes")
