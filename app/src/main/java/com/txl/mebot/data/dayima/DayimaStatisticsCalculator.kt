package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaSpanReason
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** 纯本地计算，不写入业务数据，不自动补造缺失记录或删除较长间隔。 */
object DayimaStatisticsCalculator {
    private const val RecentWindow = 6
    private const val GroupMinimum = 3

    fun calculate(snapshot: DayimaRecordsSnapshot, asOfDate: LocalDate, range: DayimaDateRange? = null): DayimaStatistics {
        require(snapshot.periods.map { it.id }.distinct().size == snapshot.periods.size) { "开始记录标识重复" }
        require(snapshot.periods.map { it.startDate }.distinct().size == snapshot.periods.size) { "开始日期重复" }
        val periods = snapshot.periods.filter { it.startDate <= asOfDate }.sortedBy { it.startDate }
        val birth = snapshot.profile?.birthDate?.takeIf { it <= asOfDate }
        val reviews = reviewDayimaSpans(snapshot)
        val markersByPair = reviews.filter { it.marker.startPeriodId != null && it.marker.endPeriodId != null }
            .associateBy { it.marker.startPeriodId to it.marker.endPeriodId }
        val cycles = periods.zipWithNext { start, end ->
            val marker = markersByPair[start.id to end.id]
            val status = when {
                marker == null -> DayimaCycleStatus.ELIGIBLE
                marker.needsReview -> DayimaCycleStatus.PENDING_REVIEW
                marker.marker.reason == DayimaSpanReason.MISSING_RECORDS -> DayimaCycleStatus.MISSING_RECORDS
                else -> DayimaCycleStatus.SPECIAL_PHASE
            }
            DayimaCycle(start.id, end.id, start.startDate, end.startDate, start.revision, end.revision,
                ChronoUnit.DAYS.between(start.startDate, end.startDate), age(birth, start.startDate), status, marker?.marker?.id)
        }
        val allTrend = trend(cycles)
        val selected = cycles.filter { range == null || it.startDate in range }
        val eligible = selected.filter { it.status == DayimaCycleStatus.ELIGIBLE }
        val distribution = distribution(eligible)
        val frequency = eligible.groupBy { it.days }.toSortedMap().map { (days, rows) ->
            DayimaCycleFrequency(days, rows.size, rows.size.toDouble() / eligible.size, rows.map { it.startPeriodId })
        }
        val years = eligible.groupBy { it.startDate.year }.toSortedMap().map { (year, rows) ->
            DayimaYearStatistics(year, rows.map { it.startPeriodId }, distribution(rows).takeIf { rows.size >= GroupMinimum })
        }
        val ages = eligible.filter { it.ageYears != null }.groupBy { requireNotNull(it.ageYears) }.toSortedMap().map { (age, rows) ->
            DayimaAgeStatistics(age, rows.map { it.startPeriodId }, distribution(rows).takeIf { rows.size >= GroupMinimum })
        }
        val periodById = snapshot.periods.associateBy { it.id }
        val pendingMarkers = reviews.count { review ->
            val start = periodById[review.marker.startPeriodId]?.startDate ?: review.marker.startDate
            review.needsReview && start <= asOfDate && (range == null || start in range)
        }
        val ageEligible = eligible.filter { it.ageYears != null }
        val historyDays = if (ageEligible.isEmpty()) 0 else ChronoUnit.DAYS.between(ageEligible.first().startDate, ageEligible.last().endDate)
        val summary = DayimaPeriodStatistics(
            range, selected, eligible.size,
            selected.count { it.status == DayimaCycleStatus.MISSING_RECORDS || it.status == DayimaCycleStatus.SPECIAL_PHASE },
            selected.count { it.status == DayimaCycleStatus.PENDING_REVIEW }, pendingMarkers,
            eligible.count { it.ageYears == null }, distribution, frequency,
            compareWith(eligible, 28.0), compareWith(eligible, distribution?.medianDays),
            allTrend.filter { range == null || it.date in range }, years, ages,
            historyDays >= 365 && ages.count { it.distribution != null } >= 2,
        )
        val current = periods.lastOrNull()?.let { DayimaCurrentCycle(it.id, it.startDate, ChronoUnit.DAYS.between(it.startDate, asOfDate)) }
        return DayimaStatistics(snapshot.dataVersion, asOfDate, birth != null,
            snapshot.periods.size - periods.size, summary, current, prediction(periods, cycles, asOfDate))
    }

    fun compare(snapshot: DayimaRecordsSnapshot, asOfDate: LocalDate, first: DayimaDateRange, second: DayimaDateRange): DayimaPeriodComparison {
        val firstStats = calculate(snapshot, asOfDate, first).period
        val secondStats = calculate(snapshot, asOfDate, second).period
        val difference = if (firstStats.eligibleCycleCount >= GroupMinimum && secondStats.eligibleCycleCount >= GroupMinimum) {
            requireNotNull(secondStats.distribution).medianDays - requireNotNull(firstStats.distribution).medianDays
        } else null
        return DayimaPeriodComparison(snapshot.dataVersion, asOfDate, firstStats, secondStats, difference)
    }

    private fun age(birth: LocalDate?, date: LocalDate): Int? = birth?.takeIf { it <= date }?.let { Period.between(it, date).years }

    private fun distribution(cycles: List<DayimaCycle>): DayimaDistribution? {
        if (cycles.isEmpty()) return null
        val values = cycles.map { it.days }.sorted()
        return DayimaDistribution(values.size, quantile(values, 0.5), quantile(values, 0.25), quantile(values, 0.75), values.first(), values.last())
    }

    private fun quantile(sorted: List<Long>, probability: Double): Double {
        val position = (sorted.size - 1) * probability
        val left = floor(position).toInt()
        val right = ceil(position).toInt()
        return sorted[left].toDouble() + (sorted[right] - sorted[left]).toDouble() * (position - left)
    }

    private fun compareWith(cycles: List<DayimaCycle>, baseline: Double?): DayimaBaselineComparison {
        if (baseline == null) return DayimaBaselineComparison(null, 0, 0, 0)
        return DayimaBaselineComparison(baseline, cycles.count { it.days.toDouble() < baseline },
            cycles.count { it.days.toDouble() == baseline }, cycles.count { it.days.toDouble() > baseline })
    }

    private fun trend(cycles: List<DayimaCycle>): List<DayimaTrendPoint> {
        val window = ArrayDeque<DayimaCycle>()
        var segmentId: String? = null
        return cycles.map { cycle ->
            if (cycle.status != DayimaCycleStatus.ELIGIBLE) {
                window.clear()
                segmentId = null
            } else {
                if (segmentId == null) segmentId = cycle.startPeriodId
                window.addLast(cycle)
                if (window.size > RecentWindow) window.removeFirst()
            }
            val full = window.size == RecentWindow
            DayimaTrendPoint(cycle.startPeriodId, cycle.startDate, cycle.days, cycle.ageYears, cycle.status,
                segmentId, if (full) quantile(window.map { it.days }.sorted(), 0.5) else null,
                if (full) window.map { it.startPeriodId } else emptyList())
        }
    }

    private fun prediction(periods: List<DayimaPeriodEntity>, cycles: List<DayimaCycle>, today: LocalDate): DayimaPredictionResult {
        val latest = periods.lastOrNull() ?: return DayimaPredictionResult(DayimaPredictionStatus.NO_RECORDS)
        val recent = cycles.asReversed().asSequence().takeWhile { it.status == DayimaCycleStatus.ELIGIBLE }
            .take(RecentWindow).toList().asReversed()
        if (recent.isEmpty()) return DayimaPredictionResult(DayimaPredictionStatus.INSUFFICIENT_CONTINUOUS_HISTORY)
        val totalWeight = recent.indices.sumOf { it + 1 }
        val average = recent.mapIndexed { index, cycle -> cycle.days.toDouble() * (index + 1) }.sum() / totalWeight
        val weightedDays = floor(average + 0.5).toLong()
        val variation = if (recent.size == 1) 2 else ceil(recent.maxOf { abs(it.days - average) }).toInt().coerceIn(2, 7)
        return try {
            val predicted = latest.startDate.plusDays(weightedDays)
            val start = predicted.minusDays(variation.toLong())
            val end = predicted.plusDays(variation.toLong())
            DayimaPredictionResult(DayimaPredictionStatus.AVAILABLE, DayimaPrediction(latest.id, latest.startDate,
                predicted, start, end, weightedDays, variation, recent.map { it.days }, recent.map { it.startPeriodId }, today > end))
        } catch (_: DateTimeException) {
            DayimaPredictionResult(DayimaPredictionStatus.DATE_OUT_OF_RANGE)
        }
    }
}

/** 复用在记录管理与统计中，避免两处对同一跨度给出不同有效性判断。 */
internal fun reviewDayimaSpans(snapshot: DayimaRecordsSnapshot): List<DayimaSpanMarkerState> {
    val byId = snapshot.periods.sortedBy { it.startDate }.withIndex().associate { it.value.id to it }
    return snapshot.spanMarkers.map { marker ->
        val start = byId[marker.startPeriodId]
        val end = byId[marker.endPeriodId]
        DayimaSpanMarkerState(marker, marker.needsReview || start == null || end == null || end.index != start.index + 1 ||
            start.value.startDate != marker.startDate || end.value.startDate != marker.endDate ||
            start.value.revision != marker.startPeriodRevision || end.value.revision != marker.endPeriodRevision)
    }
}
