package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
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
class ArchiveStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var storage: ArchiveStorage
    private lateinit var records: ArchiveRepository
    private lateinit var index: ArchiveIndexRepository
    private var key: SecretKey? = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private var keyCreations = 0
    private val cipher = ArchiveCipher { create ->
        key ?: if (create) SecretKeySpec(ByteArray(32) { 8 }, "AES").also { key = it; keyCreations++ } else throw ArchiveKeyUnavailable()
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        open()
    }
    private fun open() {
        database = MebotDatabase.create(context)
        storage = ArchiveStorage(database, database.archives(), cipher)
        records = ArchiveRepository(storage); index = ArchiveIndexRepository(storage)
    }
    @After fun tearDown() { database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private suspend fun candidate(session: String, title: String = "我的中国银行卡", assets: List<String> = emptyList()): ArchiveDraft =
        records.saveDraft(session, records.draft(session).revision, ArchiveDraftContent(candidate = ArchiveContent(title, fields = listOf(ArchiveField("号码", "6222-fixture")), assetIds = assets)))
    private suspend fun saved(session: String = "a"): ArchiveSaveReceipt {
        records.createSession(session); val draft = candidate(session)
        return records.confirm(session, "save-$session", draft.revision)
    }
    private suspend fun fails(block: suspend () -> Unit) { assertTrue(runCatching { block() }.isFailure) }

    @Test fun commonDatabaseReopensEncryptedStructuredDraftRecordAndIndependentHistory() = runBlocking {
        val saved = saved()
        records.createSession("b")
        val content = ArchiveDraftContent("我的身份证未发送内容", ArchiveContent("身份证", links = listOf(ArchiveLink("https://example.com/a", "入口"))))
        records.saveDraft("b", 0, content)
        val row = requireNotNull(database.archives().draft("b"))
        assertFalse(row.payload.toString(Charsets.UTF_8).contains("身份证"))
        database.close(); open()
        assertEquals(content, records.draft("b").content)
        assertEquals("我的中国银行卡", records.record(saved.recordId)?.content?.title)
        assertEquals(1, records.records().size)
        assertEquals(ArchiveIndexState.WAITING_CONFIGURATION, database.archives().job(saved.recordId)?.state)
        assertNull(database.archives().session("b")?.recordId)
        assertEquals(MebotDatabase.VERSION, database.openHelper.readableDatabase.version)
    }

    @Test fun sessionBindsOnceAndEveryLaterSaveUpdatesSameRecordWithSqlGuard() = runBlocking {
        val first = saved()
        val next = candidate("a", "银行卡补充信息")
        val second = records.confirm("a", "update", next.revision)
        assertEquals(first.recordId, second.recordId); assertEquals(2, second.revision)
        assertEquals(1, records.records().size)
        assertEquals(first.recordId, database.archives().session("a")?.recordId)
        val other = saved("other")
        val sql = database.openHelper.writableDatabase
        fails { sql.execSQL("UPDATE archive_sessions SET recordId = NULL WHERE id = 'a'") }
        fails { sql.execSQL("UPDATE archive_sessions SET recordId = ? WHERE id = 'a'", arrayOf(other.recordId)) }
        fails { sql.execSQL("UPDATE archive_records SET sourceSessionId = 'other' WHERE id = ?", arrayOf(first.recordId)) }
        assertEquals(first.recordId, database.archives().session("a")?.recordId)
    }

    @Test fun confirmationRetryReturnsSameReceiptWithoutClearingNewInputAndRejectsIdReuse() = runBlocking {
        records.createSession("a"); val before = candidate("a")
        val first = records.confirm("a", "operation", before.revision)
        val newDraft = records.saveDraft("a", records.draft("a").revision, ArchiveDraftContent("后来的输入"))
        assertEquals(first, records.confirm("a", "operation", before.revision))
        assertEquals(newDraft, records.draft("a"))
        fails { records.confirm("a", "operation", newDraft.revision) }
        records.createSession("b"); val other = candidate("b", "另一张卡")
        fails { records.confirm("b", "operation", other.revision) }
        assertNull(database.archives().session("b")?.recordId)
        assertEquals(1, records.records().size)
    }

    @Test fun simultaneousSameOperationCommitsOneRecordAndReceipt() = runBlocking {
        records.createSession("a"); val draft = candidate("a")
        val first = async { records.confirm("a", "retry", draft.revision) }
        val second = async { records.confirm("a", "retry", draft.revision) }
        assertEquals(first.await(), second.await()); assertEquals(1, records.records().size)
    }

    @Test fun twoEditingSessionsCannotOverwriteAnotherSessionsNewRevision() = runBlocking {
        val saved = saved()
        records.createSession("edit-a", saved.recordId); records.createSession("edit-b", saved.recordId)
        val a = candidate("edit-a", "新版本"); val b = candidate("edit-b", "过时版本")
        records.confirm("edit-a", "first", a.revision)
        fails { records.confirm("edit-b", "stale", b.revision) }
        assertEquals("新版本", records.record(saved.recordId)?.content?.title)
        assertEquals(b, records.draft("edit-b")); assertNull(database.archives().operation("stale"))
        assertEquals(2L, database.archives().job(saved.recordId)?.recordRevision)
    }

    @Test fun transactionFailureRollsBackRecordBindingReceiptDraftAndIndexTogether() = runBlocking {
        records.createSession("a"); val draft = candidate("a")
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_save BEFORE INSERT ON archive_operations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        fails { records.confirm("a", "save", draft.revision) }
        assertTrue(records.records().isEmpty()); assertNull(database.archives().session("a")?.recordId)
        assertEquals(draft, records.draft("a")); assertTrue(index.queued().isEmpty())
        sql.query("SELECT COUNT(*) FROM archive_index_jobs").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        sql.execSQL("DROP TRIGGER fail_save")
        records.confirm("a", "save", draft.revision)
        assertEquals(1, records.records().size)
    }

    @Test fun messagesAreScopedDeduplicatedAndNeverClearLaterDraft() = runBlocking {
        records.createSession("a"); records.createSession("b")
        val draft = records.saveDraft("a", 0, ArchiveDraftContent("我的卡"))
        val sent = records.sendMessage("a", "message", draft.revision, "我的卡", emptyList())
        val later = records.saveDraft("a", records.draft("a").revision, ArchiveDraftContent("下一句"))
        assertEquals(sent, records.sendMessage("a", "message", draft.revision, "我的卡", emptyList()))
        assertEquals(later, records.draft("a")); assertTrue(records.context("b").messages.isEmpty())
        fails { records.sendMessage("b", "message", 0, "我的卡", emptyList()) }
        fails { records.sendMessage("a", "message", draft.revision, "换内容", emptyList()) }
        assertEquals(listOf("我的卡"), records.context("a").messages.map { it.text })
    }

    @Test fun candidateCannotStealAnotherSessionsAssetAndCompositeMessageForeignKeysProtectScope() = runBlocking {
        records.createSession("a"); records.createSession("b")
        storage.write {
            database.archives().insertAsset(ArchiveAssetEntity("asset", "archive/originals/asset.bin", cipher.encrypt(byteArrayOf(1), "fixture"), 10, 0))
            database.archives().insertSessionAsset(ArchiveSessionAssetEntity("a", "asset", 0, true))
        }
        val draft = candidate("b", assets = listOf("asset"))
        fails { records.confirm("b", "steal", draft.revision) }
        assertNull(database.archives().session("b")?.recordId)
        val b = records.saveDraft("b", draft.revision, ArchiveDraftContent("文字"))
        fails { records.sendMessage("b", "steal-message", b.revision, "文字", listOf("asset")) }
        assertNull(database.archives().message("steal-message"))
        records.sendMessage("b", "b-message", b.revision, "文字", emptyList())
        fails { database.archives().insertMessageAssets(listOf(ArchiveMessageAssetEntity("b-message", "a", "asset", 0))) }
    }

    @Test fun lateCancelledAndReplacedAnalysisCannotOverwriteNewerInputOrAnotherSession() = runBlocking {
        records.createSession("a"); records.createSession("b")
        val old = records.beginAnalysis("a", "old", 0)
        records.saveDraft("a", old.draftRevision, ArchiveDraftContent("用户又输入了"))
        assertFalse(records.completeAnalysis(old, ArchiveContent("迟到结果"), emptyList(), "旧回复"))
        val cancelled = records.beginAnalysis("a", "cancel", records.draft("a").revision)
        records.cancelAnalysis("a", "cancel")
        assertFalse(records.completeAnalysis(cancelled, null, emptyList(), "被取消"))
        val replaced = records.beginAnalysis("a", "replaced", records.draft("a").revision)
        val latest = records.beginAnalysis("a", "latest", replaced.draftRevision)
        assertFalse(records.completeAnalysis(replaced, ArchiveContent("旧"), emptyList(), "旧"))
        assertTrue(records.completeAnalysis(latest, ArchiveContent("最新候选"), emptyList(), "最新回复"))
        assertFalse(records.completeAnalysis(latest, ArchiveContent("重复"), emptyList(), "重复"))
        assertEquals("用户又输入了", records.draft("a").content.input)
        assertEquals("最新候选", records.draft("a").content.candidate?.title)
        assertEquals(listOf("最新回复"), records.context("a").messages.map { it.text })
        assertTrue(records.context("b").messages.isEmpty()); assertNull(records.draft("b").content.candidate)
    }

    @Test fun changedOrDeletedRecordRejectsLateAnalysisAndDeletedSessionCannotCreateReplacement() = runBlocking {
        val saved = saved()
        records.createSession("edit", saved.recordId)
        val pending = records.beginAnalysis("edit", "pending", 0)
        val newer = candidate("a", "新版"); val revision = records.confirm("a", "update", newer.revision)
        assertFalse(records.completeAnalysis(pending, ArchiveContent("旧资料"), emptyList(), "旧资料"))
        val pendingDelete = records.beginAnalysis("edit", "delete-pending", records.draft("edit").revision)
        val deletion = records.delete("a", "delete", revision.revision)
        assertEquals(deletion, records.delete("a", "delete", revision.revision))
        assertFalse(records.completeAnalysis(pendingDelete, ArchiveContent("复活"), emptyList(), "复活"))
        fails { candidate("a", "新建替代") }
        assertNull(records.record(saved.recordId)); assertTrue(records.records().isEmpty())
        assertEquals(saved.recordId, database.archives().session("a")?.recordId)
        assertEquals(ArchiveSessionState.TARGET_DELETED, database.archives().session("edit")?.state)
        assertNull(database.archives().job(saved.recordId)); assertTrue(index.vectors().isEmpty())
    }

    @Test fun missingKeyBlocksReadAndWritesWithoutGeneratingReplacementOrDestroyingCiphertext() = runBlocking {
        val saved = saved(); val original = requireNotNull(database.archives().record(saved.recordId)).payload!!.clone()
        key = null
        fails { records.record(saved.recordId) }; fails { records.createSession("new") }
        assertEquals(0, keyCreations); assertNull(database.archives().session("new"))
        assertArrayEquals(original, database.archives().record(saved.recordId)?.payload)
    }

    @Test fun onlyEmptyStoreMayCreateKeyAndUnresolvedCandidateCannotBeConfirmed() = runBlocking {
        key = null; records.createSession("a"); assertEquals(1, keyCreations)
        val draft = records.saveDraft("a", 0, ArchiveDraftContent(candidate = ArchiveContent("待确认"), conflicts = listOf("缺少归属")))
        fails { records.confirm("a", "invalid", draft.revision) }
        assertNull(database.archives().operation("invalid")); assertTrue(records.records().isEmpty())
    }

    @Test fun incompleteManualEditorPersistsButFormalConfirmationStillRequiresTitle() = runBlocking {
        records.createSession("a")
        val editor = records.prepareManualEditor("a")
        assertEquals("", editor.content.candidate!!.title)
        val incomplete = records.saveDraft("a", editor.revision, editor.content.copy(candidate = editor.content.candidate!!.copy(body = "未完成字段")))
        fails { records.confirm("a", "incomplete", incomplete.revision) }
        database.close(); open()
        assertEquals("未完成字段", records.prepareManualEditor("a").content.candidate!!.body)
        assertTrue(records.records().isEmpty())
    }

    @Test fun sentMessageNeverDiscardsExistingManualFieldsAndHistoryIsScoped() = runBlocking {
        records.createSession("a"); records.createSession("b")
        val candidate = ArchiveContent("手动内容", fields = listOf(ArchiveField("个人字段", "原值")))
        val input = records.saveDraft("a", 0, ArchiveDraftContent("补充消息", candidate))
        records.sendMessage("a", "message", input.revision, "补充消息", emptyList())
        assertEquals(candidate, records.draft("a").content.candidate)
        val history = records.sessions()
        assertEquals(setOf("a", "b"), history.map { it.id }.toSet())
        assertEquals("手动内容", history.first { it.id == "a" }.title)
        assertEquals("补充消息", history.first { it.id == "a" }.preview)
        assertEquals("", history.first { it.id == "b" }.preview)
    }
}
