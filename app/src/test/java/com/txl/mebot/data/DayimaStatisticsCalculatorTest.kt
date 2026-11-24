package com.txl.mebot.data

import com.txl.mebot.data.dayima.DayimaCycleStatus
import com.txl.mebot.data.dayima.DayimaDateRange
import com.txl.mebot.data.dayima.DayimaPredictionStatus
import com.txl.mebot.data.dayima.DayimaRecordsSnapshot
import com.txl.mebot.data.dayima.DayimaStatisticsCalculator
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaProfileEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DayimaStatisticsCalculatorTest {
    private val start = LocalDate.of(2026, 1, 1)

    @Test fun emptyHistoryDoesNotInventACycleOrPercentages() {
        val result = calculate(snapshot(emptyList()), start)
        assertEquals(0, result.period.completedCycleCount)
        assertTrue(result.period.frequency.isEmpty())
        assertNull(result.period.distribution)
        assertNull(result.period.comparedWith28Days.equalProportion)
        assertNull(result.period.comparedWithPersonalMedian.baselineDays)
        assertNull(result.currentCycle)
        assertEquals(DayimaPredictionStatus.NO_RECORDS, result.prediction.status)
    }

    @Test fun oneOldStartRemainsAnOpenCycleEvenAfterSeveralYears() {
        val periods = periods(start.minusYears(3))
        val result = calculate(snapshot(periods), start)
        assertEquals(0, result.period.completedCycleCount)
        assertTrue(result.currentCycle!!.daysSinceStart > 1_000)
        assertEquals(DayimaPredictionStatus.INSUFFICIENT_CONTINUOUS_HISTORY, result.prediction.status)
    }

    @Test fun datesAreSortedAndOnlyAdjacentStartDifferencesCount() {
        val periods = periods(start, 28, 30)
        val result = calculate(snapshot(periods.reversed()), periods.last().startDate.plusDays(9))
        assertEquals(listOf(28L, 30L), result.period.cycles.map { it.days })
        assertEquals(listOf("p0", "p1"), result.period.cycles.map { it.startPeriodId })
        assertEquals(2, result.period.completedCycleCount)
        assertEquals(9L, result.currentCycle?.daysSinceStart)
        assertEquals("p2", result.currentCycle?.startPeriodId)
    }

    @Test fun dateRangeUsesCycleStartAndKeepsSuccessorsOutsideTheRange() {
        val periods = periods(LocalDate.of(2025, 12, 31), 28, 28)
        val asOf = LocalDate.of(2026, 3, 1)
        val all = calculate(snapshot(periods), asOf)
        val january = DayimaStatisticsCalculator.calculate(snapshot(periods), asOf,
            DayimaDateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        assertEquals(1, january.period.completedCycleCount)
        assertEquals("p1", january.period.cycles.single().startPeriodId)
        assertEquals(LocalDate.of(2026, 2, 25), january.period.cycles.single().endDate)
        assertEquals(2026, january.period.years.single().year)
        assertEquals(all.prediction, january.prediction)
        assertEquals(all.currentCycle, january.currentCycle)
    }

    @Test fun futureStoredDatesAreNotObservationsAndDoNotCloseTheCurrentCycle() {
        val result = calculate(snapshot(periods(start, 28, 28)), LocalDate.of(2026, 2, 1))
        assertEquals(1, result.ignoredFutureRecordCount)
        assertEquals(1, result.period.completedCycleCount)
        assertEquals(3L, result.currentCycle?.daysSinceStart)
        assertEquals(listOf(28L), result.prediction.prediction?.recentIntervals)
    }

    @Test fun aValidMarkerEndingAfterTheEvaluationDateDoesNotBecomeStaleJustBecauseOfThatDate() {
        val periods = periods(start, 28, 28)
        val result = calculate(snapshot(periods, listOf(marker(periods, 1))), LocalDate.of(2026, 2, 1))
        assertEquals(1, result.period.completedCycleCount)
        assertEquals(0, result.period.pendingReviewMarkerCount)
        assertEquals(0, result.period.excludedCycleCount)
    }

    @Test fun anEmptySelectedRangeHasNoSamplesButDoesNotChangeTheCurrentPrediction() {
        val periods = periods(start, 28)
        val source = snapshot(periods)
        val all = calculate(source, periods.last().startDate)
        val empty = DayimaStatisticsCalculator.calculate(source, periods.last().startDate,
            DayimaDateRange(start.minusYears(2), start.minusYears(1)))
        assertEquals(0, empty.period.eligibleCycleCount)
        assertTrue(empty.period.frequency.isEmpty())
        assertNull(empty.period.distribution)
        assertEquals(all.prediction, empty.prediction)
    }

    @Test fun frequencyPercentagesQuartilesAndBothBaselinesUseEligibleCycles() {
        val periods = periods(start, 26, 28, 28, 30)
        val stats = calculate(snapshot(periods), periods.last().startDate).period
        assertEquals(listOf(26L, 28L, 30L), stats.frequency.map { it.days })
        assertEquals(listOf(1, 2, 1), stats.frequency.map { it.count })
        assertEquals(1.0, stats.frequency.sumOf { it.proportion }, 0.000001)
        assertEquals(listOf("p1", "p2"), stats.frequency[1].cycleStartIds)
        assertEquals(28.0, stats.distribution!!.medianDays, 0.0)
        assertEquals(27.5, stats.distribution.lowerQuartileDays, 0.0)
        assertEquals(28.5, stats.distribution.upperQuartileDays, 0.0)
        assertEquals(1, stats.comparedWith28Days.shorterCount)
        assertEquals(2, stats.comparedWith28Days.equalCount)
        assertEquals(1, stats.comparedWith28Days.longerCount)
        assertEquals(stats.comparedWith28Days, stats.comparedWithPersonalMedian)
    }

    @Test fun personalMedianCanBeHalfADayWithoutAnInventedEqualCount() {
        val periods = periods(start, 27, 28)
        val comparison = calculate(snapshot(periods), periods.last().startDate).period.comparedWithPersonalMedian
        assertEquals(27.5, comparison.baselineDays!!, 0.0)
        assertEquals(1, comparison.shorterCount)
        assertEquals(0, comparison.equalCount)
        assertEquals(1, comparison.longerCount)
    }

    @Test fun explicitMissingAndSpecialSpansRemainVisibleButDoNotEnterDistribution() {
        val periods = periods(start, 28, 35, 27, 29)
        val markers = listOf(marker(periods, 1), marker(periods, 2).copy(reason = DayimaSpanReason.SPECIAL_PHASE))
        val result = calculate(snapshot(periods, markers), periods.last().startDate)
        assertEquals(4, result.period.completedCycleCount)
        assertEquals(2, result.period.eligibleCycleCount)
        assertEquals(2, result.period.excludedCycleCount)
        assertEquals(listOf(28L, 29L), result.period.frequency.map { it.days })
        assertEquals(listOf(29L), result.prediction.prediction?.recentIntervals)
    }

    @Test fun staleExactPairIsPendingReviewRatherThanAnAppliedOldExclusion() {
        val periods = periods(start, 28)
        val oldMarker = marker(periods, 0)
        val changed = periods.mapIndexed { index, period -> if (index == 0) period.copy(revision = 2) else period }
        val result = calculate(snapshot(changed, listOf(oldMarker)), periods.last().startDate)
        assertEquals(DayimaCycleStatus.PENDING_REVIEW, result.period.cycles.single().status)
        assertEquals(1, result.period.pendingReviewCycleCount)
        assertEquals(1, result.period.pendingReviewMarkerCount)
        assertEquals(0, result.period.excludedCycleCount)
        assertTrue(result.period.frequency.isEmpty())
        assertNull(result.prediction.prediction)
    }

    @Test fun anOldNonAdjacentMarkerDoesNotGetAppliedToNewSubcycles() {
        val original = periods(start, 28)
        val marker = marker(original, 0).copy(needsReview = true)
        val middle = DayimaPeriodEntity("middle", start.plusDays(14), 1, 1)
        val result = calculate(snapshot(original + middle, listOf(marker)), original.last().startDate)
        assertEquals(listOf(14L, 14L), result.period.cycles.map { it.days })
        assertEquals(2, result.period.eligibleCycleCount)
        assertEquals(0, result.period.pendingReviewCycleCount)
        assertEquals(1, result.period.pendingReviewMarkerCount)
        val orphan = calculate(snapshot(original.drop(1), listOf(marker.copy(startPeriodId = null))), original.last().startDate)
        assertEquals(0, orphan.period.completedCycleCount)
        assertEquals(1, orphan.period.pendingReviewMarkerCount)
    }

    @Test fun rollingMedianRequiresSixCyclesAndSlidesWithTraceableSources() {
        val periods = periods(start, 26, 27, 28, 29, 30, 31, 32)
        val trend = calculate(snapshot(periods), periods.last().startDate).period.trend
        assertTrue(trend.take(5).all { it.rollingMedianDays == null })
        assertEquals(28.5, trend[5].rollingMedianDays!!, 0.0)
        assertEquals(29.5, trend[6].rollingMedianDays!!, 0.0)
        assertEquals((1..6).map { "p$it" }, trend[6].rollingCycleStartIds)
        val range = DayimaDateRange(periods[5].startDate, periods[6].startDate)
        assertEquals(trend.drop(5), DayimaStatisticsCalculator.calculate(snapshot(periods), periods.last().startDate, range).period.trend)
    }

    @Test fun rollingLinesAndPredictionDoNotBridgeAnExcludedCycle() {
        val periods = periods(start.minusYears(2), *IntArray(13) { 28 })
        val result = calculate(snapshot(periods, listOf(marker(periods, 6))), periods.last().startDate)
        val trend = result.period.trend
        assertNotNull(trend[5].rollingMedianDays)
        assertNull(trend[6].segmentId)
        assertTrue(trend.subList(7, 12).all { it.rollingMedianDays == null })
        assertNotNull(trend[12].rollingMedianDays)
        assertNotEquals(trend[5].segmentId, trend[12].segmentId)
        assertEquals((7..12).map { "p$it" }, result.prediction.prediction?.cycleStartIds)
    }

    @Test fun ageIsCalculatedAtEachStartAndLeapBirthdaysFollowCalendarYears() {
        val birth = LocalDate.of(2000, 2, 29)
        val periods = listOf(LocalDate.of(2025, 2, 28), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 29))
            .mapIndexed { index, date -> DayimaPeriodEntity("p$index", date, 1, 1) }
        val stats = calculate(snapshot(periods, birth = birth), periods.last().startDate).period
        assertEquals(listOf(24, 25), stats.cycles.map { it.ageYears })
        assertEquals(listOf(24, 25), stats.ages.map { it.ageYears })
        assertTrue(stats.ages.all { it.distribution == null })
    }

    @Test fun sparseYearAndAgeGroupsExposeCountsWithoutAnAggregateBand() {
        val periods = periods(LocalDate.of(2024, 12, 1), 28, 28, 28, 28, 28)
        val stats = calculate(snapshot(periods, birth = LocalDate.of(2000, 1, 1)), periods.last().startDate).period
        assertEquals(listOf(2, 3), stats.years.map { it.sampleSize })
        assertNull(stats.years[0].distribution)
        assertNotNull(stats.years[1].distribution)
        assertEquals(listOf(24, 25), stats.ages.map { it.ageYears })
        assertNull(stats.ages[0].distribution)
        assertNotNull(stats.ages[1].distribution)
        assertFalse(stats.hasEnoughAgeHistory)
    }

    @Test fun birthDateCanBeMissingOrLaterThanSomeRecordsWithoutNegativeAges() {
        val periods = periods(start, 28, 28)
        val missing = calculate(snapshot(periods), periods.last().startDate)
        assertTrue(missing.period.ages.isEmpty())
        assertEquals(2, missing.period.ageUnavailableCycleCount)
        assertFalse(missing.birthDateAvailable)
        val partiallyKnown = calculate(snapshot(periods, birth = start.plusDays(1)), periods.last().startDate)
        assertEquals(listOf(null, 0), partiallyKnown.period.cycles.map { it.ageYears })
        assertEquals(1, partiallyKnown.period.ageUnavailableCycleCount)
        val futureBirth = calculate(snapshot(periods, birth = periods.last().startDate.plusDays(1)), periods.last().startDate)
        assertFalse(futureBirth.birthDateAvailable)
        assertTrue(futureBirth.period.ages.isEmpty())
    }

    @Test fun longHistoryWithTwoPopulatedAgeGroupsCanDescribeAgeChanges() {
        val dates = listOf("2024-01-01", "2024-01-29", "2024-02-26", "2024-03-25", "2025-01-01", "2025-01-29", "2025-02-26", "2025-03-26")
        val periods = dates.mapIndexed { index, date -> DayimaPeriodEntity("p$index", LocalDate.parse(date), 1, 1) }
        val stats = calculate(snapshot(periods, birth = LocalDate.of(2000, 1, 1)), periods.last().startDate).period
        assertTrue(stats.hasEnoughAgeHistory)
        assertEquals(listOf(4, 3), stats.ages.map { it.sampleSize })
    }

    @Test fun predictionMatchesTheOldWeightedAlgorithm() {
        val periods = periods(LocalDate.of(2026, 5, 1), 29, 28, 30)
        val prediction = calculate(snapshot(periods), periods.last().startDate).prediction.prediction!!
        assertEquals(listOf(29L, 28L, 30L), prediction.recentIntervals)
        assertEquals(29L, prediction.weightedCycleDays)
        assertEquals(2, prediction.variationDays)
        assertEquals(LocalDate.of(2026, 8, 25), prediction.predictedDate)
        assertEquals(LocalDate.of(2026, 8, 23), prediction.rangeStart)
        assertEquals(LocalDate.of(2026, 8, 27), prediction.rangeEnd)
    }

    @Test fun predictionUsesOnlyTheLatestSixIntervalsAndRoundsHalfUp() {
        val periods = periods(start.minusYears(2), 60, 25, 26, 27, 28, 29, 30)
        val prediction = calculate(snapshot(periods), periods.last().startDate).prediction.prediction!!
        assertEquals(listOf(25L, 26L, 27L, 28L, 29L, 30L), prediction.recentIntervals)
        assertEquals(28L, prediction.weightedCycleDays)
        assertEquals(4, prediction.variationDays)
        val half = periods(start, 27, 27, 28)
        assertEquals(28L, calculate(snapshot(half), half.last().startDate).prediction.prediction?.weightedCycleDays)
    }

    @Test fun predictionRangeHasTheOldTwoDayMinimumAndSevenDayMaximum() {
        val one = periods(start, 28)
        assertEquals(2, calculate(snapshot(one), one.last().startDate).prediction.prediction?.variationDays)
        val variable = periods(start, 24, 35)
        val prediction = calculate(snapshot(variable), variable.last().startDate).prediction.prediction!!
        assertEquals(31L, prediction.weightedCycleDays)
        assertEquals(7, prediction.variationDays)
    }

    @Test fun aBreakAtTheLatestCycleCannotBorrowOlderHistoryForPrediction() {
        val periods = periods(start, 28, 28, 28)
        for (flag in listOf(marker(periods, 2), marker(periods, 2).copy(needsReview = true))) {
            val result = calculate(snapshot(periods, listOf(flag)), periods.last().startDate)
            assertEquals(2, result.period.eligibleCycleCount)
            assertEquals(DayimaPredictionStatus.INSUFFICIENT_CONTINUOUS_HISTORY, result.prediction.status)
        }
        val tail = periods(start, 20, 50, 30, 31)
        val prediction = calculate(snapshot(tail, listOf(marker(tail, 1))), tail.last().startDate).prediction.prediction!!
        assertEquals(listOf(30L, 31L), prediction.recentIntervals)
        assertEquals(31L, prediction.weightedCycleDays)
    }

    @Test fun overduePredictionsAreNotRolledForwardAndDateOverflowIsReported() {
        val periods = periods(start, 28)
        val original = calculate(snapshot(periods), periods.last().startDate).prediction.prediction!!
        val late = calculate(snapshot(periods), start.plusYears(2))
        assertEquals(original.predictedDate, late.prediction.prediction?.predictedDate)
        assertTrue(late.prediction.prediction!!.rangeHasEnded)
        assertEquals(1, late.period.completedCycleCount)
        val edge = listOf(LocalDate.MAX.minusDays(29), LocalDate.MAX.minusDays(1)).mapIndexed { i, d -> DayimaPeriodEntity("p$i", d, 1, 1) }
        assertEquals(DayimaPredictionStatus.DATE_OUT_OF_RANGE, calculate(snapshot(edge), LocalDate.MAX).prediction.status)
    }

    @Test fun periodComparisonUsesOneVersionAndRequiresThreeSamplesPerSide() {
        val periods = periods(start, 26, 27, 28, 29, 30, 31)
        val source = snapshot(periods)
        val a = DayimaDateRange(periods[0].startDate, periods[2].startDate)
        val b = DayimaDateRange(periods[3].startDate, periods[5].startDate)
        val result = DayimaStatisticsCalculator.compare(source, periods.last().startDate, a, b)
        assertEquals(source.dataVersion, result.dataVersion)
        assertEquals(3.0, result.medianDifferenceDays!!, 0.0)
        val sparse = DayimaDateRange(periods[4].startDate, periods[5].startDate)
        assertNull(DayimaStatisticsCalculator.compare(source, periods.last().startDate, a, sparse).medianDifferenceDays)
    }

    @Test fun veryLongUnmarkedIntervalsAreRetainedAsObservedBins() {
        val periods = listOf(start.minusYears(10), start).mapIndexed { index, date -> DayimaPeriodEntity("p$index", date, 1, 1) }
        val stats = calculate(snapshot(periods), start).period
        assertEquals(1, stats.frequency.size)
        assertTrue(stats.frequency.single().days > 3_000)
        assertEquals(1, stats.eligibleCycleCount)
    }

    @Test fun invalidRangesAndAmbiguousDuplicateStartsAreRejected() {
        assertTrue(runCatching { DayimaDateRange(start.plusDays(1), start) }.isFailure)
        val one = periods(start).single()
        assertTrue(runCatching { calculate(snapshot(listOf(one, one.copy(id = "another"))), start) }.isFailure)
    }

    private fun calculate(source: DayimaRecordsSnapshot, asOf: LocalDate) = DayimaStatisticsCalculator.calculate(source, asOf)
    private fun snapshot(periods: List<DayimaPeriodEntity>, markers: List<DayimaSpanMarkerEntity> = emptyList(), birth: LocalDate? = null) =
        DayimaRecordsSnapshot(42, birth?.let { DayimaProfileEntity(birthDate = it, updatedAt = 1) }, periods, markers)
    private fun periods(first: LocalDate, vararg intervals: Int): List<DayimaPeriodEntity> {
        var current = first
        return listOf(first).plus(intervals.map { current = current.plusDays(it.toLong()); current })
            .mapIndexed { index, date -> DayimaPeriodEntity("p$index", date, 1, 1) }
    }
    private fun marker(periods: List<DayimaPeriodEntity>, index: Int) = DayimaSpanMarkerEntity(
        "marker-$index", periods[index].id, periods[index + 1].id, periods[index].startDate, periods[index + 1].startDate,
        periods[index].revision, periods[index + 1].revision, DayimaSpanReason.MISSING_RECORDS, createdAt = 1, updatedAt = 1,
    )
}
