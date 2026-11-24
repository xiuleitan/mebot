package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.home.HomeEventStore
import com.txl.mebot.data.home.RoomSubscriptionHomeBridge
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.time.*
import kotlinx.coroutines.flow.first
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
class SubscriptionReminderEngineTest {
    private lateinit var context: Context
    private lateinit var db: SubscriptionReminderTestDatabase
    private lateinit var storage: SubscriptionStorage
    private lateinit var reminders: SubscriptionReminderStorage
    private lateinit var records: SubscriptionRepository
    private lateinit var preferences: SubscriptionReminderSettingsRepository
    private lateinit var lifecycle: SubscriptionLifecycle
    private val clock = TestClock("2026-10-27T00:30:00Z")
    private var currentZone: ZoneId = ZoneOffset.UTC

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(SubscriptionReminderTestDatabase.TEST_FILE)
        db = SubscriptionReminderTestDatabase.create(context)
        storage = db.storage()
        reminders = db.reminderStorage()
        records = SubscriptionRepository(storage, clock)
        preferences = SubscriptionReminderSettingsRepository(reminders, clock)
        lifecycle = SubscriptionLifecycle(storage, records, clock) { currentZone }
    }

    @After fun teardown() {
        db.close()
        context.deleteDatabase(SubscriptionReminderTestDatabase.TEST_FILE)
    }

    private fun engine() = SubscriptionReminderEngine(
        reminders, lifecycle, SubscriptionStatisticsRepository(storage) { currentZone }, preferences,
        RoomSubscriptionHomeBridge(db), clock,
    ) { currentZone }

    private fun date(value: String) = LocalDate.parse(value)
    private fun values(target: String = "2026-10-30", unit: SubscriptionCycleUnit = SubscriptionCycleUnit.MONTH) =
        SubscriptionValues("云盘会员", 2500, "CNY", unit, 1, anchorDate = date(target), nextBillingDate = date(target), autoRenew = true)
    private suspend fun plan(id: String) = requireNotNull(reminders.reminders.plan(id))
    private suspend fun item(id: String) = requireNotNull(records.get(id))
    private suspend fun event(plan: SubscriptionReminderPlanEntity) =
        requireNotNull(db.home().event(HomeEventStore.subscriptionId(plan.subscriptionId, plan.cycleId)))

    @Test fun homeShowsReminderDateBeforeAnyCutoffAndRestartsDoNotCreateDelivery() = runBlocking {
        val sub = records.create("create", values())
        engine().reconcile()
        val first = plan(sub.id)
        assertEquals(date("2026-10-27"), event(first).eventDate)
        assertEquals(SubscriptionReminderPlanState.PENDING, first.state)
        clock.set("2026-10-27T09:03:00Z")
        engine().reconcile()
        assertEquals(first, plan(sub.id))
        assertNull(reminders.reminders.delivery(sub.id, first.cycleId))
        assertTrue(records.payments(sub.id).isEmpty())
    }

    @Test fun pastDeliveryAndHomeCompletionRemainAfterTogglingSettingsAndRescheduling() = runBlocking {
        val sub = records.create("create", values("2026-10-31"))
        engine().reconcile()
        val first = plan(sub.id)
        val original = event(first)
        val completion = clock.millis()
        db.home().updateEvent(original.copy(completedAt = completion, revision = original.revision + 1))
        val receipt = SubscriptionReminderDeliveryEntity(
            sub.id, first.cycleId, SubscriptionReminderDeliveryState.DELIVERED,
            "legacy", first.generation, first.targetType, first.targetDate, completion,
            deliveredAt = completion, updatedAt = completion,
        )
        reminders.reminders.insertDelivery(receipt)
        preferences.save(sub.id, 0, false, 540)
        engine().reconcile()
        assertNull(reminders.reminders.plan(sub.id))
        val old = item(sub.id)
        records.update("move", sub.id, old.revision, SubscriptionValues.from(old).copy(
            anchorDate = date("2026-11-01"), nextBillingDate = date("2026-11-01"),
        ))
        preferences.save(sub.id, 1, true, 600)
        engine().reconcile()
        val revised = plan(sub.id)
        assertEquals(first.cycleId, revised.cycleId)
        assertEquals(SubscriptionReminderPlanState.HANDLED, revised.state)
        assertEquals(completion, event(revised).completedAt)
        assertEquals(original.eventDate, event(revised).eventDate)
        assertEquals(receipt, reminders.reminders.delivery(sub.id, revised.cycleId))
        assertTrue(records.payments(sub.id).isEmpty())
    }

    @Test fun disablingAutoRenewCancelsOpenHomeReminderAndKeepsSubscription() = runBlocking {
        val sub = records.create("create", values())
        engine().reconcile()
        val old = plan(sub.id)
        lifecycle.stopRenewal("stop", sub.id, item(sub.id).revision, date("2026-10-30"))
        engine().reconcile()
        assertNull(reminders.reminders.plan(sub.id))
        assertTrue(event(old).cancelled)
        assertFalse(item(sub.id).autoRenew)
        lifecycle.resumeRenewal("resume", sub.id, item(sub.id).revision, date("2026-10-30"))
        engine().reconcile()
        assertFalse(event(plan(sub.id)).cancelled)
    }

    @Test fun dailySubscriptionsRollToTheNextThreeDayAdvanceCase() = runBlocking {
        val sub = records.create("create", values("2026-10-01", SubscriptionCycleUnit.DAY))
        engine().reconcile()
        val first = plan(sub.id)
        assertEquals(date("2026-10-30"), first.targetDate)
        assertEquals(date("2026-10-27"), event(first).eventDate)
        clock.set("2026-10-28T00:00:00Z")
        engine().reconcile()
        val next = plan(sub.id)
        assertEquals(date("2026-10-31"), next.targetDate)
        assertEquals(date("2026-10-28"), event(next).eventDate)
        assertNotEquals(first.cycleId, next.cycleId)
    }

    @Test fun expiredNonRenewingTermDoesNotCreateHomeReminderAndRetainsHistory() = runBlocking {
        val sub = records.create("create", values().copy(autoRenew = false, nextBillingDate = null, validUntil = date("2026-10-30")))
        clock.set("2026-10-31T10:00:00Z")
        engine().reconcile()
        assertNull(reminders.reminders.plan(sub.id))
        assertEquals(1, records.history(sub.id).size)
        assertTrue(db.home().observeEvents(date("2026-10-27"), date("2026-10-31")).first().isEmpty())
    }

    private class TestClock(value: String) : Clock() {
        private var current = Instant.parse(value)
        fun set(value: String) { current = Instant.parse(value) }
        override fun instant() = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
    }
}
