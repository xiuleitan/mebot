package com.txl.mebot.data.dayima

import java.time.DateTimeException
import java.time.LocalDate

enum class DayimaCyclePhase { MENSTRUAL, FOLLICULAR, OVULATION, LUTEAL }
enum class DayimaPhaseEstimateStatus { NO_RECORDS, START_ONLY, AVAILABLE, INSUFFICIENT_HISTORY, OVERLAPPING_RANGES, AWAITING_RECORD, DATE_OUT_OF_RANGE }

/** 日历参考分段；排卵段表示时间不确定范围，不表示持续多日排卵。 */
data class DayimaPhaseRange(val phase: DayimaCyclePhase, val dates: DayimaDateRange?)

data class DayimaPhaseEstimate(
    val asOfDate: LocalDate,
    val startDate: LocalDate?,
    val cycleDay: Long?,
    val status: DayimaPhaseEstimateStatus,
    val currentPhase: DayimaCyclePhase?,
    val ranges: List<DayimaPhaseRange>,
    val nextPeriodRange: DayimaDateRange? = null,
    val ovulationReferenceDate: LocalDate? = null,
) {
    val nearExpectedPeriod get() = status == DayimaPhaseEstimateStatus.AVAILABLE && nextPeriodRange?.let { asOfDate in it } == true
}

/** 纯派生信息，不写库、不补造出血结束日或已经排卵的事实。
 * 卵泡期本身从月经首日开始；四段导航中的卵泡期仅显示经后部分。
 */
object DayimaCyclePhases {
    const val ReferenceBleedingDays = 5L

    fun estimate(statistics: DayimaStatistics): DayimaPhaseEstimate {
        val current = statistics.currentCycle
        val empty = DayimaPhaseEstimate(statistics.asOfDate, current?.startDate, current?.daysSinceStart?.plus(1),
            DayimaPhaseEstimateStatus.NO_RECORDS, null, DayimaCyclePhase.entries.map { DayimaPhaseRange(it, null) })
        if (current == null) return empty
        return try {
            val bleeding = DayimaDateRange(current.startDate, current.startDate.plusDays(ReferenceBleedingDays - 1))
            val earlyPhase = DayimaCyclePhase.MENSTRUAL.takeIf { statistics.asOfDate in bleeding }
            val partial = empty.copy(currentPhase = earlyPhase,
                ranges = empty.ranges.map { if (it.phase == DayimaCyclePhase.MENSTRUAL) it.copy(dates = bleeding) else it })
            val prediction = statistics.prediction.prediction ?: return partial.copy(status =
                if (statistics.prediction.status == DayimaPredictionStatus.DATE_OUT_OF_RANGE) DayimaPhaseEstimateStatus.DATE_OUT_OF_RANGE
                else if (earlyPhase != null) DayimaPhaseEstimateStatus.START_ONLY else DayimaPhaseEstimateStatus.INSUFFICIENT_HISTORY)
            if (prediction.latestPeriodId != current.startPeriodId || prediction.latestDate != current.startDate)
                return partial.copy(status = DayimaPhaseEstimateStatus.INSUFFICIENT_HISTORY)
            val nextRange = DayimaDateRange(prediction.rangeStart, prediction.rangeEnd)
            // 既有来潮范围不是置信区间；叠加一般的排卵到来潮 10～16 天差异，只作日历参考。
            val ovulation = DayimaDateRange(prediction.rangeStart.minusDays(16), prediction.rangeEnd.minusDays(10))
            if (ovulation.from <= bleeding.through || ovulation.through >= prediction.rangeStart)
                return partial.copy(status = if (statistics.asOfDate > prediction.rangeEnd) DayimaPhaseEstimateStatus.AWAITING_RECORD
                    else DayimaPhaseEstimateStatus.OVERLAPPING_RANGES, currentPhase = earlyPhase.takeUnless { statistics.asOfDate > prediction.rangeEnd },
                    nextPeriodRange = nextRange)
            val ranges = listOf(
                DayimaPhaseRange(DayimaCyclePhase.MENSTRUAL, bleeding),
                DayimaPhaseRange(DayimaCyclePhase.FOLLICULAR, DayimaDateRange(bleeding.through.plusDays(1), ovulation.from.minusDays(1))),
                DayimaPhaseRange(DayimaCyclePhase.OVULATION, ovulation),
                DayimaPhaseRange(DayimaCyclePhase.LUTEAL, DayimaDateRange(ovulation.through.plusDays(1), prediction.rangeEnd)),
            )
            val expired = statistics.asOfDate > prediction.rangeEnd
            partial.copy(status = if (expired) DayimaPhaseEstimateStatus.AWAITING_RECORD else DayimaPhaseEstimateStatus.AVAILABLE,
                currentPhase = if (expired) null else ranges.singleOrNull { it.dates?.let { dates -> statistics.asOfDate in dates } == true }?.phase,
                ranges = ranges, nextPeriodRange = nextRange, ovulationReferenceDate = prediction.predictedDate.minusDays(14))
        } catch (_: DateTimeException) {
            empty.copy(status = DayimaPhaseEstimateStatus.DATE_OUT_OF_RANGE)
        }
    }
}
