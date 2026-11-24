package com.txl.mebot.data.schedule

/** 展示已保存的周期规则，无需再次调用模型。 */
fun RecurrenceRule.description(): String = buildString {
    append(when (pattern) {
        RecurrencePattern.DAILY -> "每天"
        RecurrencePattern.WEEKLY -> "每周" + weekDays.sortedBy { it.value }.joinToString("、") { "一二三四五六日"[it.value - 1].toString() }
        RecurrencePattern.MONTHLY -> "每月${monthDay}日"
        RecurrencePattern.INTERVAL -> "每${intervalDays}天"
    })
    endDate?.let { append("，至${it.year}年${it.monthValue}月${it.dayOfMonth}日") }
}
