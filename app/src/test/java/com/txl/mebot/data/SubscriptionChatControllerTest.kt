package com.txl.mebot.data

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.subscriptions.SubscriptionsViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionChatControllerTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var model: SubscriptionsViewModel
    private lateinit var saved: SavedStateHandle
    private var activity: ActivityController<ComponentActivity>? = null
    private var interpretation: SubscriptionInterpretation = SubscriptionInterpretation.Ask("请补充金额、周期、扣费日期和是否自动续费？")
    private var failure: SubscriptionAnalysisFailure? = null
    private var analysisGate: CompletableDeferred<Unit>? = null
    private var calls = 0
    private var context: SubscriptionAnalysisContext? = null
    private val chat get() = model.chat
    private fun values(amount: Long = 2500): SubscriptionValues {
        val date = LocalDate.now().plusDays(5)
        return SubscriptionValues("云盘会员", amount, "CNY", SubscriptionCycleUnit.MONTH, 1, anchorDate = date, nextBillingDate = date, autoRenew = true)
    }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app); createModel()
    }
    private fun createModel(handle: SavedStateHandle = SavedStateHandle()) {
        saved = handle
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val interpreter = SubscriptionInterpreter {
            calls++; context = it; analysisGate?.await()
            failure?.let { kind -> throw SubscriptionAnalysisException(kind) }; interpretation
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SubscriptionsViewModel(app, handle, container, interpreter) as T
        }
        model = ViewModelProvider(activity!!.get(), factory)[SubscriptionsViewModel::class.java]
        await { !chat.state.value.loading && chat.state.value.sessionId != null && !model.state.value.loading }
    }
    private fun destroy() { activity?.pause()?.stop()?.destroy(); activity = null; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40)) }
    @After fun teardown() { destroy(); container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME) }
    private fun send(text: String) {
        chat.changeInput(text); chat.send()
        await { !chat.state.value.sending && !chat.state.value.analyzing && chat.state.value.messages.any { it.content == text } }
    }
    private fun proposal() { interpretation = SubscriptionInterpretation.Propose(values()); send("云盘每月25元，自动续费，下次扣费五天后"); await { chat.state.value.proposal != null } }

    @Test fun nameOnlyIsAskedInConversationThenCompleteFactsProduceCardAfterOneConfirmation() = runBlocking {
        send("我买了好医保")
        await { chat.state.value.messages.any { it.role == MessageRole.ASSISTANT } }
        assertTrue(container.database.subscriptions().all().isEmpty())
        proposal()
        assertTrue(container.database.subscriptions().all().isEmpty())
        chat.confirm(); chat.confirm()
        await { chat.state.value.record != null && !chat.state.value.confirming && model.state.value.records.size == 1 }
        assertEquals(2500L, model.state.value.records.single().amountMinor)
        assertEquals(1, container.subscriptions.history(model.state.value.records.single().id).size)
        assertTrue(chat.state.value.messages.any { "已创建" in it.content })
        assertEquals("", chat.state.value.input)
    }
    @Test fun rapidInputAndNewChatKeepSeparateRoomDraftsAndHistoryWithoutSavedState() = runBlocking {
        val first = chat.state.value.sessionId!!
        repeat(50) { index -> chat.changeInput("第一份未发送输入 $index") }
        chat.openCreation(forceNew = true)
        await { !chat.state.value.loading && chat.state.value.sessionId != first }
        val second = chat.state.value.sessionId!!
        chat.changeInput("第二份输入"); chat.saveInput()
        await { runBlocking { container.database.subscriptionConversations().draft(second)!!.inputText == "第二份输入" } }
        chat.openSession(first); await { !chat.state.value.loading && chat.state.value.sessionId == first }
        assertEquals("第一份未发送输入 49", chat.state.value.input)
        destroy(); createModel()
        chat.openSession(second); await { !chat.state.value.loading && chat.state.value.sessionId == second }
        assertEquals("第二份输入", chat.state.value.input)
        chat.history(); await { !chat.state.value.historyLoading && chat.state.value.chats.size >= 2 }
        assertTrue(chat.state.value.chats.map { it.conversation.id }.containsAll(listOf(first, second)))
    }
    @Test fun pendingProposalAndOriginalConversationRestoreAfterRecreationWithoutReanalyzing() = runBlocking {
        proposal()
        val session = chat.state.value.sessionId!!
        val request = chat.state.value.proposal!!.operation.id
        destroy(); createModel()
        await { chat.state.value.proposal != null }
        assertEquals(session, chat.state.value.sessionId)
        assertEquals(request, chat.state.value.proposal!!.operation.id)
        assertEquals(1, calls)
        chat.confirm(); await { chat.state.value.record != null && !chat.state.value.confirming }
        val item = chat.state.value.record!!
        chat.openCreation(forceNew = true); await { !chat.state.value.loading && chat.state.value.sessionId != session }
        chat.openSubscription(item.id); await { !chat.state.value.loading && chat.state.value.sessionId == session }
        assertTrue(chat.state.value.messages.any { "已创建" in it.content })
        assertEquals(1, container.database.subscriptions().all().size)
    }
    @Test fun analysisFailureKeepsOneSentMessageAndExplicitRetryContinuesSameConversation() = runBlocking {
        failure = SubscriptionAnalysisFailure.NETWORK
        send("我订阅了云盘")
        await { chat.state.value.failure == SubscriptionAnalysisFailure.NETWORK }
        val session = chat.state.value.sessionId
        assertEquals(1, chat.state.value.messages.count { it.role == MessageRole.USER })
        failure = null; interpretation = SubscriptionInterpretation.Propose(values())
        chat.retry(); await { !chat.state.value.analyzing && !chat.state.value.sending && chat.state.value.proposal != null }
        assertEquals(session, chat.state.value.sessionId)
        assertEquals(1, chat.state.value.messages.count { it.role == MessageRole.USER })
        assertEquals(2, calls)
        assertTrue(container.database.subscriptions().all().isEmpty())
    }
    @Test fun savedStateBackupRestoresRawInputAfterDatabaseWriteFailure() = runBlocking {
        val session = chat.state.value.sessionId!!
        container.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_chat_draft BEFORE UPDATE ON subscription_drafts BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        chat.changeInput("最后未保存的输入"); chat.saveInput(); await { chat.state.value.inputError }
        val backup = saved.keys().associateWith { saved.get<Any>(it) }
        destroy(); container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_chat_draft")
        createModel(SavedStateHandle(backup))
        assertEquals(session, chat.state.value.sessionId)
        assertEquals("最后未保存的输入", chat.state.value.input)
    }
    @Test fun alreadySentSavedStateBackupCannotRestoreOldTextAndCreateDuplicateMessage() = runBlocking {
        val session = chat.state.value.sessionId!!
        val token = "consumed-token"
        val draft = container.database.subscriptionConversations().draft(session)!!
        container.subscriptionConversations.saveDraft(session, "", JSONObject().put("chatLastSentToken", token).toString(), draft.revision)
        destroy()
        createModel(SavedStateHandle(mapOf("subscription_chat_session" to session,
            "subscription_chat_input" to JSONObject().put("id", session).put("text", "已经发送的旧内容").put("token", token).toString())))
        assertEquals("", chat.state.value.input)
    }
    @Test fun screenshotRemainsLocalAcrossRestartAndFeedsOnlyCurrentChatImageToModel() = runBlocking {
        val session = chat.state.value.sessionId!!
        val bytes = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }.let { bitmap ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
            }; stream.toByteArray()
        }
        val photo = container.subscriptionAttachments.importImage(session, { ByteArrayInputStream(bytes) })
        await { chat.state.value.pending.size == 1 }
        destroy(); createModel()
        await { chat.state.value.pending.size == 1 }
        assertTrue(container.media.fileFor(photo.id)!!.isFile)
        interpretation = SubscriptionInterpretation.Propose(values())
        chat.send(); await { !chat.state.value.analyzing && !chat.state.value.sending && chat.state.value.proposal != null }
        assertEquals(photo.id, context!!.images.single().assetId)
        assertTrue(context!!.images.single().dataUrl.startsWith("data:image/jpeg;base64,"))
        chat.confirm(); await { chat.state.value.record != null && !chat.state.value.confirming }
        assertEquals(photo.id, chat.state.value.record!!.coverAssetId)
        assertTrue(container.media.fileFor(photo.id)!!.isFile)
        assertTrue(chat.state.value.pending.isEmpty())
    }
    @Test fun interruptedAnalysisRequiresExplicitRetryAfterModelRecreation() = runBlocking {
        analysisGate = CompletableDeferred()
        chat.changeInput("中断前发送的订阅信息"); chat.send()
        await { chat.state.value.analyzing && calls == 1 }
        val session = chat.state.value.sessionId!!
        destroy()
        await { runBlocking { SubscriptionChatMeta.read(container.database.subscriptionConversations().draft(session)!!.fieldsJson).status == "failed" } }
        analysisGate = null; interpretation = SubscriptionInterpretation.Propose(values())
        createModel(); await { chat.state.value.failure == SubscriptionAnalysisFailure.INTERRUPTED }
        assertEquals(1, calls)
        chat.retry(); await { !chat.state.value.analyzing && chat.state.value.proposal != null }
        assertEquals(2, calls)
    }
    @Test fun unsentAdjustmentCannotAccidentallyConfirmTheOldProposal() = runBlocking {
        proposal()
        chat.changeInput("金额改成30元"); chat.confirm()
        assertFalse(chat.state.value.confirming)
        assertTrue(container.database.subscriptions().all().isEmpty())
        interpretation = SubscriptionInterpretation.Propose(values(3000))
        chat.send(); await { !chat.state.value.sending && !chat.state.value.analyzing && chat.state.value.proposal?.values?.amountMinor == 3000L }
        chat.confirm(); await { !chat.state.value.confirming && chat.state.value.record != null }
        assertEquals(3000L, chat.state.value.record!!.amountMinor)
    }
    @Test fun reopeningCurrentConversationDoesNotLoseItsRunningAnalysisState() = runBlocking {
        analysisGate = CompletableDeferred()
        interpretation = SubscriptionInterpretation.Propose(values())
        chat.changeInput("正在解析的订阅"); chat.send(); await { chat.state.value.analyzing && !chat.state.value.sending }
        val id = chat.state.value.sessionId!!
        chat.openSession(id); await { !chat.state.value.loading }
        assertTrue(chat.state.value.analyzing)
        analysisGate!!.complete(Unit)
        await { !chat.state.value.analyzing && chat.state.value.proposal != null }
        assertEquals(1, calls)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待订阅聊天状态超时：${chat.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
