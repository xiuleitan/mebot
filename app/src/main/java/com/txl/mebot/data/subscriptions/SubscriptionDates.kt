package com.txl.mebot.data.subscriptions

import com.txl.mebot.data.local.SubscriptionCycleUnit
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 始终从原始锚点定位周期，不能从二月裁短后的日期逐月累加。 */
object SubscriptionDates {
    fun onOrAfter(anchor: LocalDate, unit: SubscriptionCycleUnit, interval: Int, monthEnd: Boolean, from: LocalDate): LocalDate? {
        if (unit == SubscriptionCycleUnit.NONE) return null
        require(interval in 1..10_000)
        require(!monthEnd || unit in setOf(SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR))
        val step = interval.toLong()
        return when (unit) {
            SubscriptionCycleUnit.DAY, SubscriptionCycleUnit.WEEK -> {
                val days = if (unit == SubscriptionCycleUnit.WEEK) step * 7 else step
                val distance = ChronoUnit.DAYS.between(anchor, from).coerceAtLeast(0)
                anchor.plusDays(Math.multiplyExact((distance + days - 1) / days, days))
            }
            SubscriptionCycleUnit.MONTH -> {
                val start = YearMonth.from(anchor)
                val distance = ChronoUnit.MONTHS.between(start, YearMonth.from(from)).coerceAtLeast(0)
                var period = distance / step
                fun occurrence(index: Long): LocalDate {
                    val month = start.plusMonths(Math.multiplyExact(index, step))
                    return if (monthEnd) month.atEndOfMonth() else month.atDay(anchor.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
                }
                var candidate = occurrence(period)
                if (candidate.isBefore(from)) candidate = occurrence(++period)
                candidate
            }
            SubscriptionCycleUnit.YEAR -> {
                var period = (from.year.toLong() - anchor.year).coerceAtLeast(0) / step
                fun occurrence(index: Long): LocalDate {
                    val year = Math.toIntExact(anchor.year + Math.multiplyExact(index, step))
                    val month = YearMonth.of(year, anchor.month)
                    return if (monthEnd) month.atEndOfMonth() else month.atDay(anchor.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
                }
                var candidate = occurrence(period)
                if (candidate.isBefore(from)) candidate = occurrence(++period)
                candidate
            }
            SubscriptionCycleUnit.NONE -> null
        }
    }

    fun after(anchor: LocalDate, unit: SubscriptionCycleUnit, interval: Int, monthEnd: Boolean, date: LocalDate): LocalDate? =
        onOrAfter(anchor, unit, interval, monthEnd, date.plusDays(1))
}
