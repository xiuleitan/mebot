package com.txl.mebot.data.trading

import com.txl.mebot.data.trading.alerts.TradingPushCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingPushCheckTest {
    @Test
    fun matchingPushCanArriveBeforeTheHttpResponse() = runBlocking<Unit> {
        val check = TradingPushCheck()
        assertTrue(check.verify("current-device") { checkId ->
            check.receive("current-device", checkId)
        })
    }

    @Test
    fun acceptedRequestAndUnrelatedPushesDoNotVerifyTheConnection() = runBlocking<Unit> {
        val check = TradingPushCheck(timeoutMillis = 100L)
        var expiredId: String? = null
        assertFalse(check.verify("current-device") { expiredId = it })
        assertFalse(check.verify("current-device") { checkId ->
            assertNotEquals(expiredId, checkId)
            check.receive("old-device", checkId)
            check.receive("current-device", expiredId)
        })
    }
}
