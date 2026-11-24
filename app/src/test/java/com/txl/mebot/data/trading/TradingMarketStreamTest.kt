package com.txl.mebot.data.trading

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingMarketStreamTest {
    @Test
    fun socketRejectsLateEventsFromDifferentContracts() {
        val hype = TradingContract.HYPE
        val ticker = """
            {"stream":"hypeusdt@ticker","data":{"e":"24hrTicker","s":"HYPEUSDT","c":"62.827","P":"4.584"}}
        """.trimIndent()
        assertEquals(MarketQuote(62.827, 4.584), parseMarketUpdate(ticker, TradingInterval.M15, hype))
        assertNull(parseMarketUpdate(ticker, TradingInterval.M15, TradingContract.ETH))
        assertNull(parseMarketUpdate(ticker.replace("HYPEUSDT", "ETHUSDT"), TradingInterval.M15, hype))
    }
    @Test
    fun markPriceStreamPreservesDecimalPrecision() {
        val message = """
            {"stream":"btcusdt@markPrice@1s","data":{"e":"markPriceUpdate","s":"BTCUSDT","p":"59162.56000000"}}
        """.trimIndent()
        assertEquals(MarketMarkPrice("59162.56000000".toBigDecimal()), parseMarketUpdate(message, TradingInterval.M15))
    }

    @Test
    fun tickerProvidesTheSameRollingChangeForEveryChartPeriod() {
        val ticker = """
            {"stream":"btcusdt@ticker","data":{"e":"24hrTicker","s":"BTCUSDT","c":"59860.40","P":"-1.275"}}
        """.trimIndent()

        for (interval in TradingInterval.entries) {
            assertEquals(MarketQuote(59860.40, -1.275), parseMarketUpdate(ticker, interval))
        }
    }

    @Test
    fun combinedStreamStillDecodesAClosedCandle() {
        val candle = """
            {"stream":"btcusdt@kline_15m","data":{"e":"kline","s":"BTCUSDT","k":{
                "s":"BTCUSDT","i":"15m","t":1790000000000,"o":"60100","h":"60200",
                "l":"59700","c":"59860.40","v":"42.1","x":true
            }}}
        """.trimIndent()

        for ((interval, streamInterval) in listOf(
            TradingInterval.M1 to "1m",
            TradingInterval.M5 to "5m",
            TradingInterval.M15 to "15m",
            TradingInterval.D3 to "3d",
        )) {
            val message = candle.replace("15m", streamInterval)
            val tick = parseMarketUpdate(message, interval) as MarketTick
            assertEquals(streamInterval, tick.candle.interval)
            assertEquals(59860.40, tick.candle.close, 0.0)
            assertTrue(tick.closed)
            assertNull(parseMarketUpdate(message, TradingInterval.H1))
        }
    }

    @Test
    fun invalidOrMismatchedQuotesAreIgnored() {
        val ticker = """
            {"stream":"btcusdt@ticker","data":{"e":"24hrTicker","s":"BTCUSDT","c":"59860.40","P":"1.125"}}
        """.trimIndent()

        assertNull(parseMarketUpdate(ticker.replace("BTCUSDT", "ETHUSDT"), TradingInterval.M15))
        assertNull(parseMarketUpdate(ticker.replace("1.125", "NaN"), TradingInterval.M15))
        assertNull(parseMarketUpdate(ticker.replace("59860.40", "0"), TradingInterval.M15))
    }
}
