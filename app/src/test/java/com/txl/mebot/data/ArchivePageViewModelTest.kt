package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.ui.archive.ArchiveViewModel
import java.io.File
import java.time.Duration
import javax.crypto.spec.SecretKeySpec
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchivePageViewModelTest {
    private lateinit var app: Application
    private lateinit var database: MebotDatabase
    private lateinit var records: ArchiveRepository
    private lateinit var files: ArchiveAttachmentRepository
    private lateinit var model: ArchiveViewModel
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var handle: SavedStateHandle
    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "archive").deleteRecursively()
        database = MebotDatabase.create(app)
        val storage = ArchiveStorage(database, database.archives(), ArchiveCipher { SecretKeySpec(ByteArray(32) { 3 }, "AES") })
        records = ArchiveRepository(storage); files = ArchiveAttachmentRepository(app, storage)
        create()
    }
    private fun create(saved: SavedStateHandle = SavedStateHandle()) {
        handle = saved
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ArchiveViewModel(app, saved, records, files) as T
        }
        model = ViewModelProvider(activity!!.get(), factory)[ArchiveViewModel::class.java]
        await { !model.state.value.loading && !model.state.value.busy }
        assertNull(model.state.value.error)
    }
    private fun destroy() { activity?.pause()?.stop()?.destroy(); activity = null; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40)) }
    @After fun tearDown() {
        destroy(); database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "archive").deleteRecursively(); File(app.cacheDir, "archive-picker-fixture.txt").delete()
    }
    private fun idle() = await { !model.state.value.busy && !model.state.value.dirty }
    private fun editor() { model.expand(); model.editCurrent(); idle(); assertNotNull(model.state.value.candidate) }
    private fun home() { model.collapse(); model.finishReturnHome(); idle(); assertFalse(model.state.value.returningHome) }
    private fun saved(title: String): ArchiveRecord {
        editor(); model.changeEditor { it.copy(title = title, body = "完整本地资料") }; idle()
        model.saveEditor(); await { model.state.value.editorSaved && !model.state.value.busy }
        model.editorClosed()
        return model.state.value.detail!!
    }

    @Test fun rapidTypingAndIncompleteEditorSurviveRestartWithoutSavedStateOrApi() = runBlocking {
        val id = model.state.value.context!!.sessionId
        editor()
        repeat(40) { i -> model.changeEditor { it.copy(title = "", body = "未完成资料 $i", links = listOf(ArchiveLink("https://example.com/?x=$i"))) } }
        model.closeEditor(); idle()
        destroy(); create()
        assertNull(model.state.value.candidate)
        model.selectSession(id); idle()
        assertEquals(id, model.state.value.context!!.sessionId)
        assertEquals("", model.state.value.candidate!!.title)
        assertEquals("未完成资料 39", model.state.value.candidate!!.body)
        assertEquals("https://example.com/?x=39", model.state.value.candidate!!.links.single().url)
        assertTrue(records.records().isEmpty())
    }

    @Test fun immediateSwitchFlushesEachSessionsLatestInputAndHistoryRestoresIndependently() = runBlocking {
        val a = model.state.value.context!!.sessionId
        model.changeInput("身份证会话尚未发送")
        model.newSession(); idle()
        val b = model.state.value.context!!.sessionId
        model.changeInput("银行卡会话尚未发送")
        model.selectSession(a); idle()
        assertEquals("身份证会话尚未发送", model.state.value.input)
        assertEquals("银行卡会话尚未发送", records.draft(b).content.input)
        destroy(); create()
        assertEquals(a, model.state.value.context!!.sessionId)
        model.selectSession(b); idle()
        assertEquals("银行卡会话尚未发送", model.state.value.input)
        model.history(); idle()
        assertEquals(setOf(a, b), model.state.value.sessions.map { it.id }.toSet())
    }

    @Test fun duplicateSaveCreatesOneCardAndLaterEditKeepsItsIdentityAndOriginalLinks() = runBlocking {
        editor()
        val urls = listOf("https://example.com/path?a=1&a=2#frag", "https://example.com/path?a=1&a=2#frag", "https://example.com/path?a=3")
        model.changeEditor { it.copy(title = "我的中国银行卡", fields = listOf(ArchiveField("号码", "fixture-card")), links = urls.map { url -> ArchiveLink(url, "原链接") }) }
        idle(); model.saveEditor(); model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.busy }
        assertEquals(1, records.records().size)
        model.editorClosed()
        val first = model.state.value.detail!!
        assertEquals(urls, first.content.links.map { it.url })
        model.editRecord(first.id, true); idle()
        model.changeEditor { it.copy(title = "银行卡补充") }; idle(); model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.busy }
        assertEquals(first.id, records.records().single().id)
        assertEquals(2L, records.records().single().revision)
        assertEquals(urls, records.records().single().content.links.map { it.url })
    }

    @Test fun pickerResultQueuedDuringSwitchStillImportsIntoItsOriginalSession() = runBlocking {
        val a = model.state.value.context!!.sessionId
        assertTrue(model.preparePicker())
        val source = File(app.cacheDir, "archive-picker-fixture.txt").apply { writeText("source fixture text") }
        model.newSession()
        model.picked(listOf(source.toUri())) // 导航尚在进行时返回，不丢结果，也不跟随当前会话。
        idle()
        val b = model.state.value.context!!.sessionId
        assertNotEquals(a, b); assertTrue(model.state.value.context!!.assets.isEmpty())
        assertEquals(1, records.context(a).assets.size)
        assertTrue(records.context(b).assets.isEmpty())
        source.delete(); destroy(); create()
        model.selectSession(a); idle()
        val asset = model.state.value.context!!.assets.single()
        assertEquals("archive-picker-fixture.txt", asset.info.name)
        files.withDecryptedFile(asset.id) { assertEquals("source fixture text", it.readText()) }
    }

    @Test fun sendingMoreTextPreservesAnUnfinishedManualEditor() = runBlocking {
        val id = model.state.value.context!!.sessionId
        editor(); model.changeEditor { it.copy(title = "手动标题", fields = listOf(ArchiveField("手动字段", "不可丢失"))) }; idle()
        model.closeEditor(); idle(); model.changeInput("补充消息"); idle(); model.send(); idle()
        assertEquals("手动标题", model.state.value.candidate!!.title)
        assertEquals("不可丢失", model.state.value.candidate!!.fields.single().value)
        assertEquals(listOf("补充消息"), model.state.value.context!!.messages.map { it.text })
        destroy(); create(); model.selectSession(id); idle(); assertEquals("手动标题", model.state.value.candidate!!.title)
    }

    @Test fun failedDraftWritePreventsNavigationAndRetryPreservesLatestInput() = runBlocking {
        val original = model.state.value.context!!.sessionId
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_draft BEFORE UPDATE ON archive_drafts BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        model.changeInput("不能丢失的草稿"); model.newSession()
        await { model.state.value.error != null && !model.state.value.busy }
        assertEquals(original, model.state.value.context!!.sessionId)
        assertEquals("不能丢失的草稿", model.state.value.input); assertTrue(model.state.value.dirty)
        assertEquals(1, records.sessions().size)
        sql.execSQL("DROP TRIGGER fail_draft")
        model.saveInput(); idle()
        assertEquals("不能丢失的草稿", records.draft(original).content.input)
    }

    @Test fun failedConfirmationRollsBackAndRetryUsesOneFormalRecord() = runBlocking {
        editor(); model.changeEditor { it.copy(title = "需要重试") }; idle()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_confirm BEFORE INSERT ON archive_operations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        model.saveEditor(); await { model.state.value.error != null && !model.state.value.busy }
        assertFalse(model.state.value.editorSaved); assertTrue(records.records().isEmpty())
        assertEquals("需要重试", model.state.value.candidate!!.title)
        sql.execSQL("DROP TRIGGER fail_confirm")
        model.saveEditor(); await { model.state.value.editorSaved && !model.state.value.busy }
        assertEquals(1, records.records().size)
    }

    @Test fun staleEditorCannotOverwriteNewerRecordAndExplicitReloadAllowsReviewingLatest() = runBlocking {
        val first = saved("原档案")
        model.editRecord(first.id, true); idle()
        model.changeEditor { it.copy(title = "过时编辑") }; idle()
        records.createSession("other-editor", first.id)
        val other = records.saveDraft("other-editor", 0, ArchiveDraftContent(candidate = ArchiveContent("外部新版本")))
        records.confirm("other-editor", "other-save", other.revision)
        model.saveEditor(); await { !model.state.value.busy && model.state.value.conflict }
        assertEquals("过时编辑", model.state.value.candidate!!.title)
        assertEquals("外部新版本", records.record(first.id)!!.content.title)
        model.reloadEditor(); idle()
        assertEquals("外部新版本", model.state.value.candidate!!.title)
        model.changeEditor { it.copy(body = "核对后的补充") }; idle(); model.saveEditor()
        await { model.state.value.editorSaved && !model.state.value.busy }
        assertEquals(3L, records.record(first.id)!!.revision)
    }

    @Test fun deletedCardDisappearsWhileItsClosedConversationAndMessagesRemain() = runBlocking {
        model.changeInput("历史原文"); idle(); model.send(); idle()
        val first = saved("待删除档案")
        model.delete(first); idle()
        assertTrue(model.state.value.cards.isEmpty()); assertNull(records.record(first.id))
        assertTrue(model.state.value.context!!.closed)
        assertEquals("历史原文", model.state.value.context!!.messages.single().text)
        model.history(); idle(); assertTrue(model.state.value.sessions.single().closed)
    }

    @Test fun cardsAndSessionHistoryPageWithoutDroppingOrDuplicatingLocalRecords() = runBlocking {
        repeat(55) { i ->
            val id = records.createSession("page-$i")
            val draft = records.saveDraft(id, 0, ArchiveDraftContent(candidate = ArchiveContent("档案 $i")))
            records.confirm(id, "save-page-$i", draft.revision)
        }
        model.refreshList(); idle()
        assertEquals(50, model.state.value.cards.size); assertTrue(model.state.value.moreRecords)
        model.moreRecords(); idle()
        assertEquals(55, model.state.value.cards.map { it.record.id }.distinct().size); assertFalse(model.state.value.moreRecords)
        model.history(); idle(); assertTrue(model.state.value.moreSessions)
        model.history(true); idle()
        assertEquals(56, model.state.value.sessions.map { it.id }.distinct().size); assertFalse(model.state.value.moreSessions)
        val cards = model.state.value.cards
        model.search()
        assertEquals(cards, model.state.value.cards)
        assertTrue(model.state.value.notice!!.contains("语义搜索"))
    }

    @Test fun pickerOwnershipSurvivesActivityRecreationWhileAnotherConversationIsSelected() = runBlocking {
        val a = model.state.value.context!!.sessionId
        assertTrue(model.preparePicker())
        model.newSession(); idle()
        val b = model.state.value.context!!.sessionId
        val originalHandle = handle
        destroy(); create(originalHandle)
        assertEquals(b, model.state.value.context!!.sessionId)
        val source = File(app.cacheDir, "archive-picker-fixture.txt").apply { writeText("恢复选择器结果") }
        model.picked(listOf(source.toUri())); idle()
        assertEquals(b, model.state.value.context!!.sessionId)
        assertEquals(1, records.context(a).assets.size); assertTrue(records.context(b).assets.isEmpty())
    }

    @Test fun savingThenTypingAtHomeCreatesSeparateCardAndDetailEditOpensOriginalChat() = runBlocking {
        val a = model.state.value.context!!.sessionId
        model.changeInput("银行卡原始资料"); idle(); model.send(); idle()
        val cardA = saved("银行卡")
        model.finishReturnHome(); idle()
        assertFalse(model.state.value.expanded); assertNull(model.state.value.context!!.record)
        assertTrue(model.state.value.context!!.messages.isEmpty()); assertEquals("", model.state.value.input)
        val b = model.state.value.context!!.sessionId
        assertNotEquals(a, b)
        model.changeInput("身份证原始资料"); idle(); model.send(); idle()
        assertEquals(b, model.state.value.context!!.sessionId)
        val cardB = saved("身份证")
        model.finishReturnHome(); idle()
        assertEquals(2, records.records().size)
        val homeId = model.state.value.context!!.sessionId
        model.openDetail(cardA.id); idle()
        assertEquals(cardA.id, model.state.value.detail!!.id); assertEquals(homeId, model.state.value.context!!.sessionId)
        model.editRecord(cardA.id); idle()
        assertTrue(model.state.value.expanded); assertFalse(model.state.value.editorOpen)
        assertNull(model.state.value.detail); assertEquals(a, model.state.value.context!!.sessionId)
        assertEquals(listOf("银行卡原始资料"), model.state.value.context!!.messages.map { it.text })
        model.changeInput("补充银行卡资料"); idle(); model.send(); idle()
        assertEquals(cardA.id, model.state.value.context!!.record!!.id)
        assertEquals(listOf("身份证原始资料"), records.context(b).messages.map { it.text })
        assertEquals("身份证", records.record(cardB.id)!!.content.title)
    }

    @Test fun closingUnconfirmedChatPreservesItsDraftAndUnsentInputOutsideTheHomeComposer() = runBlocking {
        val a = model.state.value.context!!.sessionId
        model.changeInput("档案 A 的原始资料"); idle(); model.send(); idle()
        editor(); model.changeEditor { it.copy(title = "未保存的 A", body = "待确认资料") }; idle(); model.closeEditor(); idle()
        model.changeInput("A 的未发送补充")
        home()
        val b = model.state.value.context!!.sessionId
        assertNotEquals(a, b); assertEquals("", model.state.value.input); assertNull(model.state.value.candidate)
        assertEquals("A 的未发送补充", records.draft(a).content.input)
        assertEquals("未保存的 A", records.draft(a).content.candidate!!.title)
        model.changeInput("档案 B 的资料"); idle(); model.send(); idle()
        assertEquals(b, model.state.value.context!!.sessionId)
        assertEquals(listOf("档案 B 的资料"), model.state.value.context!!.messages.map { it.text })
        model.selectSession(a); idle()
        assertEquals("A 的未发送补充", model.state.value.input)
        assertEquals("未保存的 A", model.state.value.candidate!!.title)
    }

    @Test fun homeTextAndPendingAttachmentSurviveEditingACardAndReturnToSameNewArchive() = runBlocking {
        records.createSession("card-chat")
        val draft = records.saveDraft("card-chat", 0, ArchiveDraftContent(candidate = ArchiveContent("已保存的卡片")))
        val record = records.confirm("card-chat", "card-save", draft.revision)
        val h = model.state.value.context!!.sessionId
        model.changeInput("首页未发送的新档案")
        val source = File(app.cacheDir, "archive-picker-fixture.txt").apply { writeText("新档案的附件") }
        assertTrue(model.preparePicker()); model.picked(listOf(source.toUri())); idle()
        val asset = model.state.value.pending.single().id
        model.openDetail(record.recordId); idle(); model.editRecord(record.recordId); idle()
        assertEquals("card-chat", model.state.value.context!!.sessionId)
        assertTrue(model.state.value.pending.isEmpty()); assertEquals("", model.state.value.input)
        model.changeInput("卡片会话的未发送补充")
        home()
        assertEquals(h, model.state.value.context!!.sessionId)
        assertEquals("首页未发送的新档案", model.state.value.input); assertEquals(asset, model.state.value.pending.single().id)
        assertEquals("卡片会话的未发送补充", records.draft("card-chat").content.input)
        files.withDecryptedFile(asset) { assertEquals("新档案的附件", it.readText()) }
    }

    @Test fun cardsOlderThanHistoryPageAlwaysReopenTheirOriginalConversationWithoutCreatingAnother() = runBlocking {
        val original = model.state.value.context!!.sessionId
        model.changeInput("最初的档案聊天"); idle(); model.send(); idle()
        val card = saved("较早的档案"); model.finishReturnHome(); idle()
        repeat(110) { i -> records.createSession("recent-$i"); val draft = records.saveDraft("recent-$i", 0, ArchiveDraftContent(input = "其他会话 $i")); records.sendMessage("recent-$i", "recent-message-$i", draft.revision, draft.content.input, emptyList()) }
        records.createSession("legacy-extra-editor", card.id)
        val legacy = records.saveDraft("legacy-extra-editor", 0, ArchiveDraftContent(input = "遗留的另一次编辑"))
        records.sendMessage("legacy-extra-editor", "legacy-message", legacy.revision, legacy.content.input, emptyList())
        assertFalse(records.sessions(100).any { it.id == original })
        val sessions = database.archives().recordSessions(card.id).map { it.id }.sorted()
        repeat(3) {
            model.openDetail(card.id); idle(); model.editRecord(card.id); idle()
            assertEquals(original, model.state.value.context!!.sessionId)
            assertEquals(listOf("最初的档案聊天"), model.state.value.context!!.messages.map { it.text })
            home()
        }
        assertEquals(sessions, database.archives().recordSessions(card.id).map { it.id }.sorted())
    }

    @Test fun restartingOnHomeKeepsNewInputSeparateFromSavedCardAndCardStillRestoresHistory() = runBlocking {
        val original = model.state.value.context!!.sessionId
        model.changeInput("保存前的原始消息"); idle(); model.send(); idle()
        val card = saved("已保存档案"); model.finishReturnHome(); idle()
        val h = model.state.value.context!!.sessionId
        model.changeInput("下一份档案还未发送"); idle()
        destroy(); create()
        assertEquals(h, model.state.value.context!!.sessionId); assertNull(model.state.value.context!!.record)
        assertEquals("下一份档案还未发送", model.state.value.input)
        model.editRecord(card.id); idle()
        assertEquals(original, model.state.value.context!!.sessionId)
        assertEquals(listOf("保存前的原始消息"), model.state.value.context!!.messages.map { it.text })
    }

    @Test fun collapseWriteFailurePreservesTheOldInputAndRetryCanSafelyReturnHome() = runBlocking {
        val a = model.state.value.context!!.sessionId
        model.changeInput("档案原文"); idle(); model.send(); idle()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_home BEFORE UPDATE ON archive_drafts BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        model.changeInput("退出前尚未发送的补充"); model.collapse(); model.finishReturnHome()
        await { model.state.value.error != null && !model.state.value.busy }
        assertEquals(a, model.state.value.context!!.sessionId); assertEquals("退出前尚未发送的补充", model.state.value.input)
        assertTrue(model.state.value.returningHome); assertFalse(model.state.value.editable)
        sql.execSQL("DROP TRIGGER fail_home")
        model.finishReturnHome(); idle()
        assertNotEquals(a, model.state.value.context!!.sessionId); assertEquals("", model.state.value.input)
        assertEquals("退出前尚未发送的补充", records.draft(a).content.input)
    }

    @Test fun returningToUnusedHomeDoesNotAccumulateEmptySessions() = runBlocking {
        val h = model.state.value.context!!.sessionId
        repeat(4) { home(); assertEquals(h, model.state.value.context!!.sessionId) }
        assertEquals(1, records.sessions().size)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待档案本地状态超时：loading=${model.state.value.loading}, busy=${model.state.value.busy}, dirty=${model.state.value.dirty}, error=${model.state.value.error}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
