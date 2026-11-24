package com.txl.mebot.data

import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaProfileEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DayimaCyclePhasesTest {
    private val start = LocalDate.of(2026, 10, 1)
    private fun period(id: String, date: LocalDate) = DayimaPeriodEntity(id, date, 0, 0)
    private fun snapshot(interval: Long = 28) = DayimaRecordsSnapshot(2, null,
        listOf(period("previous", start.minusDays(interval)), period("current", start)), emptyList())
    private fun estimate(day: Long, data: DayimaRecordsSnapshot = snapshot()) =
        DayimaCyclePhases.estimate(DayimaStatisticsCalculator.calculate(data, start.plusDays(day - 1)))

    @Test fun emptyHistoryHasFourGuidesButNoInventedDatesOrCycleDay() {
        val result = estimate(1, DayimaRecordsSnapshot(0, null, emptyList(), emptyList()))
        assertEquals(DayimaPhaseEstimateStatus.NO_RECORDS, result.status)
        assertNull(result.cycleDay)
        assertNull(result.currentPhase)
        assertEquals(4, result.ranges.size)
        assertTrue(result.ranges.all { it.dates == null })
    }

    @Test fun singleStartUsesAnExplicitFiveDayReferenceAndNoOvulationPrediction() {
        val data = snapshot().copy(periods = listOf(period("current", start)))
        for (day in 1L..5L) {
            val result = estimate(day, data)
            assertEquals(day, result.cycleDay)
            assertEquals(DayimaPhaseEstimateStatus.START_ONLY, result.status)
            assertEquals(DayimaCyclePhase.MENSTRUAL, result.currentPhase)
            assertNull(result.ovulationReferenceDate)
            assertTrue(result.ranges.drop(1).all { it.dates == null })
        }
        assertEquals(DayimaPhaseEstimateStatus.INSUFFICIENT_HISTORY, estimate(6, data).status)
        assertNull(estimate(6, data).currentPhase)
    }

    @Test fun regularHistoryProducesOrderedReferenceSegmentsAndAnUncertaintyWindow() {
        val result = estimate(15)
        assertEquals(DayimaPhaseEstimateStatus.AVAILABLE, result.status)
        assertEquals(DayimaCyclePhase.OVULATION, result.currentPhase)
        assertEquals(start.plusDays(14), result.ovulationReferenceDate)
        assertEquals(listOf(
            DayimaDateRange(start, start.plusDays(4)),
            DayimaDateRange(start.plusDays(5), start.plusDays(9)),
            DayimaDateRange(start.plusDays(10), start.plusDays(20)),
            DayimaDateRange(start.plusDays(21), start.plusDays(30)),
        ), result.ranges.map { it.dates })
        for (day in 1L..31L) {
            val date = start.plusDays(day - 1)
            assertEquals(1, result.ranges.count { date in requireNotNull(it.dates) })
        }
    }

    @Test fun phaseTransitionsUseInclusiveCycleDaysAndUpdateAtDateBoundaries() {
        val expected = mapOf(1L to DayimaCyclePhase.MENSTRUAL, 5L to DayimaCyclePhase.MENSTRUAL,
            6L to DayimaCyclePhase.FOLLICULAR, 10L to DayimaCyclePhase.FOLLICULAR,
            11L to DayimaCyclePhase.OVULATION, 21L to DayimaCyclePhase.OVULATION,
            22L to DayimaCyclePhase.LUTEAL, 31L to DayimaCyclePhase.LUTEAL)
        for ((day, phase) in expected) {
            assertEquals(day, estimate(day).cycleDay)
            assertEquals("cycle day $day", phase, estimate(day).currentPhase)
        }
    }

    @Test fun longerHistoryDoesNotPinOvulationToDayFourteenOrAssumeTwentyEightDays() {
        val data = snapshot(40)
        val result = estimate(15, data)
        assertEquals(DayimaCyclePhase.FOLLICULAR, result.currentPhase)
        assertEquals(start.plusDays(26), result.ovulationReferenceDate)
        assertEquals(start.plusDays(22), result.ranges.single { it.phase == DayimaCyclePhase.OVULATION }.dates?.from)
    }

    @Test fun expectedPeriodRangePromptsRecordingAndExpiresWithoutInventingAnotherCycle() {
        assertFalse(estimate(26).nearExpectedPeriod)
        assertTrue(estimate(27).nearExpectedPeriod)
        assertTrue(estimate(31).nearExpectedPeriod)
        for (day in listOf(32L, 57L, 400L)) {
            val result = estimate(day)
            assertEquals(DayimaPhaseEstimateStatus.AWAITING_RECORD, result.status)
            assertNull(result.currentPhase)
            assertEquals(day, result.cycleDay)
            assertEquals(start, result.startDate)
            assertFalse(result.nearExpectedPeriod)
        }
    }

    @Test fun overlappingEstimatesKeepOnlyTheExplicitEarlyReference() {
        val data = snapshot(21)
        val early = estimate(3, data)
        assertEquals(DayimaPhaseEstimateStatus.OVERLAPPING_RANGES, early.status)
        assertEquals(DayimaCyclePhase.MENSTRUAL, early.currentPhase)
        assertNull(estimate(8, data).currentPhase)
        assertTrue(early.ranges.drop(1).all { it.dates == null })
    }

    @Test fun excludedLatestIntervalCannotReuseAnEarlierCycleForOvulation() {
        val data = snapshot()
        val a = data.periods[0]; val b = data.periods[1]
        val marker = DayimaSpanMarkerEntity("gap", a.id, b.id, a.startDate, b.startDate, a.revision, b.revision,
            DayimaSpanReason.MISSING_RECORDS, createdAt = 0, updatedAt = 0)
        val result = estimate(15, data.copy(spanMarkers = listOf(marker)))
        assertEquals(DayimaPhaseEstimateStatus.INSUFFICIENT_HISTORY, result.status)
        assertNull(result.currentPhase)
        assertNull(result.ovulationReferenceDate)
    }

    @Test fun futureStartDoesNotBecomeACurrentCycleOrPhase() {
        val data = snapshot().copy(periods = listOf(period("future", start.plusDays(1))))
        val result = estimate(1, data)
        assertEquals(DayimaPhaseEstimateStatus.NO_RECORDS, result.status)
        assertNull(result.startDate)
        assertNull(result.currentPhase)
    }

    @Test fun crossingLeapDayKeepsCalendarDatesAndInclusiveDayCount() {
        val date = LocalDate.of(2024, 2, 26)
        val data = DayimaRecordsSnapshot(2, null,
            listOf(period("a", date.minusDays(28)), period("b", date)), emptyList())
        val result = DayimaCyclePhases.estimate(DayimaStatisticsCalculator.calculate(data, LocalDate.of(2024, 2, 29)))
        assertEquals(4L, result.cycleDay)
        assertEquals(DayimaCyclePhase.MENSTRUAL, result.currentPhase)
        assertEquals(LocalDate.of(2024, 3, 1), result.ranges.first().dates?.through)
    }

    @Test fun birthDateDoesNotChangePhaseEstimates() {
        val data = snapshot()
        val original = estimate(15, data)
        assertEquals(original, estimate(15, data.copy(profile = DayimaProfileEntity(birthDate = LocalDate.of(1980, 2, 29), updatedAt = 10))))
    }

    @Test fun extremeDatesDoNotOverflowIntoAFakePhase() {
        val date = LocalDate.MAX.minusDays(1)
        val data = DayimaRecordsSnapshot(1, null, listOf(period("edge", date)), emptyList())
        val result = DayimaCyclePhases.estimate(DayimaStatisticsCalculator.calculate(data, date))
        assertEquals(DayimaPhaseEstimateStatus.DATE_OUT_OF_RANGE, result.status)
        assertNull(result.currentPhase)
        assertTrue(result.ranges.all { it.dates == null })
    }
}
