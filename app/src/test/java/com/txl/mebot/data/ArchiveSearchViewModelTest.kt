package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.ArchiveIndexState
import com.txl.mebot.ui.archive.ArchiveViewModel
import java.time.Duration
import java.util.UUID
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
class ArchiveSearchViewModelTest {
    private lateinit var f: ArchiveAnalysisFixture
    private lateinit var engine: ArchiveSemanticEngine
    private lateinit var model: ArchiveViewModel
    private var activity: ActivityController<ComponentActivity>? = null
    private var hold = false
    private var networkFails = false
    private var queryCalls = 0
    private var documentCalls = 0
    private var scheduled = 0
    private data class Pending(val text: String, val response: CompletableDeferred<FloatArray> = CompletableDeferred())
    private val calls = CopyOnWriteArrayList<Pending>()
    private val config = ArchiveEmbeddingSettings(enabled = true, dimensions = 3)
    @Before fun setup() {
        f = ArchiveAnalysisFixture()
        engine = f.semantic(ArchiveEmbeddingClient { _, _, text, use ->
            if (use == ArchiveEmbeddingUse.DOCUMENT) { documentCalls++; if (text.contains("身份证")) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f) }
            else {
                queryCalls++
                if (networkFails) throw ArchiveSemanticException(ArchiveSemanticFailure.NETWORK)
                if (hold) { val pending = Pending(text); calls += pending; withContext(NonCancellable) { pending.response.await() } }
                else if (text.contains("身份证")) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
            }
        }) { scheduled++ }
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ArchiveViewModel(f.app, SavedStateHandle(), f.records, f.files, null, engine) as T
        }
        model = ViewModelProvider(activity!!.get(), factory)[ArchiveViewModel::class.java]
        await { !model.state.value.loading && !model.state.value.busy }
    }
    @After fun teardown() {
        calls.forEach { it.response.complete(floatArrayOf(1f, 0f, 0f)) }
        activity?.pause()?.stop()?.destroy()
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        f.close()
    }
    private fun idle() = await { !model.state.value.busy && !model.state.value.dirty }
    private suspend fun save(title: String, id: String = UUID.randomUUID().toString()): ArchiveRecord {
        f.records.createSession(id)
        val draft = f.records.saveDraft(id, 0, ArchiveDraftContent(candidate = ArchiveContent(title, body = "本地资料 $title")))
        val receipt = f.records.confirm(id, "save-$id", draft.revision)
        return f.records.record(receipt.recordId)!!
    }
    private suspend fun indexed(count: Int) {
        engine.saveSettings(config, ArchiveEmbeddingKeyChange.Replace("fixture-key"), engine.settings.load().revision)
        while (engine.runQueue()) { }
        model.refreshList(); idle()
        await { model.state.value.indexSummary.ready == count }
    }
    private fun search(text: String) {
        model.changeQuery(text); model.search()
        await { !model.state.value.searchLoading && model.state.value.submittedQuery == text }
    }

    @Test fun newQueryCancelsOldResponseAndSearchingNeverConsumesNewArchiveInput() = runBlocking {
        save("银行卡"); val id = save("身份证").id; indexed(2)
        val home = model.state.value.context!!.sessionId
        model.changeInput("首页未发送的新档案"); idle(); hold = true
        model.changeQuery("银行卡"); model.search(); await { calls.size == 1 }
        model.changeQuery("身份证"); model.search(); await { calls.size == 2 }
        calls[1].response.complete(floatArrayOf(0f, 1f, 0f))
        await { !model.state.value.searchLoading && model.state.value.searchCards.isNotEmpty() }
        assertEquals(id, model.state.value.searchCards.first().record.id)
        calls[0].response.complete(floatArrayOf(1f, 0f, 0f))
        repeat(15) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        assertEquals("身份证", model.state.value.submittedQuery); assertEquals(id, model.state.value.searchCards.first().record.id)
        assertEquals(home, model.state.value.context!!.sessionId); assertEquals("首页未发送的新档案", model.state.value.input)
        assertTrue(f.records.context(home).messages.isEmpty()); assertEquals(2, f.records.records().size)
    }
    @Test fun pagingAndClearingSearchUseOneQueryVectorAndReturnToTheLocalCards() = runBlocking {
        repeat(51) { save("资料 $it") }; indexed(51)
        search("资料"); assertEquals(24, model.state.value.searchCards.size); assertTrue(model.state.value.moreSearch)
        model.moreSearch(); await { !model.state.value.searchLoading }; assertEquals(48, model.state.value.searchCards.size)
        model.moreSearch(); await { !model.state.value.searchLoading }; assertEquals(51, model.state.value.searchCards.size)
        assertEquals(51, model.state.value.searchCards.map { it.record.id }.distinct().size)
        assertEquals(1, queryCalls); assertFalse(model.state.value.moreSearch)
        model.changeQuery("")
        assertNull(model.state.value.submittedQuery); assertEquals(model.state.value.cards, model.state.value.displayedCards); assertEquals(1, queryCalls)
    }
    @Test fun disabledSearchUsesLocalTextWithoutAKeyNetworkOrPreparedIndex() = runBlocking {
        val local = save("我的身份证"); save("银行卡")
        networkFails = true
        search("身份证")
        assertTrue(model.state.value.localTextSearch)
        assertNull(model.state.value.searchFailure)
        assertEquals(listOf(local.id), model.state.value.searchCards.map { it.record.id })
        assertEquals(0, queryCalls); assertEquals(0, documentCalls)
        assertEquals(0, model.state.value.indexSummary.ready)
    }
    @Test fun settingsToggleUpdatesAnActiveQueryEvenWhenTheArchiveIsEmpty() = runBlocking {
        search("证件")
        assertTrue(model.state.value.localTextSearch); assertEquals(0, queryCalls)
        engine.saveSettings(config, ArchiveEmbeddingKeyChange.Replace("fixture-key"), engine.settings.load().revision)
        await { !model.state.value.localTextSearch && !model.state.value.searchLoading && queryCalls == 1 }
        assertNull(model.state.value.searchFailure); assertTrue(model.state.value.searchCards.isEmpty())
        engine.saveSettings(config.copy(enabled = false), ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        await { model.state.value.localTextSearch && !model.state.value.searchLoading }
        assertNull(model.state.value.searchFailure); assertEquals(1, queryCalls)
    }
    @Test fun networkFailureRetainsQueryAndDisablingSemanticSearchRefreshesItLocally() = runBlocking {
        val local = save("我的身份证"); save("银行卡"); indexed(2)
        networkFails = true
        search("身份证"); assertEquals(ArchiveSemanticFailure.NETWORK, model.state.value.searchFailure)
        assertEquals("身份证", model.state.value.query); assertTrue(model.state.value.searchCards.isEmpty())
        engine.saveSettings(config.copy(enabled = false), ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        await { !model.state.value.searchLoading && model.state.value.localTextSearch && model.state.value.searchFailure == null }
        assertTrue(model.state.value.localTextSearch); assertNull(model.state.value.searchFailure)
        assertEquals(listOf(local.id), model.state.value.searchCards.map { it.record.id }); assertEquals(1, queryCalls)
    }
    @Test fun deletingAResultRefreshesFromCachedQueryWithoutShowingDeletedCardOrMakingANewQueryRequest() = runBlocking {
        val deleted = save("银行卡"); val remaining = save("身份证"); indexed(2)
        search("资料"); assertEquals(2, model.state.value.searchCards.size)
        model.delete(deleted); idle()
        await { !model.state.value.searchLoading && model.state.value.searchCards.size == 1 }
        assertEquals(remaining.id, model.state.value.searchCards.single().record.id); assertEquals(1, queryCalls)
        assertNull(f.records.record(deleted.id))
    }
    @Test fun confirmationQueuesIndexOnlyAfterLocalSaveAndWorkerStateUpdatesThePage() = runBlocking {
        engine.saveSettings(config, ArchiveEmbeddingKeyChange.Replace("fixture-key"), 0)
        model.changeInput("手动登记本地资料"); idle(); model.send(); idle()
        model.editCurrent(); idle(); model.changeEditor { it.copy(title = "银行卡") }; idle(); model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.busy }
        val record = f.records.records().single()
        assertEquals(0, documentCalls); assertEquals(ArchiveIndexState.QUEUED, f.db.archives().job(record.id)!!.state); assertTrue(scheduled > 0)
        model.editorClosed(); model.finishReturnHome(); idle()
        engine.runQueue(); await { model.state.value.indexSummary.ready == 1 }
        assertEquals(1, documentCalls); assertEquals(record.id, engine.index.vectors().single().recordId)
        assertNull(model.state.value.context!!.record)
    }
    @Test fun settingsToggleAutomaticallySwitchesTheCurrentQueryBetweenLocalAndSemanticSearch() = runBlocking {
        val id = save("身份证").id; save("银行卡"); indexed(2); search("身份证")
        assertEquals(2, model.state.value.searchCards.size)
        engine.saveSettings(config.copy(enabled = false), ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        await { model.state.value.localTextSearch && !model.state.value.searchLoading && model.state.value.searchCards.size == 1 }
        assertEquals(id, model.state.value.searchCards.single().record.id)
        assertNull(model.state.value.searchFailure); assertEquals(1, queryCalls)
        engine.saveSettings(config, ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        while (engine.runQueue()) { }
        await { !model.state.value.localTextSearch && !model.state.value.searchLoading && model.state.value.searchCards.size == 2 }
        assertEquals(id, model.state.value.searchCards.first().record.id)
        assertEquals(2, queryCalls)
        model.changeQuery(""); assertEquals(2, model.state.value.cards.size)
    }
    @Test fun disablingSemanticSearchDuringARequestRejectsItsLateReply() = runBlocking {
        val id = save("银行卡").id; save("身份证"); indexed(2)
        hold = true
        model.changeQuery("银行卡"); model.search(); await { calls.size == 1 }
        engine.saveSettings(config.copy(enabled = false), ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        await { model.state.value.localTextSearch && !model.state.value.searchLoading && model.state.value.searchCards.size == 1 }
        calls.single().response.complete(floatArrayOf(0f, 1f, 0f))
        repeat(15) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5) }
        assertTrue(model.state.value.localTextSearch); assertNull(model.state.value.searchFailure)
        assertEquals(listOf(id), model.state.value.searchCards.map { it.record.id })
        assertEquals(1, queryCalls)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待搜索页面超时：busy=${model.state.value.busy}, searchLoading=${model.state.value.searchLoading}, error=${model.state.value.searchFailure}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
