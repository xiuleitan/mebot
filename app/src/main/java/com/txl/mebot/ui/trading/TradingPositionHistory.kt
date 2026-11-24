package com.txl.mebot.ui.trading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.txl.mebot.R
import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingClosedPosition
import com.txl.mebot.data.local.TradingFill
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingHistorySummary
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.TradingPositionReview
import com.txl.mebot.data.trading.TradingTrendDirection
import com.txl.mebot.data.trading.coversCandle
import com.txl.mebot.data.trading.tradingProblem
import com.txl.mebot.ui.common.hapticAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val HistoryTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun TradingPositionHistoryHeading(summary: TradingHistorySummary?) {
    val typography = MaterialTheme.typography
    val netPnl = summary?.netPnl
    val winRate = summary?.winRatePercent?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"
    val valueStyle = typography.labelLarge.copy(fontFeatureSettings = "tnum")
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.trading_history_title),
            modifier = Modifier.semantics { heading() },
            style = typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(
                R.string.trading_history_net_pnl,
                netPnl?.let { formatTradingPnl(it, showPositiveSign = true) } ?: "—",
            ),
            style = valueStyle,
            color = pnlColor(netPnl),
        )
        Text(
            stringResource(R.string.trading_history_win_rate, winRate),
            style = valueStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TradingClosedPositionCard(position: TradingClosedPosition, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val pnl = position.realizedPnl.toBigDecimal()
    val trend = TradingTrendDirection.entries.firstOrNull { it.name == position.trendAtOpen }

    Surface(
        onClick = hapticAction(onClick),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF23272F),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(position.sideLabel),
                        style = typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (position.side == "LONG") TradingUp else TradingDown,
                    )
                    Text(
                        stringResource(R.string.trading_history_trend, trend?.let { stringResource(it.label) }
                            ?: stringResource(R.string.trading_position_unrecorded)),
                        style = typography.bodySmall,
                        color = trend?.accentColor() ?: colors.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(
                        R.string.trading_history_realized_pnl,
                        formatTradingPnl(pnl, showPositiveSign = true),
                    ),
                    modifier = Modifier.widthIn(max = 150.dp),
                    style = typography.labelLarge,
                    color = pnlColor(pnl),
                    textAlign = TextAlign.End,
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    if (position.openingKnown) stringResource(R.string.trading_history_opened, historyTime(position.firstTradeAt))
                    else stringResource(R.string.trading_history_incomplete),
                    Modifier.padding(end = 6.dp),
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.trading_history_closed, historyTime(position.closedAt)),
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TradingPositionReviewDialog(
    contract: TradingContract,
    position: TradingClosedPosition,
    interval: TradingInterval,
    model: TradingViewModel,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val scope = rememberCoroutineScope()
    val fillScroll = rememberLazyListState()
    var selected by remember(position) { mutableStateOf(position.closingTradeId) }
    val review by produceState<Result<TradingPositionReview>?>(null, position) {
        value = loadReview { model.positionReview(position) }
    }
    val fills = review?.getOrNull()?.fills.orEmpty()
    val focus = fills.firstOrNull { it.tradeId == selected }?.time ?: position.closedAt
    var retry by remember { mutableStateOf(0) }
    val candles by produceState<Result<List<TradingCandle>>?>(null, contract, interval, focus, retry) {
        if (value?.getOrNull()?.coversCandle(interval, focus) != true) {
            value = null
            value = loadReview { model.candlesForReview(contract, interval, focus) }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(0.94f).fillMaxHeight(0.88f),
            shape = RoundedCornerShape(10.dp),
            color = TradingBackground,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.trading_history_review_title, contract.symbol, stringResource(position.sideLabel), interval.label),
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = hapticAction(onDismiss)) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(20.dp))
                    }
                }
                Text(
                    historyTime(focus),
                    Modifier.padding(start = 12.dp, bottom = 6.dp),
                    style = typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                HorizontalDivider(color = colors.outlineVariant)
                Surface(Modifier.fillMaxWidth().weight(1f), color = TradingChartBackground) {
                    val bars = candles?.getOrNull()
                    if (bars != null) {
                        TradingChart(
                            contract = contract,
                            interval = interval,
                            bars = bars,
                            tool = null,
                            fills = fills,
                            onFill = { ids ->
                                val index = fills.indexOfFirst { it.tradeId in ids }
                                if (index >= 0) {
                                    selected = fills[index].tradeId
                                    scope.launch { fillScroll.animateScrollToItem(index) }
                                }
                            },
                            onLine = { _, _ -> },
                            onOlder = { symbol, period, before -> model.older(symbol, period, before) },
                            modifier = Modifier.fillMaxSize(),
                            focusTime = focus,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    (candles?.exceptionOrNull() as? Exception)?.tradingProblem()?.message
                                        ?: stringResource(R.string.trading_history_chart_loading),
                                    style = typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                                if (candles?.isFailure == true) {
                                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Text(
                    stringResource(R.string.trading_history_fills),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val plan = review?.getOrNull()?.plan
                val planPrices = listOfNotNull(
                    plan?.stopLoss?.let { stringResource(R.string.trading_history_stop_loss, formatPrice(it, contract.priceDecimals)) },
                    plan?.takeProfit?.let { stringResource(R.string.trading_history_take_profit, formatPrice(it, contract.priceDecimals)) },
                )
                if (planPrices.isNotEmpty()) {
                    Text(
                        planPrices.joinToString(" · "),
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                        style = typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = fillScroll,
                ) {
                    items(fills, key = { it.tradeId }) { fill ->
                        TradingReviewFillRow(contract, position, fill, fill.tradeId == selected) { selected = fill.tradeId }
                    }
                    if (fills.isEmpty()) item {
                        Text(
                            stringResource(when {
                                review == null -> R.string.trading_history_detail_loading
                                review?.isFailure == true -> R.string.trading_history_detail_failed
                                else -> R.string.trading_history_detail_empty
                            }),
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TradingReviewFillRow(
    contract: TradingContract,
    position: TradingClosedPosition,
    fill: TradingFill,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val action = when {
        fill.tradeId == position.closingTradeId -> R.string.trading_history_fill_close
        fill.tradeId == position.firstTradeId && position.openingKnown -> R.string.trading_history_fill_open
        (fill.side == "BUY") == (position.side == "LONG") -> R.string.trading_history_fill_add
        else -> R.string.trading_history_fill_reduce
    }
    Surface(
        onClick = hapticAction(onClick),
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = colors.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(action),
                    style = typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (fill.side == "BUY") TradingUp else TradingDown,
                )
                Text(formatTradeTime(fill.time), style = typography.bodySmall, color = colors.onSurfaceVariant)
                fill.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = typography.bodySmall) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(formatPrice(fill.price, contract.priceDecimals), style = typography.labelLarge)
                Text(fill.quantity.trimDecimal() + " " + contract.asset, style = typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

private val TradingClosedPosition.sideLabel: Int
    get() = if (side == "LONG") R.string.trading_position_long else R.string.trading_position_short

private fun historyTime(time: Long): String =
    HistoryTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

private suspend fun <T> loadReview(read: suspend () -> T): Result<T> = try {
    Result.success(read())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
