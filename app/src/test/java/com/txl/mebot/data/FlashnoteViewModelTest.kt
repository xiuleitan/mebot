package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.ui.flashnote.FlashnoteInputSaveState
import com.txl.mebot.ui.flashnote.FlashnoteViewModel
import com.txl.mebot.ui.flashnote.asEntry
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FlashnoteViewModelTest {
    private lateinit var application: Application
    private lateinit var database: MebotDatabase
    private lateinit var repository: FlashnoteRepository
    private lateinit var clock: PageClock
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: FlashnoteViewModel

    @Before fun setUp() {
        resetDefaultFactory()
        application = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(application, MebotDatabase::class.java).build()
        clock = PageClock()
        repository = FlashnoteRepository(database, clock, dayZone = { ZoneOffset.UTC })
        createModel()
    }

    private fun createModel(handle: SavedStateHandle = SavedStateHandle(),
        commit: suspend (FlashnoteDraftEntity) -> FlashnoteEntity = repository::save) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FlashnoteViewModel(application, handle, repository, commit,
                statisticsClock = clock, statisticsZone = { ZoneOffset.UTC }) as T
        }
        model = ViewModelProvider(activity.get(), factory)[FlashnoteViewModel::class.java]
        model.setVisible(true)
        await { model.state.value.draftLoaded && !model.state.value.loading && model.state.value.countsLoaded }
    }

    private fun destroyModel() {
        model.setVisible(false)
        await { !model.state.value.isSaving && model.state.value.inputSaveState != FlashnoteInputSaveState.SAVING &&
            model.state.value.pendingOperations.isEmpty() && model.state.value.editor?.let {
                !it.busy && it.inputSaveState != FlashnoteInputSaveState.SAVING
            } != false }
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @After fun tearDown() {
        destroyModel()
        database.close()
        (application as? MebotApplication)?.container?.let { it.stopMaintenance(); it.database.close() }
        application.deleteDatabase(MebotDatabase.FILE_NAME)
        resetDefaultFactory()
    }

    @Test fun emptyDatabaseHasRealEmptyListsAndNoExampleNotes() = runBlocking {
        assertTrue(model.state.value.entries.isEmpty())
        assertTrue(model.state.value.counts.isEmpty())
        assertEquals("", model.state.value.input)
        assertFalse(model.state.value.canSave)
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
        assertTrue(repository.notes(FlashnoteStatus.COMPLETED).isEmpty())
        model.selectStatus(FlashnoteStatus.COMPLETED)
        await { !model.state.value.loading }
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test fun rapidTypingDoubleSaveAndPageExitCommitOnlyTheLatestInputOnce() = runBlocking {
        repeat(50) { model.changeInput("一件想做的事\n补充内容 $it") }
        model.save()
        model.save()
        model.setVisible(false)
        await { !model.state.value.isSaving && model.state.value.savedEvent != null && model.state.value.draftLoaded }
        model.setVisible(true)
        await { model.state.value.entries.size == 1 && model.state.value.counts[FlashnoteStatus.PENDING] == 1 }
        val saved = repository.notes(FlashnoteStatus.PENDING).single()
        assertEquals("一件想做的事", saved.title)
        assertEquals("一件想做的事\n补充内容 49", saved.content)
        assertEquals("", model.state.value.input)
        assertEquals(saved.id, model.state.value.savedEvent?.id)
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun failedCommitRetainsInputAndRetryUsesTheSameDraft() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_flashnote_page_cleanup
            BEFORE DELETE ON flashnote_drafts BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
        model.changeInput("失败后仍要保留\n第二行")
        model.save()
        await { !model.state.value.isSaving && model.state.value.saveFailed }
        assertEquals("失败后仍要保留\n第二行", model.state.value.input)
        assertFalse(model.state.value.inputEditable)
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
        assertEquals(model.state.value.input, repository.openCreation().content)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_flashnote_page_cleanup")
        model.save()
        await { !model.state.value.isSaving && !model.state.value.saveFailed && model.state.value.entries.size == 1 }
        assertEquals("", model.state.value.input)
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun lostAcknowledgementRetryDoesNotDuplicateAnAlreadyCommittedNote() = runBlocking {
        destroyModel()
        var failOnce = true
        createModel(commit = { draft ->
            val saved = repository.save(draft)
            if (failOnce) { failOnce = false; throw IOException("simulated missing acknowledgement") }
            saved
        })
        model.changeInput("已经落库但首次没有收到结果")
        model.save()
        await { !model.state.value.isSaving && model.state.value.saveFailed }
        val first = repository.notes(FlashnoteStatus.PENDING).single()
        assertEquals(first.content, model.state.value.input)
        model.changeInput("不能混入下一次提交")
        assertEquals(first.content, model.state.value.input)
        model.save()
        await { !model.state.value.isSaving && !model.state.value.saveFailed && model.state.value.input.isEmpty() }
        assertEquals(listOf(first), repository.notes(FlashnoteStatus.PENDING))
    }

    @Test fun leavingBeforeDebounceFlushesInputAndANewModelRestoresItWithoutSubmitting() = runBlocking {
        model.changeInput("还没提交的多行草稿\n继续补充")
        model.setVisible(false)
        await { model.state.value.inputSaveState == FlashnoteInputSaveState.SAVED }
        assertEquals(model.state.value.input, repository.openCreation().content)
        destroyModel()
        createModel()
        assertEquals("还没提交的多行草稿\n继续补充", model.state.value.input)
        assertTrue(model.state.value.entries.isEmpty())
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun listsCountsAndSelectedDetailFollowDatabaseChangesAndDeletion() = runBlocking {
        val first = create("第一件\n第一件详情", 1_000)
        val second = create("第二件", 2_000)
        repository.complete(second.id, second.revision)
        val third = create("第三件", 3_000)
        await { model.state.value.entries.map { it.id } == listOf(first.id, third.id) && model.state.value.counts[FlashnoteStatus.COMPLETED] == 1 }
        model.openDetail(first.id)
        await { !model.state.value.detailLoading && model.state.value.detail?.id == first.id }
        val editing = repository.openEditing(first.id)
        val changed = repository.saveInput(editing.key, "修改后的第一件", "数据库中的新详情", editing.revision)
        val updated = repository.save(changed)
        await { model.state.value.detail?.title == updated.title && model.state.value.entries.first().title == updated.title }
        assertEquals(first.createdAt, model.state.value.detail?.createdAt)
        repeat(8) { model.selectStatus(FlashnoteStatus.COMPLETED); model.selectStatus(FlashnoteStatus.PENDING) }
        model.selectStatus(FlashnoteStatus.COMPLETED)
        await { !model.state.value.loading && model.state.value.entries.map { it.id } == listOf(second.id) }
        repository.complete(third.id, third.revision)
        await { model.state.value.entries.map { it.id } == listOf(second.id, third.id) && model.state.value.counts[FlashnoteStatus.COMPLETED] == 2 }
        repository.delete(updated.id, updated.revision)
        await { !model.state.value.detailLoading && model.state.value.detail == null && (model.state.value.counts[FlashnoteStatus.PENDING] ?: 0) == 0 }
        assertEquals(first.id, model.state.value.detailId)
        model.selectStatus(FlashnoteStatus.PENDING)
        await { !model.state.value.loading }
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test fun savedFilterAndDetailRestoreAndResumeRequeriesHiddenChanges() = runBlocking {
        val saved = create("已完成的记录", 1_000)
        val completed = repository.complete(saved.id, saved.revision)
        destroyModel()
        createModel(SavedStateHandle(mapOf("flashnote.status" to FlashnoteStatus.COMPLETED.name, "flashnote.detail" to saved.id)))
        await { !model.state.value.detailLoading && model.state.value.detail?.id == saved.id }
        assertEquals(FlashnoteStatus.COMPLETED, model.state.value.status)
        assertEquals(listOf(saved.id), model.state.value.entries.map { it.id })
        model.setVisible(false)
        repository.restore(saved.id, completed.revision)
        model.setVisible(true)
        await { !model.state.value.loading && model.state.value.entries.isEmpty() && model.state.value.detail?.completed == false }
        model.selectStatus(FlashnoteStatus.PENDING)
        await { !model.state.value.loading && model.state.value.entries.singleOrNull()?.id == saved.id }
    }

    @Test fun draftConflictRetainsLocalTextUntilExplicitKeepInputRetry() = runBlocking {
        val draft = repository.openCreation()
        repository.saveInput(draft.key, "", "其他上下文已保存的输入", draft.revision)
        model.changeInput("本页面尚未保存的文字")
        model.savePendingInput()
        await { model.state.value.inputSaveState == FlashnoteInputSaveState.CONFLICT }
        assertEquals("本页面尚未保存的文字", model.state.value.input)
        assertEquals("其他上下文已保存的输入", repository.openCreation().content)
        model.retryInputSave()
        await { model.state.value.inputSaveState == FlashnoteInputSaveState.SAVED }
        assertEquals("本页面尚未保存的文字", repository.openCreation().content)
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun closedOrChangedDetailIgnoresOldSelectionResults() = runBlocking {
        val first = create("第一项", 1_000)
        val second = create("第二项", 2_000)
        model.openDetail(first.id)
        model.openDetail(second.id)
        await { !model.state.value.detailLoading && model.state.value.detail?.id == second.id }
        model.openDetail(null)
        repository.complete(first.id, first.revision)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertNull(model.state.value.detailId)
        assertNull(model.state.value.detail)
    }

    @Test fun inFlightCommitSurvivesPageAndViewModelClosure() = runBlocking {
        destroyModel()
        val started = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        createModel(commit = { draft -> started.set(true); release.await(); repository.save(draft) })
        val closing = model
        model.changeInput("退出期间仍要保存")
        model.save()
        await { started.get() }
        model.setVisible(false)
        activity.pause().stop().destroy()
        release.complete(Unit)
        await { !closing.state.value.isSaving && closing.state.value.savedEvent != null }
        assertEquals("退出期间仍要保存", repository.notes(FlashnoteStatus.PENDING).single().content)
        createModel()
        await { model.state.value.entries.size == 1 }
        assertEquals("", model.state.value.input)
    }

    @Test fun detailAndEditingPreserveFullContentCreationOrderAndIndependentComposer() = runBlocking {
        val originalBody = "第一件想做的事\n" + (1..120).joinToString("\n") { "第 $it 行：完整的具体内容 https://example.com/$it" }
        val first = create(originalBody, 1_000)
        val second = create("后创建的第二件", 2_000)
        model.changeInput("主页面还未提交的想法")
        edit(first.id)
        assertEquals(originalBody, model.state.value.detail?.detail)
        assertEquals(originalBody, model.state.value.editor?.content)
        val changedBody = originalBody + "\n\n继续补充，不限制长度"
        clock.time = 9_000
        model.changeEditTitle("编辑后的长标题不套用日程的八字限制")
        model.changeEditContent(changedBody)
        model.saveEdit()
        model.saveEdit()
        await { model.state.value.editor == null && model.state.value.detail?.detail == changedBody &&
            model.state.value.entries.firstOrNull()?.title == "编辑后的长标题不套用日程的八字限制" }
        val saved = requireNotNull(repository.note(first.id))
        assertEquals(first.createdAt, saved.createdAt)
        assertEquals(2L, saved.revision)
        assertEquals(listOf(first.id, second.id), model.state.value.entries.map { it.id })
        assertEquals("主页面还未提交的想法", model.state.value.input)
        await { model.state.value.inputSaveState == FlashnoteInputSaveState.SAVED }
        assertEquals("主页面还未提交的想法", repository.openCreation().content)
        assertNull(database.flashnoteDao().editingDraft(first.id))
    }

    @Test fun cancellingEditingDiscardsOnlyItsDraftAndLateAutosaveCannotRecreateIt() = runBlocking {
        val original = create("原始标题\n原始正文", 1_000)
        model.changeInput("独立的新建草稿")
        edit(original.id)
        model.changeEditTitle("尚未保存的标题")
        model.changeEditContent("已暂存的编辑正文")
        await { model.state.value.editor?.inputSaveState == FlashnoteInputSaveState.SAVED }
        model.changeEditContent("取消前刚输入，尚未防抖保存")
        model.cancelEdit()
        await { model.state.value.editor == null }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(original, repository.note(original.id))
        assertNull(database.flashnoteDao().editingDraft(original.id))
        assertEquals("独立的新建草稿", repository.openCreation().content)
        edit(original.id)
        assertEquals(original.content, model.state.value.editor?.content)
        assertEquals(original.title, model.state.value.editor?.title)
    }

    @Test fun closingEditingFlushesItsDraftAndANewModelResumesWithoutChangingTheNote() = runBlocking {
        val original = create("尚未编辑的正文", 1_000)
        edit(original.id)
        model.changeEditTitle("重开可恢复的标题")
        model.changeEditContent("关闭时尚未提交\n保留所有换行")
        model.openDetail(null)
        await { runBlocking { database.flashnoteDao().editingDraft(original.id)?.content == "关闭时尚未提交\n保留所有换行" } }
        assertEquals(original, repository.note(original.id))
        destroyModel()
        createModel()
        edit(original.id)
        assertEquals("重开可恢复的标题", model.state.value.editor?.title)
        assertEquals("关闭时尚未提交\n保留所有换行", model.state.value.editor?.content)
        assertEquals(original, repository.note(original.id))
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun failedEditRollsBackAndRetryKeepsTheSameSnapshotAndCreationTime() = runBlocking {
        val original = create("原始正文", 1_000)
        edit(original.id)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_edit_cleanup BEFORE DELETE ON flashnote_drafts
            WHEN OLD.editingNoteId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
        model.changeEditTitle("")
        model.changeEditContent("新的正文首行\n保存失败后仍保留")
        model.saveEdit()
        await { model.state.value.editor?.let { it.saveFailed && !it.isSaving } == true }
        assertEquals(original, repository.note(original.id))
        assertEquals("新的正文首行\n保存失败后仍保留", model.state.value.editor?.content)
        model.changeEditContent("不能混进另一次提交")
        assertEquals("新的正文首行\n保存失败后仍保留", model.state.value.editor?.content)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit_cleanup")
        model.saveEdit()
        await { model.state.value.editor == null && model.state.value.detail?.title == "新的正文首行" }
        val saved = requireNotNull(repository.note(original.id))
        assertEquals(original.createdAt, saved.createdAt)
        assertEquals(2L, saved.revision)
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun lostEditAcknowledgementRetryDoesNotApplyTheEditTwice() = runBlocking {
        val original = create("旧内容", 1_000)
        destroyModel()
        var failOnce = true
        createModel(commit = { draft ->
            val saved = repository.save(draft)
            if (draft.editingNoteId != null && failOnce) { failOnce = false; throw IOException("missing acknowledgement") }
            saved
        })
        edit(original.id)
        model.changeEditContent("已落库但未确认的修改")
        model.saveEdit()
        await { model.state.value.editor?.let { it.saveFailed && !it.isSaving } == true }
        val committed = requireNotNull(repository.note(original.id))
        assertEquals(2L, committed.revision)
        model.saveEdit()
        model.saveEdit()
        await { model.state.value.editor == null }
        assertEquals(committed, repository.note(original.id))
        assertNull(database.flashnoteDao().editingDraft(original.id))
    }

    @Test fun completionAndRestorationFromPageAreDeduplicatedAndKeepOldestFirst() = runBlocking {
        val first = create("最早", 1_000)
        val second = create("居中", 2_000)
        val third = create("最新", 3_000)
        model.toggleCompleted(third.asEntry())
        repeat(10) { model.toggleCompleted(first.asEntry()) }
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.entries.map { it.id } == listOf(second.id) &&
            model.state.value.counts[FlashnoteStatus.COMPLETED] == 2 }
        model.selectStatus(FlashnoteStatus.COMPLETED)
        await { !model.state.value.loading && model.state.value.entries.map { it.id } == listOf(first.id, third.id) }
        val completed = requireNotNull(repository.note(first.id))
        assertEquals(2L, completed.revision)
        assertEquals(first.createdAt, completed.createdAt)
        model.openDetail(first.id)
        await { model.state.value.detail?.completed == true && !model.state.value.detailLoading }
        repeat(8) { model.toggleCompleted(completed.asEntry()) }
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.detail?.completed == false &&
            model.state.value.entries.map { it.id } == listOf(third.id) }
        val restored = requireNotNull(repository.note(first.id))
        assertEquals(3L, restored.revision)
        assertNull(restored.completedAt)
        model.selectStatus(FlashnoteStatus.PENDING)
        await { !model.state.value.loading && model.state.value.entries.map { it.id } == listOf(first.id, second.id) }
    }

    @Test fun failedStatusWriteKeepsOriginalStateAndCanRetryFromPage() = runBlocking {
        val original = create("状态操作", 1_000)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_flashnote_status BEFORE UPDATE OF status ON flashnotes
            BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
        model.toggleCompleted(original.asEntry())
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.actionFailure != null }
        assertEquals(original, repository.note(original.id))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_flashnote_status")
        model.retryAction()
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.actionFailure == null &&
            (model.state.value.counts[FlashnoteStatus.PENDING] ?: 0) == 0 }
        assertEquals(FlashnoteStatus.COMPLETED, repository.note(original.id)?.status)
    }

    @Test fun editingACompletedNoteKeepsItsStatusCompletionTimeAndSelectedList() = runBlocking {
        val first = create("较早完成的事项", 1_000)
        val second = create("较晚创建的事项", 2_000)
        val completed = repository.complete(first.id, first.revision)
        repository.complete(second.id, second.revision)
        model.selectStatus(FlashnoteStatus.COMPLETED)
        await { !model.state.value.loading && model.state.value.entries.size == 2 }
        edit(first.id)
        model.changeEditContent("已完成事项的完整补充内容\n仍然保持已完成")
        model.saveEdit()
        await { model.state.value.editor == null && model.state.value.detail?.detail == "已完成事项的完整补充内容\n仍然保持已完成" }
        val saved = requireNotNull(repository.note(first.id))
        assertEquals(FlashnoteStatus.COMPLETED, model.state.value.status)
        assertEquals(FlashnoteStatus.COMPLETED, saved.status)
        assertEquals(completed.completedAt, saved.completedAt)
        assertEquals(first.createdAt, saved.createdAt)
        assertEquals(listOf(first.id, second.id), model.state.value.entries.map { it.id })
    }

    @Test fun deleteFailureRetriesAndSuccessCleansEditingDraftWithoutAffectingComposer() = runBlocking {
        val original = create("准备删除", 1_000)
        model.changeInput("删除后仍保留的新建草稿")
        edit(original.id)
        model.changeEditContent("关联的未提交编辑草稿")
        model.openDetail(null)
        await { runBlocking { database.flashnoteDao().editingDraft(original.id)?.content == "关联的未提交编辑草稿" } }
        model.openDetail(original.id)
        await { model.state.value.detail?.id == original.id && !model.state.value.detailLoading }
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_flashnote_delete BEFORE DELETE ON flashnotes
            BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
        model.delete(original.asEntry())
        model.delete(original.asEntry())
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.actionFailure != null }
        assertEquals(original, repository.note(original.id))
        assertFalse(model.state.value.detailDeleted)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_flashnote_delete")
        model.retryAction()
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.detailDeleted }
        model.openDetail(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertNull(repository.note(original.id))
        assertNull(database.flashnoteDao().editingDraft(original.id))
        assertEquals("删除后仍保留的新建草稿", repository.openCreation().content)
        assertNull(model.state.value.detailId)
    }

    @Test fun staleActionRequiresARefreshAndCannotDeleteANewerRevisionOnRetry() = runBlocking {
        val original = create("原始内容", 1_000)
        model.openDetail(original.id)
        await { model.state.value.detail?.id == original.id && !model.state.value.detailLoading }
        val draft = repository.openEditing(original.id)
        val newer = repository.save(repository.saveInput(draft.key, "新标题", "已更新的内容", draft.revision))
        model.delete(original.asEntry())
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.actionFailure?.conflict == true }
        model.retryAction()
        await { model.state.value.actionFailure == null && model.state.value.detail?.revision == newer.revision && !model.state.value.detailLoading }
        assertEquals(newer, repository.note(original.id))
        assertFalse(model.state.value.detailDeleted)
        model.delete(requireNotNull(model.state.value.detail))
        await { model.state.value.detailDeleted }
        assertNull(repository.note(original.id))
    }

    @Test fun cancellationFailureRetainsDraftAndRetryDoesNotChangeTheOriginalNote() = runBlocking {
        val original = create("保持原样", 1_000)
        edit(original.id)
        model.changeEditContent("应在取消成功后删除的修改")
        await { model.state.value.editor?.inputSaveState == FlashnoteInputSaveState.SAVED }
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_cancel_edit BEFORE DELETE ON flashnote_drafts
            WHEN OLD.editingNoteId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
        model.cancelEdit()
        await { model.state.value.editor?.let { it.cancelFailed && !it.isCancelling } == true }
        assertEquals("应在取消成功后删除的修改", model.state.value.editor?.content)
        assertEquals(original, repository.note(original.id))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cancel_edit")
        model.cancelEdit()
        await { model.state.value.editor == null }
        assertEquals(original, repository.note(original.id))
        assertNull(database.flashnoteDao().editingDraft(original.id))
    }

    @Test fun editingDraftConflictKeepsLocalTextUntilExplicitRetry() = runBlocking {
        val original = create("原始内容", 1_000)
        edit(original.id)
        val shared = repository.openEditing(original.id)
        repository.saveInput(shared.key, shared.title, "其他上下文中的修改", shared.revision)
        model.changeEditContent("本页面保留的多行修改\n第二行")
        await { model.state.value.editor?.inputSaveState == FlashnoteInputSaveState.CONFLICT }
        assertEquals("其他上下文中的修改", repository.openEditing(original.id).content)
        model.retryEditInput()
        await { model.state.value.editor?.inputSaveState == FlashnoteInputSaveState.SAVED }
        assertEquals("本页面保留的多行修改\n第二行", repository.openEditing(original.id).content)
        model.saveEdit()
        await { model.state.value.editor == null }
        assertEquals("本页面保留的多行修改\n第二行", repository.note(original.id)?.content)
    }

    @Test fun externallyDeletedNoteIsNotRecreatedByAnOpenEditorOrLateAutosave() = runBlocking {
        val original = create("可能已被删除", 1_000)
        edit(original.id)
        model.changeEditContent("删除前刚开始编辑")
        repository.delete(original.id, original.revision)
        model.saveEdit()
        await { model.state.value.editor?.let { it.saveFailed && !it.isSaving } == true }
        assertEquals("删除前刚开始编辑", model.state.value.editor?.content)
        model.cancelEdit()
        await { model.state.value.editor == null }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertNull(repository.note(original.id))
        assertNull(database.flashnoteDao().editingDraft(original.id))
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun inFlightEditSurvivesSheetPageAndViewModelClosure() = runBlocking {
        val original = create("关闭前的内容", 1_000)
        destroyModel()
        val started = AtomicBoolean()
        val finished = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        createModel(commit = { draft ->
            started.set(true)
            release.await()
            repository.save(draft).also { finished.set(true) }
        })
        edit(original.id)
        model.changeEditContent("关闭期间仍然完成保存")
        model.saveEdit()
        await { started.get() }
        model.openDetail(null)
        model.setVisible(false)
        activity.pause().stop().destroy()
        release.complete(Unit)
        await { finished.get() }
        assertEquals("关闭期间仍然完成保存", repository.note(original.id)?.content)
        createModel()
        await { model.state.value.entries.singleOrNull()?.detail == "关闭期间仍然完成保存" }
        assertNull(database.flashnoteDao().editingDraft(original.id))
        assertEquals(original.createdAt, repository.note(original.id)?.createdAt)
    }

    @Test fun savedEditingSelectionRestoresDraftWithoutAutomaticallyCommittingIt() = runBlocking {
        val original = create("尚未修改", 1_000)
        edit(original.id)
        model.changeEditContent("后台保留的编辑草稿")
        model.setVisible(false)
        await { model.state.value.editor?.inputSaveState == FlashnoteInputSaveState.SAVED }
        destroyModel()
        createModel(SavedStateHandle(mapOf("flashnote.detail" to original.id, "flashnote.editing" to original.id)))
        await { model.state.value.editor?.let { !it.loading && it.content == "后台保留的编辑草稿" } == true }
        assertEquals(original, repository.note(original.id))
    }

    @Test fun closedQueuedEditorCannotReopenAfterAnotherWriteCompletes() = runBlocking {
        val original = create("先前的卡片", 1_000)
        destroyModel()
        val started = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        createModel(commit = { draft -> started.set(true); release.await(); repository.save(draft) })
        model.openDetail(original.id)
        await { !model.state.value.detailLoading && model.state.value.detail?.id == original.id }
        model.changeInput("阻塞写入期间的新事项")
        model.save()
        await { started.get() }
        model.beginEditing()
        model.openDetail(null)
        release.complete(Unit)
        await { !model.state.value.isSaving && model.state.value.draftLoaded }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertNull(model.state.value.detailId)
        assertNull(model.state.value.editor)
        assertEquals(original, repository.note(original.id))
    }

    @Test fun switchingEditorAfterDraftWriteFailureOffersRecoveryOfThePreviousInput() = runBlocking {
        val first = create("第一件", 1_000)
        val second = create("第二件", 2_000)
        edit(first.id)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_edit_input BEFORE UPDATE ON flashnote_drafts
            WHEN OLD.editingNoteId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'simulated failure'); END""")
        model.changeEditContent("未能落库但仍需保留的修改")
        model.openDetail(second.id)
        await { !model.state.value.detailLoading && model.state.value.detail?.id == second.id }
        model.beginEditing()
        await { model.state.value.actionFailure?.editingNoteId == first.id }
        assertNull(model.state.value.editor)
        model.retryAction()
        await { model.state.value.editor?.noteId == first.id }
        assertEquals("未能落库但仍需保留的修改", model.state.value.editor?.content)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit_input")
        model.retryEditInput()
        await { model.state.value.editor?.inputSaveState == FlashnoteInputSaveState.SAVED }
        model.saveEdit()
        await { model.state.value.editor == null }
        assertEquals("未能落库但仍需保留的修改", repository.note(first.id)?.content)
        assertEquals(second, repository.note(second.id))
    }

    @Test
    @Config(application = MebotApplication::class)
    fun defaultSavedStateFactoryCanCreateTheProductionModel() = runBlocking {
        destroyModel()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity.get())[FlashnoteViewModel::class.java]
        model.setVisible(true)
        await { model.state.value.draftLoaded && !model.state.value.loading }
        model.changeInput("默认工厂的本地记录")
        model.save()
        await { !model.state.value.isSaving && model.state.value.savedEvent != null }
        assertEquals("默认工厂的本地记录", (application as MebotApplication).container.flashnotes.notes(FlashnoteStatus.PENDING).single().content)
    }

    @Test fun statisticsStartCollapsedAndLoadDailyHistoryOnlyAfterOpening() = runBlocking {
        val first = create("九月完成", timestamp("2026-09-01T00:00:00Z"))
        clock.time = timestamp("2026-09-30T02:00:00Z")
        repository.complete(first.id, first.revision)
        val second = create("十月完成", timestamp("2026-10-05T00:00:00Z"))
        clock.time = timestamp("2026-10-25T02:00:00Z")
        repository.complete(second.id, second.revision)
        await { model.state.value.counts[FlashnoteStatus.COMPLETED] == 2 }
        destroyModel()
        createModel()
        assertFalse(model.state.value.statistics.expanded)
        assertFalse(model.state.value.statistics.loading)
        assertTrue(model.state.value.statistics.days.isEmpty())
        model.selectStatus(FlashnoteStatus.COMPLETED)
        await { !model.state.value.loading }
        assertFalse(model.state.value.statistics.expanded)
        model.setStatisticsExpanded(true)
        awaitStatistics()
        val stats = model.state.value.statistics
        assertEquals(java.time.YearMonth.of(2026, 10), stats.month)
        assertEquals(1L, stats.total)
        assertEquals(1L, stats.selectedCount)
        assertEquals(1, stats.activeDays)
        assertTrue(stats.canGoPrevious)
        assertFalse(stats.canGoNext)
        model.selectStatus(FlashnoteStatus.PENDING)
        await { !model.state.value.loading }
        assertTrue(model.state.value.statistics.expanded)
        assertEquals(stats, model.state.value.statistics)
    }

    @Test fun explicitlyCollapsedStatisticsRestoreWithoutBeingReopenedByDefault() = runBlocking {
        val note = create("已收起的统计", timestamp("2026-10-25T02:00:00Z"))
        repository.complete(note.id, note.revision)
        destroyModel()
        createModel(SavedStateHandle(mapOf("flashnote.statistics.expanded" to false)))
        assertFalse(model.state.value.statistics.expanded)
        assertTrue(model.state.value.statistics.days.isEmpty())
        model.setVisible(false)
        model.setVisible(true)
        assertFalse(model.state.value.statistics.expanded)
        model.setStatisticsExpanded(true)
        awaitStatistics()
        assertEquals(1L, model.state.value.statistics.total)
    }

    @Test fun statisticsFollowCompleteRestoreAndDeleteWithoutInflatingHistoricalCount() = runBlocking {
        val note = create("今天推进", timestamp("2026-10-25T02:00:00Z"))
        model.refreshStatisticsDate(); model.setStatisticsExpanded(true); awaitStatistics()
        model.toggleCompleted(note.asEntry())
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.statistics.total == 1L }
        repeat(6) { model.toggleCompleted(note.asEntry()) }
        await { model.state.value.pendingOperations.isEmpty() }
        assertEquals(1L, model.state.value.statistics.total)
        val done = requireNotNull(repository.note(note.id))
        model.toggleCompleted(done.asEntry())
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.statistics.total == 0L }
        model.toggleCompleted(requireNotNull(repository.note(note.id)).asEntry())
        await { model.state.value.pendingOperations.isEmpty() && model.state.value.statistics.total == 1L }
        model.delete(requireNotNull(repository.note(note.id)).asEntry())
        await { model.state.value.pendingOperations.isEmpty() && (model.state.value.counts[FlashnoteStatus.COMPLETED] ?: 0) == 0 }
        assertEquals(1L, model.state.value.statistics.total)
        assertEquals(1, model.state.value.statistics.activeDays)
        assertNull(repository.note(note.id))
    }

    @Test fun monthNavigationAndDaySelectionStayWithinKnownHistoryAndIgnoreFutureDates() = runBlocking {
        val september = create("较早记录", timestamp("2026-09-10T00:00:00Z"))
        clock.time = timestamp("2026-09-20T02:00:00Z")
        repository.complete(september.id, september.revision)
        val october = create("今天的记录", timestamp("2026-10-25T02:00:00Z"))
        repository.complete(october.id, october.revision)
        model.refreshStatisticsDate(); model.setStatisticsExpanded(true); awaitStatistics()
        model.selectStatisticsDay(26)
        assertEquals(25, model.state.value.statistics.selectedDay.dayOfMonth)
        model.selectStatisticsMonth(1)
        assertEquals(java.time.YearMonth.of(2026, 10), model.state.value.statistics.month)
        model.selectStatisticsMonth(-1); awaitStatistics()
        assertEquals(java.time.YearMonth.of(2026, 9), model.state.value.statistics.month)
        assertEquals(1L, model.state.value.statistics.total)
        model.selectStatisticsDay(20)
        assertEquals(1L, model.state.value.statistics.selectedCount)
        model.selectStatisticsDay(9)
        assertEquals(20, model.state.value.statistics.selectedDay.dayOfMonth)
        model.selectStatisticsDay(21)
        assertEquals(0L, model.state.value.statistics.selectedCount)
        assertFalse(model.state.value.statistics.canGoPrevious)
        model.selectStatisticsMonth(1); awaitStatistics()
        assertEquals(1L, model.state.value.statistics.total)
        assertEquals(25, model.state.value.statistics.selectedDay.dayOfMonth)
    }

    @Test fun collapsingStopsHistoryUpdatesAndReexpandingQueriesFreshLocalData() = runBlocking {
        val note = create("收起期间完成", timestamp("2026-10-25T02:00:00Z"))
        model.refreshStatisticsDate(); model.setStatisticsExpanded(true); awaitStatistics()
        model.setStatisticsExpanded(false)
        repository.complete(note.id, note.revision)
        await { model.state.value.counts[FlashnoteStatus.COMPLETED] == 1 }
        assertEquals(0L, model.state.value.statistics.total)
        assertFalse(model.state.value.statistics.loading)
        model.setStatisticsExpanded(true); awaitStatistics()
        assertEquals(1L, model.state.value.statistics.total)
        model.setVisible(false)
        val completed = requireNotNull(repository.note(note.id))
        repository.restore(note.id, completed.revision)
        model.setVisible(true); awaitStatistics()
        assertEquals(0L, model.state.value.statistics.total)
    }

    @Test fun restoredMonthAndSelectedDaySurviveRecreationAndDoNotChangeListFilter() = runBlocking {
        val old = create("历史事项", timestamp("2026-09-01T00:00:00Z"))
        clock.time = timestamp("2026-09-15T02:00:00Z")
        repository.complete(old.id, old.revision)
        create("当前事项", timestamp("2026-10-25T02:00:00Z"))
        destroyModel()
        createModel(SavedStateHandle(mapOf("flashnote.status" to FlashnoteStatus.COMPLETED.name,
            "flashnote.statistics.expanded" to true, "flashnote.statistics.month" to "2026-09", "flashnote.statistics.day" to "2026-09-15")))
        awaitStatistics()
        assertEquals(FlashnoteStatus.COMPLETED, model.state.value.status)
        assertEquals(java.time.YearMonth.of(2026, 9), model.state.value.statistics.month)
        assertEquals(15, model.state.value.statistics.selectedDay.dayOfMonth)
        assertEquals(1L, model.state.value.statistics.selectedCount)
    }

    @Test fun midnightRefreshFollowsTodayButKeepsAnExplicitlyReviewedHistoricalMonth() = runBlocking {
        val old = create("月末完成", timestamp("2026-10-31T23:50:00Z"))
        repository.complete(old.id, old.revision)
        model.refreshStatisticsDate(); model.setStatisticsExpanded(true); awaitStatistics()
        clock.time = timestamp("2026-11-01T00:05:00Z")
        model.refreshStatisticsDate(); awaitStatistics()
        assertEquals(java.time.YearMonth.of(2026, 11), model.state.value.statistics.month)
        assertEquals(1, model.state.value.statistics.selectedDay.dayOfMonth)
        assertEquals(0L, model.state.value.statistics.total)
        model.selectStatisticsMonth(-1); awaitStatistics()
        assertEquals(1L, model.state.value.statistics.total)
        clock.time = timestamp("2026-11-02T00:05:00Z")
        model.refreshStatisticsDate(); awaitStatistics()
        assertEquals(java.time.YearMonth.of(2026, 10), model.state.value.statistics.month)
        assertEquals(1L, model.state.value.statistics.total)
    }

    @Test fun emptyHistoryHasNoExampleBarsAndClampsOldSavedMonthsToCurrentMonth() = runBlocking {
        destroyModel()
        clock.time = timestamp("2026-10-25T02:00:00Z")
        createModel(SavedStateHandle(mapOf("flashnote.statistics.expanded" to true,
            "flashnote.statistics.month" to "2020-01", "flashnote.statistics.day" to "2020-01-15")))
        awaitStatistics()
        assertEquals(java.time.YearMonth.of(2026, 10), model.state.value.statistics.month)
        assertEquals(0L, model.state.value.statistics.total)
        assertTrue(model.state.value.statistics.days.isEmpty())
        assertFalse(model.state.value.statistics.canGoPrevious)
        assertFalse(model.state.value.statistics.canGoNext)
    }

    private fun awaitStatistics() = await { model.state.value.statistics.expanded && !model.state.value.statistics.loading && !model.state.value.statistics.failed }
    private fun timestamp(value: String) = Instant.parse(value).toEpochMilli()

    private suspend fun create(content: String, time: Long): FlashnoteEntity {
        clock.time = time
        val draft = FlashnoteDraftEntity(key = UUID.randomUUID().toString(), content = content, updatedAt = time)
        database.flashnoteDao().insertDraft(draft)
        return repository.save(draft)
    }

    private fun edit(id: String) {
        model.openDetail(id)
        await { !model.state.value.detailLoading && model.state.value.detail?.id == id }
        model.beginEditing()
        await { model.state.value.editor?.let { !it.loading && !it.loadFailed && it.noteId == id } == true }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待闪念页面状态超时" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }

    // 默认工厂持有 Application；Robolectric 会在同一 JVM 中切换测试 Application。
    private fun resetDefaultFactory() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }

    private class PageClock : Clock() {
        var time = 1_000L
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(time)
        override fun millis(): Long = time
    }
}
