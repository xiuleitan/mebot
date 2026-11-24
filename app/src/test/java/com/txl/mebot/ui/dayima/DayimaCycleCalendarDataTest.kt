package com.txl.mebot.ui.dayima

import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.DayimaPeriodEntity
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class DayimaCycleCalendarDataTest {
    private val start = LocalDate.of(2026, 10, 11)
    private val today = LocalDate.of(2026, 10, 26)
    private fun statistics(date: LocalDate = today, interval: Long = 27) = DayimaStatisticsCalculator.calculate(
        DayimaRecordsSnapshot(2, null, listOf(DayimaPeriodEntity("previous", start.minusDays(interval), 0, 0),
            DayimaPeriodEntity("current", start, 0, 0)), emptyList()), date)
    private fun days(stats: DayimaStatistics, month: YearMonth) =
        dayimaCalendarDays(month, DayimaCyclePhases.estimate(stats), stats.prediction.prediction).filterNotNull()
            .filter { YearMonth.from(it.date) == month }

    @Test fun adjacentMonthsCoverTheCurrentStartAndNextPrediction() {
        val stats = statistics()
        assertEquals(YearMonth.of(2026, 10), dayimaCalendarFirstMonth(DayimaCyclePhases.estimate(stats), stats.prediction.prediction))
        assertEquals(LocalDate.of(2026, 11, 7), stats.prediction.prediction?.predictedDate)
    }

    @Test fun phaseColorsStopAtPredictionWithoutInventingTheNextPeriod() {
        val stats = statistics()
        val cells = days(stats, YearMonth.of(2026, 10)) + days(stats, YearMonth.of(2026, 11))
        fun phase(date: String) = cells.single { it.date == LocalDate.parse(date) }.phase
        assertNull(phase("2026-10-10"))
        assertEquals(DayimaCyclePhase.MENSTRUAL, phase("2026-10-11"))
        assertEquals(DayimaCyclePhase.FOLLICULAR, phase("2026-10-16"))
        assertEquals(DayimaCyclePhase.OVULATION, phase("2026-10-20"))
        assertEquals(DayimaCyclePhase.LUTEAL, phase("2026-11-06"))
        assertNull(phase("2026-11-07"))
        assertNull(phase("2026-11-08"))
        assertEquals(listOf(start), cells.filter { it.start }.map { it.date })
        assertEquals(listOf(today), cells.filter { it.today }.map { it.date })
        assertEquals(listOf(LocalDate.of(2026, 11, 7)), cells.filter { it.predicted }.map { it.date })
    }

    @Test fun sundayFirstGridUsesOnlyTheWeeksNeededByBothMonths() {
        val stats = statistics()
        val cells = dayimaCalendarDays(YearMonth.of(2026, 11), DayimaCyclePhases.estimate(stats), stats.prediction.prediction)
        assertEquals(63, cells.size)
        assertEquals(LocalDate.of(2026, 11, 1), cells.first()?.date)
        assertEquals(61, cells.filterNotNull().size)
        assertEquals(LocalDate.of(2026, 12, 31), cells.filterNotNull().last().date)
        assertTrue(cells.drop(61).all { it == null })
    }

    @Test fun leapFebruaryAndMonthLeadingCellsRemainCorrect() {
        val estimate = DayimaCyclePhases.estimate(statistics())
        val leap = dayimaCalendarDays(YearMonth.of(2024, 2), estimate, null)
        assertTrue(leap.take(4).all { it == null }) // 2024-02-01 为周四。
        assertEquals(29, leap.filterNotNull().count { it.date.monthValue == 2 })
        val common = dayimaCalendarDays(YearMonth.of(2026, 2), estimate, null)
        assertEquals(28, common.filterNotNull().count { it.date.monthValue == 2 })
    }

    @Test fun aCycleCrossingDecemberKeepsTheRightCalendarYears() {
        val date = LocalDate.of(2026, 12, 20)
        val stats = DayimaStatisticsCalculator.calculate(DayimaRecordsSnapshot(2, null, listOf(
            DayimaPeriodEntity("a", date.minusDays(28), 0, 0), DayimaPeriodEntity("b", date, 0, 0)), emptyList()), date.plusDays(10))
        val first = dayimaCalendarFirstMonth(DayimaCyclePhases.estimate(stats), stats.prediction.prediction)
        assertEquals(YearMonth.of(2026, 12), first)
        assertEquals(YearMonth.of(2027, 1), first.plusMonths(1))
        assertTrue(days(stats, first.plusMonths(1)).single { it.predicted }.date.year == 2027)
    }

    @Test fun oneStartShowsOnlyTheFiveDayReferenceAndNoFakeOvulation() {
        val stats = DayimaStatisticsCalculator.calculate(DayimaRecordsSnapshot(1, null,
            listOf(DayimaPeriodEntity("a", start, 0, 0)), emptyList()), today)
        val cells = days(stats, YearMonth.of(2026, 10))
        assertEquals(5, cells.count { it.phase == DayimaCyclePhase.MENSTRUAL })
        assertTrue(cells.none { it.phase != null && it.phase != DayimaCyclePhase.MENSTRUAL })
        assertTrue(cells.none { it.predicted })
    }

    @Test fun expiredPredictionStopsColoringAndReturnsToTheCurrentMonth() {
        val stats = statistics(LocalDate.of(2026, 11, 10))
        val estimate = DayimaCyclePhases.estimate(stats)
        assertEquals(DayimaPhaseEstimateStatus.AWAITING_RECORD, estimate.status)
        assertEquals(YearMonth.of(2026, 11), dayimaCalendarFirstMonth(estimate, stats.prediction.prediction))
        val cells = days(stats, YearMonth.of(2026, 10)) + days(stats, YearMonth.of(2026, 11))
        assertTrue(cells.none { it.phase != null || it.predicted })
        assertEquals(1, cells.count { it.start })
    }

    @Test fun longCycleKeepsTodayVisibleInsteadOfJumpingSeveralMonthsAhead() {
        val stats = statistics(interval = 95)
        assertEquals(YearMonth.of(2026, 10), dayimaCalendarFirstMonth(DayimaCyclePhases.estimate(stats), stats.prediction.prediction))
    }

    @Test fun aStalePredictionFromAnotherStartIsNeverMarkedAsTheNextDate() {
        val stats = statistics()
        val stale = requireNotNull(stats.prediction.prediction).copy(latestDate = start.minusDays(1))
        val cells = dayimaCalendarDays(YearMonth.of(2026, 11), DayimaCyclePhases.estimate(stats), stale).filterNotNull()
        assertTrue(cells.none { it.predicted || it.phase != null })
    }

    @Test fun lastSupportedMonthPairDoesNotOverflowItsDateGrid() {
        val empty = DayimaPhaseEstimate(LocalDate.MAX, null, null, DayimaPhaseEstimateStatus.NO_RECORDS,
            null, DayimaCyclePhase.entries.map { DayimaPhaseRange(it, null) })
        val first = dayimaCalendarFirstMonth(empty, null)
        assertEquals(DayimaLastFirstMonth, first)
        val cells = dayimaCalendarDays(first, empty, null).filterNotNull()
        assertEquals(LocalDate.MAX, cells.last().date)
    }

    @Test fun adjacentMonthsShareTheirBoundaryWeekWithoutRepeatingDays() {
        val estimate = DayimaCyclePhases.estimate(statistics())
        val cells = dayimaCalendarDays(YearMonth.of(2026, 7), estimate, null)
        val julyEnd = cells.indexOfFirst { it?.date == LocalDate.of(2026, 7, 31) }
        val augustStart = cells.indexOfFirst { it?.date == LocalDate.of(2026, 8, 1) }
        assertEquals(julyEnd + 1, augustStart)
        assertEquals(julyEnd / 7, augustStart / 7)
        assertEquals(62, cells.filterNotNull().map { it.date }.toSet().size)
        assertEquals(listOf(DayimaCalendarMonthBand(YearMonth.of(2026, 7), 5),
            DayimaCalendarMonthBand(YearMonth.of(2026, 8), 5)), dayimaCalendarMonthBands(cells))
    }

    @Test fun everyMonthPairHasOnlyOuterPaddingAndCorrectWeekdayColumns() {
        val estimate = DayimaCyclePhases.estimate(statistics())
        for (month in 1..12) {
            val first = YearMonth.of(2025, month)
            val cells = dayimaCalendarDays(first, estimate, null)
            assertEquals(0, cells.size % 7)
            assertTrue(cells.subList(cells.indexOfFirst { it != null }, cells.indexOfLast { it != null } + 1).all { it != null })
            val dates = cells.filterNotNull().map { it.date }
            assertEquals(first.lengthOfMonth() + first.plusMonths(1).lengthOfMonth(), dates.size)
            assertEquals(dates.size, dates.toSet().size)
            dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
            cells.forEachIndexed { index, day -> if (day != null) assertEquals(day.date.dayOfWeek.value % 7, index % 7) }
        }
    }

    @Test fun leftMonthBandsCoverEverySharedWeekIncludingLeapAndYearBoundaries() {
        val estimate = DayimaCyclePhases.estimate(statistics())
        for (first in listOf(YearMonth.of(2026, 12), YearMonth.of(2024, 2), YearMonth.of(2026, 10))) {
            val cells = dayimaCalendarDays(first, estimate, null)
            val bands = dayimaCalendarMonthBands(cells)
            assertEquals(listOf(first, first.plusMonths(1)), bands.map { it.month })
            assertEquals(cells.size / 7, bands.sumOf { it.weekCount })
            val owners = bands.flatMap { band -> List(band.weekCount) { band.month } }
            cells.chunked(7).forEachIndexed { index, week ->
                assertEquals(YearMonth.from(week.firstNotNullOf { it?.date }), owners[index])
            }
        }
    }

    @Test fun earliestMonthDoesNotNeedToConstructDatesBeforeTheSupportedRange() {
        val empty = DayimaPhaseEstimate(LocalDate.MIN, null, null, DayimaPhaseEstimateStatus.NO_RECORDS,
            null, DayimaCyclePhase.entries.map { DayimaPhaseRange(it, null) })
        val cells = dayimaCalendarDays(DayimaFirstMonth, empty, null)
        assertEquals(LocalDate.MIN, cells.firstNotNullOf { it?.date })
        assertEquals(DayimaFirstMonth.plusMonths(1).atEndOfMonth(), cells.filterNotNull().last().date)
        assertEquals(2, dayimaCalendarMonthBands(cells).size)
    }
}
