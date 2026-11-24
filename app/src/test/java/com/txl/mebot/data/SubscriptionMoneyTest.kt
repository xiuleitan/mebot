package com.txl.mebot.data

import com.txl.mebot.data.subscriptions.SubscriptionMoney
import org.junit.Assert.*
import org.junit.Test

class SubscriptionMoneyTest {
    @Test fun decimalInputConvertsToMinorUnitsWithoutFloatingPointRounding() {
        assertEquals(2500L, SubscriptionMoney.parse("25.00", "CNY"))
        assertEquals(1L, SubscriptionMoney.parse("0.01", "USD"))
        assertEquals(0L, SubscriptionMoney.parse("0", "CNY"))
        assertEquals(9_007_199_254_740_993L, SubscriptionMoney.parse("90071992547409.93", "CNY"))
    }
    @Test fun currenciesRespectTheirPrecisionAndFormattingKeepsTheirIdentity() {
        assertEquals(100L, SubscriptionMoney.parse("100", "JPY"))
        assertEquals("JP¥100", SubscriptionMoney.format(100, "JPY"))
        assertEquals("¥25.00", SubscriptionMoney.format(2500, "CNY"))
        assertEquals("US$25.00", SubscriptionMoney.format(2500, "USD"))
        assertEquals("25.00", SubscriptionMoney.editable(2500, "CNY"))
        assertTrue(runCatching { SubscriptionMoney.parse("1.5", "JPY") }.isFailure)
    }
    @Test fun invalidNegativeExponentialOverflowAndOverPreciseAmountsAreRejected() {
        listOf("-1", "1e3", "NaN", "", "1,000", "0.001", "999999999999999999999999").forEach {
            assertTrue(it, runCatching { SubscriptionMoney.parse(it, "CNY") }.isFailure)
        }
        assertTrue(runCatching { SubscriptionMoney.parse("1", "???") }.isFailure)
    }
}
