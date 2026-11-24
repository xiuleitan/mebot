package com.txl.mebot.data.trading

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TradingPerformanceTest {
    @Test
    fun walletTransfersAreNeutralToReturnAndWithdrawalsAreCountedGross() {
        val first = Instant.parse("2026-10-31T23:59:59Z").toEpochMilli()
        val second = first + TRADING_DAY_MS
        val third = second + TRADING_DAY_MS
        val transfers = listOf(
            TradingWalletTransfer(first, "1000".toBigDecimal()),
            TradingWalletTransfer(first + TRADING_DAY_MS / 2, "500".toBigDecimal()),
            TradingWalletTransfer(second + TRADING_DAY_MS / 2, "-250".toBigDecimal()),
        )
        val days = listOf(
            TradingPerformanceDay(first, "1000".toBigDecimal()),
            TradingPerformanceDay(second, "1625".toBigDecimal(), dailyTransfers(first, second, transfers)),
            TradingPerformanceDay(third, "1225".toBigDecimal(), dailyTransfers(second, third, transfers)),
        )
        val closeHistory = listOf(
            TradingPerformanceClose(second, "89.00"),
            TradingPerformanceClose(third, "-100.12"),
            TradingPerformanceClose(third + 10_000, "19.00"),
        )
        val report = summarizeTradingPerformance(days, closeHistory, tradingDay(third) + 1)

        assertEquals(first, report.from)
        assertEquals(third, report.through)
        assertEquals("-25", report.profit?.toPlainString())
        assertEquals("250", report.withdrawn?.toPlainString())
        assertEquals(listOf(0.0, 10.0, -1.0), report.returns.map { it.percent.toDouble() })
        assertEquals(-1.0, checkNotNull(report.monthReturnPercent).toDouble(), 0.0001)
        assertEquals(10.0, checkNotNull(report.maxDrawdownPercent).toDouble(), 0.0001)
        assertEquals(50.0, checkNotNull(report.winRatePercent), 0.0001)
        assertEquals(7.0, checkNotNull(report.closesPerWeek), 0.0001)
    }

    @Test
    fun pendingTransferIntervalKeepsThePreviouslyVerifiedReport() {
        val first = Instant.parse("2026-11-01T23:59:59Z").toEpochMilli()
        val second = first + TRADING_DAY_MS
        val third = second + TRADING_DAY_MS
        val days = listOf(
            TradingPerformanceDay(first, "100".toBigDecimal()),
            TradingPerformanceDay(second, "80".toBigDecimal(), dailyTransfers(first, second, emptyList())),
            TradingPerformanceDay(third, "50".toBigDecimal()),
        )
        assertEquals(second, summarizeTradingPerformance(days, emptyList(), tradingDay(third) + 1).through)
        val completed = days.take(2) + days.last().copy(transfers = dailyTransfers(second, third,
            listOf(TradingWalletTransfer(third, "-30".toBigDecimal()))))
        val updated = summarizeTradingPerformance(completed, emptyList(), tradingDay(third) + 1)
        assertEquals(third, updated.through)
        assertEquals(listOf(0.0, -20.0, -20.0), updated.returns.map { it.percent.toDouble() })
        assertEquals("30", updated.withdrawn?.toPlainString())
    }

    @Test
    fun ambiguousCapitalShowsTheAmountWithoutInventingAProfitRate() {
        val first = Instant.parse("2026-11-01T23:59:59Z").toEpochMilli()
        val second = first + TRADING_DAY_MS
        val days = listOf(
            TradingPerformanceDay(first, "0".toBigDecimal()),
            TradingPerformanceDay(second, "100".toBigDecimal(), dailyTransfers(first, second, emptyList())),
        )
        val report = summarizeTradingPerformance(days, emptyList(), tradingDay(second) + 1)
        assertEquals("100", report.profit?.toPlainString())
        assertEquals(1, report.returns.size)
        assertNull(report.monthReturnPercent)
        assertNull(report.maxDrawdownPercent)
    }

    @Test
    fun monthReturnNeedsTheCurrentMonthAndItsOpeningSnapshot() {
        val first = Instant.parse("2026-11-28T23:59:59Z").toEpochMilli()
        val second = first + TRADING_DAY_MS
        val third = second + TRADING_DAY_MS
        val days = listOf(
            TradingPerformanceDay(first, "100".toBigDecimal()),
            TradingPerformanceDay(second, "105".toBigDecimal(), dailyTransfers(first, second, emptyList())),
            TradingPerformanceDay(third, "115".toBigDecimal(), dailyTransfers(second, third, emptyList())),
        )
        assertNull(summarizeTradingPerformance(days.dropLast(1), emptyList(), tradingDay(third)).monthReturnPercent)
        assertNull(summarizeTradingPerformance(days, emptyList(), tradingDay(third) + 1).monthReturnPercent)
    }
}
