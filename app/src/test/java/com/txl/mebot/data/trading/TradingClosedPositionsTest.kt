package com.txl.mebot.data.trading

import com.txl.mebot.data.local.TradingFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingClosedPositionsTest {
    @Test
    fun additionsAndReductionsStayTogetherUntilThePositionCloses() {
        val open = fill(1, "BUY", "0.003")
        val add = fill(2, "BUY", "0.002")
        val reduce = fill(3, "SELL", "0.001", realized = "1.23")
        val close = fill(4, "SELL", "0.004", realized = "-2.34")
        assertTrue(closedTradingPositions(listOf(reduce, add, open), listOf(position("BOTH", "LONG", "0.004"))).positions.isEmpty())

        val newestFirst = listOf(close, reduce, add, open)
        val history = closedTradingPositions(newestFirst, emptyList()).positions.single()
        assertTrue(history.openingKnown)
        assertEquals("LONG", history.side)
        assertEquals(open.tradeId, history.firstTradeId)
        assertEquals(close.tradeId, history.closingTradeId)
        assertEquals("-1.11", history.realizedPnl)
        assertEquals(newestFirst.asReversed(), history.reviewFills(newestFirst))
    }

    @Test
    fun closingAndReopeningBetweenPositionSnapshotsCreatesOnlyTheClosedRound() {
        val earlier = fill(1, "BUY", "1")
        val close = fill(2, "SELL", "1", realized = "2.8")
        val reopened = fill(3, "BUY", "2").copy(time = close.time)
        val fills = listOf(reopened, close, earlier)
        val closed = closedTradingPositions(fills, listOf(position("BOTH", "LONG", "2"))).positions.single()
        assertEquals(close.tradeId, closed.closingTradeId)
        assertEquals(listOf(earlier, close), closed.reviewFills(fills))
    }

    @Test
    fun anExecutionThatReversesThePositionBelongsToBothRoundsInTheCorrectQty() {
        val first = fill(1, "BUY", "3")
        val reverse = fill(2, "SELL", "5", realized = "10.5")
        val final = fill(3, "BUY", "2", realized = "-0.25")
        val newestFirst = listOf(final, reverse, first)
        val (short, long) = closedTradingPositions(newestFirst, emptyList()).positions

        assertEquals("LONG", long.side)
        assertEquals("10.5", long.realizedPnl)
        assertEquals(listOf("3", "3"), long.reviewFills(listOf(reverse, first)).map { it.quantity })
        assertEquals("SHORT", short.side)
        assertEquals("-0.25", short.realizedPnl)
        val shortFills = short.reviewFills(listOf(final, reverse))
        assertEquals("2", shortFills.first().quantity)
        assertEquals("0", shortFills.first().realizedPnl)
        assertEquals("2", shortFills.last().quantity)
    }

    @Test
    fun hedgeLegsCloseIndependently() {
        val long = fill(1, "BUY", "1", "LONG")
        val openShort = fill(2, "SELL", "2", "SHORT")
        val closeShort = fill(3, "BUY", "2", "SHORT", "1")
        val closed = closedTradingPositions(
            listOf(closeShort, openShort, long),
            listOf(position("LONG", "LONG", "1")),
        ).positions.single()
        assertEquals("SHORT", closed.positionSide)
        assertEquals(openShort.tradeId, closed.firstTradeId)
        assertEquals(closeShort.tradeId, closed.closingTradeId)
    }

    @Test
    fun anAvailableCloseCanBeArchivedWithoutInventingAnOpening() {
        val reduce = fill(2, "SELL", "0.5", realized = "0.25")
        val close = fill(3, "SELL", "1.5", realized = "3.125")
        val history = closedTradingPositions(listOf(close, reduce), emptyList()).positions.single()
        assertFalse(history.openingKnown)
        assertEquals(reduce.time, history.firstTradeAt)
        assertEquals("3.375", history.realizedPnl)
        assertTrue(closedTradingPositions(listOf(reduce), listOf(position("BOTH", "LONG", "1.5"))).positions.isEmpty())
    }

    @Test
    fun anImpossibleHedgeBalanceDoesNotProduceFalseClosures() {
        val opening = fill(1, "BUY", "1", "LONG")
        val replay = closedTradingPositions(listOf(opening), emptyList())
        assertFalse(replay.complete)
        assertTrue(replay.positions.isEmpty())
    }

    @Test
    fun aSnapshotThatTurnsARealizedCloseIntoAnOpeningIsNotArchived() {
        val open = fill(1, "BUY", "1")
        val close = fill(2, "SELL", "1", realized = "7.5")
        val mismatched = position("BOTH", "SHORT", "1")
        val replay = closedTradingPositions(listOf(close, open), listOf(mismatched))
        assertFalse(replay.complete)
        assertTrue(replay.positions.isEmpty())
    }

    private fun position(positionSide: String, side: String, quantity: String) =
        TradingPosition(side, positionSide, 10, quantity, "60000", "61000", "0")

    private fun fill(id: Long, side: String, quantity: String, bucket: String = "BOTH", realized: String = "0") =
        TradingFill("account", id, id, id * 1_000, side, bucket, "60000", quantity, realized)
}
