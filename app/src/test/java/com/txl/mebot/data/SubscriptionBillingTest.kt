package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.math.BigInteger
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionBillingTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var storage: SubscriptionStorage
    private lateinit var records: SubscriptionRepository
    private lateinit var stats: SubscriptionStatisticsRepository
    private lateinit var billing: SubscriptionBillingRepository
    private val clock = MutableClock("2026-10-01")
    private val october = YearMonth.of(2026, 10)
    private fun day(date: String) = LocalDate.parse(date)
    private fun values(anchor: String = "2026-10-05", amount: Long = 2500, currency: String = "CNY",
        unit: SubscriptionCycleUnit = SubscriptionCycleUnit.MONTH, interval: Int = 1) = SubscriptionValues("云盘", amount, currency,
        unit, interval, anchorDate = day(anchor), nextBillingDate = day(anchor), autoRenew = true)
    private fun life() = SubscriptionLifecycle(storage, records, clock) { ZoneOffset.UTC }
    private suspend fun item(id: String) = requireNotNull(records.get(id))
    private suspend fun plan(id: String, month: YearMonth = october, index: Int = 0) = billing.month(id, month).plans[index]
    private suspend fun confirm(id: String, request: String, plan: SubscriptionPlannedCharge?, amount: Long, date: String, currency: String = "CNY") =
        billing.record(request, id, item(id).revision, plan, SubscriptionPaymentValues(amount, currency, day(date)))
    private fun total(summary: SubscriptionSpendingSummary, currency: String = "CNY") = summary.estimated.firstOrNull { it.currencyCode == currency }?.amountMinor ?: BigInteger.ZERO
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        storage = SubscriptionStorage(db, db.subscriptions(), db.subscriptionConversations(), db.mediaDao())
        records = SubscriptionRepository(storage, clock)
        stats = SubscriptionStatisticsRepository(storage) { ZoneOffset.UTC }
        billing = SubscriptionBillingRepository(storage, records, stats, clock) { ZoneOffset.UTC }
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }

    @Test fun completeNaturalMonthSurvivesNextDateMaintenanceWithoutInventingPayment() = runBlocking {
        val sub = records.create("create", values("2026-10-01", unit = SubscriptionCycleUnit.WEEK))
        clock.set("2026-10-27"); life().reconcile()
        assertEquals(day("2026-10-29"), item(sub.id).nextBillingDate)
        assertEquals(5, billing.month(sub.id, october).plans.size)
        assertEquals(BigInteger.valueOf(12500), total(stats.summary(october)))
        assertTrue(records.payments(sub.id).isEmpty())
        assertTrue(stats.summary(october).previousActual.isEmpty())
    }
    @Test fun actualReplacesSameEventOnlyAndCanDifferInPriceAndCurrency() = runBlocking {
        val sub = records.create("create", values())
        clock.set("2026-10-27")
        val payment = confirm(sub.id, "pay", plan(sub.id), 2000, "2026-10-05", "USD")
        assertEquals(1, records.payments(sub.id).size)
        assertEquals(BigInteger.ZERO, total(stats.summary(october)))
        assertEquals(BigInteger.valueOf(2000), total(stats.summary(october), "USD"))
        assertEquals(0, stats.summary(october).unconfirmedPlanCount)
        val repeated = billing.record("pay", sub.id, 1, plan(sub.id), SubscriptionPaymentValues(2000, "USD", day("2026-10-05")))
        assertEquals(payment, repeated)
        assertTrue(runCatching { confirm(sub.id, "another-pay", plan(sub.id), 2000, "2026-10-05", "USD") }.isFailure)
        assertNull(storage.records.operation("another-pay"))
        assertEquals(2L, item(sub.id).revision)
    }
    @Test fun advancePaymentInPreviousMonthSuppressesCurrentPlanAcrossMonths() = runBlocking {
        val sub = records.create("create", values())
        confirm(sub.id, "paid-early", plan(sub.id), 2300, "2026-09-30")
        val summary = stats.summary(october)
        assertEquals(BigInteger.ZERO, total(summary))
        assertEquals(BigInteger.valueOf(2300), summary.previousActual.single().amountMinor)
        assertEquals(1, summary.previousConfirmedCount)
    }
    @Test fun unassociatedExtraPaymentDoesNotSuppressPlanByDateOrEqualAmount() = runBlocking {
        val sub = records.create("create", values())
        clock.set("2026-10-27")
        confirm(sub.id, "extra", null, 2500, "2026-10-05")
        assertEquals(BigInteger.valueOf(5000), total(stats.summary(october)))
        assertEquals(1, stats.summary(october).unconfirmedPlanCount)
    }
    @Test fun ordinaryRepriceArchiveAndDeletionCannotRewriteConfirmedHistory() = runBlocking {
        val sub = records.create("create", values("2026-09-05"))
        val september = YearMonth.of(2026, 9)
        val paid = confirm(sub.id, "paid-sep", plan(sub.id, september), 2300, "2026-09-05")
        clock.set("2026-10-10"); life().reconcile()
        val current = item(sub.id)
        val edited = records.update("reprice", sub.id, current.revision, SubscriptionValues.from(current).copy(amountMinor = 9000))
        legacyArchived(records, "archive", sub.id, edited.revision)
        life().delete("delete", sub.id, item(sub.id).revision)
        assertEquals(paid, storage.records.payment(paid.id))
        assertEquals(BigInteger.valueOf(2300), stats.summary(october).previousActual.single().amountMinor)
        assertEquals(SubscriptionStatus.DELETED, item(sub.id).status)
        // 10 月 5 日早于改价，未确认的该期仍按原计划统计。
        assertEquals(BigInteger.valueOf(2500), total(stats.summary(october)))
    }
    @Test fun correctionMovesActualMonthWithImmutableBeforeAndAfterAndStablePaymentIdentity() = runBlocking {
        val sub = records.create("create", values())
        clock.set("2026-10-27")
        val paid = confirm(sub.id, "paid", plan(sub.id), 2500, "2026-10-05")
        val correction = SubscriptionPaymentValues(1800, "USD", day("2026-09-30"), "账单更正")
        val changed = billing.correct("fix", sub.id, 2, paid, correction)
        assertEquals(paid.id, changed.id); assertEquals(paid.billingEventId, changed.billingEventId)
        assertEquals(paid.confirmedAt, changed.confirmedAt)
        val audit = JSONObject(storage.records.operation("fix")!!.payloadJson)
        assertEquals(2500L, audit.getJSONObject("before").getLong("amountMinor"))
        assertEquals("2026-10-05", audit.getJSONObject("before").getString("paidOn"))
        assertEquals("USD", audit.getJSONObject("after").getString("currencyCode"))
        assertEquals("账单更正", audit.getJSONObject("after").getString("notes"))
        assertEquals(changed, billing.correct("fix", sub.id, 2, paid, correction))
        assertTrue(runCatching { billing.correct("fix", sub.id, 2, paid, correction.copy(amountMinor = 1900)) }.isFailure)
        assertEquals(BigInteger.ZERO, total(stats.summary(october)))
        assertEquals(BigInteger.valueOf(1800), stats.summary(october).previousActual.single().amountMinor)
        assertEquals(3, records.history(sub.id).size)
        assertEquals(3L, item(sub.id).revision)
    }
    @Test fun voidAndReconfirmKeepOneRowAndRestoreThenReplaceForecast() = runBlocking {
        val sub = records.create("create", values())
        clock.set("2026-10-27")
        val paid = confirm(sub.id, "paid", plan(sub.id), 2200, "2026-10-05")
        val voided = billing.void("void", sub.id, 2, paid)
        assertEquals(voided, billing.void("void", sub.id, 2, paid))
        assertEquals(BigInteger.valueOf(2500), total(stats.summary(october)))
        assertEquals(0, stats.summary(october).currentConfirmedCount)
        assertTrue(billing.month(sub.id, october).payments.single().voided)
        val restored = billing.correct("again", sub.id, 3, voided, SubscriptionPaymentValues(2100, "CNY", day("2026-10-06")))
        assertEquals(paid.id, restored.id)
        assertEquals(1, records.payments(sub.id).size)
        assertEquals(BigInteger.valueOf(2100), total(stats.summary(october)))
        assertFalse(billing.month(sub.id, october).payments.single().voided)
        assertTrue(JSONObject(storage.records.operation("again")!!.payloadJson).getJSONObject("before").getBoolean("voided"))
    }
    @Test fun staleCorrectionAndCrossSubscriptionCannotMutateAnyPaymentOrLeaveOperation() = runBlocking {
        val a = records.create("a", values()); val b = records.create("b", values())
        clock.set("2026-10-27")
        val payment = confirm(a.id, "paid", plan(a.id), 2500, "2026-10-05")
        val values = SubscriptionPaymentValues(1900, "CNY", day("2026-10-05"))
        assertTrue(runCatching { billing.correct("wrong-sub", b.id, 1, payment, values) }.isFailure)
        assertTrue(runCatching { billing.correct("wrong-rev", a.id, 1, payment, values) }.exceptionOrNull() is SubscriptionConflict)
        val changed = billing.correct("valid", a.id, 2, payment, values)
        assertTrue(runCatching { billing.correct("old-payment", a.id, 3, payment, values) }.exceptionOrNull() is SubscriptionConflict)
        assertEquals(changed, storage.records.payment(payment.id))
        listOf("wrong-sub", "wrong-rev", "old-payment").forEach { assertNull(storage.records.operation(it)) }
    }
    @Test fun futureActualDatesAndChangedPlansRequireUserToRecheck() = runBlocking {
        val sub = records.create("create", values())
        val original = plan(sub.id)
        assertTrue(runCatching { confirm(sub.id, "future", original, 2500, "2026-10-05") }.isFailure)
        assertNull(storage.records.operation("future"))
        records.update("move", sub.id, 1, values("2026-11-05"))
        assertTrue(runCatching { confirm(sub.id, "outdated", original, 2500, "2026-10-01") }.isFailure)
        assertEquals(BigInteger.ZERO, total(stats.summary(october)))
        assertEquals("cycle:0", plan(sub.id, october.plusMonths(1)).eventId)
    }
    @Test fun reschedulingAcrossMonthKeepsConfirmedEventAndNewFutureCycleDistinct() = runBlocking {
        val sub = records.create("create", values())
        confirm(sub.id, "paid", plan(sub.id), 2000, "2026-10-01")
        records.update("move", sub.id, 2, values("2026-11-05", 3000))
        assertEquals(BigInteger.valueOf(2000), total(stats.summary(october)))
        assertEquals("cycle:1", plan(sub.id, october.plusMonths(1)).eventId)
        assertEquals(BigInteger.valueOf(3000), total(stats.summary(october.plusMonths(1))))
    }
    @Test fun priceChangeOnlyAffectsRemainingPlannedOccurrencesAndNotesDoNotSplitHistory() = runBlocking {
        val sub = records.create("create", values("2026-10-01", 100, unit = SubscriptionCycleUnit.DAY))
        clock.set("2026-10-15"); life().reconcile()
        val current = item(sub.id)
        val edited = records.update("price", sub.id, current.revision, SubscriptionValues.from(current).copy(amountMinor = 200))
        records.update("notes", sub.id, edited.revision, SubscriptionValues.from(edited).copy(notes = "不改计费"))
        assertEquals(31, billing.month(sub.id, october).plans.size)
        assertEquals(BigInteger.valueOf(14 * 100L + 17 * 200L), total(stats.summary(october)))
    }
    @Test fun stoppingAndResumingDoesNotReusePastEventOrInventInactiveCharges() = runBlocking {
        val sub = records.create("create", values("2026-10-01", 100, unit = SubscriptionCycleUnit.DAY))
        clock.set("2026-10-10"); life().reconcile()
        life().stopRenewal("stop", sub.id, item(sub.id).revision, day("2026-10-31"))
        clock.set("2026-10-20"); life().resumeRenewal("resume", sub.id, item(sub.id).revision, day("2026-10-20"))
        val plans = billing.month(sub.id, october).plans
        assertEquals(21, plans.size)
        assertEquals(21, plans.map { it.eventId }.distinct().size)
        assertFalse(plans.any { it.date in day("2026-10-10")..day("2026-10-19") })
        assertEquals(BigInteger.valueOf(2100), total(stats.summary(october)))
    }
    @Test fun monthEndLeapYearAndMultipleIntervalsHaveWholeMonthPlans() = runBlocking {
        val sub = records.create("create", values("2024-01-31"))
        assertEquals(day("2024-02-29"), plan(sub.id, YearMonth.of(2024, 2)).date)
        assertEquals(day("2024-03-31"), plan(sub.id, YearMonth.of(2024, 3)).date)
        val annual = records.create("annual", values("2024-02-29", unit = SubscriptionCycleUnit.YEAR))
        assertEquals(day("2025-02-28"), plan(annual.id, YearMonth.of(2025, 2)).date)
        assertEquals(day("2028-02-29"), plan(annual.id, YearMonth.of(2028, 2)).date)
        val every2 = records.create("two", values("2026-10-01", unit = SubscriptionCycleUnit.WEEK, interval = 2))
        assertEquals(listOf(1, 15, 29), billing.month(every2.id, october).plans.map { it.date.dayOfMonth })
    }
    @Test fun currencyTotalsStaySeparateAndDoNotOverflowLong() = runBlocking {
        records.create("max-a", values(amount = Long.MAX_VALUE))
        records.create("max-b", values(amount = Long.MAX_VALUE))
        records.create("usd", values(amount = 3000, currency = "USD"))
        val summary = stats.summary(october)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, total(summary))
        assertEquals(BigInteger.valueOf(3000), total(summary, "USD"))
        assertEquals(listOf("CNY", "USD"), summary.estimated.map { it.currencyCode })
        assertEquals("¥184,467,440,737,095,516.14", SubscriptionMoney.format(total(summary), "CNY"))
    }
    @Test fun latePaymentIsCountedOnActualDateAndZeroConfirmationIsRealHistory() = runBlocking {
        val sub = records.create("create", values("2026-09-05"))
        clock.set("2026-10-27")
        confirm(sub.id, "late", plan(sub.id, october.minusMonths(1)), 2300, "2026-10-02")
        val free = records.create("free", values("2026-09-15"))
        confirm(free.id, "zero", plan(free.id, october.minusMonths(1)), 0, "2026-09-15")
        val summary = stats.summary(october)
        assertEquals(BigInteger.valueOf(7300), total(summary))
        assertEquals(BigInteger.ZERO, summary.previousActual.single().amountMinor)
        assertEquals(1, summary.previousConfirmedCount)
    }
    @Test fun ledgerPaginationUsesActualDateAndPreservesVoidedRows() = runBlocking {
        val sub = records.create("create", values())
        clock.set("2026-10-27")
        repeat(25) { confirm(sub.id, "extra-$it", null, 100, "2026-10-${(it + 1).toString().padStart(2, '0')}") }
        val page = billing.month(sub.id, october)
        assertEquals(20, page.payments.size); assertTrue(page.hasMore)
        assertEquals(25, page.payments.first().payment.paidOn.dayOfMonth)
        assertFalse(billing.month(sub.id, october, 40).hasMore)
        assertEquals(25, billing.month(sub.id, october, 40).payments.size)
        assertEquals(1, billing.month(sub.id, october, -1).payments.size)
        val second = billing.month(sub.id, october, offset = 20)
        assertEquals(5, second.payments.size); assertFalse(second.hasMore)
        assertTrue(page.payments.map { it.payment.id }.intersect(second.payments.map { it.payment.id }.toSet()).isEmpty())
    }
    @Test fun billingHistoryReadsBeyondOnePageWithoutLoadingChatIntoStatistics() = runBlocking {
        val sub = records.create("create", values())
        repeat(205) { index ->
            val current = item(sub.id)
            records.update("note-$index", sub.id, current.revision, SubscriptionValues.from(current).copy(notes = "备注 $index"))
        }
        assertEquals(BigInteger.valueOf(2500), total(stats.summary(october)))
        assertEquals(1, billing.month(sub.id, october).plans.size)
    }
    @Test fun failedAuditInsertRollsBackCorrectionAndOperationTogether() = runBlocking {
        val sub = records.create("create", values())
        clock.set("2026-10-27")
        val paid = confirm(sub.id, "paid", plan(sub.id), 2500, "2026-10-05")
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_payment_audit BEFORE INSERT ON subscription_changes
            WHEN NEW.kind = 'CORRECT_PAYMENT' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { billing.correct("fix", sub.id, 2, paid, SubscriptionPaymentValues(1800, "CNY", day("2026-10-05"))) }.isFailure)
        assertEquals(paid, storage.records.payment(paid.id))
        assertEquals(2L, item(sub.id).revision)
        assertNull(storage.records.operation("fix"))
        assertEquals(2, records.history(sub.id).size)
    }
    @Test fun voidAfterScheduleChangeCannotReassignTheLaterCycleIdentity() = runBlocking {
        val sub = records.create("create", values())
        val paid = confirm(sub.id, "paid", plan(sub.id), 2000, "2026-10-01")
        records.update("move", sub.id, 2, values("2026-11-05", 3000))
        val next = plan(sub.id, october.plusMonths(1))
        assertEquals("cycle:1", next.eventId)
        billing.void("void", sub.id, 3, paid)
        assertEquals(next, plan(sub.id, october.plusMonths(1)))
        assertEquals(BigInteger.valueOf(3000), total(stats.summary(october.plusMonths(1))))
    }
    @Test fun independentSubscriptionsHaveSeparateEventsEvenWhenDatesAndAmountsMatch() = runBlocking {
        val a = records.create("a", values()); val b = records.create("b", values())
        clock.set("2026-10-27")
        val aPlan = plan(a.id)
        assertTrue(runCatching { confirm(b.id, "wrong-plan", aPlan, 2500, "2026-10-05") }.isFailure)
        confirm(a.id, "a-paid", aPlan, 2000, "2026-10-05")
        assertEquals(BigInteger.valueOf(4500), total(stats.summary(october)))
        assertNull(storage.records.operation("wrong-plan"))
    }
    private class MutableClock(date: String) : Clock() {
        private var instant = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC)
        fun set(date: String) { instant = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC) }
        override fun instant() = instant
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
