package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.subscriptions.SubscriptionsState
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class SubscriptionLifecycleTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var storage: SubscriptionStorage
    private lateinit var records: SubscriptionRepository
    private val jan31 = LocalDate.of(2026, 1, 31)
    private fun life(day: String) = SubscriptionLifecycle(storage, records, Clock.fixed(LocalDate.parse(day).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC), { ZoneOffset.UTC })
    private fun values(next: LocalDate = jan31) = SubscriptionValues("云盘", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
        anchorDate = jan31, nextBillingDate = next, autoRenew = true)
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        storage = SubscriptionStorage(db, db.subscriptions(), db.subscriptionConversations(), db.mediaDao())
        records = SubscriptionRepository(storage)
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }

    @Test fun maintenanceAdvancesDatesFromOriginalAnchorAndNeverInventsPaidHistory() = runBlocking {
        val item = records.create("create", values())
        life("2026-02-01").reconcile()
        assertEquals(LocalDate.of(2026, 2, 28), records.get(item.id)!!.nextBillingDate)
        life("2026-02-01").reconcile()
        assertEquals(2L, records.get(item.id)!!.revision)
        life("2026-03-01").reconcile()
        assertEquals(LocalDate.of(2026, 3, 31), records.get(item.id)!!.nextBillingDate)
        assertEquals(jan31, records.get(item.id)!!.anchorDate)
        assertTrue(records.payments(item.id).isEmpty())
        assertEquals(listOf(SubscriptionOperationKind.ADVANCE, SubscriptionOperationKind.ADVANCE, SubscriptionOperationKind.CREATE), records.history(item.id).map { it.kind })
    }

    @Test fun cancelledTrialKeepsItsOriginalRecordAndEndsOnlyAfterTheValidDay() = runBlocking {
        val next = LocalDate.of(2026, 11, 1)
        val item = records.create("trial", values(next).copy(anchorDate = next))
        val end = LocalDate.of(2026, 10, 31)
        val service = life("2026-10-26")
        val stopped = service.stopRenewal("stop", item.id, 1, end)
        assertEquals(SubscriptionStatus.CANCEL_AT_PERIOD_END, stopped.status)
        assertEquals(stopped, service.stopRenewal("stop", item.id, 1, end))
        assertTrue(runCatching { service.stopRenewal("stop", item.id, 1, end.minusDays(1)) }.isFailure)
        life("2026-10-31").reconcile()
        assertEquals(1, SubscriptionsState(records = listOf(records.get(item.id)!!), today = LocalDate.of(2026, 10, 31)).ongoing.size)
        life("2026-11-01").reconcile()
        assertEquals(SubscriptionStatus.CANCEL_AT_PERIOD_END, records.get(item.id)!!.status)
        assertTrue(SubscriptionsState(records = listOf(records.get(item.id)!!), today = LocalDate.of(2026, 11, 1)).ongoing.isEmpty())
        assertFalse(records.history(item.id).any { it.kind == SubscriptionOperationKind.ARCHIVE })
        assertEquals(next, records.get(item.id)!!.nextBillingDate)
        assertTrue(records.payments(item.id).isEmpty())
    }

    @Test fun manualExpiryIsDerivedWithoutArchivingAndPermanentLicenseHasNoDates() = runBlocking {
        val manual = records.create("manual", values().copy(autoRenew = false, nextBillingDate = null, validUntil = jan31))
        val permanent = records.create("permanent", SubscriptionValues("永久许可", 100, "JPY", SubscriptionCycleUnit.NONE, 0))
        life("2035-10-01").reconcile()
        assertEquals(manual, records.get(manual.id))
        assertEquals(permanent, records.get(permanent.id))
        assertEquals(listOf(permanent.id), SubscriptionsState(records = listOf(manual, permanent), today = LocalDate.of(2035, 10, 1)).ongoing.map { it.id })
        assertEquals(1, records.history(manual.id).size)
    }

    @Test fun resumedBillingKeeps31AnchorAndDuplicateActionHasOnlyOneHistoryEntry() = runBlocking {
        val service = life("2026-02-01")
        val item = records.create("create", values())
        service.stopRenewal("stop", item.id, 1, LocalDate.of(2026, 2, 28))
        val next = LocalDate.of(2026, 2, 28)
        val resumed = service.resumeRenewal("resume", item.id, 2, next)
        assertEquals(jan31, resumed.anchorDate)
        assertEquals(resumed, service.resumeRenewal("resume", item.id, 2, next))
        life("2026-03-01").reconcile()
        assertEquals(LocalDate.of(2026, 3, 31), records.get(item.id)!!.nextBillingDate)
        assertEquals(4, records.history(item.id).size)
    }

    @Test fun archiveRestoreAndSoftDeletePreserveDedicatedConversationMessagesAndPayments() = runBlocking {
        val service = life("2026-01-01")
        val conversations = SubscriptionConversationRepository(storage)
        val session = conversations.newConversation()
        val item = service.save("create", null, null, values(), session.id)
        conversations.appendAssistantMessage(session.id, "assistant", "原会话记录")
        records.recordPayment("payment", item.id, 1, "event", 2500, "CNY", jan31)
        val deleted = service.delete("delete", item.id, 2)
        assertEquals(deleted, service.delete("delete", item.id, 2))
        assertEquals(SubscriptionStatus.DELETED, deleted.status)
        assertTrue(records.observeOngoing().first().isEmpty())
        val restored = service.save("restore", item.id, 3, values())
        assertEquals(restored, service.save("restore", item.id, 3, values()))
        assertEquals(SubscriptionStatus.ACTIVE, restored.status)
        assertEquals(session.id, conversations.forSubscription(item.id).id)
        assertEquals("原会话记录", conversations.messages(session.id).single().content)
        assertEquals(1, records.payments(item.id).size)
        assertEquals(SubscriptionOperationKind.RESTORE, records.history(item.id).first().kind)
        assertTrue(runCatching { service.save("restore", item.id, 3, values().copy(amountMinor = 3500)) }.isFailure)
    }

    @Test fun repeatedManualSaveBindsItsDraftOnceAndUnrelatedSameTitleRemainsIndependent() = runBlocking {
        val service = life("2026-01-01")
        val session = SubscriptionConversationRepository(storage).newConversation()
        val item = service.save("create", null, null, values(), session.id)
        assertEquals(item, service.save("create", null, null, values(), session.id))
        val other = records.create("other", values())
        val edited = service.save("edit", item.id, 1, values().copy(notes = "只改第一条"))
        assertEquals("只改第一条", edited.notes)
        assertEquals(other, records.get(other.id))
        assertEquals(item.id, db.subscriptionConversations().conversation(session.id)!!.subscriptionId)
        assertEquals(2, db.subscriptions().all().size)
    }

    @Test fun staleActionsAndInvalidDateRulesDoNotChangeTheSubscriptionOrCreateAReceipt() = runBlocking {
        val service = life("2026-02-01")
        val item = records.create("create", values())
        assertTrue(runCatching { service.save("past", null, null, values()) }.isFailure)
        assertTrue(runCatching { service.save("wrong-month-end", null, null,
            values(LocalDate.of(2026, 2, 27)).copy(anchorAtMonthEnd = true)) }.isFailure)
        val updated = records.update("edit", item.id, 1, values().copy(notes = "新版本"))
        assertTrue(runCatching { service.delete("stale", item.id, 1) }.isFailure)
        assertEquals(updated, records.get(item.id))
        assertNull(db.subscriptions().operation("stale"))
        assertNull(db.subscriptions().operation("past"))
    }

    @Test fun failedBulkMaintenanceRollsBackAllAdvancesAndRetryProducesOneResultPerRecord() = runBlocking {
        val first = records.create("first", values())
        val second = records.create("second", values())
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_advance BEFORE INSERT ON subscription_changes
            WHEN NEW.subscriptionId = '${second.id}' AND NEW.kind = 'ADVANCE' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { life("2026-02-01").reconcile() }.isFailure)
        assertEquals(first, records.get(first.id)); assertEquals(second, records.get(second.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_advance")
        life("2026-02-01").reconcile(); life("2026-02-01").reconcile()
        assertEquals(2, records.history(first.id).size); assertEquals(2, records.history(second.id).size)
        assertEquals(LocalDate.of(2026, 2, 28), records.get(second.id)!!.nextBillingDate)
    }

    @Test fun timezoneChangeUsesTheNewLocalDayWithoutRecreatingTheMaintenanceService() = runBlocking {
        val expiry = LocalDate.of(2026, 10, 31)
        val item = records.create("timezone", values().copy(autoRenew = false, nextBillingDate = null, validUntil = expiry))
        var zone: java.time.ZoneId = ZoneOffset.UTC
        val service = SubscriptionLifecycle(storage, records,
            Clock.fixed(java.time.Instant.parse("2026-10-31T23:30:00Z"), ZoneOffset.UTC), { zone })
        service.reconcile()
        assertEquals(SubscriptionStatus.ACTIVE, records.get(item.id)!!.status)
        zone = java.time.ZoneId.of("Asia/Shanghai")
        service.reconcile()
        assertEquals(item, records.get(item.id))
        assertTrue(records.get(item.id)!!.hasExpired(java.time.Instant.parse("2026-10-31T23:30:00Z").atZone(zone).toLocalDate()))
        service.reconcile()
        assertEquals(1, records.history(item.id).size)
    }
}
