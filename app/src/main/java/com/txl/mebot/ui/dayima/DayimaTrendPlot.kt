package com.txl.mebot.ui.dayima

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaCycleStatus
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@Composable
internal fun DayimaTrendCanvas(plot: DayimaTrendPlot, onSelect: (DayimaChartSelection) -> Unit) {
    val colors = scheduleColors()
    val primary = MaterialTheme.colorScheme.primary
    val summary = MaterialTheme.colorScheme.tertiary
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.secondary)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val yValues = listOf(plot.maxY, (plot.minY + plot.maxY) / 2, plot.minY)
    val yLabels = yValues.map { textMeasurer.measure(AnnotatedString(it.dayimaNumber()), style) }
    val xLabels = plot.xTicks.map { value ->
        val label = if (plot.view == DayimaTrendView.TIME) LocalDate.ofEpochDay(value.toLong()).format(
            DateTimeFormatter.ofPattern(if (plot.maxX - plot.minX > 366) "yyyy/M" else "M/d", Locale.CHINA)) else value.toInt().toString()
        textMeasurer.measure(AnnotatedString(label), style)
    }
    val axisLabel = textMeasurer.measure(AnnotatedString(stringResource(when (plot.view) {
        DayimaTrendView.TIME -> R.string.dayima_chart_time_axis
        DayimaTrendView.YEAR -> R.string.dayima_chart_year_axis
        DayimaTrendView.AGE -> R.string.dayima_chart_age_axis
    })), style)
    val left = with(density) { (yLabels.maxOfOrNull { it.size.width } ?: 0) + 10.dp.toPx() }
    val top = with(density) { maxOf(8.dp.toPx(), (yLabels.maxOfOrNull { it.size.height } ?: 0) / 2f + 2.dp.toPx()) }
    val plotHeight = with(density) { maxOf(104.dp.toPx(), (yLabels.maxOfOrNull { it.size.height } ?: 0) * 4f + 16.dp.toPx()) }
    val tickGap = with(density) { maxOf(10.dp.toPx(), (yLabels.maxOfOrNull { it.size.height } ?: 0) / 2f + 4.dp.toPx()) }
    val footer = maxOf(xLabels.maxOfOrNull { it.size.height } ?: 0, axisLabel.size.height) + tickGap + with(density) { 4.dp.toPx() }
    val canvasHeight = with(density) { (top + plotHeight + footer).toDp() }
    val right = axisLabel.size.width + (xLabels.maxOfOrNull { it.size.width } ?: 0) / 2f + with(density) { 8.dp.toPx() }
    val radius = with(density) { 24.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val currentSelect = rememberUpdatedState(onSelect)
    val description = stringResource(R.string.dayima_chart_plot_description, stringResource(plot.view.label()), plot.points.size,
        plot.minY.dayimaNumber(), plot.maxY.dayimaNumber())
    val viewAction = stringResource(R.string.dayima_chart_view_cycles, plot.points.size)
    Canvas(Modifier.fillMaxWidth().height(canvasHeight).onSizeChanged { canvasSize = it }
        .semantics {
            contentDescription = description
            onClick(label = viewAction) {
                currentSelect.value(DayimaChartSelection(DayimaChartSelectionKind.POINTS, cycleStartIds = plot.points.map { it.cycle.startPeriodId }))
                true
            }
        }.pointerInput(plot, canvasSize, left, right, top, plotHeight, radius) {
            detectTapGestures { offset ->
                plot.hit(offset.x - left, offset.y - top, (canvasSize.width - left - right).coerceAtLeast(0f), plotHeight, radius)
                    ?.let(currentSelect.value)
            }
        }) {
        val width = (size.width - left - right).coerceAtLeast(0f)
        if (width <= 0) return@Canvas
        fun point(x: Double, y: Double) = Offset(left + (plot.xFraction(x) * width).toFloat(), top + (plot.yFraction(y) * plotHeight).toFloat())
        yValues.forEachIndexed { index, value ->
            val y = point(plot.minX, value).y
            drawLine(colors.divider.copy(alpha = 0.8f), Offset(left, y), Offset(left + width, y), 1.dp.toPx())
            drawText(yLabels[index], topLeft = Offset(left - yLabels[index].size.width - 8.dp.toPx(), y - yLabels[index].size.height / 2f))
        }
        // 四分位范围仅在该组样本达到门槛时存在，不跨空年份／年龄连接汇总线。
        plot.groups.forEach { group ->
            val distribution = group.distribution ?: return@forEach
            val lower = point(group.key.toDouble(), distribution.lowerQuartileDays)
            val upper = point(group.key.toDouble(), distribution.upperQuartileDays)
            val median = point(group.key.toDouble(), distribution.medianDays)
            drawRect(summary.copy(alpha = 0.16f), Offset(upper.x - 7.dp.toPx(), upper.y), Size(14.dp.toPx(), (lower.y - upper.y).coerceAtLeast(1.dp.toPx())))
            drawLine(summary, lower, upper, 2.dp.toPx())
            listOf(lower, upper).forEach { cap -> drawLine(summary, cap - Offset(6.dp.toPx(), 0f), cap + Offset(6.dp.toPx(), 0f), 2.dp.toPx()) }
            drawLine(summary, median - Offset(9.dp.toPx(), 0f), median + Offset(9.dp.toPx(), 0f), 3.dp.toPx(), cap = StrokeCap.Round)
        }
        plot.rollingLines.forEach { line ->
            drawLine(summary, point(line.first.x, requireNotNull(line.first.rollingMedian)),
                point(line.second.x, requireNotNull(line.second.rollingMedian)), 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        plot.points.forEach { value ->
            val position = point(value.x, value.cycle.days.toDouble())
            if (value.cycle.status == DayimaCycleStatus.ELIGIBLE) drawCircle(primary.copy(alpha = if (plot.view == DayimaTrendView.TIME) 0.95f else 0.7f), 3.5.dp.toPx(), position)
            else {
                val offset = Offset(3.5.dp.toPx(), 3.5.dp.toPx())
                drawLine(colors.secondary, position - offset, position + offset, 2.dp.toPx())
                drawLine(colors.secondary, position + Offset(-offset.x, offset.y), position + Offset(offset.x, -offset.y), 2.dp.toPx())
            }
            value.rollingMedian?.let { drawCircle(summary, 2.5.dp.toPx(), point(value.x, it)) }
        }
        val placed = mutableListOf<Pair<Float, Float>>()
        val axisStart = size.width - axisLabel.size.width
        val positions = plot.xTicks.mapIndexed { index, tick ->
            val labelWidth = xLabels[index].size.width.toFloat()
            (point(tick, plot.minY).x - labelWidth / 2).coerceIn(0f, max(0f, axisStart - 8.dp.toPx() - labelWidth))
        }
        // 先保证两端日期可读，余下刻度仅在不重叠时显示，字号遵循系统设置。
        val order = (listOf(0, xLabels.lastIndex) + xLabels.indices).distinct().filter { it in xLabels.indices }
        order.forEach { index ->
            val start = positions[index]
            val end = start + xLabels[index].size.width
            if (placed.none { (a, b) -> start < b + 6.dp.toPx() && end > a - 6.dp.toPx() }) {
                drawText(xLabels[index], topLeft = Offset(start, top + plotHeight + tickGap))
                placed += start to end
            }
        }
        drawText(axisLabel, topLeft = Offset(axisStart, top + plotHeight + tickGap))
    }
}
