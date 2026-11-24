package com.txl.mebot.data.trading

import com.txl.mebot.data.local.TradingFill
import java.math.BigDecimal
import java.math.RoundingMode

internal data class TradingPositionKey(val scope: String, val positionKey: String, val symbol: String = BTC_CONTRACT)

internal data class TradingPositionPlan(val stopLoss: String? = null, val takeProfit: String? = null)

internal data class TradingPositionDetails(
    val position: TradingPosition,
    val key: TradingPositionKey,
    val snapshotKey: TradingPositionKey,
    val openingFill: TradingFill?,
    val trendAtOpen: TradingTrendDirection?,
)

internal data class TradingPositionMetrics(
    val entryValue: BigDecimal,
    val floatingPnl: BigDecimal,
    val riskMultiple: BigDecimal?,
)

internal enum class PositionAlertChoice(val multiple: Int?) {
    R1(1), R2(2), R3(3), R4(4), STOP(null)
}

internal data class PositionAlertTarget(val price: String, val direction: String)

internal fun TradingPosition.alertTarget(stopLoss: BigDecimal?, choice: PositionAlertChoice): PositionAlertTarget? {
    val stop = stopLoss?.takeIf { it.signum() > 0 } ?: return null
    val isLong = side == "LONG"
    val multiple = choice.multiple
    if (multiple == null) {
        return PositionAlertTarget(stop.stripTrailingZeros().toPlainString(), if (isLong) "down" else "up")
    }
    val entry = entryPrice.toBigDecimalOrNull() ?: return null
    val risk = priceRisk(entry, stop) ?: return null
    val profit = risk * multiple.toBigDecimal()
    val target = if (isLong) entry + profit else entry - profit
    if (target.signum() <= 0) return null
    return PositionAlertTarget(target.stripTrailingZeros().toPlainString(), if (isLong) "up" else "down")
}

private fun priceRisk(entry: BigDecimal, stop: BigDecimal): BigDecimal? =
    (entry - stop).abs().takeIf { entry.signum() > 0 && it.signum() > 0 }

private val PlanPricePattern = Regex("""(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]+)?""")

internal fun parsePositionPlanPrice(input: String): BigDecimal? {
    val text = input.trim()
    if (text.length > 32 || !PlanPricePattern.matches(text)) return null
    return text.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
}

// Walk a complete slice of fills backwards from the exchange position.
internal fun openingPositionFill(position: TradingPosition, newestFirst: List<TradingFill>): TradingFill? {
    val direction = if (position.side == "LONG") 1 else -1
    var remaining = position.quantity.toBigDecimal()
    if (direction < 0) remaining = remaining.negate()
    for (fill in newestFirst) {
        if (fill.positionSide != position.positionSide || fill.time > position.updatedAt) continue
        val quantity = fill.quantity.toBigDecimal()
        val change = if (fill.side == "BUY") quantity else quantity.negate()
        val before = remaining - change
        if (before.signum() == 0) return fill
        if (before.signum() != direction) return fill.takeIf { position.positionSide == "BOTH" }
        remaining = before
    }
    return null
}

internal fun fillsForCurrentPositions(
    fills: List<TradingFill>,
    positions: List<TradingPositionDetails>,
): List<TradingFill> {
    if (positions.none { it.openingFill != null }) return emptyList()
    return fills.filter { fill ->
        positions.any { details ->
            val opening = details.openingFill ?: return@any false
            fill.scope == opening.scope &&
                fill.symbol == opening.symbol &&
                fill.positionSide == details.position.positionSide &&
                fill.time <= details.position.updatedAt &&
                (fill.time > opening.time || fill.time == opening.time && fill.tradeId >= opening.tradeId)
        }
    }
}

internal fun tradingPositionMetrics(
    position: TradingPosition,
    plan: TradingPositionPlan,
    liveMarkPrice: BigDecimal?,
): TradingPositionMetrics {
    val quantity = position.quantity.toBigDecimal()
    val entry = position.entryPrice.toBigDecimal()
    val entryValue = quantity * entry
    val floatingPnl = if (liveMarkPrice != null && entry.signum() > 0) {
        val difference = liveMarkPrice - entry
        quantity * if (position.side == "LONG") difference else difference.negate()
    } else {
        position.unrealizedPnl.toBigDecimal()
    }
    val risk = plan.stopLoss?.toBigDecimal()?.let { priceRisk(entry, it) }?.times(quantity)
    val multiple = risk?.takeIf { it.signum() > 0 }
        ?.let { floatingPnl.divide(it, 2, RoundingMode.HALF_UP) }
    return TradingPositionMetrics(entryValue, floatingPnl, multiple)
}
