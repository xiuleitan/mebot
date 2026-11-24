package com.txl.mebot.ui.trading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.trading.TradingPerformance
import com.txl.mebot.data.trading.TradingProblem
import com.txl.mebot.data.trading.TradingReturnPoint
import com.txl.mebot.ui.common.hapticAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val SettlementDate = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneOffset.UTC)

@Composable
internal fun TradingPerformanceDialog(observe: () -> Flow<TradingPerformance?>, onDismiss: () -> Unit) {
    val result by remember {
        observe().map { Result.success(it) }.catch { emit(Result.failure(it)) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val title = stringResource(R.string.trading_performance_title)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = TradingChartBackground,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 2.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = fonts.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = hapticAction(onDismiss)) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(15.dp))
                    }
                }
                val loaded = result
                val report = loaded?.getOrNull()
                if (report != null) {
                    TradingPerformanceOverview(report)
                } else {
                    val message = when {
                        loaded == null -> stringResource(R.string.trading_performance_loading)
                        loaded.isFailure -> TradingProblem.STORAGE.message
                        else -> TradingProblem.ACCOUNT_REQUIRED.message
                    }
                    Text(message, Modifier.padding(bottom = 4.dp), style = fonts.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TradingPerformanceOverview(report: TradingPerformance) {
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val metrics = listOf(
        Triple(R.string.trading_performance_profit, report.profit?.let { formatPrice(it.toPlainString()) + " USDT" }, pnlColor(report.profit)),
        Triple(R.string.trading_performance_month, report.monthReturnPercent?.toDouble()?.let(::performancePercent), pnlColor(report.monthReturnPercent)),
        Triple(R.string.trading_performance_withdrawn, report.withdrawn?.let { formatPrice(it.toPlainString()) + " USDT" }, colors.onSurface),
        Triple(R.string.trading_performance_win_rate, report.winRatePercent?.let(::performancePercent), colors.onSurface),
        Triple(R.string.trading_performance_drawdown, report.maxDrawdownPercent?.toDouble()?.let(::performancePercent), colors.onSurface),
        Triple(R.string.trading_performance_frequency, report.closesPerWeek?.let {
            stringResource(R.string.trading_performance_per_week, formatPrice(it, 1))
        }, colors.onSurface),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (report.from != null && report.through != null) {
            Text(
                stringResource(R.string.trading_performance_coverage, settlementDate(report.from), settlementDate(report.through)),
                style = fonts.labelSmall, color = colors.onSurfaceVariant,
            )
        }
        for (row in metrics.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value, color) in row) {
                    Column(Modifier.weight(1f)) {
                        Text(value ?: "—", style = fonts.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = color, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(label), style = fonts.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Text(stringResource(R.string.trading_performance_sources), style = fonts.labelSmall, color = colors.onSurfaceVariant)
        if (report.returns.size > 1) {
            TradingReturnCurve(report.returns)
        } else {
            val hint = if (report.through == null) R.string.trading_performance_pending else R.string.trading_performance_no_return
            Text(stringResource(hint), style = fonts.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TradingReturnCurve(points: List<TradingReturnPoint>) {
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisStyle = fonts.labelSmall.copy(color = colors.onSurfaceVariant, fontFeatureSettings = "tnum")
    val values = remember(points) { points.map { it.percent.toDouble() } }
    val low = values.min()
    val high = values.max()
    val inset = if (low == high) max(abs(high) * 0.01, 0.03) else max((high - low) * 0.1, 0.015)
    val ceiling = high + inset
    val range = ceiling - (low - inset)
    val axisNumbers = (0..3).map { index -> measurer.measure(performancePercent(ceiling - range * index / 3), axisStyle) }
    val footers = listOf(points.first(), points.last()).distinctBy { it.time }.map {
        measurer.measure(settlementDate(it.time), axisStyle)
    }
    val axisWidth = with(density) { axisNumbers.maxOf { it.size.width } + 8.dp.toPx() }
    val left = with(density) { 3.dp.toPx() }
    val top = axisNumbers.first().size.height / 2f + left
    val fractions = remember(points) {
        val from = points.first().time
        val duration = points.last().time - from
        points.map { if (duration == 0L) 0.5f else ((it.time - from).toDouble() / duration).toFloat() }
    }
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val point = points[selected]
    val shownDate = settlementDate(point.time)
    val shownValue = performancePercent(point.percent.toDouble())
    val lineColor = colors.primary
    fun selectAt(x: Float, width: Int) {
        val location = (x - left) / (width - left - axisWidth).coerceAtLeast(1f)
        selected = fractions.indices.minBy { abs(fractions[it] - location) }
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.trading_performance_curve), style = fonts.labelLarge, fontWeight = FontWeight.SemiBold)
        Text("$shownDate  $shownValue", style = fonts.labelMedium, color = colors.primary)
    }
    Canvas(Modifier.fillMaxWidth().height(154.dp)
        .semantics { contentDescription = "$shownDate  $shownValue" }
        .pointerInput(points, axisWidth, left) { detectTapGestures { selectAt(it.x, size.width) } }
        .pointerInput(points, axisWidth, left) {
            detectHorizontalDragGestures(
                onDragStart = { selectAt(it.x, size.width) },
                onHorizontalDrag = { change, _ -> selectAt(change.position.x, size.width) },
            )
        },
    ) {
        val right = (size.width - axisWidth).coerceAtLeast(left + 1f)
        val bottom = (size.height - footers.first().size.height - 7.dp.toPx()).coerceAtLeast(top + 1f)
        axisNumbers.forEachIndexed { index, label ->
            val y = top + (bottom - top) * index / 3
            drawLine(colors.outlineVariant, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            drawText(label, topLeft = Offset(right + 8.dp.toPx(), y - label.size.height / 2f))
        }
        footers.forEachIndexed { index, text ->
            val x = when {
                footers.size == 1 -> (left + right - text.size.width) / 2
                index == 0 -> left
                else -> right - text.size.width
            }
            drawText(text, topLeft = Offset(x.coerceAtLeast(left), bottom + 6.dp.toPx()))
        }
        val coordinates = values.mapIndexed { index, value ->
            Offset(left + (right - left) * fractions[index], top + ((ceiling - value) / range).toFloat() * (bottom - top))
        }
        val cursor = coordinates[selected]
        if (coordinates.size > 1) {
            val curve = Path().apply {
                moveTo(coordinates.first().x, coordinates.first().y)
                coordinates.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val area = Path().apply {
                addPath(curve)
                lineTo(coordinates.last().x, bottom)
                lineTo(coordinates.first().x, bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.20f), lineColor.copy(alpha = 0.02f)), top, bottom))
            drawPath(curve, lineColor, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(lineColor, radius = 3.dp.toPx(), center = cursor)
    }
}

private fun performancePercent(value: Double) = "${formatPrice(value)}%"
private fun settlementDate(time: Long) = SettlementDate.format(Instant.ofEpochMilli(time))
