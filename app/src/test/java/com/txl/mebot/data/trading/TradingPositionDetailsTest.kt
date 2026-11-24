package com.txl.mebot.data.trading

import com.txl.mebot.data.local.TradingFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TradingPositionDetailsTest {
    @Test
    fun addingAndPartiallyClosingStillFindsTheFirstOpeningFill() {
        val position = position(side = "LONG", quantity = "1", updatedAt = 3)
        val opening = fill(1, "BUY", "1")
        val fills = listOf(fill(4, "BUY", "1"), fill(3, "SELL", "0.75"), fill(2, "BUY", "0.75"), opening)

        assertEquals(opening, openingPositionFill(position, fills))
        assertNull(openingPositionFill(position, fills.dropLast(1)))
    }

    @Test
    fun aFlipOpensANewOneWayPosition() {
        val reversal = fill(5, "BUY", "3")
        assertEquals(reversal, openingPositionFill(position("LONG", "2", 5), listOf(reversal, fill(4, "SELL", "1"))))
    }

    @Test
    fun hedgeSidesAreResolvedIndependently() {
        val opening = fill(2, "SELL", "2", positionSide = "SHORT")
        val position = position("SHORT", "1.5", 3).copy(positionSide = "SHORT")
        val fills = listOf(fill(3, "BUY", "0.5", "SHORT"), fill(3, "BUY", "2", "LONG"), opening)
        assertEquals(opening, openingPositionFill(position, fills))
        assertNull(openingPositionFill(position, listOf(fill(2, "SELL", "3", "SHORT"))))
    }

    @Test
    fun entryValueAndRiskMultipleUseThePositionQuantityAndLiveMarkPrice() {
        val position = position("LONG", "0.25", 3)
        val metrics = tradingPositionMetrics(position, TradingPositionPlan(stopLoss = "59000"), "60500".toBigDecimal())
        assertEquals(0, metrics.entryValue.compareTo("15000".toBigDecimal()))
        assertEquals(0, metrics.floatingPnl.compareTo("125".toBigDecimal()))
        assertEquals("0.50", metrics.riskMultiple?.toPlainString())

        val short = tradingPositionMetrics(position.copy(side = "SHORT"), TradingPositionPlan(stopLoss = "61000"), "59500".toBigDecimal())
        assertEquals("0.50", short.riskMultiple?.toPlainString())
        val loss = tradingPositionMetrics(position, TradingPositionPlan(stopLoss = "59000"), "59500".toBigDecimal())
        assertEquals("-0.50", loss.riskMultiple?.toPlainString())
    }

    @Test
    fun missingAndBreakevenStopsHaveNoRatioAndInvalidPricesAreRejected() {
        val position = position("LONG", "0.25", 3)
        assertNull(tradingPositionMetrics(position, TradingPositionPlan(), null).riskMultiple)
        assertNull(tradingPositionMetrics(position, TradingPositionPlan(stopLoss = "60000"), null).riskMultiple)
        assertEquals("59600.50", parsePositionPlanPrice("59,600.50")?.toPlainString())
        for (invalid in listOf("0", "-5", "1e99", "59,6")) assertNull(parsePositionPlanPrice(invalid))
    }

    @Test
    fun positionAlertTargetsFollowTheRiskDistanceAndPositionSide() {
        val short = position("SHORT", "10", 3).copy(entryPrice = "1725.93")
        val long = short.copy(side = "LONG")
        val choices = listOf(PositionAlertChoice.R1, PositionAlertChoice.R2, PositionAlertChoice.R3, PositionAlertChoice.R4)
        assertEquals(
            listOf("1682.86", "1639.79", "1596.72", "1553.65").map { PositionAlertTarget(it, "down") },
            choices.map { short.alertTarget("1769".toBigDecimal(), it) },
        )
        assertEquals(
            listOf("1769", "1812.07", "1855.14", "1898.21").map { PositionAlertTarget(it, "up") },
            choices.map { long.alertTarget("1682.86".toBigDecimal(), it) },
        )
        assertEquals(PositionAlertTarget("1769", "up"), short.alertTarget("1769".toBigDecimal(), PositionAlertChoice.STOP))
        assertEquals(PositionAlertTarget("1725.93", "down"), long.alertTarget("1725.93".toBigDecimal(), PositionAlertChoice.STOP))
        assertNull(short.alertTarget("1725.93".toBigDecimal(), PositionAlertChoice.R1))
        assertNull(short.alertTarget("2500".toBigDecimal(), PositionAlertChoice.R4))
        assertNull(long.alertTarget(null, PositionAlertChoice.STOP))
    }

    @Test
    fun currentPositionMarkersIncludeItsOpeningAdditionsAndReductions() {
        val opening = fill(10, "BUY", "1").copy(time = 100)
        val added = fill(11, "BUY", "0.5").copy(time = 110)
        val reduced = fill(12, "SELL", "0.25").copy(time = 120)
        val precedingClose = fill(9, "SELL", "1").copy(time = 100)
        val newer = fill(13, "SELL", "1.25").copy(time = 121)
        val otherAccount = added.copy(scope = "other-account")
        val current = details(position("LONG", "1.25", 120), opening)

        assertEquals(
            listOf(reduced, added, opening),
            fillsForCurrentPositions(listOf(newer, reduced, otherAccount, added, opening, precedingClose), listOf(current)),
        )
    }

    @Test
    fun currentMarkersSeparateHedgeSidesAndRequireAKnownOpening() {
        val long = fill(10, "BUY", "1", "LONG")
        val short = fill(20, "SELL", "1", "SHORT")
        val previousShort = fill(15, "BUY", "0.5", "SHORT")
        val fills = listOf(short, previousShort, long)
        val longPosition = details(position("LONG", "1", 20).copy(positionSide = "LONG"), long)
        val shortPosition = details(position("SHORT", "1", 20).copy(positionSide = "SHORT"), short)

        assertEquals(listOf(short, long), fillsForCurrentPositions(fills, listOf(longPosition, shortPosition)))
        assertEquals(listOf(long), fillsForCurrentPositions(fills, listOf(longPosition, shortPosition.copy(openingFill = null))))
        assertEquals(emptyList<TradingFill>(), fillsForCurrentPositions(fills, emptyList()))
    }

    private fun details(position: TradingPosition, opening: TradingFill): TradingPositionDetails {
        val key = TradingPositionKey(opening.scope, opening.tradeId.toString())
        return TradingPositionDetails(position, key, key, opening, trendAtOpen = null)
    }

    private fun position(side: String, quantity: String, updatedAt: Long) =
        TradingPosition(side, "BOTH", updatedAt, quantity, "60000", "60100", "25")

    private fun fill(id: Long, side: String, quantity: String, positionSide: String = "BOTH") =
        TradingFill("test", id, id, id, side, positionSide, "60000", quantity, "0")
}
