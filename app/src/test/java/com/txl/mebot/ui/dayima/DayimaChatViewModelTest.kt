package com.txl.mebot.ui.dayima

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.local.*
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayimaChatViewModelTest {
    private lateinit var application: Application
    private lateinit var db: MebotDatabase
    private lateinit var records: DayimaRepository
    private lateinit var chats: DayimaConversationRepository
    private lateinit var engine: DayimaChatCoordinator
    private lateinit var attachments: DayimaAttachmentRepository
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: DayimaChatViewModel
    private val today = LocalDate.of(2026, 10, 25)
    private val calls = AtomicInteger()
    private var handler: suspend (DayimaAiContext) -> DayimaAiDecision = { DayimaAiDecision.Answer("你好") }

    @Before fun setup() {
        // Robolectric 每个用例使用新的私有目录，不能复用上个用例的 FileProvider 路径。
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(application, MebotDatabase::class.java).build()
        val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        records = DayimaRepository(db, clock) { ZoneOffset.UTC }
        chats = DayimaConversationRepository(db, clock) { ZoneOffset.UTC }
        attachments = DayimaAttachmentRepository(application, db, LocalMediaStore(application, db, clock), clock)
        engine = DayimaChatCoordinator(db, records, chats, DayimaTextInterpreter { calls.incrementAndGet(); handler(it) }, clock) { ZoneOffset.UTC }
        createModel()
    }
    private fun createModel(handle: SavedStateHandle = SavedStateHandle(), beforeVisible: () -> Unit = {}) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = DayimaChatViewModel(application, handle, chats, engine, attachments) as T
        }
        model = ViewModelProvider(activity.get(), factory)[DayimaChatViewModel::class.java]
        beforeVisible()
        model.setVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
    }
    private fun destroy() {
        model.setVisible(false)
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    }
    @After fun cleanup() { destroy(); db.close(); File(application.filesDir, "media").deleteRecursively(); File(application.cacheDir, "dayima_ui_camera").deleteRecursively() }

    private fun photo() = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
        output.toByteArray()
    }

    @Test fun cameraImageDraftRestoresAndSendingOnlyTheImageAsksForDateWithoutModelAccess() = runBlocking {
        destroy(); val handle = SavedStateHandle(); createModel(handle)
        val uri = model.prepareCamera()!!
        application.contentResolver.openOutputStream(uri)!!.use { it.write(photo()) }
        model.cameraResult(true)
        await { !model.state.value.media.busy && model.state.value.media.images.size == 1 }
        val image = model.state.value.media.images.single().asset.id
        model.changeInput("保存的草稿文字"); await { model.state.value.draftState == DayimaDraftState.SAVED }
        destroy(); createModel(handle)
        assertEquals(image, model.state.value.media.images.single().asset.id)
        assertEquals("保存的草稿文字", model.state.value.input); assertEquals(0, calls.get())
        model.changeInput(""); await { model.state.value.draftState == DayimaDraftState.SAVED }
        assertTrue(model.state.value.canSend); model.send()
        await { !model.state.value.processing && model.state.value.operation?.state == DayimaOperationState.WAITING_FOR_INPUT }
        assertTrue(model.state.value.media.images.isEmpty()); assertEquals(1, model.state.value.messageImages.values.sumOf { it.size })
        assertEquals(0, calls.get()); assertTrue(records.periods().isEmpty())
    }

    @Test fun restoredCameraResultArrivingBeforeThePageLoadsUsesItsOriginalDraft() = runBlocking {
        destroy(); val handle = SavedStateHandle(); createModel(handle)
        model.changeInput("拍照前的草稿"); await { model.state.value.draftState == DayimaDraftState.SAVED }
        val session = model.state.value.sessionId
        val uri = model.prepareCamera()!!
        application.contentResolver.openOutputStream(uri)!!.use { it.write(photo()) }
        destroy(); createModel(handle, beforeVisible = { model.cameraResult(true) })
        await { !model.state.value.media.busy }
        assertEquals(session, model.state.value.sessionId)
        assertEquals("拍照前的草稿", model.state.value.input)
        assertEquals(1, model.state.value.media.images.size)
        assertEquals(0, calls.get()); assertTrue(records.periods().isEmpty())
    }

    @Test fun cancellingCameraRemovesTemporaryCaptureAndKeepsInputAndBusinessFacts() = runBlocking {
        destroy(); val handle = SavedStateHandle(); createModel(handle)
        model.changeInput("输入需要保留"); await { model.state.value.draftState == DayimaDraftState.SAVED }
        model.prepareCamera()!!
        val file = File(application.cacheDir, "dayima_ui_camera/${handle.get<String>("dayima.camera.file")}")
        assertTrue(file.exists()); model.cameraResult(false); await { !file.exists() }
        assertEquals("输入需要保留", model.state.value.input); assertTrue(model.state.value.media.images.isEmpty())
        assertFalse(handle.contains("dayima.picker.session")); assertEquals(0, calls.get()); assertTrue(records.periods().isEmpty())
    }

    @Test fun corruptCameraImageFailsWithoutPreventingTextChat() = runBlocking {
        model.changeInput("依然可发送的文字"); await { model.state.value.draftState == DayimaDraftState.SAVED }
        val uri = model.prepareCamera()!!
        application.contentResolver.openOutputStream(uri)!!.use { it.write("bad image".toByteArray()) }
        model.cameraResult(true); await { !model.state.value.media.busy && model.state.value.media.failure != null }
        assertEquals("依然可发送的文字", model.state.value.input); assertTrue(model.state.value.canSend)
        model.send(); await { !model.state.value.processing && model.state.value.messages.size == 2 }
        assertEquals(1, calls.get()); assertTrue(records.periods().isEmpty())
    }

    @Test fun pickerResultFromOldChatCannotAttachToTheNewChat() = runBlocking {
        val old = model.state.value.sessionId!!; assertTrue(model.prepareImagePicker())
        model.newChat(); await { model.state.value.loaded && !model.state.value.loading && model.state.value.sessionId != old }
        val directory = File(application.cacheDir, "dayima_ui_camera").apply { mkdirs() }
        val source = File(directory, "stale-picker.png").apply { writeBytes(photo()) }
        val uri = FileProvider.getUriForFile(application, "${application.packageName}.attachments", source)
        model.pickedImages(listOf(uri), true); await { !model.state.value.media.busy && model.state.value.media.failure != null }
        assertTrue(model.state.value.media.images.isEmpty()); assertTrue(chats.snapshot(old)!!.images.isEmpty())
        assertEquals(0, calls.get()); assertTrue(records.periods().isEmpty())
    }

    @Test fun draftAndCurrentIndependentSessionRestoreWithoutCallingTheModel() = runBlocking {
        destroy(); val handle = SavedStateHandle(); createModel(handle)
        val id = model.state.value.sessionId!!
        model.expand(); model.changeInput("尚未发送的情况说明")
        await { model.state.value.draftState == DayimaDraftState.SAVED }
        assertEquals("尚未发送的情况说明", chats.snapshot(id)!!.draft.inputText)
        destroy(); createModel(handle)
        assertEquals(id, model.state.value.sessionId)
        assertEquals("尚未发送的情况说明", model.state.value.input)
        assertTrue(model.state.value.expanded)
        assertEquals(0, calls.get())
    }

    @Test fun sendAndFollowupEditThenNewChatDoNotShareAnImplicitTarget() = runBlocking {
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(it.now.toLocalDate(), null))) }
        model.changeInput("今天来了"); model.send(); model.send()
        await { !model.state.value.processing && model.state.value.messages.size == 2 }
        assertEquals(1, records.periods().size)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Edit(DayimaTargetRef(last = true), today.minusDays(1)))) }
        model.changeInput("改成昨天"); model.send()
        await { !model.state.value.processing && model.state.value.messages.size == 4 }
        assertEquals(today.minusDays(1), records.periods().single().startDate)
        val old = model.state.value.sessionId
        model.newChat(); await { model.state.value.loaded && !model.state.value.loading && model.state.value.sessionId != old }
        assertTrue(model.state.value.messages.isEmpty())
        model.changeInput("改成昨天"); model.send()
        await { !model.state.value.processing && model.state.value.messages.size == 2 }
        assertEquals(DayimaOperationState.WAITING_FOR_INPUT, model.state.value.operation?.state)
        assertEquals(1, records.periods().size)
    }

    @Test fun modelFailureKeepsUserMessageAndRetriesSameRequestOnlyOnce() = runBlocking {
        handler = { throw DayimaChatException(DayimaChatFailure.NETWORK) }
        model.changeInput("今天开始，备注重试一次"); model.send()
        await { !model.state.value.processing && model.state.value.failure == DayimaChatFailure.NETWORK && model.state.value.operation?.state == DayimaOperationState.FAILED }
        val request = model.state.value.operation!!.requestMessageId
        assertEquals(1, model.state.value.messages.size)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, "重试一次"))) }
        model.retryAnalysis(); model.retryAnalysis()
        await { !model.state.value.processing && model.state.value.operation?.state == DayimaOperationState.SUCCEEDED && model.state.value.messages.size == 2 }
        assertEquals(request, model.state.value.operation?.requestMessageId)
        assertEquals(2, calls.get())
        assertEquals(1, records.notesForPeriod(records.periods().single().id).size)
    }

    @Test fun newChatCancelsEvenAnUncooperativeLateResponseAndReopeningDoesNotAutoRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }
            DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, null))) }
        model.changeInput("今天来了"); model.send()
        await { entered.isCompleted }
        val old = model.state.value.sessionId!!
        model.newChat(); await { !model.state.value.loading && model.state.value.sessionId != old }
        release.complete(Unit)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(records.periods().isEmpty())
        assertTrue(model.state.value.messages.isEmpty())
        assertFalse(model.state.value.processing)
        model.selectChat(old); await { !model.state.value.loading && model.state.value.sessionId == old }
        assertEquals(DayimaOperationState.FAILED, model.state.value.operation?.state)
        assertEquals(1, calls.get())
    }

    @Test fun deletingCurrentChatStartsFreshAndKeepsRecordedBusinessData() = runBlocking {
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, "保留"))) }
        model.changeInput("今天开始"); model.send()
        await { !model.state.value.processing && model.state.value.messages.size == 2 }
        val old = model.state.value.sessionId!!
        model.askDelete(old); model.deleteChat()
        await { model.state.value.loaded && !model.state.value.loading && model.state.value.sessionId != old }
        assertNull(chats.snapshot(old))
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(1, records.periods().size)
        assertEquals(1, records.notesForPeriod(records.periods().single().id).size)
    }

    @Test fun closingACompletedTaskStartsBlankAndTheNextRequestDoesNotReuseItsContextOrTarget() = runBlocking {
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, "本次正文"))) }
        model.expand(); model.changeInput("今天来了"); model.send()
        await { !model.state.value.processing && model.state.value.operation?.state == DayimaOperationState.SUCCEEDED }
        val old = model.state.value.sessionId!!
        model.finishTask(); assertEquals(old, model.state.value.sessionId)
        model.expand(false)
        assertFalse(model.state.value.composerEditable) // 收起动画结束前不能把新输入写进旧任务。
        model.finishTask()
        await { model.state.value.loaded && !model.state.value.loading && model.state.value.sessionId != old }
        assertFalse(model.state.value.expanded); assertEquals("", model.state.value.input)
        assertTrue(model.state.value.composerEditable)
        assertTrue(model.state.value.messages.isEmpty()); assertEquals(1, records.periods().size)
        assertEquals(2, chats.snapshot(old)!!.messages.size)
        handler = {
            assertEquals(1, it.messages.size); assertTrue(it.lastTargets.isEmpty())
            DayimaAiDecision.Write(listOf(DayimaChatMutation.Edit(DayimaTargetRef(last = true), today.minusDays(1))))
        }
        model.expand(); model.changeInput("改成昨天"); model.send()
        await { !model.state.value.processing && model.state.value.operation?.state == DayimaOperationState.WAITING_FOR_INPUT }
        assertEquals(today, records.periods().single().startDate)
    }

    @Test fun anUnsentFollowupIsKeptWhenTheTaskClosesInsteadOfBeingReplacedWithABlankSession() = runBlocking {
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today, null))) }
        model.expand(); model.changeInput("今天来了"); model.send()
        await { !model.state.value.processing && model.state.value.operation?.state == DayimaOperationState.SUCCEEDED }
        val id = model.state.value.sessionId
        model.changeInput("还没发送的补充内容"); model.expand(false); model.finishTask()
        assertTrue(model.state.value.composerEditable)
        await { model.state.value.draftState == DayimaDraftState.SAVED }
        assertEquals(id, model.state.value.sessionId); assertEquals("还没发送的补充内容", model.state.value.input)
        assertEquals("还没发送的补充内容", chats.snapshot(id!!)!!.draft.inputText)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待独立聊天状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
