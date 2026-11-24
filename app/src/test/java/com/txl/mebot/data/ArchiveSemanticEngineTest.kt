package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.ArchiveIndexState
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveSemanticEngineTest {
    private lateinit var f: ArchiveAnalysisFixture
    private val config = ArchiveEmbeddingSettings(enabled = true, dimensions = 3)
    @Before fun setup() { f = ArchiveAnalysisFixture() }
    @After fun teardown() { f.close() }
    private suspend fun save(title: String, body: String = "资料", id: String = UUID.randomUUID().toString()): ArchiveSaveReceipt {
        f.records.createSession(id)
        val draft = f.records.saveDraft(id, 0, ArchiveDraftContent(candidate = ArchiveContent(title, body = body)))
        return f.records.confirm(id, "save-$id", draft.revision)
    }
    private suspend fun enable(engine: ArchiveSemanticEngine, values: ArchiveEmbeddingSettings = config) = engine.saveSettings(values, ArchiveEmbeddingKeyChange.Replace("fixture-key"), engine.settings.load().revision)

    @Test fun unconfiguredAndDisabledIndexingMakeNoNetworkCallsAndLocalLookupStillWorks() = runBlocking {
        var calls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> calls++; floatArrayOf(1f, 0f, 0f) })
        val saved = save("我的银行卡", "离线资料")
        engine.start(); assertFalse(engine.runQueue())
        assertEquals(ArchiveSemanticFailure.CONFIGURATION, (runCatching { engine.search("银行卡") }.exceptionOrNull() as ArchiveSemanticException).failure)
        val local = engine.localSearch("离线")
        assertEquals(saved.recordId, engine.page(local, 0).first.single().record.id)
        enable(engine, config.copy(enabled = false)); engine.start(); assertFalse(engine.runQueue()); assertEquals(0, calls)
        assertEquals(ArchiveIndexState.WAITING_CONFIGURATION, f.db.archives().job(saved.recordId)!!.state)
    }

    @Test fun semanticScoresOverrideLiteralTitleMatchAndPagingUsesOneQueryEmbedding() = runBlocking {
        var queryCalls = 0; var documentCalls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, key, text, use ->
            assertEquals("fixture-key", key)
            if (use == ArchiveEmbeddingUse.QUERY) { queryCalls++; floatArrayOf(1f, 0f, 0f) }
            else { documentCalls++; if (text.contains("真正相关")) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f) }
        })
        val literal = save("我的中国银行卡", "低向量分数")
        val semantic = save("借记卡资料", "真正相关的资料")
        enable(engine); assertFalse(engine.runQueue()); assertEquals(2, documentCalls)
        val snapshot = engine.search("我的中国银行卡")
        assertEquals(listOf(semantic.recordId, literal.recordId), snapshot.hits.map { it.recordId })
        val (first, offset) = engine.page(snapshot, 0, 1); val second = engine.page(snapshot, offset, 1).first
        assertEquals(semantic.recordId, first.single().record.id); assertEquals(literal.recordId, second.single().record.id)
        assertEquals(1, queryCalls); assertTrue(first.single().snippet.contains("真正相关"))
        engine.search("我的中国银行卡"); assertEquals(1, queryCalls)
    }

    @Test fun moreThanOneVectorPageAndMultiplePartsHaveStableUniqueArchiveResults() = runBlocking {
        var queryCalls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, use -> if (use == ArchiveEmbeddingUse.QUERY) queryCalls++; floatArrayOf(1f, 0f, 0f) })
        repeat(130) { save("资料 $it", id = "record-chat-$it") }
        val long = save("长资料", "长段落🙂".repeat(2500), "long-chat")
        enable(engine)
        var turns = 0
        do { val more = engine.runQueue(); turns++; check(turns < 30) } while (more)
        val snapshot = engine.search("资料")
        assertEquals(131, snapshot.hits.size); assertEquals(131, snapshot.hits.map { it.recordId }.distinct().size)
        assertEquals(1, snapshot.hits.count { it.recordId == long.recordId })
        assertEquals(snapshot.hits.map { it.recordId }.sorted(), snapshot.hits.map { it.recordId })
        val ids = mutableListOf<String>(); var offset = 0
        while (offset < snapshot.hits.size) { val (page, next) = engine.page(snapshot, offset, 17); ids += page.map { it.record.id }; offset = next }
        assertEquals(snapshot.hits.map { it.recordId }, ids); assertEquals(1, queryCalls)
    }

    @Test fun unchangedConfirmationKeepsRevisionAndReadyVectorsWithoutAnotherPaidIndexRequest() = runBlocking {
        var calls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> calls++; floatArrayOf(1f, 0f, 0f) })
        val first = save("固定档案", id = "a"); enable(engine); engine.runQueue()
        val before = f.db.archives().effectiveEmbeddings().single().vector.copyOf()
        val draft = f.records.prepareManualEditor("a")
        val second = f.records.confirm("a", "same-content-save", draft.revision)
        assertEquals(first.recordId, second.recordId); assertEquals(first.revision, second.revision)
        assertFalse(engine.runQueue()); assertEquals(1, calls)
        assertEquals(ArchiveIndexState.READY, f.db.archives().job(first.recordId)!!.state)
        assertArrayEquals(before, f.db.archives().effectiveEmbeddings().single().vector)
    }

    @Test fun changedAndDeletedArchivesDisappearFromOldSearchPagesImmediatelyAndCannotReceiveLateIndex() = runBlocking {
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> floatArrayOf(1f, 0f, 0f) })
        val a = save("档案 A", id = "a"); val b = save("档案 B", id = "b")
        enable(engine); engine.runQueue(); val snapshot = engine.search("资料")
        var draft = f.records.prepareManualEditor("a")
        draft = f.records.saveDraft("a", draft.revision, draft.content.copy(candidate = draft.content.candidate!!.copy(body = "更新的正文")))
        f.records.confirm("a", "update-a", draft.revision)
        val oldLease = engine.index.claim(a.recordId)!!
        f.records.delete("a", "delete-a", 2); f.records.delete("b", "delete-b", 1)
        assertTrue(engine.page(snapshot, 0).first.isEmpty())
        assertFalse(engine.index.complete(oldLease, listOf(ArchiveEmbeddingPart("过期", floatArrayOf(1f, 0f, 0f)))))
        assertTrue(engine.index.vectors().isEmpty()); assertTrue(f.records.records().isEmpty())
        assertEquals(0, engine.index.summary().total)
    }

    @Test fun changingSpaceDuringRequestRejectsOldResponseAndQueuesOnlyTheNewConfiguration() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val result = CompletableDeferred<FloatArray>(); var calls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { settings, _, _, _ -> calls++; if (settings.model == config.model) { entered.complete(Unit); result.await() } else floatArrayOf(0f, 1f, 0f) })
        val record = save("档案", id = "a"); enable(engine)
        val work = async { engine.runQueue() }; entered.await()
        engine.saveSettings(config.copy(model = "new-model"), ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        result.complete(floatArrayOf(1f, 0f, 0f)); assertTrue(work.await())
        assertTrue(engine.index.vectors().isEmpty()); assertEquals(ArchiveIndexState.QUEUED, f.db.archives().job(record.recordId)!!.state)
        assertFalse(engine.runQueue()); assertEquals(2, calls)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), engine.index.vectors().single().vector, .0001f)
    }

    @Test fun changingOnlyCredentialPreservesReadyIndexButDisablingInvalidatesResultsAndOldSnapshots() = runBlocking {
        var calls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> calls++; floatArrayOf(1f, 0f, 0f) })
        save("档案", id = "a"); enable(engine); engine.runQueue(); val snapshot = engine.search("资料")
        val before = engine.index.configuration()!!.fingerprint
        engine.saveSettings(config, ArchiveEmbeddingKeyChange.Replace("replacement-key"), engine.settings.load().revision)
        assertEquals(before, engine.index.configuration()!!.fingerprint); assertFalse(engine.runQueue()); assertEquals(2, calls)
        engine.saveSettings(config.copy(enabled = false), ArchiveEmbeddingKeyChange.Keep, engine.settings.load().revision)
        assertTrue(engine.index.vectors().isEmpty())
        assertEquals(ArchiveSemanticFailure.CHANGED, (runCatching { engine.page(snapshot, 0) }.exceptionOrNull() as ArchiveSemanticException).failure)
        assertFalse(engine.runQueue()); assertEquals(2, calls)
    }

    @Test fun transientFailuresHaveBoundedRetriesAndFatalErrorsWaitForManualAction() = runBlocking {
        var calls = 0; var failure: ArchiveSemanticFailure? = ArchiveSemanticFailure.NETWORK; var scheduled = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> calls++; failure?.let { throw ArchiveSemanticException(it) }; floatArrayOf(1f, 0f, 0f) }) { scheduled++ }
        val record = save("重试资料"); enable(engine)
        assertTrue(engine.runQueue()); assertTrue(engine.runQueue()); assertFalse(engine.runQueue())
        assertEquals(3, calls); assertEquals(3, f.db.archives().job(record.recordId)!!.attempts)
        assertEquals(ArchiveSemanticFailure.NETWORK, engine.index.summary().failure)
        engine.start(); assertEquals(3, calls)
        failure = ArchiveSemanticFailure.AUTHORIZATION; engine.retryIndex(); assertFalse(engine.runQueue()); assertEquals(4, calls)
        assertFalse(engine.runQueue()); assertEquals(4, calls)
        failure = null; engine.retryIndex(); assertFalse(engine.runQueue()); assertEquals(5, calls)
        assertEquals(1, engine.index.summary().ready); assertTrue(scheduled >= 3)
    }

    @Test fun concurrentStartupDoesNotRevokeAnActiveLeaseAndNewProcessRecoveryDoes() = runBlocking {
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> floatArrayOf(1f, 0f, 0f) })
        val record = save("恢复资料"); enable(engine)
        val lease = engine.index.claim(record.recordId)!!
        coroutineScope { (1..30).map { async(Dispatchers.IO) { engine.initialize() } }.awaitAll() }
        assertTrue(engine.index.isCurrent(lease))
        val restarted = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> floatArrayOf(1f, 0f, 0f) })
        restarted.initialize(); assertFalse(engine.index.isCurrent(lease))
        assertFalse(engine.index.complete(lease, listOf(ArchiveEmbeddingPart("旧结果", floatArrayOf(1f, 0f, 0f)))))
        assertFalse(restarted.runQueue()); assertEquals(1, engine.index.summary().ready)
    }

    @Test fun cancellingWorkerMakesItsLeaseRecoverableAndUncooperativeReplyCannotPublish() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val response = CompletableDeferred<FloatArray>()
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> entered.complete(Unit); withContext(NonCancellable) { response.await() } })
        val record = save("取消资料"); enable(engine)
        val work = launch { engine.runQueue() }; entered.await(); work.cancel(); response.complete(floatArrayOf(1f, 0f, 0f)); work.join()
        assertTrue(engine.index.vectors().isEmpty())
        assertEquals(ArchiveIndexState.FAILED, f.db.archives().job(record.recordId)!!.state)
        assertEquals("INTERRUPTED", f.db.archives().job(record.recordId)!!.failureCode)
    }

    @Test fun failedWorkerRegistrationKeepsPersistedJobAndNeverRollsBackTheSavedArchive() = runBlocking {
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, _ -> floatArrayOf(1f, 0f, 0f) }) { error("fixture registration failed") }
        val saved = save("已确认资料"); enable(engine)
        assertNotNull(f.records.record(saved.recordId)); assertEquals(ArchiveIndexState.QUEUED, f.db.archives().job(saved.recordId)!!.state)
        assertFalse(engine.runQueue()); assertEquals(1, engine.index.summary().ready)
    }

    @Test fun rebuildingSameSpaceInvalidatesOldSearchSnapshotsAndPreservesOriginalRecords() = runBlocking {
        var queryCalls = 0
        val engine = f.semantic(ArchiveEmbeddingClient { _, _, _, use -> if (use == ArchiveEmbeddingUse.QUERY) queryCalls++; floatArrayOf(1f, 0f, 0f) })
        val record = save("重建资料"); enable(engine); engine.runQueue()
        val old = engine.search("资料")
        engine.rebuild(); engine.runQueue()
        assertNotNull(f.records.record(record.recordId))
        assertEquals(ArchiveSemanticFailure.CHANGED, (runCatching { engine.page(old, 0) }.exceptionOrNull() as ArchiveSemanticException).failure)
        val fresh = engine.search("资料")
        assertEquals(old.fingerprint, fresh.fingerprint); assertNotEquals(old.configurationRevision, fresh.configurationRevision)
        assertEquals(2, queryCalls); assertEquals(record.recordId, engine.page(fresh, 0).first.single().record.id)
    }

    @Test fun repeatedProcessInterruptionDoesNotResetAutomaticAttemptBudget() = runBlocking {
        var calls = 0
        val client = ArchiveEmbeddingClient { _, _, _, _ -> calls++; floatArrayOf(1f, 0f, 0f) }
        val engine = f.semantic(client); val record = save("中断恢复资料"); enable(engine)
        repeat(3) {
            assertNotNull(engine.index.claim(record.recordId))
            f.semantic(client).initialize() // 模拟进程在请求途中退出，恢复时不重置已消耗的次数。
        }
        assertNull(engine.index.claim(record.recordId))
        assertEquals(ArchiveIndexState.FAILED, f.db.archives().job(record.recordId)!!.state)
        assertEquals(3, f.db.archives().job(record.recordId)!!.attempts)
        assertFalse(engine.runQueue()); assertEquals(0, calls)
        engine.retryIndex(); assertFalse(engine.runQueue()); assertEquals(1, calls)
    }
}
