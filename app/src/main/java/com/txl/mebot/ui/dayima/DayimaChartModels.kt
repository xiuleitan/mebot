package com.txl.mebot.ui.dayima

import com.txl.mebot.data.dayima.DayimaCycle
import com.txl.mebot.data.dayima.DayimaCycleStatus
import com.txl.mebot.data.dayima.DayimaDistribution
import com.txl.mebot.data.dayima.DayimaStatistics
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal enum class DayimaChartRange { ALL, LAST_YEAR, CUSTOM }
internal enum class DayimaTrendView { TIME, YEAR, AGE }
internal enum class DayimaChartSelectionKind { HISTOGRAM, POINTS, YEAR, AGE, ALL }

/** 只保留查询键，原始周期始终从最新版本解析，编辑／删除后不会复活旧数据。 */
internal data class DayimaChartSelection(
    val kind: DayimaChartSelectionKind,
    val fromDays: Long = 0,
    val throughDays: Long = 0,
    val group: Int = 0,
    val cycleStartIds: List<String> = emptyList(),
) {
    fun cycles(data: DayimaChartData): List<DayimaCycle> {
        val ids = cycleStartIds.toHashSet()
        val source = if (kind == DayimaChartSelectionKind.POINTS) data.sourceCycles else data.statistics.period.cycles
        return source.filter { cycle -> when (kind) {
            DayimaChartSelectionKind.HISTOGRAM -> cycle.status == DayimaCycleStatus.ELIGIBLE && cycle.days in fromDays..throughDays
            DayimaChartSelectionKind.POINTS -> cycle.startPeriodId in ids
            DayimaChartSelectionKind.YEAR -> cycle.startDate.year == group
            DayimaChartSelectionKind.AGE -> cycle.ageYears == group
            DayimaChartSelectionKind.ALL -> true
        } }
    }
}

internal data class DayimaHistogramBin(
    val fromDays: Long,
    val throughDays: Long,
    val cycleStartIds: List<String>,
    val gap: Boolean = false,
) {
    val count: Int get() = cycleStartIds.size
    fun selection() = DayimaChartSelection(DayimaChartSelectionKind.HISTOGRAM, fromDays, throughDays)
}

internal data class DayimaPlotPoint(
    val cycle: DayimaCycle,
    val x: Double,
    val rollingMedian: Double? = null,
    val rollingCycleIds: List<String> = emptyList(),
    val segmentId: String? = null,
)

internal data class DayimaPlotGroup(val key: Int, val distribution: DayimaDistribution?, val cycleStartIds: List<String>)
internal data class DayimaRollingLine(val first: DayimaPlotPoint, val second: DayimaPlotPoint)

/** 数据坐标独立于像素、字号和屏幕；渲染与点击共用同一组变换。 */
internal data class DayimaTrendPlot(
    val view: DayimaTrendView,
    val points: List<DayimaPlotPoint>,
    val groups: List<DayimaPlotGroup>,
    val rollingLines: List<DayimaRollingLine>,
    val minX: Double, val maxX: Double,
    val minY: Double, val maxY: Double,
    val xTicks: List<Double>,
) {
    fun xFraction(x: Double): Double = (x - minX) / (maxX - minX)
    fun yFraction(y: Double): Double = 1.0 - (y - minY) / (maxY - minY)

    fun hit(x: Float, y: Float, width: Float, height: Float, radius: Float): DayimaChartSelection? {
        if (points.isEmpty() || width <= 0 || height <= 0 || x !in 0f..width || y !in 0f..height) return null
        if (view != DayimaTrendView.TIME) {
            val group = groups.minByOrNull { kotlin.math.abs(xFraction(it.key.toDouble()) * width - x) } ?: return null
            if (kotlin.math.abs(xFraction(group.key.toDouble()) * width - x) > radius) return null
            return DayimaChartSelection(if (view == DayimaTrendView.YEAR) DayimaChartSelectionKind.YEAR else DayimaChartSelectionKind.AGE, group = group.key)
        }
        val ids = linkedSetOf<String>()
        fun near(point: DayimaPlotPoint, value: Double): Boolean {
            val dx = xFraction(point.x) * width - x
            val dy = yFraction(value) * height - y
            return dx * dx + dy * dy <= radius * radius
        }
        for (point in points) {
            if (near(point, point.cycle.days.toDouble())) ids += point.cycle.startPeriodId
            if (point.rollingMedian?.let { near(point, it) } == true) ids += point.rollingCycleIds
        }
        return ids.takeIf { it.isNotEmpty() }?.let { DayimaChartSelection(DayimaChartSelectionKind.POINTS, cycleStartIds = it.toList()) }
    }
}

internal data class DayimaChartData(
    val statistics: DayimaStatistics,
    val sourceCycles: List<DayimaCycle>,
    val histogram: List<DayimaHistogramBin>,
    val time: DayimaTrendPlot,
    val years: DayimaTrendPlot,
    val ages: DayimaTrendPlot,
) {
    fun plot(view: DayimaTrendView): DayimaTrendPlot = when (view) {
        DayimaTrendView.TIME -> time
        DayimaTrendView.YEAR -> years
        DayimaTrendView.AGE -> ages
    }

    companion object {
        fun calculate(statistics: DayimaStatistics, sourceCycles: List<DayimaCycle> = statistics.period.cycles): DayimaChartData {
            val frequencies = statistics.period.frequency
            fun bin(from: Long, through: Long = from) = DayimaHistogramBin(from, through,
                frequencies.filter { it.days in from..through }.flatMap { it.cycleStartIds })
            val histogram = buildList {
                frequencies.forEachIndexed { index, frequency ->
                    if (index > 0) {
                        val previous = frequencies[index - 1].days
                        val gap = frequency.days - previous - 1
                        if (gap in 1..8) for (day in previous + 1 until frequency.days) add(bin(day))
                        else if (gap > 8) add(DayimaHistogramBin(previous + 1, frequency.days - 1, emptyList(), gap = true))
                    }
                    add(DayimaHistogramBin(frequency.days, frequency.days, frequency.cycleStartIds))
                }
            }
            return DayimaChartData(statistics, sourceCycles, histogram,
                plot(statistics, DayimaTrendView.TIME), plot(statistics, DayimaTrendView.YEAR), plot(statistics, DayimaTrendView.AGE))
        }

        private fun plot(statistics: DayimaStatistics, view: DayimaTrendView): DayimaTrendPlot {
            val trends = statistics.period.trend.associateBy { it.cycleStartId }
            val cycles = statistics.period.cycles.filter { view != DayimaTrendView.AGE || it.ageYears != null }
            fun groupKey(cycle: DayimaCycle) = if (view == DayimaTrendView.AGE) requireNotNull(cycle.ageYears) else cycle.startDate.year
            val groups = if (view == DayimaTrendView.TIME) emptyList() else cycles.groupBy(::groupKey).toSortedMap().map { (key, rows) ->
                val distribution = if (view == DayimaTrendView.YEAR) statistics.period.years.find { it.year == key }?.distribution
                    else statistics.period.ages.find { it.ageYears == key }?.distribution
                DayimaPlotGroup(key, distribution, rows.map { it.startPeriodId })
            }
            val indices = mutableMapOf<Int, Int>()
            val points = cycles.map { cycle ->
                val trend = trends[cycle.startPeriodId]
                val x = if (view == DayimaTrendView.TIME) cycle.startDate.toEpochDay().toDouble() else {
                    val key = groupKey(cycle)
                    val index = indices.getOrDefault(key, 0)
                    indices[key] = index + 1
                    // 分组图每列为同一周岁／年份；小幅错开重叠的原始点，不表示小数年龄。
                    key + ((index % 7) - 3) * 0.035
                }
                DayimaPlotPoint(cycle, x, trend?.rollingMedianDays?.takeIf { view == DayimaTrendView.TIME },
                    trend?.rollingCycleStartIds.orEmpty(), trend?.segmentId)
            }
            val lines = if (view != DayimaTrendView.TIME) emptyList() else points.zipWithNext().mapNotNull { (a, b) ->
                if (a.rollingMedian != null && b.rollingMedian != null && a.segmentId != null && a.segmentId == b.segmentId)
                    DayimaRollingLine(a, b) else null
            }
            val values = points.flatMap { listOfNotNull(it.cycle.days.toDouble(), it.rollingMedian) }
            val low = values.minOrNull() ?: 0.0
            val high = values.maxOrNull() ?: 1.0
            val padding = max(2.0, (high - low) * 0.12)
            val minY = max(0.0, floor(low - padding))
            val maxY = ceil(high + padding)
            val first = if (view == DayimaTrendView.TIME) points.firstOrNull()?.x else groups.firstOrNull()?.key?.toDouble()
            val last = if (view == DayimaTrendView.TIME) points.lastOrNull()?.x else groups.lastOrNull()?.key?.toDouble()
            val minX = (first ?: 0.0) - if (view == DayimaTrendView.TIME) { if (first == last) 1.0 else 0.0 } else 0.5
            val maxX = (last ?: 1.0) + if (view == DayimaTrendView.TIME) { if (first == last) 1.0 else 0.0 } else 0.5
            val ticks = if (points.isEmpty()) emptyList() else if (view == DayimaTrendView.TIME) {
                (0..3).map { requireNotNull(first) + (requireNotNull(last) - first) * it / 3 }.map { kotlin.math.round(it) }.distinct()
            } else listOf(0, (groups.lastIndex / 3), (groups.lastIndex * 2 / 3), groups.lastIndex).distinct().map { groups[it].key.toDouble() }
            return DayimaTrendPlot(view, points, groups, lines, minX, maxX, minY, maxY, ticks)
        }
    }
}
