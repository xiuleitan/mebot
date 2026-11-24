package com.txl.mebot.ui.trading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.trading.FearGreedMarket
import com.txl.mebot.data.trading.FearGreedRating
import com.txl.mebot.data.trading.FearGreedReading
import com.txl.mebot.data.trading.FearGreedValue
import com.txl.mebot.ui.common.hapticAction
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val SentimentColors = listOf(TradingDown, Color(0xFFE8AE78), Color(0xFF92A3B9), Color(0xFF8BC6A6), TradingUp)
private val SentimentTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun TradingFearGreedRow(values: Map<FearGreedMarket, FearGreedValue>) {
    var selected by remember { mutableStateOf<FearGreedMarket?>(null) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FearGreedMarket.entries.forEach { market ->
            FearGreedBar(
                market = market,
                reading = values[market]?.reading,
                modifier = Modifier.weight(1f),
                onClick = { selected = market },
            )
        }
    }
    selected?.let { market ->
        FearGreedDetails(market, values[market] ?: FearGreedValue()) { selected = null }
    }
}

@Composable
private fun FearGreedBar(
    market: FearGreedMarket,
    reading: FearGreedReading?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = reading?.let { (it.score / 100).toFloat() }
    val gradient = remember { Brush.horizontalGradient(SentimentColors) }
    val tint = reading?.rating?.let { SentimentColors[it.ordinal] } ?: colors.onSurfaceVariant

    Column(
        modifier.clickable(role = Role.Button, onClick = hapticAction(onClick))
            .heightIn(min = 42.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(market.label),
                Modifier.padding(end = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Text(
                if (reading == null) "—" else "${reading.score.roundToInt()} · ${stringResource(reading.rating.label)}",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(7.dp)) {
            val radius = size.height / 2
            val trackHeight = 3.dp.toPx()
            val topLeft = Offset(0f, center.y - trackHeight / 2)
            val trackSize = Size(size.width, trackHeight)
            val corners = CornerRadius(trackHeight / 2)
            if (progress == null) {
                drawRoundRect(colors.outlineVariant, topLeft = topLeft, size = trackSize, cornerRadius = corners)
            } else {
                drawRoundRect(gradient, topLeft = topLeft, size = trackSize, cornerRadius = corners)
                val x = radius + (size.width - 2 * radius).coerceAtLeast(0f) * progress
                drawCircle(colors.background, radius = radius, center = Offset(x, center.y))
                drawCircle(colors.onSurface, radius = radius - 1.dp.toPx(), center = Offset(x, center.y))
            }
        }
    }
}

@Composable
private fun FearGreedDetails(market: FearGreedMarket, value: FearGreedValue, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trading_fear_greed_title, stringResource(market.label))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.trading_fear_greed_source, market.source))
                val time = value.reading?.dataTime
                if (time != null) {
                    val date = when (market) {
                        FearGreedMarket.CRYPTO -> SentimentTime.format(time.atZone(ZoneId.systemDefault()))
                        FearGreedMarket.US_STOCKS -> time.atOffset(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    val label = if (market == FearGreedMarket.US_STOCKS) {
                        R.string.trading_fear_greed_session_date
                    } else {
                        R.string.trading_fear_greed_data_time
                    }
                    Text(stringResource(label, date))
                }
                if (value.refreshFailed || time == null) {
                    val status = when {
                        !value.refreshFailed -> R.string.trading_fear_greed_loading
                        time == null -> R.string.trading_fear_greed_unavailable
                        else -> R.string.trading_fear_greed_cached
                    }
                    Text(stringResource(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trading_fear_greed_done)) } },
    )
}

private val FearGreedMarket.label: Int
    get() = when (this) {
        FearGreedMarket.CRYPTO -> R.string.trading_fear_greed_crypto
        FearGreedMarket.US_STOCKS -> R.string.trading_fear_greed_us
    }

private val FearGreedRating.label: Int
    get() = when (this) {
        FearGreedRating.EXTREME_FEAR -> R.string.trading_fear_greed_extreme_fear
        FearGreedRating.FEAR -> R.string.trading_fear_greed_fear
        FearGreedRating.NEUTRAL -> R.string.trading_fear_greed_neutral
        FearGreedRating.GREED -> R.string.trading_fear_greed_greed
        FearGreedRating.EXTREME_GREED -> R.string.trading_fear_greed_extreme_greed
    }
