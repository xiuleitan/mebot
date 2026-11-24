package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.subscriptions.SubscriptionsViewModel
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionBillingControllerTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var model: SubscriptionsViewModel
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var handle: SavedStateHandle
    private lateinit var item: SubscriptionEntity
    private val today = LocalDate.now()
    private val billing get() = model.billing
    @Before fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app)
        item = container.subscriptions.create("create", SubscriptionValues("账单云盘", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
            anchorDate = today, nextBillingDate = today, autoRenew = true))
        createModel()
    }
    private fun createModel(state: SavedStateHandle = SavedStateHandle()) {
        handle = state
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SubscriptionsViewModel(app, state, container) as T
        }
        model = ViewModelProvider(activity!!.get(), factory)[SubscriptionsViewModel::class.java]
        await { !model.state.value.loading && !model.state.value.spendingLoading }
    }
    private fun destroy() { activity?.pause()?.stop()?.destroy(); activity = null; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40)) }
    @After fun tearDown() { destroy(); container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME) }
    private fun ledger() { billing.open(item.id); await { !billing.state.value.loading && billing.state.value.data != null } }
    private fun extra() { billing.openPayment(); await { billing.state.value.editor != null && !billing.state.value.busy } }
    private fun closeEditor() = runBlocking { assertTrue(billing.flush()); billing.dismissEditor(); await { !billing.state.value.loading } }
    private fun save() { billing.save(); await { !billing.state.value.busy && (billing.state.value.saved || billing.state.value.editorError != null) } }

    @Test fun rawPaymentDraftSurvivesRestartAndPreservesOtherEditorAndChatFields() = runBlocking {
        val session = container.subscriptionConversations.forSubscription(item.id)
        var draft = container.database.subscriptionConversations().draft(session.id)!!
        container.subscriptionConversations.saveDraft(session.id, "尚未发送的聊天", JSONObject().put("manualEditor", JSONObject().put("notes", "订阅编辑草稿")).toString(), draft.revision)
        ledger(); extra()
        val token = billing.state.value.editor!!.requestId
        repeat(30) { index -> billing.change { it.copy(amount = "12.", notes = "未完成金额 $index") } }
        closeEditor(); destroy(); createModel(); ledger(); extra()
        assertEquals(token, billing.state.value.editor!!.requestId)
        assertEquals("12.", billing.state.value.editor!!.amount)
        assertEquals("未完成金额 29", billing.state.value.editor!!.notes)
        draft = container.database.subscriptionConversations().draft(session.id)!!
        assertEquals("尚未发送的聊天", draft.inputText)
        assertEquals("订阅编辑草稿", JSONObject(draft.fieldsJson).getJSONObject("manualEditor").getString("notes"))
        billing.change { it.copy(amount = "12.50") }
        billing.save(); billing.save(); save()
        assertTrue(billing.state.value.saved)
        assertEquals(1, container.subscriptions.payments(item.id).size)
        assertEquals(1250L, container.subscriptions.payments(item.id).single().amountMinor)
        assertEquals(token, container.subscriptions.payments(item.id).single().operationId)
    }
    @Test fun separateCycleAndExtraDraftsCannotOverwriteEachOther() {
        ledger()
        val plan = billing.state.value.data!!.plans.single()
        billing.openPayment(plan); await { billing.state.value.editor != null && !billing.state.value.busy }
        billing.change { it.copy(amount = "20.00", notes = "周期草稿") }; closeEditor()
        extra(); billing.change { it.copy(amount = "3.00", notes = "额外扣费草稿") }; closeEditor()
        billing.openPayment(plan); await { billing.state.value.editor != null && !billing.state.value.busy }
        assertEquals("周期草稿", billing.state.value.editor!!.notes)
        assertEquals("20.00", billing.state.value.editor!!.amount)
        assertNotNull(billing.state.value.editor!!.plan)
        closeEditor(); extra()
        assertEquals("额外扣费草稿", billing.state.value.editor!!.notes)
        assertEquals("3.00", billing.state.value.editor!!.amount)
        assertNull(billing.state.value.editor!!.plan)
    }
    @Test fun failedSaveAndOptimisticConflictRetainInputUntilExplicitRefresh() = runBlocking {
        ledger(); extra()
        billing.change { it.copy(date = today.plusDays(1).toString(), notes = "保留内容") }; save()
        assertFalse(billing.state.value.saved)
        assertTrue(container.subscriptions.payments(item.id).isEmpty())
        assertEquals("保留内容", billing.state.value.editor!!.notes)
        billing.change { it.copy(date = today.toString(), amount = "18.50") }
        container.subscriptions.update("external", item.id, 1, SubscriptionValues.from(item).copy(notes = "外部更新"))
        await { model.state.value.records.single().revision == 2L }
        save(); assertTrue(billing.state.value.conflict)
        assertEquals("18.50", billing.state.value.editor!!.amount)
        billing.reloadEditor(); await { !billing.state.value.busy && !billing.state.value.conflict }
        assertEquals(2L, billing.state.value.editor!!.expectedRevision)
        assertEquals("18.50", billing.state.value.editor!!.amount)
        assertEquals("保留内容", billing.state.value.editor!!.notes)
        save(); assertTrue(billing.state.value.saved)
        assertEquals(1850L, container.subscriptions.payments(item.id).single().amountMinor)
    }
    @Test fun committedButUnclearedDraftIsRecoveredByOperationReceiptWithoutDuplicatePayment() = runBlocking {
        ledger(); extra()
        val editor = billing.state.value.editor!!
        container.subscriptions.recordPayment(editor.requestId, item.id, editor.expectedRevision, "manual:${editor.requestId}",
            2500, "CNY", today)
        closeEditor(); extraOrAlreadySaved()
        assertNull(billing.state.value.editor)
        assertEquals("这笔扣费已保存", billing.state.value.notice)
        assertEquals(1, container.subscriptions.payments(item.id).size)
        val root = JSONObject(container.database.subscriptionConversations().draft(editor.conversationId)!!.fieldsJson)
        assertFalse(root.has("paymentEditors"))
        extra()
        assertNotEquals(editor.requestId, billing.state.value.editor!!.requestId)
    }
    private fun extraOrAlreadySaved() { billing.openPayment(); await { !billing.state.value.busy && (billing.state.value.editor != null || billing.state.value.notice != null) } }
    @Test fun confirmedCycleCorrectionVoidAndReconfirmationDriveMainStatistics() = runBlocking {
        ledger()
        billing.openPayment(billing.state.value.data!!.plans.single())
        await { billing.state.value.editor != null && !billing.state.value.busy }
        billing.change { it.copy(amount = "20.00") }; save()
        assertTrue(billing.state.value.saved)
        await { model.state.value.spending?.current?.entries?.count { it.confirmed } == 1 && !model.state.value.spendingLoading }
        assertEquals(1, model.state.value.spending!!.current.entries.size)
        assertEquals(java.math.BigInteger.valueOf(2000), model.state.value.spending!!.current.cnyMinor)
        billing.dismissEditor(); await { !billing.state.value.loading }
        val payment = billing.state.value.data!!.payments.single().payment
        billing.openPayment(paymentId = payment.id); await { billing.state.value.editor != null && !billing.state.value.busy }
        billing.change { it.copy(amount = "19.00", date = today.minusMonths(1).toString()) }; save()
        await { model.state.value.spending?.previous?.entries?.count { it.confirmed } == 1 && !model.state.value.spendingLoading }
        assertEquals(java.math.BigInteger.valueOf(1900), model.state.value.spending!!.previous.cnyMinor)
        assertEquals(java.math.BigInteger.ZERO, model.state.value.spending!!.current.cnyMinor)
        val change = container.subscriptions.history(item.id).first()
        val correction = JSONObject(requireNotNull(container.database.subscriptions().operation(change.operationId)).payloadJson)
        assertEquals(2000L, correction.getJSONObject("before").getLong("amountMinor"))
        assertEquals(1900L, correction.getJSONObject("after").getLong("amountMinor"))
        billing.dismissEditor(); await { !billing.state.value.loading }
        billing.moveMonth(-1); await { !billing.state.value.loading && billing.state.value.data?.month == YearMonth.from(today).minusMonths(1) }
        billing.requestVoid(billing.state.value.data!!.payments.single().payment); billing.void()
        await { !billing.state.value.busy && billing.state.value.voidIntent == null && !billing.state.value.loading }
        await { model.state.value.spending?.let { value -> value.current.entries.none { it.confirmed } && value.previous.entries.none { it.confirmed } } == true && !model.state.value.spendingLoading }
        assertEquals(java.math.BigInteger.valueOf(2500), model.state.value.spending!!.current.cnyMinor)
        assertTrue(billing.state.value.data!!.payments.single().voided)
        billing.openPayment(paymentId = payment.id); await { billing.state.value.editor != null && !billing.state.value.busy }
        save(); assertTrue(billing.state.value.saved)
        assertEquals(1, container.subscriptions.payments(item.id).size)
        await { model.state.value.spending?.previous?.entries?.count { it.confirmed } == 1 && !model.state.value.spendingLoading }
    }
    @Test fun savedStateRestoresLatestRawPaymentInputAfterDraftWriteFailure() = runBlocking {
        ledger(); extra()
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_payment_draft
            BEFORE UPDATE ON subscription_drafts BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        billing.change { it.copy(amount = "17.", notes = "中断恢复内容") }
        await { billing.state.value.editorError != null }
        val backup = handle.keys().associateWith { handle.get<Any>(it) }
        destroy()
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_payment_draft")
        createModel(SavedStateHandle(backup)); ledger(); extra()
        assertEquals("17.", billing.state.value.editor!!.amount)
        assertEquals("中断恢复内容", billing.state.value.editor!!.notes)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待扣费状态超时：${billing.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
