package com.txl.mebot.ui.trading

import org.junit.Assert.assertEquals
import org.junit.Test

class TradingPnlFormatTest {
    @Test
    fun cardAndHistoryAmountsShowExactlyTwoDecimalPlaces() {
        assertEquals("5.00", formatTradingPnl("5".toBigDecimal()))
        assertEquals("+69.45", formatTradingPnl("69.45".toBigDecimal(), showPositiveSign = true))
        assertEquals("-62.71", formatTradingPnl("-62.71111".toBigDecimal()))
        assertEquals("-1.24", formatTradingPnl("-1.235".toBigDecimal()))
        assertEquals("0.00", formatTradingPnl("-0.001".toBigDecimal(), showPositiveSign = true))
    }
}
