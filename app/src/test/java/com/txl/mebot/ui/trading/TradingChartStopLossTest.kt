package com.txl.mebot.ui.trading

import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingPosition
import com.txl.mebot.data.trading.TradingPositionDetails
import com.txl.mebot.data.trading.TradingPositionKey
import com.txl.mebot.data.trading.TradingPositionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingChartStopLossTest {
    @Test
    fun chartShowsSavedStopsOnlyForOpenPositionsInTheSelectedContract() {
        val eth = TradingContract.ETH
        val long = position(eth, "LONG")
        val short = position(eth, "SHORT")
        val btc = position(TradingContract.BTC, "LONG")
        val closed = long.key.copy(positionKey = "older-fill")
        val state = TradingState(
            contract = eth,
            positions = listOf(long, short, btc),
            positionPlans = mapOf(
                long.key to TradingPositionPlan(stopLoss = "1,680.25"),
                short.snapshotKey to TradingPositionPlan(stopLoss = "1769.00"),
                btc.key to TradingPositionPlan(stopLoss = "61500"),
                closed to TradingPositionPlan(stopLoss = "1670"),
            ),
        )
        assertEquals(
            listOf(TradingChartStopLoss("1680.25", "LONG"), TradingChartStopLoss("1769", "SHORT")),
            state.chartStopLosses(),
        )
        assertEquals(
            listOf(TradingChartStopLoss("1680.25", "LONG")),
            state.copy(positionPlans = state.positionPlans + (short.key to TradingPositionPlan())).chartStopLosses(),
        )
        assertTrue(state.copy(positions = emptyList()).chartStopLosses().isEmpty())
    }

    private fun position(contract: TradingContract, side: String): TradingPositionDetails {
        val key = TradingPositionKey("account", "$side:fill:1", contract.symbol)
        return TradingPositionDetails(
            TradingPosition(side, side, 1L, "1", "1724.50", "1721.37", "0"),
            key, key.copy(positionKey = "$side:snapshot:1"), null, null,
        )
    }
}
