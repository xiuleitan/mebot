package com.txl.mebot.data.trading

import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingClosedPosition
import com.txl.mebot.data.local.TradingFill
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

internal data class TradingPositionReview(
    val fills: List<TradingFill>,
    val plan: TradingPositionPlan?,
)

internal data class ClosedPositionReplay(
    val positions: List<TradingClosedPosition>,
    val complete: Boolean,
)

private class PendingClosure(
    val closing: TradingFill,
    val side: String,
    val closingQuantity: String?,
) {
    var firstKnown = closing
    var realizedPnl: BigDecimal = BigDecimal.ZERO

    fun toClosedPosition(openingKnown: Boolean, openingQuantity: String? = null) = TradingClosedPosition(
        scope = closing.scope,
        positionSide = closing.positionSide,
        closingTradeId = closing.tradeId,
        side = side,
        firstTradeId = firstKnown.tradeId,
        firstTradeAt = firstKnown.time,
        openingKnown = openingKnown,
        closedAt = closing.time,
        openingQuantity = openingQuantity,
        closingQuantity = closingQuantity,
        realizedPnl = realizedPnl.stripTrailingZeros().toPlainString(),
        trendAtOpen = null,
        symbol = closing.symbol,
    )
}

// The caller supplies a complete, newest-first range ending at a checked position snapshot.
internal fun closedTradingPositions(
    newestFirst: List<TradingFill>,
    positions: List<TradingPosition>,
): ClosedPositionReplay {
    val balances = positions.associate { position ->
        val quantity = position.quantity.toBigDecimal()
        position.positionSide to if (position.side == "LONG") quantity else quantity.negate()
    }

    val sides = newestFirst.groupBy { it.positionSide }.map { (positionSide, fills) ->
        closedTradingPositionsForSide(fills, balances[positionSide] ?: BigDecimal.ZERO)
    }
    val closed = sides.filterNotNull().flatten()
        .sortedWith(compareByDescending<TradingClosedPosition> { it.closedAt }.thenByDescending { it.closingTradeId })
    return ClosedPositionReplay(closed, complete = sides.all { it != null })
}

private fun closedTradingPositionsForSide(
    fills: List<TradingFill>,
    finalBalance: BigDecimal,
): List<TradingClosedPosition>? {
    val closed = mutableListOf<TradingClosedPosition>()
    var after = finalBalance
    var pending: PendingClosure? = null

    for (fill in fills) {
        val quantity = fill.quantity.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val change = when (fill.side) {
            "BUY" -> quantity
            "SELL" -> quantity.negate()
            else -> return null
        }
        val before = after - change
        val previousSign = before.signum()
        val nextSign = after.signum()
        when (fill.positionSide) {
            "LONG" -> if (previousSign < 0 || nextSign < 0) return null
            "SHORT" -> if (previousSign > 0 || nextSign > 0) return null
            "BOTH" -> Unit
            else -> return null
        }

        val opens = nextSign != 0 && nextSign != previousSign
        val closes = previousSign != 0 && previousSign != nextSign
        val realizedPnl = fill.realizedPnl.toBigDecimalOrNull() ?: return null
        // An open or addition cannot realize P&L. A mismatch means the balance and fills disagree.
        if (!closes && nextSign == change.signum() && realizedPnl.signum() != 0) return null
        // A reversal closes the prior side and opens the next side in the same fill.
        if (opens) {
            pending?.let { episode ->
                episode.firstKnown = fill
                val openedQuantity = after.abs().toPlainString().takeIf { closes }
                closed.add(episode.toClosedPosition(openingKnown = true, openingQuantity = openedQuantity))
            }
            pending = null
        }
        if (closes) {
            pending = PendingClosure(
                closing = fill,
                side = if (previousSign > 0) "LONG" else "SHORT",
                closingQuantity = before.abs().toPlainString().takeIf { opens },
            )
        }
        pending?.let { episode ->
            episode.firstKnown = fill
            episode.realizedPnl += realizedPnl
        }
        after = before
    }

    pending?.let { closed.add(it.toClosedPosition(openingKnown = false)) }
    return closed
}

internal fun TradingClosedPosition.reviewFills(newestFirst: List<TradingFill>): List<TradingFill> =
    newestFirst.asReversed().mapNotNull { fill ->
        if (fill.time == firstTradeAt && fill.tradeId < firstTradeId ||
            fill.time == closedAt && fill.tradeId > closingTradeId
        ) return@mapNotNull null
        when {
            fill.tradeId == firstTradeId && openingQuantity != null ->
                fill.copy(quantity = openingQuantity, realizedPnl = "0")
            fill.tradeId == closingTradeId && closingQuantity != null ->
                fill.copy(quantity = closingQuantity)
            else -> fill
        }
    }

internal fun TradingInterval.shiftCandles(at: Long, steps: Int): Long {
    if (this == TradingInterval.MONTH1) {
        return Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC)
            .plusMonths(steps.toLong()).toInstant().toEpochMilli()
    }
    val period = when (chartSpan) {
        "minute" -> 60_000L
        "hour" -> 3_600_000L
        "day" -> 86_400_000L
        else -> 604_800_000L
    }
    return at + period * multiplier * steps
}

internal fun List<TradingCandle>.coversCandle(
    interval: TradingInterval,
    time: Long,
): Boolean {
    val candle = lastOrNull { it.time <= time } ?: return false
    return time < interval.shiftCandles(candle.time, 1)
}
