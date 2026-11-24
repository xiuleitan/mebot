package com.txl.mebot.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.SubscriptionCycleUnit
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.subscriptions.SubscriptionValues
import com.txl.mebot.ui.subscriptions.SubscriptionsViewModel
import java.time.Duration
import java.time.LocalDate
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** 仅在开发机测试真实 ViewModel 和主库事务，不操作用户手机界面。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionsViewModelTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var model: SubscriptionsViewModel
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var handle: SavedStateHandle

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app)
        createModel()
    }
    private fun createModel(state: SavedStateHandle = SavedStateHandle()) {
        handle = state
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SubscriptionsViewModel(app, state, container) as T
        }
        model = ViewModelProvider(activity!!.get(), factory)[SubscriptionsViewModel::class.java]
        await { !model.state.value.loading }
        assertNull(model.state.value.loadError)
    }
    private fun destroyModel() { activity?.pause()?.stop()?.destroy(); activity = null; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40)) }
    @After fun tearDown() { destroyModel(); container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME) }
    private fun openExisting() = runBlocking {
        val date = LocalDate.now().plusDays(5)
        val item = container.database.subscriptions().all().firstOrNull() ?: container.subscriptions.create("fixture", SubscriptionValues("原云盘", 1000, "CNY", SubscriptionCycleUnit.MONTH, 1,
            anchorDate = date, nextBillingDate = date, autoRenew = true))
        model.openEdit(item); await { model.state.value.editor != null && !model.state.value.busy }
    }
    private fun fill() { model.changeEditor { it.copy(title = "真实云盘", amount = "25.00", next = LocalDate.now().plusDays(5).toString()) } }

    @Test fun rapidTypingAndClosingRestoreTheLatestManualDraftWithoutSavedState() = runBlocking {
        openExisting()
        val id = model.state.value.editor!!.conversationId
        repeat(40) { index -> model.changeEditor { it.copy(title = "新订阅 $index", notes = "尚未发出的备注 $index") } }
        assertTrue(model.flushEditor()); model.dismissEditor()
        destroyModel(); createModel(); openExisting()
        assertEquals(id, model.state.value.editor!!.conversationId)
        assertEquals("新订阅 39", model.state.value.editor!!.title)
        assertEquals("尚未发出的备注 39", model.state.value.editor!!.notes)
        assertEquals("原云盘", container.database.subscriptions().all().single().title)
    }

    @Test fun repeatedEditTapsUpdateOnlyTheExistingCardAndReopeningUsesANewRequest() = runBlocking {
        openExisting(); fill()
        val original = model.state.value.editor!!
        model.saveEditor(); model.saveEditor()
        await { model.state.value.editorSaved && model.state.value.records.size == 1 }
        val record = container.database.subscriptions().all().single()
        assertEquals("真实云盘", record.title)
        assertEquals(2500L, record.amountMinor)
        assertEquals(record.id, container.database.subscriptionConversations().conversation(original.conversationId)!!.subscriptionId)
        assertEquals(2, container.subscriptions.history(record.id).size)
        model.dismissEditor(); openExisting()
        assertEquals(original.conversationId, model.state.value.editor!!.conversationId)
        assertNotEquals(original.requestId, model.state.value.editor!!.requestId)
        assertEquals("真实云盘", model.state.value.editor!!.title)
    }

    @Test fun invalidInputIsPreservedInRoomAcrossModelRecreation() = runBlocking {
        openExisting()
        model.changeEditor { it.copy(title = "订阅待核对", amount = "25.0x") }
        model.saveEditor()
        await { !model.state.value.busy && model.state.value.editorError != null }
        val original = model.state.value.editor!!
        assertEquals("原云盘", container.database.subscriptions().all().single().title)
        destroyModel(); createModel(); openExisting()
        assertEquals(original.conversationId, model.state.value.editor!!.conversationId)
        assertEquals(original.requestId, model.state.value.editor!!.requestId)
        assertEquals("25.0x", model.state.value.editor!!.amount)
    }

    @Test fun writeFailurePreservesInputAndRetryUsesTheSameConfirmationWithoutDuplicateBinding() = runBlocking {
        openExisting(); fill()
        val original = model.state.value.editor!!
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_vm_history
            BEFORE INSERT ON subscription_changes BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        model.saveEditor()
        await { !model.state.value.busy && model.state.value.editorError != null }
        assertEquals(original, model.state.value.editor)
        assertEquals("原云盘", container.database.subscriptions().all().single().title)
        assertNotNull(container.database.subscriptionConversations().conversation(original.conversationId)!!.subscriptionId)
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_vm_history")
        model.saveEditor()
        await { model.state.value.editorSaved && model.state.value.records.size == 1 }
        assertEquals(1, container.database.subscriptions().all().size)
        assertEquals(original.requestId, model.state.value.editor!!.requestId)
    }

    @Test fun concurrentEditShowsConflictAndOnlyExplicitReloadReplacesTheUnsentForm() = runBlocking {
        val date = LocalDate.now().plusDays(5)
        val base = SubscriptionValues("原名称", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
            anchorDate = date, nextBillingDate = date, autoRenew = true)
        val item = container.subscriptions.create("external", base)
        await { model.state.value.records.size == 1 }
        model.openEdit(item); await { model.state.value.editor != null && !model.state.value.busy }
        model.changeEditor { it.copy(title = "本地未保存的名称") }
        container.subscriptions.update("external-update", item.id, 1, base.copy(notes = "其他操作的新版本"))
        await { model.state.value.records.single().revision == 2L }
        model.saveEditor(); await { model.state.value.conflict && !model.state.value.busy }
        assertEquals("本地未保存的名称", model.state.value.editor!!.title)
        model.reloadEditor(); await { !model.state.value.busy && !model.state.value.conflict }
        assertEquals(2L, model.state.value.editor!!.expectedRevision)
        assertEquals("原名称", model.state.value.editor!!.title)
        assertEquals("其他操作的新版本", model.state.value.editor!!.notes)
    }

    @Test fun savedStateBackupRecoversLatestTextWhenDatabaseDraftSaveWasInterrupted() {
        openExisting()
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_vm_draft
            BEFORE UPDATE ON subscription_drafts BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        model.changeEditor { it.copy(title = "数据库失败时的最新输入", amount = "12.34") }
        await { model.state.value.editorError != null }
        val backup = handle.keys().associateWith { handle.get<Any>(it) }
        destroyModel()
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_vm_draft")
        createModel(SavedStateHandle(backup)); openExisting()
        assertEquals("数据库失败时的最新输入", model.state.value.editor!!.title)
        assertEquals("12.34", model.state.value.editor!!.amount)
    }

    @Test fun notificationTargetOpensTheExactLocalSubscriptionAndNeverFallsBackToAnotherCard() = runBlocking {
        val first = container.subscriptions.create("notification-first", SubscriptionValues("第一份永久许可", 1200, "CNY", SubscriptionCycleUnit.NONE, 0))
        val second = container.subscriptions.create("notification-second", SubscriptionValues("第二份永久许可", 2400, "CNY", SubscriptionCycleUnit.NONE, 0))
        model.openFromReminder(second.id)
        await { model.state.value.detail?.id == second.id }
        model.openFromReminder(first.id)
        await { model.state.value.detail?.id == first.id }
        model.dismissDetail()
        model.openFromReminder("missing-local-subscription")
        await { model.state.value.notice != null }
        assertNull(model.state.value.detailId)
        assertTrue(model.state.value.notice!!.contains("已不存在"))
    }

    @Test fun endedNotificationTargetShowsItsOwnHistoryWithoutRestoringTheSubscription() = runBlocking {
        val item = container.subscriptions.create("ended-notification", SubscriptionValues("旧许可", 1200, "CNY", SubscriptionCycleUnit.NONE, 0))
        legacyArchived(container.subscriptions, "archive-notification", item.id, item.revision)
        model.openFromReminder(item.id)
        await { model.state.value.detail?.status == com.txl.mebot.data.local.SubscriptionStatus.ARCHIVED }
        assertEquals(item.id, model.state.value.detailId)
        assertEquals(com.txl.mebot.data.local.SubscriptionStatus.ARCHIVED, container.subscriptions.get(item.id)!!.status)
    }

    @Test fun notificationNavigationRetainsOriginalSubscriptionAndPaymentEditorDrafts() = runBlocking {
        openExisting(); fill()
        val original = container.database.subscriptions().all().single()
        val target = container.subscriptions.create("navigation-target", SubscriptionValues("通知目标", 300, "CNY", SubscriptionCycleUnit.NONE, 0))
        model.openFromReminder(target.id)
        await { model.state.value.detailId == target.id && model.state.value.editor == null }
        model.openEdit(original)
        await { model.state.value.editor != null && !model.state.value.busy }
        assertEquals("真实云盘", model.state.value.editor!!.title)
        assertEquals("25.00", model.state.value.editor!!.amount)
        assertEquals("原云盘", container.subscriptions.get(original.id)!!.title)
        model.dismissEditor()
        model.billing.open(original.id)
        await { model.billing.state.value.data != null && !model.billing.state.value.loading }
        model.billing.openPayment()
        await { model.billing.state.value.editor != null && !model.billing.state.value.busy }
        model.billing.change { it.copy(amount = "16.78", notes = "原订阅的未提交扣费") }
        model.openFromReminder(target.id)
        await { model.billing.state.value.editor == null && !model.billing.state.value.ledgerOpen && model.state.value.detailId == target.id }
        model.billing.open(original.id)
        await { model.billing.state.value.data != null && !model.billing.state.value.loading }
        model.billing.openPayment()
        await { model.billing.state.value.editor != null && !model.billing.state.value.busy }
        assertEquals("16.78", model.billing.state.value.editor!!.amount)
        assertEquals("原订阅的未提交扣费", model.billing.state.value.editor!!.notes)
        assertEquals(original.id, model.billing.state.value.editor!!.subscriptionId)
    }

    @Test fun deletingThroughTheOnlyDetailActionKeepsHistoryAndCannotApplyBeforeConfirmation() = runBlocking {
        val item = container.subscriptions.create("delete", SubscriptionValues("待删除许可", 100, "CNY", SubscriptionCycleUnit.NONE, 0))
        model.requestDelete(item); model.dismissDelete()
        assertEquals(item, container.subscriptions.get(item.id))
        model.requestDelete(item); model.performDelete(); model.performDelete()
        await { !model.state.value.busy && model.state.value.deletion == null && model.state.value.records.any { it.id == item.id && it.status == com.txl.mebot.data.local.SubscriptionStatus.DELETED } }
        assertFalse(model.state.value.ongoing.any { it.id == item.id })
        assertTrue(model.state.value.ended.any { it.id == item.id })
        assertEquals(2, container.subscriptions.history(item.id).size)
    }

    private fun picture(name: String, color: Int): Uri {
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        val file = File(app.filesDir, "media/originals/picker-source/$name").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file, name)
    }

    @Test fun selectedImagesAppendInOrderKeepFirstCoverAndRestoreWithDraftBeforeSaving() = runBlocking {
        openExisting()
        val a = picture("first.png", Color.RED); val b = picture("second.png", Color.GREEN)
        val request = model.state.value.editor!!.requestId
        model.importImages(request, listOf(a, b, a)); await { !model.state.value.busy }
        assertNull(model.state.value.editorError)
        val draft = model.state.value.editor!!
        assertEquals(2, draft.imageAssetIds!!.size); assertEquals(draft.imageAssetIds!!.first(), draft.coverAssetId)
        assertTrue(container.subscriptions.imageIds(draft.targetId!!).isEmpty())
        assertTrue(model.flushEditor()); model.dismissEditor(); destroyModel(); createModel(); openExisting()
        assertEquals(draft, model.state.value.editor)
        model.saveEditor(); await { model.state.value.editorSaved }
        assertEquals(draft.imageAssetIds, container.subscriptions.imageIds(draft.targetId!!))
        assertEquals(draft.coverAssetId, container.subscriptions.get(draft.targetId!!)!!.coverAssetId)
    }

    @Test fun imageAndDraftAssociationRollbackTogetherAndRetryKeepsTheOriginalRequest() = runBlocking {
        openExisting(); val draft = model.state.value.editor!!
        val uri = picture("retry.png", Color.BLUE)
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_image_draft BEFORE UPDATE ON subscription_drafts
            BEGIN SELECT RAISE(ABORT,'fixture failure'); END""")
        model.importImages(draft.requestId, listOf(uri)); await { !model.state.value.busy }
        assertNotNull(model.state.value.editorError); assertEquals(draft, model.state.value.editor)
        assertTrue(container.database.subscriptionConversations().mediaLinks(draft.conversationId).isEmpty())
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_image_draft")
        model.importImages(draft.requestId, listOf(uri)); await { !model.state.value.busy }
        assertNull(model.state.value.editorError); assertEquals(draft.requestId, model.state.value.editor!!.requestId)
        assertEquals(1, model.state.value.editor!!.imageAssetIds!!.size)
    }

    @Test fun stalePickerDoesNotModifyAnotherEditorAndDetailImageChangesPersistImmediately() = runBlocking {
        openExisting(); val old = model.state.value.editor!!
        model.dismissEditor()
        val target = container.subscriptions.create("another", SubscriptionValues("另一个", 10, "CNY", SubscriptionCycleUnit.NONE, 0))
        model.openEdit(target); await { !model.state.value.busy && model.state.value.editor?.targetId == target.id }
        model.importImages(old.requestId, listOf(picture("stale.png", Color.RED)))
        assertFalse(model.state.value.busy); assertTrue(model.state.value.editor!!.imageAssetIds!!.isEmpty())
        model.importImages(model.state.value.editor!!.requestId, listOf(picture("a.png", Color.RED), picture("b.png", Color.GREEN)))
        await { !model.state.value.busy }; model.saveEditor(); await { model.state.value.editorSaved }
        model.dismissEditor(); model.openDetail(target.id)
        await { model.state.value.detail?.revision == 2L }
        val ids = container.subscriptions.imageIds(target.id)
        model.changeImage(model.state.value.detail!!, ids.last(), remove = false)
        await { !model.state.value.busy && model.state.value.detail?.coverAssetId == ids.last() }
        assertEquals(ids.last(), container.subscriptions.get(target.id)!!.coverAssetId)
        model.changeImage(model.state.value.detail!!, ids.last(), remove = true)
        await { !model.state.value.busy && model.state.value.detail?.coverAssetId == ids.first() }
        assertEquals(listOf(ids.first()), container.subscriptions.imageIds(target.id))
        model.changeImage(model.state.value.detail!!, ids.first(), remove = true)
        await { !model.state.value.busy && model.state.value.detail?.coverAssetId == null }
        assertTrue(container.subscriptions.imageIds(target.id).isEmpty())
    }

    @Test fun correctingFirstChargeMovesOnePaymentBetweenMonthsAndRetainsRenewalAnchor() = runBlocking {
        val today = LocalDate.now()
        val next = today.plusMonths(1)
        val item = container.subscriptions.create("annual", SubscriptionValues("年费", 120000, "USD", SubscriptionCycleUnit.YEAR, 1,
            anchorDate = next, validUntil = today.plusYears(1)))
        model.openEdit(item); await { model.state.value.editor != null && !model.state.value.busy }
        assertEquals(today.toString(), model.state.value.editor!!.purchaseDate)
        val corrected = today.withDayOfMonth(1).minusDays(1)
        model.changeEditor { it.copy(title = "年度会员", purchaseDate = corrected.toString()) }
        model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.spendingLoading && model.state.value.spending?.previous?.entries?.size == 1 }
        val saved = container.subscriptions.get(item.id)!!
        assertEquals(item.anchorDate, saved.anchorDate); assertEquals(item.validUntil, saved.validUntil)
        assertEquals(3L, saved.revision)
        val payment = container.subscriptions.payments(item.id).single()
        assertEquals(corrected, payment.paidOn)
        assertEquals("purchase:${item.id}", payment.billingEventId)
        assertEquals(java.math.BigInteger.ZERO, model.state.value.spending!!.current.cnyMinor)
        assertEquals(java.math.BigInteger.valueOf(805656), model.state.value.spending!!.previous.cnyMinor)
        model.dismissEditor(); model.openEdit(saved)
        await { model.state.value.editor != null && !model.state.value.busy }
        assertEquals(corrected.toString(), model.state.value.editor!!.purchaseDate)
        model.changeEditor { it.copy(notes = "仅改备注") }; model.saveEditor(); await { model.state.value.editorSaved }
        assertEquals(1, container.subscriptions.payments(item.id).size)
        assertEquals(4L, container.subscriptions.get(item.id)!!.revision)
    }

    @Test fun failedFirstChargeWriteRollsBackTheSubscriptionAndRetryKeepsOnePayment() = runBlocking {
        openExisting()
        val original = model.state.value.editor!!
        val id = original.targetId!!
        val corrected = LocalDate.now().minusDays(2).toString()
        model.changeEditor { it.copy(title = "不能部分保存", purchaseDate = corrected) }
        assertTrue(model.flushEditor()); model.dismissEditor(); openExisting()
        assertEquals(corrected, model.state.value.editor!!.purchaseDate)
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_initial_payment
            BEFORE INSERT ON subscription_payments BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        model.saveEditor(); await { !model.state.value.busy && model.state.value.editorError != null }
        assertFalse(model.state.value.editorSaved)
        assertEquals("原云盘", container.subscriptions.get(id)!!.title)
        assertEquals(1L, container.subscriptions.get(id)!!.revision)
        assertNull(container.database.subscriptions().operation(original.requestId))
        assertTrue(container.subscriptions.payments(id).isEmpty())
        assertEquals(corrected, model.state.value.editor!!.purchaseDate)
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_initial_payment")
        model.saveEditor(); model.saveEditor(); await { model.state.value.editorSaved }
        assertEquals("不能部分保存", container.subscriptions.get(id)!!.title)
        assertEquals(3L, container.subscriptions.get(id)!!.revision)
        assertEquals(LocalDate.parse(corrected), container.subscriptions.payments(id).single().paidOn)
        assertEquals(original.requestId, model.state.value.editor!!.requestId)
    }

    @Test fun futureFirstChargeDateCannotCommitOtherChangesOrLoseTheDraft() = runBlocking {
        openExisting()
        val original = model.state.value.editor!!
        model.changeEditor { it.copy(title = "不能保存未来已扣费", purchaseDate = LocalDate.now().plusDays(1).toString()) }
        model.saveEditor(); await { !model.state.value.busy && model.state.value.editorError != null }
        assertEquals("原云盘", container.subscriptions.get(original.targetId!!)!!.title)
        assertEquals(1L, container.subscriptions.get(original.targetId)!!.revision)
        assertTrue(container.subscriptions.payments(original.targetId).isEmpty())
        destroyModel(); createModel(); openExisting()
        assertEquals(LocalDate.now().plusDays(1).toString(), model.state.value.editor!!.purchaseDate)
        assertEquals("不能保存未来已扣费", model.state.value.editor!!.title)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待订阅状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
