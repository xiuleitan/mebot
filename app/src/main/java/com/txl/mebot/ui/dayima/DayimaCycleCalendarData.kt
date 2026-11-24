package com.txl.mebot.ui.dayima

import com.txl.mebot.data.dayima.DayimaCyclePhase
import com.txl.mebot.data.dayima.DayimaPhaseEstimate
import com.txl.mebot.data.dayima.DayimaPhaseEstimateStatus
import com.txl.mebot.data.dayima.DayimaPrediction
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

internal data class DayimaCalendarDay(
    val date: LocalDate,
    val phase: DayimaCyclePhase?,
    val today: Boolean,
    val start: Boolean,
    val predicted: Boolean,
)

internal data class DayimaCalendarMonthBand(val month: YearMonth, val weekCount: Int)

internal val DayimaLastFirstMonth: YearMonth = YearMonth.of(LocalDate.MAX.year, 11)
internal val DayimaFirstMonth: YearMonth = YearMonth.of(LocalDate.MIN.year, 1)

internal fun dayimaCalendarPrediction(estimate: DayimaPhaseEstimate, prediction: DayimaPrediction?): DayimaPrediction? =
    prediction?.takeIf { it.latestDate == estimate.startDate && !it.rangeHasEnded &&
        estimate.status !in setOf(DayimaPhaseEstimateStatus.NO_RECORDS, DayimaPhaseEstimateStatus.AWAITING_RECORD, DayimaPhaseEstimateStatus.DATE_OUT_OF_RANGE) }

/** 两个月能覆盖本次起止时优先完整展示，否则从今天所在月份浏览，保留前后翻月。 */
internal fun dayimaCalendarFirstMonth(estimate: DayimaPhaseEstimate, prediction: DayimaPrediction?): YearMonth {
    val first = estimate.startDate?.let(YearMonth::from)
    val last = dayimaCalendarPrediction(estimate, prediction)?.predictedDate?.let(YearMonth::from)
    val selected = if (first != null && last != null && ChronoUnit.MONTHS.between(first, last) in 0L..1L) first
        else YearMonth.from(estimate.asOfDate)
    return minOf(selected, DayimaLastFirstMonth)
}

/** 两个月共用连续周表；只在整个范围首尾补空位，不重复跨月的一周或制造记录。 */
internal fun dayimaCalendarDays(firstMonth: YearMonth, estimate: DayimaPhaseEstimate, prediction: DayimaPrediction?): List<DayimaCalendarDay?> {
    require(firstMonth in DayimaFirstMonth..DayimaLastFirstMonth)
    val firstDate = firstMonth.atDay(1)
    val lastDate = firstMonth.plusMonths(1).atEndOfMonth()
    val dateCount = ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1
    val leading = firstDate.dayOfWeek.value % 7
    val usable = dayimaCalendarPrediction(estimate, prediction)
    val expired = estimate.status in setOf(DayimaPhaseEstimateStatus.AWAITING_RECORD, DayimaPhaseEstimateStatus.DATE_OUT_OF_RANGE)
    return List((leading + dateCount + 6) / 7 * 7) { index ->
        val offset = index - leading
        if (offset !in 0 until dateCount) null else {
            val date = firstDate.plusDays(offset.toLong())
            val phase = if (expired || usable != null && date >= usable.predictedDate) null else
                estimate.ranges.firstOrNull { it.dates?.let { dates -> date in dates } == true }?.phase
                    ?.takeIf { it == DayimaCyclePhase.MENSTRUAL || usable != null }
            DayimaCalendarDay(date, phase, date == estimate.asOfDate, date == estimate.startDate, date == usable?.predictedDate)
        }
    }
}

/** 左侧月份按各周首个真实日期分段；跨月周只出现一次，月初日期在格内另作标识。 */
internal fun dayimaCalendarMonthBands(days: List<DayimaCalendarDay?>): List<DayimaCalendarMonthBand> {
    val result = mutableListOf<DayimaCalendarMonthBand>()
    for (week in days.chunked(7)) {
        val month = YearMonth.from(week.firstNotNullOf { it?.date })
        val last = result.lastOrNull()
        if (last?.month == month) result[result.lastIndex] = last.copy(weekCount = last.weekCount + 1)
        else result += DayimaCalendarMonthBand(month, 1)
    }
    return result
}
