package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.ui.archive.ArchiveViewModel
import java.time.Duration
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
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
class ArchiveAiViewModelTest {
    private lateinit var f: ArchiveAnalysisFixture
    private lateinit var model: ArchiveViewModel
    private var activity: ActivityController<ComponentActivity>? = null
    private data class Pending(val context: ArchiveAnalysisContext, val response: CompletableDeferred<ArchiveInterpretation> = CompletableDeferred())
    private val calls = CopyOnWriteArrayList<Pending>()
    private val interpreter = ArchiveInterpreter { context ->
        val pending = Pending(context); calls += pending
        // 模拟服务端已计算、客户端取消后仍迟到的结果。
        withContext(NonCancellable) { pending.response.await() }
    }
    @Before fun setup() { f = ArchiveAnalysisFixture(); create() }
    @After fun teardown() {
        calls.forEach { it.response.complete(ArchiveInterpretation.Reject("UNRELATED")) }
        destroy()
        repeat(15) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        f.close()
    }
    private fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ArchiveViewModel(f.app, SavedStateHandle(), f.records, f.files, f.coordinator(interpreter)) as T
        }
        model = ViewModelProvider(activity!!.get(), factory)[ArchiveViewModel::class.java]
        await { !model.state.value.loading && !model.state.value.busy }
        assertNull(model.state.value.error)
    }
    private fun destroy() { activity?.pause()?.stop()?.destroy(); activity = null }
    private fun idle() = await { !model.state.value.busy && !model.state.value.dirty }
    private fun send(text: String, number: Int): Pending {
        model.changeInput(text); idle(); model.send(); idle()
        await { calls.size > number }; return calls[number]
    }
    private fun answer(pending: Pending, title: String, conflicts: List<String> = emptyList()) {
        pending.response.complete(ArchiveInterpretation.Propose(ArchiveContent(title, "资料", body = "模型草稿", assetIds = pending.context.source.assets.map { it.id }), conflicts))
    }
    private fun ready() = await { !model.state.value.analyzing && model.state.value.analysis.status == ArchiveAnalysisStatus.READY }

    @Test fun completionInAnotherConversationDoesNotReplaceCurrentInputOrCandidate() = runBlocking {
        val a = model.state.value.context!!.sessionId
        val requestA = send("身份证资料", 0)
        model.newSession(); idle(); val b = model.state.value.context!!.sessionId
        model.changeInput("银行卡尚未发送"); idle()
        answer(requestA, "身份证")
        await { a !in model.state.value.inFlight }
        assertEquals(b, model.state.value.context!!.sessionId); assertEquals("银行卡尚未发送", model.state.value.input); assertNull(model.state.value.candidate)
        assertEquals("身份证", f.records.draft(a).content.candidate!!.title)
        model.selectSession(a); idle(); assertEquals("身份证", model.state.value.candidate!!.title)
        model.selectSession(b); idle(); val requestB = send("银行卡资料", 1); answer(requestB, "银行卡"); ready()
        assertEquals("银行卡", model.state.value.candidate!!.title); assertEquals(2, calls.size)
    }

    @Test fun newTypingAndNewMessageInvalidateOldResultAndPreserveLatestDraft() = runBlocking {
        val first = send("最初的资料", 0)
        model.changeInput("补充信息尚未发送"); idle()
        assertFalse(model.state.value.analyzing); assertEquals("补充信息尚未发送", model.state.value.input)
        val second = send("更正为最新资料", 1)
        answer(first, "过期草稿")
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        assertTrue(model.state.value.analyzing); assertNull(model.state.value.candidate)
        answer(second, "最新草稿"); ready()
        assertEquals("最新草稿", model.state.value.candidate!!.title)
        assertEquals(2, f.records.context(model.state.value.context!!.sessionId).messages.count { it.role == MessageRole.USER })
        assertEquals(1, model.state.value.context!!.messages.count { it.role == MessageRole.ASSISTANT })
    }

    @Test fun cancelAndRetryKeepOneUserMessageAndLateCancelledReplyCannotClearNewRequest() = runBlocking {
        val first = send("登记资料", 0)
        model.stopAnalysis(); await { !model.state.value.analyzing && !model.state.value.busy && model.state.value.analysis.status == ArchiveAnalysisStatus.CANCELLED }
        model.retryAnalysis(); idle(); await { calls.size == 2 }
        answer(first, "取消的结果")
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        assertTrue(model.state.value.analyzing); assertNull(model.state.value.candidate)
        answer(calls[1], "重试结果"); ready()
        assertEquals("重试结果", model.state.value.candidate!!.title)
        assertEquals(1, model.state.value.context!!.messages.count { it.role == MessageRole.USER })
    }

    @Test fun persistedInterruptedRequestRestoresLocallyAndRequiresManualRetry() = runBlocking {
        val id = model.state.value.context!!.sessionId
        destroy(); f.send(id, "中断前保存的消息")
        f.coordinator(interpreter).begin(id)
        create()
        assertNotEquals(id, model.state.value.context!!.sessionId)
        assertTrue(calls.isEmpty())
        model.selectSession(id); idle()
        assertEquals(id, model.state.value.context!!.sessionId)
        assertEquals(ArchiveAnalysisStatus.INTERRUPTED, model.state.value.analysis.status)
        assertNull(f.records.draft(id).activeRequestId); assertTrue(calls.isEmpty())
        model.retryAnalysis(); idle(); await { calls.size == 1 }
        answer(calls.single(), "恢复后整理"); ready()
        assertEquals("恢复后整理", model.state.value.candidate!!.title)
    }

    @Test fun serviceFailureRetainsImagesAndAllowsManualSaveWithoutNetwork() = runBlocking {
        val id = model.state.value.context!!.sessionId
        val image = f.image(id, "original.jpg")
        f.send(id, "保留原始资料", listOf(image))
        model.selectSession(id); idle(); model.retryAnalysis(); idle(); await { calls.size == 1 }
        calls.single().response.completeExceptionally(ArchiveAnalysisException(ArchiveAnalysisFailure.NETWORK))
        await { !model.state.value.analyzing && model.state.value.analysis.status == ArchiveAnalysisStatus.FAILED }
        assertEquals(ArchiveAnalysisFailure.NETWORK, model.state.value.analysis.failure)
        assertTrue(model.state.value.context!!.assets.any { it.id == image })
        model.editCurrent(); idle(); model.changeEditor { it.copy(title = "手动资料") }; idle(); model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.busy }
        assertEquals(listOf(image), f.records.records().single().content.assetIds)
        assertEquals(1, calls.size)
    }

    @Test fun editingDoesNotAutomaticallyAcknowledgeConflictsAndOneSaveCreatesOneCard() = runBlocking {
        val pending = send("模糊资料", 0); answer(pending, "证件资料", listOf("号码模糊", "地址冲突")); ready()
        model.editCurrent(); idle(); model.changeEditor { it.copy(title = "核对后的证件资料", fields = listOf(ArchiveField("号码", "00123"))) }; idle()
        assertEquals(2, model.state.value.context!!.draft.content.conflicts.size)
        model.saveEditor(); idle(); assertTrue(f.records.records().isEmpty()); assertTrue(model.state.value.error!!.contains("逐项核对"))
        model.resolveConflict(0); idle(); assertEquals(1, model.state.value.context!!.draft.content.conflicts.size)
        model.resolveConflict(0); idle(); model.saveEditor(); model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.busy }
        assertEquals(1, f.records.records().size); assertEquals("00123", f.records.records().single().content.fields.single().value)
    }

    @Test fun manualEditorCancelsPendingModelAndKeepsItsOwnEditsWhenResponseArrives() = runBlocking {
        val pending = send("原始资料", 0)
        model.editCurrent(); idle(); model.changeEditor { it.copy(title = "手动决定的标题", body = "本地编辑") }; idle()
        answer(pending, "迟到标题")
        repeat(15) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        assertEquals("手动决定的标题", model.state.value.candidate!!.title); assertEquals("本地编辑", model.state.value.candidate!!.body)
        assertTrue(model.state.value.editorOpen); assertFalse(model.state.value.analyzing)
        assertEquals("手动决定的标题", f.records.draft(model.state.value.context!!.sessionId).content.candidate!!.title)
    }

    @Test fun attachingNewMaterialInvalidatesRunningRequestAndLateResultKeepsPendingAttachment() = runBlocking {
        val pending = send("先整理已有资料", 0)
        val source = File(f.app.cacheDir, "archive-ai-later.txt").apply { writeText("新附件资料") }
        try {
            model.pickLocal(source.toUri()); idle()
            assertFalse(model.state.value.analyzing)
            assertEquals(ArchiveAnalysisFailure.CHANGED, model.state.value.analysis.failure)
            val asset = model.state.value.pending.single().id
            answer(pending, "旧图文结果")
            repeat(15) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
            assertNull(model.state.value.candidate)
            assertEquals(asset, model.state.value.pending.single().id)
            f.files.withDecryptedFile(asset) { assertEquals("新附件资料", it.readText()) }
        } finally { source.delete() }
    }

    @Test fun closingRunningChatLetsHomeStartANewArchiveAndLateResponseStaysInItsOwnHistory() = runBlocking {
        val a = model.state.value.context!!.sessionId
        val first = send("银行卡资料", 0)
        model.collapse(); model.finishReturnHome(); idle()
        val b = model.state.value.context!!.sessionId
        assertNotEquals(a, b); assertTrue(model.state.value.context!!.messages.isEmpty())
        val second = send("身份证资料", 1)
        assertEquals(listOf("身份证资料"), second.context.messages.map { it.text })
        assertEquals(b, second.context.source.sessionId)
        answer(first, "银行卡")
        await { a !in model.state.value.inFlight }
        assertEquals(b, model.state.value.context!!.sessionId)
        assertNull(model.state.value.candidate); assertTrue(model.state.value.analyzing)
        answer(second, "身份证"); ready()
        assertEquals("身份证", model.state.value.candidate!!.title)
        model.selectSession(a); idle()
        assertEquals("银行卡", model.state.value.candidate!!.title)
        assertEquals(listOf("银行卡资料"), model.state.value.context!!.messages.filter { it.role == MessageRole.USER }.map { it.text })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待 AI 状态超时：busy=${model.state.value.busy}, dirty=${model.state.value.dirty}, analysis=${model.state.value.analysis.status}, error=${model.state.value.error}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
