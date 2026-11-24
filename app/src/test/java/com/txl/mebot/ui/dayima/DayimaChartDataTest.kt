package com.txl.mebot.ui.dayima

import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaProfileEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DayimaChartDataTest {
    private val start = LocalDate.of(2020, 1, 1)

    @Test fun oneDayBinsKeepZerosAndTraceEachCountToItsStartAndEnd() {
        val data = charts(snapshot(26, 28, 28, 30))
        assertEquals(listOf(26L, 27L, 28L, 29L, 30L), data.histogram.map { it.fromDays })
        assertEquals(listOf(1, 0, 2, 0, 1), data.histogram.map { it.count })
        assertEquals(4, data.histogram.sumOf { it.count })
        val rows = data.histogram.single { it.fromDays == 28L }.selection().cycles(data)
        assertEquals(listOf("p1", "p2"), rows.map { it.startPeriodId })
        assertTrue(rows.all { it.endDate.toEpochDay() - it.startDate.toEpochDay() == 28L })
        assertEquals(4, data.time.points.size) // 最后一条开始尚未形成周期。
    }

    @Test fun distantDayBarsPreserveEveryCycleWithoutAllocatingAllEmptyDays() {
        val data = charts(snapshot(28, 28, 28, 28, 100_000))
        assertTrue(data.histogram.size < 10)
        assertEquals(5, data.histogram.sumOf { it.count })
        assertEquals(setOf("p0", "p1", "p2", "p3", "p4"), data.histogram.flatMap { it.cycleStartIds }.toSet())
        val distant = data.histogram.single { it.fromDays == 100_000L }
        assertEquals(listOf(100_000L), distant.selection().cycles(data).map { it.days })
        assertEquals(1, data.histogram.count { it.gap })
        assertTrue(data.histogram.filter { it.gap }.all { it.count == 0 })
    }

    @Test fun excludedAndPendingPointsStayTraceableAndNeverInflateHistogramCounts() {
        var snapshot = snapshot(28, 35, 30)
        snapshot = snapshot.copy(spanMarkers = listOf(marker(snapshot, 0), marker(snapshot, 1).copy(needsReview = true)))
        val data = charts(snapshot)
        assertEquals(1, data.histogram.sumOf { it.count })
        assertEquals(listOf("p2"), data.histogram.flatMap { it.cycleStartIds })
        assertEquals(3, data.time.points.size)
        assertEquals(1, data.statistics.period.excludedCycleCount)
        assertEquals(1, data.statistics.period.pendingReviewCycleCount)
        val excluded = DayimaChartSelection(DayimaChartSelectionKind.POINTS, cycleStartIds = listOf("p0")).cycles(data).single()
        assertEquals(DayimaCycleStatus.MISSING_RECORDS, excluded.status)
    }

    @Test fun timeCoordinatesUseActualDatesInsteadOfUniformRecordSlots() {
        val plot = charts(snapshot(10, 30, 20)).time
        assertEquals(3.0, (plot.points[2].x - plot.points[1].x) / (plot.points[1].x - plot.points[0].x), 0.000001)
        assertEquals(0.0, plot.xFraction(plot.points.first().x), 0.000001)
        assertEquals(1.0, plot.xFraction(plot.points.last().x), 0.000001)
    }

    @Test fun rollingLinesStartAtSixAndBreakAtEachExcludedOrPendingSpan() {
        var snapshot = snapshot(24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52)
        snapshot = snapshot.copy(spanMarkers = listOf(marker(snapshot, 7), marker(snapshot, 13).copy(needsReview = true)))
        val plot = charts(snapshot).time
        assertEquals(1, plot.rollingLines.size)
        assertEquals("p5", plot.rollingLines.single().first.cycle.startPeriodId)
        assertEquals("p6", plot.rollingLines.single().second.cycle.startPeriodId)
        assertTrue(plot.points.take(5).all { it.rollingMedian == null })
        assertTrue(plot.points.drop(7).all { it.rollingMedian == null })
    }

    @Test fun filteredRollingPointIncludesEarlierSourceCyclesAndItsValueFitsAxisBounds() {
        val snapshot = snapshot(24, 26, 28, 30, 32, 34, 36, 38)
        val range = DayimaDateRange(snapshot.periods[6].startDate, snapshot.periods[7].startDate)
        val data = charts(snapshot, range)
        assertEquals(2, data.statistics.period.completedCycleCount)
        val point = data.time.points.first()
        val rolling = requireNotNull(point.rollingMedian)
        assertTrue(data.time.yFraction(rolling) in 0.0..1.0)
        val selection = requireNotNull(data.time.hit((data.time.xFraction(point.x) * 1000).toFloat(),
            (data.time.yFraction(rolling) * 1000).toFloat(), 1000f, 1000f, 1f))
        val sources = selection.cycles(data)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6"), sources.map { it.startPeriodId })
        assertTrue(sources.any { it.startDate !in range })
    }

    @Test fun sparseYearGroupsHaveRawPointsButNoInventedSummaryBand() {
        val dates = listOf("2020-05-01", "2020-06-01", "2020-06-30", "2020-07-29", "2020-09-01", "2021-01-01", "2021-02-01").map(LocalDate::parse)
        val snapshot = snapshot(dates).copy(profile = DayimaProfileEntity(birthDate = LocalDate.of(2000, 7, 1), updatedAt = 1))
        val data = charts(snapshot)
        assertEquals(6, data.years.points.size)
        assertNotNull(data.years.groups.single { it.key == 2020 }.distribution)
        assertNull(data.years.groups.single { it.key == 2021 }.distribution)
        assertTrue(data.ages.groups.all { it.distribution?.sampleSize == 3 })
        assertEquals(listOf(19, 20), data.ages.groups.map { it.key })
        assertTrue(data.years.rollingLines.isEmpty())
        assertTrue(data.ages.rollingLines.isEmpty())
    }

    @Test fun ageChangesDoNotAlterHistogramAndUnknownAgesRemainAvailableInTimeView() {
        val original = snapshot(28, 28, 28)
        val missing = charts(original)
        assertTrue(missing.ages.points.isEmpty())
        assertEquals(3, missing.time.points.size)
        val known = charts(original.copy(profile = DayimaProfileEntity(birthDate = start.plusDays(20), updatedAt = 1)))
        assertEquals(2, known.ages.points.size)
        assertEquals(missing.histogram, known.histogram)
        assertEquals(missing.time.points.map { it.cycle.days }, known.time.points.map { it.cycle.days })
    }

    @Test fun ageGroupHitReturnsAllOriginalCyclesForThatActualAge() {
        val snapshot = snapshot(28, 28, 28).copy(profile = DayimaProfileEntity(birthDate = LocalDate.of(2000, 1, 1), updatedAt = 1))
        val data = charts(snapshot)
        val group = data.ages.groups.single()
        val hit = requireNotNull(data.ages.hit((data.ages.xFraction(group.key.toDouble()) * 300).toFloat(), 100f, 300f, 200f, 24f))
        assertEquals(DayimaChartSelectionKind.AGE, hit.kind)
        assertEquals(20, hit.group)
        assertEquals(3, hit.cycles(data).size)
    }

    @Test fun selectionReResolvesLatestFactsAfterEditingAndDeletingItsSource() {
        val original = snapshot(28, 28)
        val data = charts(original)
        val bin = data.histogram.single().selection()
        val point = DayimaChartSelection(DayimaChartSelectionKind.POINTS, cycleStartIds = listOf("p0"))
        val updated = original.copy(dataVersion = 2, periods = original.periods.map { if (it.id == "p1") it.copy(startDate = it.startDate.plusDays(1), revision = 2) else it })
        assertTrue(bin.cycles(charts(updated)).isEmpty())
        assertEquals(29L, point.cycles(charts(updated)).single().days)
        val deleted = updated.copy(dataVersion = 3, periods = updated.periods.filter { it.id != "p0" })
        assertTrue(point.cycles(charts(deleted)).isEmpty())
        assertEquals(2, bin.cycles(data).size)
    }

    @Test fun emptyAndSinglePointPlotsHaveFiniteBoundsAndIgnoreOutsideTaps() {
        val empty = charts(snapshot(listOf(start)))
        assertTrue(empty.histogram.isEmpty())
        assertNull(empty.time.hit(0f, 0f, 300f, 180f, 24f))
        val single = charts(snapshot(28)).time
        assertEquals(0.5, single.xFraction(single.points.single().x), 0.000001)
        assertTrue(single.minY < single.maxY)
        assertTrue(single.yFraction(28.0) in 0.0..1.0)
        assertNull(single.hit(-1f, 40f, 300f, 180f, 24f))
        assertNull(single.hit(40f, 40f, 0f, 180f, 24f))
    }

    private fun snapshot(vararg intervals: Long): DayimaRecordsSnapshot {
        var date = start
        return snapshot(listOf(start) + intervals.map { date = date.plusDays(it); date })
    }
    private fun snapshot(dates: List<LocalDate>) = DayimaRecordsSnapshot(1, null,
        dates.mapIndexed { index, date -> DayimaPeriodEntity("p$index", date, 1, 1) }, emptyList())
    private fun marker(snapshot: DayimaRecordsSnapshot, index: Int): DayimaSpanMarkerEntity {
        val a = snapshot.periods[index]; val b = snapshot.periods[index + 1]
        return DayimaSpanMarkerEntity("m$index", a.id, b.id, a.startDate, b.startDate, a.revision, b.revision, DayimaSpanReason.MISSING_RECORDS, createdAt = 1, updatedAt = 1)
    }
    private fun charts(snapshot: DayimaRecordsSnapshot, range: DayimaDateRange? = null): DayimaChartData {
        val today = snapshot.periods.maxOf { it.startDate }.plusDays(1)
        val all = DayimaStatisticsCalculator.calculate(snapshot, today)
        return DayimaChartData.calculate(if (range == null) all else DayimaStatisticsCalculator.calculate(snapshot, today, range), all.period.cycles)
    }
}
