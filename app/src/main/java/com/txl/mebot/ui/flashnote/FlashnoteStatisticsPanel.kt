package com.txl.mebot.ui.flashnote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.format.DateTimeFormatter
import kotlin.math.floor

@Composable
internal fun FlashnoteStatisticsPanel(
    state: FlashnoteStatisticsState, maxHeight: Dp,
    onMonth: (Int) -> Unit, onDay: (Int) -> Unit, onRetry: () -> Unit,
) {
    val colors = scheduleColors()
    Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight),
        color = androidx.compose.ui.graphics.Color.Transparent, contentColor = colors.ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val headerWidth = maxWidth
                Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = headerWidth),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ onMonth(-1) }, Modifier.size(48.dp), enabled = state.canGoPrevious && !state.loading) {
                            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.flashnote_statistics_previous_month), Modifier.size(16.dp).rotate(180f))
                        }
                        Text(state.month.format(MonthLabel), color = colors.ink, softWrap = false,
                            fontSize = 12.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                        IconButton({ onMonth(1) }, Modifier.size(48.dp), enabled = state.canGoNext && !state.loading) {
                            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.flashnote_statistics_next_month), Modifier.size(16.dp))
                        }
                    }
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                }
                state.failed -> Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) {
                    TextButton(onRetry) { Text(stringResource(R.string.flashnote_statistics_failed), color = colors.accent) }
                }
                else -> FlashnoteCompletionChart(state, onDay)
            }
        }
    }
}

@Composable
private fun FlashnoteCompletionChart(state: FlashnoteStatisticsState, onDay: (Int) -> Unit) {
    val colors = scheduleColors()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = LocalTextStyle.current.copy(color = colors.secondary, fontSize = 11.sp, lineHeight = 14.sp)
    val valueStyle = LocalTextStyle.current.copy(color = colors.ink, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
    val first = state.firstSelectableDay.coerceAtMost(state.lastSelectableDay)
    val last = state.lastSelectableDay
    val values = remember(state.month, state.days) {
        val byDay = state.days.associate { it.day to it.count }
        (1..state.month.lengthOfMonth()).map { byDay[state.month.atDay(it).toEpochDay()] ?: 0L }
    }
    val maximum = (values.maxOrNull() ?: 0).coerceAtLeast(1).toDouble()
    val fractions = values.mapIndexed { index, value ->
        key(state.month, index) {
            animateFloatAsState((value / maximum).toFloat(), tween(220), label = "flashnote_daily_completion_$index").value
        }
    }
    val valueTexts = values.mapIndexed { index, value ->
        if (value > 0 && index + 1 in first..last) measurer.measure(value.toString(), valueStyle) else null
    }
    val left = with(density) { 8.dp.toPx() }
    val right = left
    val bottom = measurer.measure("31", labelStyle).size.height + with(density) { 8.dp.toPx() }
    val plotHeight = with(density) { 124.dp.toPx() }
    val valueGap = with(density) { 3.dp.toPx() }
    var chartWidth by remember { mutableIntStateOf(0) }
    val step = (chartWidth - left - right).coerceAtLeast(1f) / values.size
    fun labelCandidates(alignAtTop: Boolean) = valueTexts.mapIndexed { index, text ->
        text?.takeIf { chartWidth > 0 }?.let {
            val x = (left + (index + 0.5f) * step - it.size.width / 2f)
                .coerceIn(0f, (chartWidth - it.size.width).coerceAtLeast(0).toFloat())
            val barTop = if (alignAtTop) 0f else values.indices.filter { day ->
                day + 1 in first..last && fractions[day] > 0f &&
                    x < left + (day + 0.8f) * step && x + it.size.width > left + (day + 0.2f) * step
            }.minOfOrNull { day -> (1f - fractions[day]) * plotHeight } ?: (1f - fractions[index]) * plotHeight
            val y = barTop - it.size.height - valueGap
            Rect(x, y, x + it.size.width, y + it.size.height)
        }
    }
    val labelPositions = placeFlashnoteValueLabels(labelCandidates(false), valueGap)
    val topPositions = placeFlashnoteValueLabels(labelCandidates(true), valueGap)
    val labelTop = (labelPositions + topPositions).filterNotNull().minOfOrNull { it.top } ?: 0f
    val topSpace = -labelTop.coerceAtMost(0f) + with(density) { 6.dp.toPx() }
    val chartHeight = with(density) { (plotHeight + topSpace + bottom).toDp().coerceAtLeast(ChartHeight) }
    val description = stringResource(R.string.flashnote_statistics_chart_description, state.month.format(MonthLabel), state.total)
    val selected = stringResource(R.string.flashnote_statistics_selected, state.selectedDay.format(DayLabel), state.selectedCount)
    fun selectAt(x: Float) {
        val width = (chartWidth - left - right).coerceAtLeast(1f)
        val day = (floor((x - left) / width * values.size).toInt() + 1).coerceIn(first, last)
        onDay(day)
    }
    val previousAction = stringResource(R.string.flashnote_statistics_previous_day)
    val nextAction = stringResource(R.string.flashnote_statistics_next_day)
    Canvas(Modifier.fillMaxWidth().height(chartHeight).onSizeChanged { chartWidth = it.width }
        .semantics {
            contentDescription = description
            stateDescription = selected
            progressBarRangeInfo = ProgressBarRangeInfo(state.selectedDay.dayOfMonth.toFloat(), first.toFloat()..last.toFloat(), (last - first - 1).coerceAtLeast(0))
            setProgress { onDay(it.toInt().coerceIn(first, last)); true }
            customActions = buildList {
                if (state.selectedDay.dayOfMonth > first) add(CustomAccessibilityAction(previousAction) { onDay(state.selectedDay.dayOfMonth - 1); true })
                if (state.selectedDay.dayOfMonth < last) add(CustomAccessibilityAction(nextAction) { onDay(state.selectedDay.dayOfMonth + 1); true })
            }
        }.onKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                onDay((state.selectedDay.dayOfMonth + if (it.key == Key.DirectionLeft) -1 else 1).coerceIn(first, last)); true
            } else false
        }.focusable()
        .pointerInput(state.month, chartWidth, first, last, left) { detectTapGestures { selectAt(it.x) } }
        .pointerInput(state.month, chartWidth, first, last, left) {
            detectHorizontalDragGestures(onDragStart = { selectAt(it.x) }) { change, _ -> change.consume(); selectAt(change.position.x) }
        }) {
        val width = (size.width - left - right).coerceAtLeast(1f)
        val height = plotHeight
        val top = size.height - bottom - height
        val step = width / values.size
        for (index in 0..2) {
            val y = top + height - index / 2f * height
            drawLine(colors.divider.copy(alpha = 0.6f), Offset(left, y), Offset(left + width, y), strokeWidth = 0.6.dp.toPx())
        }
        fractions.forEachIndexed { index, fraction ->
            if (index + 1 in first..last && fraction > 0f) {
                val h = fraction * height
                drawRoundRect(color = colors.accent.copy(alpha = if (index + 1 == state.selectedDay.dayOfMonth) 1f else 0.48f),
                    topLeft = Offset(left + step * (index + 0.2f), top + height - h), size = Size(step * 0.6f, h), cornerRadius = CornerRadius(2.dp.toPx()))
                val text = valueTexts[index]
                val position = labelPositions[index]
                if (text != null && position != null) drawText(text, topLeft = Offset(position.left, top + position.top))
            }
        }
        val labelDays = if (density.fontScale > 1.5f) listOf(1, state.month.lengthOfMonth()) else listOf(1, 10, 20, state.month.lengthOfMonth())
        labelDays.distinct().forEach { day ->
            val text = measurer.measure(day.toString(), labelStyle)
            val x = (left + (day - 0.5f) * step - text.size.width / 2).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
            drawText(text, topLeft = Offset(x, top + height + 5.dp.toPx()))
        }
    }
}

/** 保留每个柱顶数字，拥挤时只向上移动，避免遮住柱子或相邻数字。 */
internal fun placeFlashnoteValueLabels(candidates: List<Rect?>, gap: Float): List<Rect?> {
    val placed = mutableListOf<Rect?>()
    for (candidate in candidates) {
        if (candidate == null) {
            placed += null
            continue
        }
        var label: Rect = candidate
        while (true) {
            val current = label
            val collisions = placed.filterNotNull().filter { it.inflate(gap / 2).overlaps(current.inflate(gap / 2)) }
            if (collisions.isEmpty()) break
            val nextTop = collisions.minOf { it.top } - gap - current.height - 0.5f
            label = Rect(current.left, nextTop, current.right, nextTop + current.height)
        }
        placed += label
    }
    return placed
}

private val MonthLabel = DateTimeFormatter.ofPattern("yyyy年M月")
private val DayLabel = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val ChartHeight = 168.dp
