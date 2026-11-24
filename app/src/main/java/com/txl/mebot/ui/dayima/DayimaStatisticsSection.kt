package com.txl.mebot.ui.dayima

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleColors
import java.util.Locale
import kotlin.math.max

@Composable
internal fun DayimaDistributionContent(state: DayimaChartsState, model: DayimaViewModel?, extraHeight: Dp = 0.dp) {
    val colors = scheduleColors()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.dayima_chart_distribution), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(stringResource(R.string.dayima_chart_frequency_axis), style = MaterialTheme.typography.labelSmall, color = colors.secondary)
    }
    val data = state.data
    when {
        state.failed -> DayimaLoadError { model?.charts?.rebuild() }
        state.loading || data == null -> DayimaLoading()
        data.statistics.period.eligibleCycleCount == 0 -> Text(stringResource(data.emptyChartLabel()), color = colors.secondary)
        else -> {
            DayimaHistogram(data.histogram, data.statistics.period.eligibleCycleCount,
                data.statistics.period.range, extraHeight)
        }
    }
}

@Composable
private fun DayimaHistogram(
    bins: List<DayimaHistogramBin>, total: Int, rangeKey: Any?,
    extraHeight: Dp,
) {
    val colors = scheduleColors()
    val textStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val list = rememberLazyListState()
    val maxCount = max(1, bins.maxOfOrNull { it.count } ?: 1)
    val numberSize = measurer.measure(AnnotatedString(maxCount.toString()), textStyle).size
    val numberHeight = with(density) { numberSize.height.toDp() }
    val dayLabelHeight = with(density) {
        measurer.measure(AnnotatedString(stringResource(R.string.dayima_chart_day_label, 30)), textStyle).size.height.toDp()
    }
    val ticks = listOf(maxCount.toDouble(), maxCount / 2.0, 0.0).map {
        measurer.measure(AnnotatedString(it.dayimaNumber()), textStyle.copy(color = colors.secondary))
    }
    val barHeight = maxOf(52.dp, numberHeight * 2 + 8.dp) + extraHeight.coerceAtLeast(0.dp)
    val labelHeight = numberHeight + 4.dp
    val bodyHeight = barHeight + labelHeight
    val footerHeight = maxOf(numberHeight, dayLabelHeight) + 6.dp
    val yWidth = with(density) { ticks.maxOf { it.size.width }.toDp() + 10.dp }
    LaunchedEffect(rangeKey) { list.scrollToItem(0) }
    Row(Modifier.fillMaxWidth()) {
        Canvas(Modifier.width(yWidth).height(bodyHeight + footerHeight)) {
            ticks.forEachIndexed { index, tick ->
                val y = labelHeight.toPx() + barHeight.toPx() * index / 2f
                drawText(tick, topLeft = Offset(size.width - 10.dp.toPx() - tick.size.width, y - tick.size.height / 2f))
            }
        }
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(bodyHeight)) {
                val top = labelHeight.toPx()
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    val y = top + barHeight.toPx() * fraction
                    drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
            LazyRow(state = list, modifier = Modifier.fillMaxWidth(),
                userScrollEnabled = list.canScrollForward || list.canScrollBackward) {
                itemsIndexed(bins, key = { _, bin -> "${bin.fromDays}:${bin.throughDays}:${bin.gap}" }) { _, bin ->
                    val label = if (bin.gap) "⋯" else stringResource(R.string.dayima_chart_day_label, bin.fromDays)
                    val spokenLabel = if (bin.fromDays == bin.throughDays) label
                        else stringResource(R.string.dayima_chart_days_range, bin.fromDays, bin.throughDays)
                    val description = stringResource(R.string.dayima_chart_bin_accessibility, spokenLabel, bin.count,
                        (bin.count.toDouble() / total).dayimaPercent())
                    val width = maxOf(48.dp, with(density) { measurer.measure(AnnotatedString(label), textStyle).size.width.toDp() + 16.dp })
                    val fraction by animateFloatAsState(bin.count.toFloat() / maxCount, tween(220), label = "dayima_histogram_bar")
                    Column(Modifier.width(width).height(bodyHeight + footerHeight)
                        .semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(bodyHeight), contentAlignment = Alignment.BottomCenter) {
                            if (!bin.gap) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(bin.count.toString(), color = colors.ink, style = textStyle, modifier = Modifier.padding(bottom = 4.dp))
                                Surface(Modifier.width(24.dp)
                                    .height(if (bin.count == 0) 0.dp else (barHeight * fraction).coerceAtLeast(2.dp)),
                                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                                    color = MaterialTheme.colorScheme.secondary) {}
                            }
                        }
                        Text(label, Modifier.padding(top = 6.dp), textAlign = TextAlign.Center, color = colors.secondary, style = textStyle)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DayimaAgeTrendContent(state: DayimaChartsState, model: DayimaViewModel?) {
    val colors = scheduleColors()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.dayima_chart_trend), color = colors.ink,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dayima_chart_length_axis), color = colors.secondary, style = MaterialTheme.typography.labelSmall)
    }
    val data = state.data
    when {
        state.failed -> DayimaLoadError { model?.charts?.rebuild() }
        state.loading || data == null -> DayimaLoading()
        !data.statistics.birthDateAvailable -> {
            Text(stringResource(R.string.dayima_chart_age_missing), color = colors.secondary)
            TextButton(onClick = { model?.beginBirth() }) { Text(stringResource(R.string.dayima_birth_add)) }
        }
        else -> {
            val plot = data.ages
            if (plot.points.isEmpty()) Text(stringResource(R.string.dayima_chart_age_no_points), color = colors.secondary)
            else DayimaTrendCanvas(plot) { model?.charts?.select(it) }
        }
    }
}

internal fun DayimaChartData.emptyChartLabel(): Int = when {
    sourceCycles.isEmpty() -> R.string.dayima_chart_no_completed
    statistics.period.completedCycleCount == 0 -> R.string.dayima_chart_no_range_cycles
    else -> R.string.dayima_chart_no_eligible
}

internal fun DayimaTrendView.label(): Int = when (this) {
    DayimaTrendView.TIME -> R.string.dayima_chart_time
    DayimaTrendView.YEAR -> R.string.dayima_chart_years
    DayimaTrendView.AGE -> R.string.dayima_chart_age
}
internal fun Double.dayimaPercent(): String = String.format(Locale.CHINA, "%.1f", this * 100)
