package com.txl.mebot.ui.trading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.data.trading.TradingInterval
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class TradingCandleDetails(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val onLeft: Boolean,
)

@Composable
internal fun BoxScope.TradingCandleDetailsLayer(
    state: State<TradingCandleDetails?>,
    interval: TradingInterval,
    priceDecimals: Int,
) {
    val details = state.value ?: return
    val scale = LocalDensity.current.fontScale
    val rightInset = 104.dp * scale.coerceAtLeast(1f)
    val textStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 9.sp, lineHeight = 13.sp, fontFeatureSettings = "tnum",
    )
    val time = remember(details.time, interval) { candleDetailsTime(details.time, interval) }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val onLeft = details.onLeft || maxWidth - rightInset < 120.dp * scale
        Surface(
            modifier = Modifier
                .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                .padding(start = 7.dp, top = 6.dp + 14.dp * scale, end = if (onLeft) 7.dp else rightInset)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            color = TradingChartBackground,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(3.dp),
        ) {
            Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(time, Modifier.align(Alignment.End), style = textStyle)
                for ((label, price) in listOf("开" to details.open, "高" to details.high, "低" to details.low, "收" to details.close)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, Modifier.padding(end = 9.dp), style = textStyle)
                        Text(formatPrice(price, priceDecimals), style = textStyle)
                    }
                }
            }
        }
    }
}

private fun candleDetailsTime(time: Long, interval: TradingInterval): String {
    val pattern = when (interval) {
        TradingInterval.MONTH1 -> "yyyy-MM"
        TradingInterval.D1, TradingInterval.D3, TradingInterval.W1 -> "yyyy-MM-dd"
        else -> "yyyy-MM-dd HH:mm"
    }
    return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
}
