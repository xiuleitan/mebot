package com.txl.mebot.data

import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.subscriptions.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SubscriptionPresentationTest {
    private val today = LocalDate.of(2026, 10, 28)
    private fun record() = SubscriptionValues("有效期订阅", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
        anchorDate = today, validUntil = today.plusDays(5)).entity("one", 1, 2, 1)
    @Test fun nonAutomaticPurchaseIsNotMislabelledAsCancelled() {
        val item = record()
        assertEquals("未开启自动续费", item.billingDateText(today))
        assertTrue(item.dateSummary(today).startsWith("有效期至"))
    }
    @Test fun expiryIsDerivedByLocalDateAndCancelledSubscriptionRemainsUntilLastValidDay() {
        val item = record().copy(status = SubscriptionStatus.CANCEL_AT_PERIOD_END)
        val finalDay = requireNotNull(item.validUntil)
        assertFalse(item.hasEnded(finalDay))
        assertEquals("已取消续费", item.billingDateText(finalDay))
        assertTrue(item.hasEnded(finalDay.plusDays(1)))
        assertEquals("已到期", item.dateSummary(finalDay.plusDays(1)))
        val page = SubscriptionsState(records = listOf(item), today = finalDay.plusDays(1))
        assertTrue(page.ongoing.isEmpty()); assertEquals(listOf(item), page.ended)
    }
    @Test fun oldArchivedAndDeletedDataRemainEndedAndReadable() {
        for (status in listOf(SubscriptionStatus.ARCHIVED, SubscriptionStatus.DELETED)) {
            val item = record().copy(status = status)
            assertTrue(item.hasEnded(today))
            assertTrue(SubscriptionsState(records = listOf(item), today = today).ongoing.isEmpty())
        }
    }
}
