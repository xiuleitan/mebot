package com.txl.mebot.ui.trading

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.trading.TradingContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingBinanceLinkTest {
    @Test
    fun eachSelectedContractUsesItsOwnBinanceFuturesLink() {
        val context = LinkContext(ApplicationProvider.getApplicationContext())
        val contracts = listOf(TradingContract.BTC, TradingContract("TQQQUSDT", "TQQQ", 2))
        for (contract in contracts) assertTrue(openBinanceFutures(context, contract))

        assertEquals(contracts.size, context.attempts.size)
        context.attempts.zip(contracts).forEach { (intent, contract) ->
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("com.binance.dev", intent.`package`)
            assertEquals("bnc", intent.data?.scheme)
            assertEquals("app.binance.com", intent.data?.host)
            assertEquals("/trade/trade", intent.data?.path)
            assertEquals("futures", intent.data?.getQueryParameter("at"))
            assertEquals(contract.symbol, intent.data?.getQueryParameter("symbol"))
        }
    }

    @Test
    fun unavailableAppFallsBackToTheSameContractOnTheBinanceWebsite() {
        val context = LinkContext(ApplicationProvider.getApplicationContext(), rejectApp = true)
        val contract = TradingContract("币安人生USDT", "币安人生", 4)
        assertTrue(openBinanceFutures(context, contract))

        assertEquals(2, context.attempts.size)
        val web = context.attempts.last()
        assertNull(web.`package`)
        assertEquals("https", web.data?.scheme)
        assertEquals("www.binance.com", web.data?.host)
        assertEquals(listOf("en", "futures", contract.symbol), web.data?.pathSegments)
    }

    @Test
    fun reportsFailureWhenNeitherLinkCanOpen() {
        val context = LinkContext(ApplicationProvider.getApplicationContext(), rejectApp = true, rejectWeb = true)
        assertFalse(openBinanceFutures(context, TradingContract.BTC))
        assertEquals(2, context.attempts.size)
    }

    private class LinkContext(
        context: Context,
        private val rejectApp: Boolean = false,
        private val rejectWeb: Boolean = false,
    ) : ContextWrapper(context) {
        val attempts = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            attempts.add(intent)
            val rejected = if (intent.`package` != null) rejectApp else rejectWeb
            if (rejected) throw ActivityNotFoundException()
        }
    }
}
