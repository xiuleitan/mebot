package com.txl.mebot.data.trading

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

internal const val TRADING_DAY_MS = 86_400_000L
private val ReturnPrecision = MathContext.DECIMAL128

internal fun tradingDay(time: Long): Long = Math.floorDiv(time, TRADING_DAY_MS)

internal data class TradingWalletTransfer(val time: Long, val amount: BigDecimal)

internal data class TradingDailyTransfers(
    val from: Long,
    val deposited: BigDecimal,
    val withdrawn: BigDecimal,
    val weightedNet: BigDecimal,
)

internal data class TradingPerformanceDay(
    val time: Long,
    val balance: BigDecimal,
    val transfers: TradingDailyTransfers? = null,
)

internal data class TradingPerformanceClose(val closedAt: Long, val realizedPnl: String)

internal data class TradingReturnPoint(val time: Long, val percent: BigDecimal)

internal data class TradingPerformance(
    val from: Long? = null,
    val through: Long? = null,
    val profit: BigDecimal? = null,
    val monthReturnPercent: BigDecimal? = null,
    val withdrawn: BigDecimal? = null,
    val winRatePercent: Double? = null,
    val maxDrawdownPercent: BigDecimal? = null,
    val closesPerWeek: Double? = null,
    val returns: List<TradingReturnPoint> = emptyList(),
)

internal fun dailyTransfers(from: Long, through: Long, transfers: List<TradingWalletTransfer>): TradingDailyTransfers {
    var deposited = BigDecimal.ZERO
    var withdrawn = BigDecimal.ZERO
    var weighted = BigDecimal.ZERO
    for ((time, amount) in transfers) {
        if (time <= from || time > through) continue
        if (amount.signum() > 0) deposited += amount else withdrawn -= amount
        weighted += amount * (through - time).toBigDecimal()
    }
    return TradingDailyTransfers(from, deposited, withdrawn, weighted.divide((through - from).toBigDecimal(), ReturnPrecision))
}

internal fun summarizeTradingPerformance(
    days: List<TradingPerformanceDay>,
    closes: List<TradingPerformanceClose>,
    today: Long,
): TradingPerformance {
    val completedCloses = closes.filter { tradingDay(it.closedAt) < today }
    val winRate = completedCloses.takeIf { it.isNotEmpty() }?.let { records ->
        records.count { it.realizedPnl.toBigDecimal().signum() > 0 } * 100.0 / records.size
    }
    val frequency = completedCloses.minOfOrNull { tradingDay(it.closedAt) }?.let { firstDay ->
        completedCloses.size * 7.0 / (today - firstDay).coerceAtLeast(1)
    }
    val base = TradingPerformance(winRatePercent = winRate, closesPerWeek = frequency)

    fun hasTransfers(index: Int): Boolean = days[index].transfers?.from == days[index - 1].time
    val end = (days.lastIndex downTo 1).firstOrNull(::hasTransfers) ?: return base
    var start = end - 1
    // Only link snapshots whose intervening transfers have all been verified.
    while (start > 0 && hasTransfers(start)) start--

    val first = days[start]
    val monthStart = LocalDate.ofEpochDay(today).withDayOfMonth(1).toEpochDay()
    var profit = BigDecimal.ZERO
    var withdrawn = BigDecimal.ZERO
    var growth: BigDecimal? = BigDecimal.ONE
    var monthGrowth = BigDecimal.ONE.takeIf {
        tradingDay(first.time) < monthStart && tradingDay(days[end].time) >= monthStart
    }
    var peak = BigDecimal.ONE
    var drawdown = BigDecimal.ZERO
    val returns = mutableListOf(TradingReturnPoint(first.time, BigDecimal.ZERO))
    for (index in start + 1..end) {
        val previous = days[index - 1]
        val current = days[index]
        val flow = checkNotNull(current.transfers)
        val netProfit = current.balance - previous.balance - flow.deposited + flow.withdrawn
        profit += netProfit
        withdrawn += flow.withdrawn

        // Modified Dietz weights a transfer by the fraction of the period it stayed in the wallet.
        val capital = previous.balance + flow.weightedNet
        val factor = when {
            netProfit.signum() == 0 -> BigDecimal.ONE
            capital.signum() <= 0 -> null
            else -> (BigDecimal.ONE + netProfit.divide(capital, ReturnPrecision)).takeIf { it.signum() >= 0 }
        }
        growth = growth?.let { accumulated -> factor?.let { accumulated.multiply(it, ReturnPrecision) } }
        growth?.let { value ->
            peak = maxOf(peak, value)
            drawdown = maxOf(drawdown, (peak - value).divide(peak, ReturnPrecision))
            returns += TradingReturnPoint(current.time, value.asReturnPercent())
        }
        if (tradingDay(current.time) >= monthStart) {
            monthGrowth = if (tradingDay(previous.time) < monthStart - 1) null
                else monthGrowth?.let { accumulated -> factor?.let { accumulated.multiply(it, ReturnPrecision) } }
        }
    }

    return base.copy(
        from = first.time,
        through = days[end].time,
        profit = profit,
        monthReturnPercent = monthGrowth?.asReturnPercent(),
        withdrawn = withdrawn,
        maxDrawdownPercent = drawdown.movePointRight(2).takeIf { growth != null },
        returns = returns,
    )
}

private fun BigDecimal.asReturnPercent() = (this - BigDecimal.ONE).movePointRight(2)
