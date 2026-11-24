package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.math.BigInteger
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionSpendingTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var records: SubscriptionRepository
    private lateinit var spending: SubscriptionSpendingRepository
    private lateinit var lifecycle: SubscriptionLifecycle
    private val clock = MutableClock("2026-10-30")
    private fun day(text: String) = LocalDate.parse(text)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        val storage = SubscriptionStorage(db, db.subscriptions(), db.subscriptionConversations(), db.mediaDao())
        records = SubscriptionRepository(storage, clock); spending = SubscriptionSpendingRepository(storage, records) { ZoneOffset.UTC }
        lifecycle = SubscriptionLifecycle(storage, records, clock) { ZoneOffset.UTC }
    }
    @After fun teardown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private fun monthly(next: String = "2026-11-03", amount: Long = 2500, currency: String = "CNY") = SubscriptionValues("会员", amount, currency,
        SubscriptionCycleUnit.MONTH, 1, anchorDate = day(next), nextBillingDate = day(next), autoRenew = true)
    private fun annual(end: String = "2027-10-10", amount: Long = 120000, currency: String = "CNY") = SubscriptionValues("年卡", amount, currency,
        SubscriptionCycleUnit.YEAR, 1, anchorDate = day(end), validUntil = day(end))
    private suspend fun total(today: String) = spending.summary(day(today)).current.cnyMinor

    @Test fun nextBillingImpliesPreviousPurchaseAndOnlyReachedDatesAccumulateAfterRollover() = runBlocking {
        val item = records.create("create", monthly())
        assertEquals(day("2026-10-03"), spending.initial(item.id).date)
        assertEquals(BigInteger.valueOf(2500), total("2026-10-30"))
        assertEquals(BigInteger.ZERO, total("2026-11-02"))
        assertEquals(BigInteger.valueOf(2500), total("2026-11-03"))
        clock.set("2026-11-04"); lifecycle.reconcile()
        assertEquals(day("2026-12-03"), records.get(item.id)!!.nextBillingDate)
        assertEquals(BigInteger.valueOf(2500), total("2026-11-04"))
        assertEquals(BigInteger.valueOf(2500), spending.summary(day("2026-11-04")).previous.cnyMinor)
        assertTrue(records.payments(item.id).isEmpty())
    }
    @Test fun annualPurchaseUsesExpiryMinusOneYearAndNeverAmortizesIntoLaterMonths() = runBlocking {
        val item = records.create("annual", annual())
        assertEquals(day("2026-10-10"), spending.initial(item.id).date)
        assertEquals(BigInteger.valueOf(120000), total("2026-10-30"))
        assertEquals(BigInteger.ZERO, total("2026-11-30"))
        assertEquals(BigInteger.ZERO, total("2027-10-30"))
    }
    @Test fun annualAutoRenewAddsFullAmountOnlyWhenAnnualDueDateIsReached() = runBlocking {
        records.create("annual-auto", annual("2027-10-15").copy(autoRenew = true, nextBillingDate = day("2027-10-15")))
        assertEquals(BigInteger.valueOf(120000), total("2026-10-30"))
        assertEquals(BigInteger.ZERO, total("2027-10-14"))
        assertEquals(BigInteger.valueOf(120000), total("2027-10-15"))
        assertEquals(BigInteger.ZERO, total("2027-11-01"))
    }
    @Test fun cnyUsdAndHkdUseReferenceRatesAndRoundEachChargeToCents() = runBlocking {
        records.create("cny", monthly(amount = 11050))
        records.create("usd", monthly(amount = 10000, currency = "USD"))
        records.create("hkd", annual(amount = 10000, currency = "HKD"))
        assertEquals(BigInteger.valueOf(11050 + 67138 + 8559), total("2026-10-30"))
        assertEquals(BigInteger.ONE, SubscriptionExchangeRates.cnyMinor(1, "HKD"))
        assertEquals(BigInteger.valueOf(7), SubscriptionExchangeRates.cnyMinor(1, "USD"))
    }
    @Test fun unsupportedCurrencyIsExplicitlyRetainedAndNeverSilentlyAddedAsCny() = runBlocking {
        records.create("eur", monthly(currency = "EUR"))
        val value = spending.summary(day("2026-10-30")).current
        assertEquals(BigInteger.ZERO, value.cnyMinor)
        assertEquals(SubscriptionCurrencyTotal("EUR", BigInteger.valueOf(2500)), value.unconverted.single())
    }
    @Test fun cancellationStopsFutureAccrualAndKeepsAlreadyReachedCharges() = runBlocking {
        clock.set("2026-10-01")
        val item = records.create("create", monthly("2026-09-03").copy(nextBillingDate = day("2026-10-03")))
        clock.set("2026-10-04"); lifecycle.stopRenewal("stop", item.id, 1, day("2026-11-02"))
        assertEquals(BigInteger.valueOf(2500), total("2026-10-30"))
        assertEquals(BigInteger.ZERO, total("2026-11-30"))
    }
    @Test fun cancellationBeforeTheDueDayDoesNotAddUpcomingCharge() = runBlocking {
        clock.set("2026-10-01")
        val item = records.create("create", monthly("2026-09-03").copy(nextBillingDate = day("2026-10-03")))
        clock.set("2026-10-02"); lifecycle.stopRenewal("stop", item.id, 1, day("2026-11-02"))
        assertEquals(BigInteger.ZERO, total("2026-10-30"))
    }
    @Test fun changingFuturePriceDoesNotRepricePastExpensesAndDeletionKeepsHistory() = runBlocking {
        clock.set("2026-10-01")
        val item = records.create("create", monthly("2026-10-03"))
        clock.set("2026-10-10")
        val changed = records.update("price", item.id, 1, SubscriptionValues.from(item).copy(amountMinor = 9900))
        assertEquals(BigInteger.valueOf(2500), total("2026-10-30"))
        assertEquals(BigInteger.valueOf(9900), total("2026-11-03"))
        clock.set("2026-10-20"); lifecycle.delete("delete", changed.id, changed.revision)
        assertEquals(BigInteger.valueOf(2500), total("2026-10-30"))
        assertEquals(BigInteger.ZERO, total("2026-11-03"))
    }
    @Test fun confirmedPaymentReplacesItsAssumedCycleEvenWhenCurrencyOrMonthChanges() = runBlocking {
        clock.set("2026-10-03")
        val item = records.create("create", monthly("2026-10-03"))
        records.recordPayment("paid", item.id, 1, "cycle:0", 10000, "USD", day("2026-09-30"))
        val value = spending.summary(day("2026-10-30"))
        assertEquals(BigInteger.ZERO, value.current.cnyMinor)
        assertEquals(BigInteger.valueOf(67138), value.previous.cnyMinor)
        assertEquals(1, value.previous.entries.size)
    }
    @Test fun initialDateCorrectionMovesExactlyOneChargeAndPreservesRenewalRuleAndIdempotency() = runBlocking {
        val item = records.create("create", monthly())
        spending.correctInitialDate("correct", item.id, 1, day("2026-09-29"), day("2026-10-30"))
        spending.correctInitialDate("correct", item.id, 1, day("2026-09-29"), day("2026-10-30"))
        assertTrue(runCatching { spending.correctInitialDate("correct", item.id, 1, day("2026-09-28"), day("2026-10-30")) }.isFailure)
        assertEquals(BigInteger.ZERO, total("2026-10-30"))
        assertEquals(BigInteger.valueOf(2500), spending.summary(day("2026-10-30")).previous.cnyMinor)
        assertEquals(item.anchorDate, records.get(item.id)!!.anchorDate)
        assertEquals(item.nextBillingDate, records.get(item.id)!!.nextBillingDate)
        assertEquals(1, records.payments(item.id).size)
        spending.correctInitialDate("again", item.id, 2, day("2026-10-02"), day("2026-10-30"))
        assertEquals(1, records.payments(item.id).size)
        assertEquals(day("2026-10-02"), spending.initial(item.id).date)
        assertEquals(BigInteger.valueOf(2500), total("2026-10-30"))
        assertEquals(BigInteger.valueOf(2500), total("2026-11-03"))
    }
    @Test fun invalidOrStaleDateCorrectionLeavesAllRecordsUntouched() = runBlocking {
        val item = records.create("create", annual())
        assertTrue(runCatching { spending.correctInitialDate("future", item.id, 1, day("2026-11-01"), day("2026-10-30")) }.isFailure)
        assertTrue(runCatching { spending.correctInitialDate("stale", item.id, 0, day("2026-10-01"), day("2026-10-30")) }.isFailure)
        assertEquals(item, records.get(item.id)); assertTrue(records.payments(item.id).isEmpty())
        assertNull(db.subscriptions().operation("future")); assertNull(db.subscriptions().operation("stale"))
    }
    @Test fun explicitTermRenewalAddsOnePurchaseAndLifetimeSubscriptionOnlyCountsCreationMonth() = runBlocking {
        records.create("lifetime", SubscriptionValues("永久许可", 900, "CNY", SubscriptionCycleUnit.NONE, 0))
        val item = records.create("annual", annual("2026-12-01", amount = 10000))
        lifecycle.renewTerm("renew", item.id, 1, day("2027-12-01"))
        assertEquals(BigInteger.valueOf(10900), total("2026-10-30"))
        assertEquals(BigInteger.ZERO, total("2026-11-30"))
    }
    @Test fun monthEndAndLeapYearInferenceUseCalendarDatesAndRepeatedReadNeverWrites() = runBlocking {
        val monthly = records.create("month-end", monthly("2026-11-30").copy(anchorAtMonthEnd = true))
        assertEquals(day("2026-10-31"), spending.initial(monthly.id).date)
        val yearly = records.create("leap", annual("2028-02-29"))
        assertEquals(day("2027-02-28"), spending.initial(yearly.id).date)
        repeat(3) { spending.summary(day("2026-10-30")) }
        assertEquals(1, records.history(monthly.id).size); assertEquals(1, records.history(yearly.id).size)
        assertTrue(records.payments(monthly.id).isEmpty())
    }
    @Test fun largeMixedCurrencyTotalsNeverOverflowLong() = runBlocking {
        records.create("a", monthly(amount = Long.MAX_VALUE, currency = "USD"))
        records.create("b", monthly(amount = Long.MAX_VALUE, currency = "USD"))
        assertEquals(SubscriptionExchangeRates.cnyMinor(Long.MAX_VALUE, "USD")!! * BigInteger.TWO, total("2026-10-30"))
    }
    private class MutableClock(value: String) : Clock() {
        private var instant = LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
        fun set(value: String) { instant = LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant() }
        override fun instant() = instant
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
