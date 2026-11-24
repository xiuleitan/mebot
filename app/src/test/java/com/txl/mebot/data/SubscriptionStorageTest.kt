package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
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
class SubscriptionStorageTest {
    private lateinit var context: Context
    private lateinit var database: SubscriptionStorageTestDatabase
    private lateinit var records: SubscriptionRepository
    private lateinit var conversations: SubscriptionConversationRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-26T08:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2026, 10, 31)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE)
        database = SubscriptionStorageTestDatabase.create(context)
        connect()
    }
    private fun connect() {
        records = SubscriptionRepository(database.storage(), clock)
        conversations = SubscriptionConversationRepository(database.storage(), clock)
    }
    private fun reopen() { database.close(); database = SubscriptionStorageTestDatabase.create(context); connect() }
    @After fun tearDown() { database.close(); context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE) }

    private fun values() = SubscriptionValues("云盘会员", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
        anchorDate = date, anchorAtMonthEnd = true, nextBillingDate = date, autoRenew = true,
        account = "个人账号", paymentMethod = "微信支付", notes = "备份照片")
    private suspend fun input(id: String, text: String): SubscriptionDraftEntity {
        val old = requireNotNull(database.subscriptionConversations().draft(id))
        return conversations.saveDraft(id, text, old.fieldsJson, old.revision)
    }

    @Test fun emptyStorageInventsNoSubscriptionPaymentConversationOrHistory() = runBlocking {
        assertTrue(records.observeOngoing().first().isEmpty())
        assertTrue(database.subscriptions().all().isEmpty())
        assertTrue(database.subscriptionConversations().conversations(50).isEmpty())
        assertTrue(database.subscriptions().paymentsBetween(date.minusYears(1), date.plusYears(1)).isEmpty())
        // 只有测试夹具使用版本 1；应用主库另有正式 14→15 迁移测试。
        assertEquals(1, database.openHelper.readableDatabase.version)
    }

    @Test fun createBindingOperationSnapshotAndUnsentDraftSurviveReopen() = runBlocking {
        val a = conversations.newConversation("a")
        val b = conversations.newConversation("b")
        val draft = input(a.id, "请登记云盘")
        val message = conversations.sendUserMessage(a.id, "message-a", draft.inputText, emptyList(), draft.revision)
        input(b.id, "另一个订阅尚未发出")
        val saved = records.create("create-a", values(), a.id, message.id)
        reopen()
        assertEquals(saved, records.get(saved.id))
        assertEquals(saved.id, database.subscriptionConversations().conversation(a.id)?.subscriptionId)
        assertEquals("另一个订阅尚未发出", database.subscriptionConversations().draft(b.id)?.inputText)
        assertEquals(listOf(message), conversations.messages(a.id))
        assertTrue(conversations.messages(b.id).isEmpty())
        assertEquals(SubscriptionOperationState.APPLIED, database.subscriptions().operation("create-a")?.state)
        assertEquals(saved.id, JSONObject(records.history(saved.id).single().afterJson).getString("id"))
        assertEquals(saved.anchorDate, records.get(saved.id)?.anchorDate)
        assertTrue(records.get(saved.id)!!.anchorAtMonthEnd)
        assertTrue(records.payments(saved.id).isEmpty())
    }

    @Test fun repeatedCreationAndConfirmationAreIdempotentAndRejectChangedIntent() = runBlocking {
        val session = conversations.newConversation()
        val saved = records.create("create", values(), session.id)
        assertEquals(saved, records.create("create", values(), session.id))
        assertEquals(saved, records.create("create", values().copy(title = " 云盘会员 ", currencyCode = "cny"), session.id))
        assertTrue(runCatching { records.create("create", values().copy(amountMinor = 9900), session.id) }.isFailure)
        assertEquals(1, database.subscriptions().all().size)
        assertEquals(1, records.history(saved.id).size)
    }

    @Test fun oneConversationCannotCreateAnotherSubscriptionOrMutateAnotherOnesState() = runBlocking {
        val a = conversations.newConversation("a")
        val b = conversations.newConversation("b")
        val first = records.create("create-a", values(), a.id)
        val second = records.create("create-b", values(), b.id)
        assertTrue(runCatching { records.create("another", values(), a.id) }.isFailure)
        assertTrue(runCatching { records.update("wrong", second.id, 1, values().copy(title = "不应写入"), a.id) }.isFailure)
        assertNull(database.subscriptions().operation("wrong"))
        assertNull(database.subscriptions().operation("another"))
        assertEquals(first, records.get(first.id))
        assertEquals(second, records.get(second.id))
        assertEquals(2, database.subscriptions().all().size)
    }

    @Test fun updatePreservesHistoricalPricesAndDatesAndUsesOptimisticRevision() = runBlocking {
        val original = records.create("create", values())
        val changed = values().copy(amountMinor = 6800, nextBillingDate = date.plusMonths(1))
        val second = records.update("update", original.id, 1, changed)
        assertEquals(2, second.revision)
        assertEquals(second, records.update("update", original.id, 1, changed))
        assertTrue(runCatching { records.update("stale", original.id, 1, values()) }.isFailure)
        assertNull(database.subscriptions().operation("stale"))
        val history = records.history(original.id)
        assertEquals(listOf(2L, 1L), history.map { it.revision })
        assertEquals(2500, JSONObject(history[0].beforeJson!!).getLong("amountMinor"))
        assertEquals(6800, JSONObject(history[0].afterJson).getLong("amountMinor"))
        assertEquals(2500, JSONObject(history[1].afterJson).getLong("amountMinor"))
        assertEquals(date.toString(), JSONObject(history[1].afterJson).getString("nextBillingDate"))
    }

    @Test fun concurrentUpdatesHaveOneWinnerWithoutLostHistory() = runBlocking {
        val original = records.create("create", values())
        val results = (1..2).map { number -> async {
            runCatching { records.update("update-$number", original.id, 1, values().copy(amountMinor = number * 1000L)) }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(2, records.get(original.id)!!.revision)
        assertEquals(2, records.history(original.id).size)
    }

    @Test fun injectedHistoryFailureRollsBackSubscriptionBindingAndOperationTogether() = runBlocking {
        val session = conversations.newConversation("a")
        input(session.id, "保存失败也保留这段输入")
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_subscription_history
            BEFORE INSERT ON subscription_changes BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { records.create("create", values(), session.id) }.isFailure)
        assertTrue(database.subscriptions().all().isEmpty())
        assertNull(database.subscriptions().operation("create"))
        assertNull(database.subscriptionConversations().conversation(session.id)?.subscriptionId)
        assertEquals("保存失败也保留这段输入", database.subscriptionConversations().draft(session.id)?.inputText)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_subscription_history")
        assertNotNull(records.create("create", values(), session.id))
        assertEquals(1, database.subscriptions().all().size)
    }

    @Test fun pendingConfirmationSurvivesRestartAndCanBeAppliedExactlyOnce() = runBlocking {
        val session = conversations.newConversation()
        val payload = values().validated().json().toString()
        records.prepare("pending", SubscriptionOperationKind.CREATE, payload, session.id, null, null)
        reopen()
        assertEquals(1, database.subscriptions().observePendingOperations(session.id).first().size)
        val saved = records.create("pending", values(), session.id)
        assertEquals(saved, records.create("pending", values(), session.id))
        assertTrue(database.subscriptions().observePendingOperations(session.id).first().isEmpty())
        assertEquals(1, records.history(saved.id).size)
    }

    @Test fun failedConfirmationPreservesItsPendingIntentAndCanResumeAfterReopen() = runBlocking {
        val session = conversations.newConversation()
        val pending = records.prepare("pending", SubscriptionOperationKind.CREATE, values().validated().json().toString(), session.id, null, null)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_confirmation
            BEFORE INSERT ON subscription_changes BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { records.create("pending", values(), session.id) }.isFailure)
        assertEquals(pending, database.subscriptions().operation("pending"))
        assertNull(database.subscriptionConversations().conversation(session.id)?.subscriptionId)
        assertTrue(database.subscriptions().all().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_confirmation")
        reopen()
        val saved = records.create("pending", values(), session.id)
        assertEquals(1, records.history(saved.id).size)
        assertEquals(SubscriptionOperationState.APPLIED, database.subscriptions().operation("pending")?.state)
    }

    @Test fun largeMinorUnitAmountsSurviveSqliteJsonHistoryAndReopenWithoutRounding() = runBlocking {
        val exactAmount = 9_007_199_254_740_993L // 超过 Double 可精确表示的整数范围。
        val saved = records.create("large", values().copy(amountMinor = exactAmount))
        val payment = records.recordPayment("large-payment", saved.id, 1, "large-event", exactAmount, "CNY", date)
        reopen()
        assertEquals(exactAmount, records.get(saved.id)!!.amountMinor)
        assertEquals(exactAmount, JSONObject(records.history(saved.id).last().afterJson).getLong("amountMinor"))
        assertEquals(payment, records.payments(saved.id).single())
    }

    @Test fun actualPaymentsKeepTheirOwnMoneyDateAndCurrencyAfterRepricingAndArchiving() = runBlocking {
        val saved = records.create("create", values())
        val paid = records.recordPayment("payment", saved.id, 1, "2026-09-cycle", 18700, "USD", date.minusMonths(1), "已核对账单")
        val archived = records.update("archive", saved.id, 2,
            values().copy(amountMinor = 9900, currencyCode = "CNY", status = SubscriptionStatus.ARCHIVED, autoRenew = false))
        reopen()
        assertTrue(records.observeOngoing().first().isEmpty())
        assertEquals(archived, records.get(saved.id))
        assertEquals(listOf(paid), records.payments(saved.id))
        assertEquals(18700, records.payments(saved.id).single().amountMinor)
        assertEquals("USD", records.payments(saved.id).single().currencyCode)
        assertEquals(listOf(paid), database.subscriptions().paymentsBetween(date.minusMonths(2), date))
        assertEquals(3, records.history(saved.id).size)
    }

    @Test fun duplicatePaymentRequestOrEventCannotDoubleCountOrLeavePartialState() = runBlocking {
        val item = records.create("create", values())
        val paid = records.recordPayment("payment", item.id, 1, "cycle", 2500, "CNY", date)
        assertEquals(paid, records.recordPayment("payment", item.id, 1, "cycle", 2500, "CNY", date))
        assertTrue(runCatching { records.recordPayment("payment", item.id, 1, "cycle", 5000, "CNY", date) }.isFailure)
        assertTrue(runCatching { records.recordPayment("second", item.id, 2, "cycle", 2500, "CNY", date) }.isFailure)
        assertNull(database.subscriptions().operation("second"))
        assertEquals(2, records.get(item.id)!!.revision)
        assertEquals(listOf(paid), records.payments(item.id))
        assertEquals(2, records.history(item.id).size)
    }

    @Test fun newConversationsNeverEraseOldUnfinishedChatsOrDraftsAndManualSubscriptionReusesItsChat() = runBlocking {
        val a = conversations.newConversation()
        val draft = input(a.id, "未完成的订阅")
        conversations.sendUserMessage(a.id, "user", draft.inputText, emptyList(), draft.revision)
        input(a.id, "还没发送的补充")
        val b = conversations.newConversation()
        assertNotEquals(a.id, b.id)
        val saved = records.create("manual", values())
        val bound = conversations.forSubscription(saved.id)
        assertEquals(bound, conversations.forSubscription(saved.id))
        assertNotEquals(a.id, bound.id)
        reopen()
        assertEquals("还没发送的补充", database.subscriptionConversations().draft(a.id)?.inputText)
        assertEquals(1, conversations.messages(a.id).size)
        assertTrue(conversations.messages(b.id).isEmpty())
        assertEquals(3, database.subscriptionConversations().conversations(50).size)
    }

    @Test fun draftRevisionsProtectNewInputAndFailedWritesPreserveOldFields() = runBlocking {
        val session = conversations.newConversation()
        val first = conversations.saveDraft(session.id, "第一条", "{\"title\":\"云盘\",\"amountMinor\":2500}", 0)
        assertTrue(runCatching { conversations.saveDraft(session.id, "覆盖", "{}", 0) }.isFailure)
        assertTrue(runCatching { conversations.saveDraft(session.id, "覆盖", "[1,2]", first.revision) }.isFailure)
        assertEquals(first, database.subscriptionConversations().draft(session.id))
        reopen()
        assertEquals(first, database.subscriptionConversations().draft(session.id))
    }

    @Test fun repeatedMessageDoesNotClearLaterInputOrAppendDuplicateReply() = runBlocking {
        val session = conversations.newConversation()
        val first = input(session.id, "第一条")
        val sent = conversations.sendUserMessage(session.id, "message", first.inputText, emptyList(), first.revision)
        val later = input(session.id, "后来输入的内容")
        assertEquals(sent, conversations.sendUserMessage(session.id, "message", first.inputText, emptyList(), first.revision))
        val reply = conversations.appendAssistantMessage(session.id, "reply", "需要确认一下价格")
        assertEquals(reply, conversations.appendAssistantMessage(session.id, "reply", "需要确认一下价格"))
        assertEquals(later, database.subscriptionConversations().draft(session.id))
        assertEquals(listOf(sent, reply), conversations.messages(session.id))
    }

    @Test fun messageAndOperationSourceAreBoundToTheOriginalConversation() = runBlocking {
        val a = conversations.newConversation("a")
        val b = conversations.newConversation("b")
        val draft = input(a.id, "新建")
        conversations.sendUserMessage(a.id, "message", draft.inputText, emptyList(), draft.revision)
        assertTrue(runCatching { records.create("wrong", values(), b.id, "message") }.isFailure)
        assertTrue(runCatching { conversations.sendUserMessage(b.id, "message", "新建", emptyList(), 0) }.isFailure)
        // 即使绕过 Repository，复合外键也拒绝把 a 的消息挂到 b 的操作。
        assertTrue(runCatching { database.subscriptions().insertOperation(SubscriptionOperationEntity("bad-fk", b.id, null,
            "message", SubscriptionOperationKind.CREATE, SubscriptionOperationState.PENDING, "{}", null,
            createdAt = 1, updatedAt = 1)) }.isFailure)
        assertTrue(database.subscriptions().all().isEmpty())
    }

    @Test fun uniqueBindingAndForeignKeysRejectOrphanOrSecondDedicatedChat() = runBlocking {
        val item = records.create("manual", values())
        val first = conversations.forSubscription(item.id)
        val second = conversations.newConversation()
        assertTrue(runCatching { database.subscriptionConversations().bind(second.id, item.id, 1) }.isFailure)
        assertEquals(0, database.subscriptionConversations().bind(first.id, item.id, 1))
        assertTrue(runCatching { database.openHelper.writableDatabase.execSQL("DELETE FROM subscriptions WHERE id = ?", arrayOf(item.id)) }.isFailure)
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun historyAndMessagesArePagedAndOrderedWithoutMixingSubscriptions() = runBlocking {
        val item = records.create("create", values())
        for (index in 1..5) records.update("update-$index", item.id, index.toLong(), values().copy(notes = "第 $index 次"))
        assertEquals(listOf(4L, 3L), records.history(item.id, 2, 2).map { it.revision })
        val a = conversations.forSubscription(item.id)
        val b = conversations.newConversation()
        for (index in 1..5) conversations.appendAssistantMessage(a.id, "message-$index", "消息 $index")
        conversations.appendAssistantMessage(b.id, "other", "别的会话")
        assertEquals(listOf(2L, 3L), conversations.messages(a.id, 2, 2).map { it.sequence })
        assertEquals(5, conversations.observeMessages(a.id).first().size)
    }

    @Test fun invalidMoneyCurrencyAndCycleCannotWriteAnOperationOrSubscription() = runBlocking {
        val invalid = listOf(values().copy(title = "  "), values().copy(amountMinor = -1), values().copy(currencyCode = "???"),
            values().copy(cycleInterval = 0), values().copy(anchorDate = null), values().copy(nextBillingDate = null),
            values().copy(status = SubscriptionStatus.CANCEL_AT_PERIOD_END), values().copy(nextBillingDate = date.minusDays(1)))
        invalid.forEachIndexed { index, value -> assertTrue(runCatching { records.create("invalid-$index", value) }.isFailure) }
        assertTrue(database.subscriptions().all().isEmpty())
        assertNull(database.subscriptions().operation("invalid-0"))
        val permanent = records.create("permanent", SubscriptionValues("永久许可", 100, "JPY", SubscriptionCycleUnit.NONE, 0))
        assertEquals("JPY", permanent.currencyCode)
        assertNull(permanent.nextBillingDate)
        assertEquals(100, permanent.amountMinor)
    }
}
