package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.FlashnoteConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftNotFound
import com.txl.mebot.data.flashnote.FlashnoteNotFound
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
class FlashnoteRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var clock: FlashnoteClock
    private lateinit var repository: FlashnoteRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        clock = FlashnoteClock()
        database = MebotDatabase.create(context)
        repository = FlashnoteRepository(database, clock)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun firstCommitCreatesPendingNoteWithFullBodyAndActualCreationTime() = runBlocking {
        val body = "\n  整理一份自己真正想读的书单  \n第二行 | https://example.com\n"
        clock.time = 2_000
        val draft = input(body)
        clock.time = 3_000
        val saved = repository.save(draft)
        assertEquals(draft.key, saved.id)
        assertEquals("整理一份自己真正想读的书单", saved.title)
        assertEquals(body, saved.content)
        assertEquals(FlashnoteStatus.PENDING, saved.status)
        assertEquals(3_000L, saved.createdAt)
        assertEquals(3_000L, saved.updatedAt)
        assertEquals(1L, saved.revision)
        assertNull(saved.completedAt)
        assertNull(repository.draft(draft.key))
        assertEquals(saved, repository.observeNote(saved.id).first())
    }

    @Test fun blankContentIsRejectedWithoutDiscardingTheDraft() = runBlocking {
        val draft = input(" \n\t ", "只有标题")
        assertTrue(runCatching { repository.save(draft) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(draft, repository.draft(draft.key))
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun concurrentAndReopenedRetriesOfOneDraftProduceOnlyOneNote() = runBlocking {
        val draft = input("同一次记录", "标题")
        val results = coroutineScope {
            List(12) { async(Dispatchers.Default) { repository.save(draft) } }.awaitAll()
        }
        val saved = results.first()
        assertTrue(results.all { it == saved })
        assertEquals(listOf(saved), repository.notes(FlashnoteStatus.PENDING))
        reopen()
        clock.time = 20_000
        assertEquals(saved, repository.save(draft))
        val next = input("下一件尚未提交的事")
        assertNotEquals(draft.key, next.key)
        assertEquals(saved, repository.save(draft))
        assertEquals(next, repository.draft(next.key))
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun separateDraftsCanIntentionallySaveTheSameText() = runBlocking {
        val first = create("相同内容")
        val second = create("相同内容")
        assertNotEquals(first.id, second.id)
        assertEquals(2, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun editPreservesCreationAndCompletedStatusAndRepeatedSaveDoesNotRewriteTimes() = runBlocking {
        val original = create("原正文", "原标题")
        clock.time = 2_000
        val completed = repository.complete(original.id, original.revision)
        val opened = repository.openEditing(original.id)
        val draft = repository.saveInput(opened.key, "  新标题  ", "新正文\n完整保留", opened.revision)
        clock.time = 3_000
        val edited = repository.save(draft)
        assertEquals("新标题", edited.title)
        assertEquals("新正文\n完整保留", edited.content)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(completed.status, edited.status)
        assertEquals(completed.completedAt, edited.completedAt)
        assertEquals(3_000L, edited.updatedAt)
        assertEquals(completed.revision + 1, edited.revision)
        clock.time = 4_000
        reopen()
        assertEquals(edited, repository.save(draft))
        assertEquals(listOf(edited), repository.notes(FlashnoteStatus.COMPLETED))
    }

    @Test fun creationReplayCannotOverwriteALaterEdit() = runBlocking {
        val request = input("最初内容")
        val original = repository.save(request)
        val opened = repository.openEditing(original.id)
        val changed = repository.saveInput(opened.key, "新标题", "后来修改的内容", opened.revision)
        val edited = repository.save(changed)
        assertTrue(runCatching { repository.save(request) }.exceptionOrNull() is FlashnoteConflict)
        assertEquals(edited, repository.note(edited.id))
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun editingAndCompletingOutOfOrderNeverChangeOldestFirstLists() = runBlocking {
        val first = create("第一件")
        clock.time = 2_000
        val second = create("第二件")
        clock.time = 3_000
        val third = create("第三件")
        val opened = repository.openEditing(first.id)
        val draft = repository.saveInput(opened.key, "第一件修改", "修改后的第一件", opened.revision)
        clock.time = 4_000
        val edited = repository.save(draft)
        assertEquals(listOf(first.id, second.id, third.id), repository.notes(FlashnoteStatus.PENDING).map { it.id })
        clock.time = 5_000
        val secondCompleted = repository.complete(second.id, second.revision)
        clock.time = 6_000
        val firstCompleted = repository.complete(first.id, edited.revision)
        assertEquals(listOf(first.id, second.id), repository.observeNotes(FlashnoteStatus.COMPLETED).first().map { it.id })
        repository.restore(second.id, secondCompleted.revision)
        repository.restore(first.id, firstCompleted.revision)
        assertEquals(listOf(first.id, second.id, third.id), repository.notes(FlashnoteStatus.PENDING).map { it.id })
        assertEquals(first.createdAt, repository.note(first.id)?.createdAt)
    }

    @Test fun completeAndRestoreRetriesAreNoOpsAndAnOldCompleteCannotUndoRestore() = runBlocking {
        val original = create("完成时间不能被重试刷新")
        clock.time = 2_000
        val completed = repository.complete(original.id, original.revision)
        clock.time = 3_000
        assertEquals(completed, repository.complete(original.id, original.revision))
        clock.time = 4_000
        val restored = repository.restore(original.id, completed.revision)
        assertNull(restored.completedAt)
        assertEquals(original.createdAt, restored.createdAt)
        clock.time = 5_000
        assertEquals(restored, repository.restore(original.id, completed.revision))
        assertTrue(runCatching { repository.complete(original.id, original.revision) }.exceptionOrNull() is FlashnoteConflict)
        assertEquals(restored, repository.note(original.id))
    }

    @Test fun staleEditRetainsDraftAndDoesNotOverwriteANewerStatus() = runBlocking {
        val original = create("原文")
        val opened = repository.openEditing(original.id)
        val draft = repository.saveInput(opened.key, "新标题", "尚未提交的新正文", opened.revision)
        val completed = repository.complete(original.id, original.revision)
        assertTrue(runCatching { repository.save(draft) }.exceptionOrNull() is FlashnoteConflict)
        assertEquals(completed, repository.note(original.id))
        assertEquals(draft, repository.draft(draft.key))
        assertTrue(repository.discardDraft(draft.key, draft.revision))
        val refreshed = repository.openEditing(original.id)
        assertEquals(completed.revision, refreshed.baseRevision)
        assertEquals(completed.content, refreshed.content)
    }

    @Test fun duplicateInputSaveIsANoOpButStaleDifferentTextAndDiscardAreRejected() = runBlocking {
        val opened = repository.openCreation()
        val first = repository.saveInput(opened.key, "标题", "已保存输入", opened.revision)
        clock.time = 5_000
        assertEquals(first, repository.saveInput(opened.key, "标题", "已保存输入", opened.revision))
        assertTrue(runCatching {
            repository.saveInput(opened.key, "标题", "迟到的旧内容", opened.revision)
        }.exceptionOrNull() is FlashnoteDraftConflict)
        assertTrue(runCatching { repository.discardDraft(first.key, opened.revision) }.exceptionOrNull() is FlashnoteDraftConflict)
        assertEquals(first, repository.draft(first.key))
    }

    @Test fun deletedNoteCannotBeResurrectedByOldCreationEditingOrInputRequests() = runBlocking {
        val creation = input("即将删除")
        val original = repository.save(creation)
        val other = create("另一件要保留")
        val opened = repository.openEditing(original.id)
        val editing = repository.saveInput(opened.key, "未提交标题", "未提交编辑内容", opened.revision)
        val independent = input("独立的新建输入")
        assertTrue(repository.delete(original.id, original.revision))
        assertFalse(repository.delete(original.id, original.revision))
        assertTrue(runCatching { repository.save(creation) }.exceptionOrNull() is FlashnoteDraftNotFound)
        assertTrue(runCatching { repository.save(editing) }.exceptionOrNull() is FlashnoteNotFound)
        assertTrue(runCatching {
            repository.saveInput(editing.key, editing.title, "迟到输入", editing.revision)
        }.exceptionOrNull() is FlashnoteDraftNotFound)
        assertNull(repository.note(original.id))
        assertNull(repository.draft(editing.key))
        assertEquals(independent, repository.draft(independent.key))
        assertEquals(listOf(other), repository.notes(FlashnoteStatus.PENDING))
    }

    @Test fun canceledCreationCannotBeSubmittedAndNextCreationUsesANewId() = runBlocking {
        val old = input("取消的输入")
        assertTrue(repository.discardDraft(old.key, old.revision))
        assertFalse(repository.discardDraft(old.key, old.revision))
        val next = input("新输入")
        assertNotEquals(old.key, next.key)
        assertTrue(runCatching { repository.save(old) }.exceptionOrNull() is FlashnoteDraftNotFound)
        assertTrue(runCatching {
            repository.saveInput(old.key, old.title, old.content, old.revision)
        }.exceptionOrNull() is FlashnoteDraftNotFound)
        assertEquals(next, repository.draft(next.key))
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun unchangedEditAndCanceledEditingDoNotMutateTheOriginal() = runBlocking {
        val original = create("保留的原文", "原标题")
        clock.time = 3_000
        val unchanged = repository.openEditing(original.id)
        assertEquals(original, repository.save(unchanged))
        assertNull(repository.draft(unchanged.key))
        val opened = repository.openEditing(original.id)
        val changed = repository.saveInput(opened.key, "不保存", "取消的编辑", opened.revision)
        repository.discardDraft(changed.key, changed.revision)
        assertTrue(runCatching { repository.save(changed) }.isFailure)
        assertEquals(original, repository.note(original.id))
    }

    @Test fun staleDeleteCannotRemoveANewerVersion() = runBlocking {
        val original = create("原文")
        val completed = repository.complete(original.id, original.revision)
        assertTrue(runCatching { repository.delete(original.id, original.revision) }.exceptionOrNull() is FlashnoteConflict)
        assertEquals(completed, repository.note(original.id))
        assertTrue(repository.delete(original.id, completed.revision))
        assertNull(repository.note(original.id))
    }

    @Test fun failedCreationCleanupRollsBackAndLeavesTheDraftForRetry() = runBlocking {
        val draft = input("失败时不能丢掉")
        failDraftCleanup()
        assertTrue(runCatching { repository.save(draft) }.isFailure)
        assertNull(repository.note(draft.key))
        assertEquals(draft, repository.draft(draft.key))
        allowDraftCleanup()
        clock.time = 4_000
        val saved = repository.save(draft)
        assertEquals(4_000L, saved.createdAt)
        assertEquals(listOf(saved), repository.notes(FlashnoteStatus.PENDING))
        assertNull(repository.draft(draft.key))
    }

    @Test fun failedEditingCleanupRollsBackTheNoteUpdateAndRetainsInput() = runBlocking {
        val original = create("原文", "原标题")
        val opened = repository.openEditing(original.id)
        val draft = repository.saveInput(opened.key, "新标题", "新内容", opened.revision)
        failDraftCleanup()
        assertTrue(runCatching { repository.save(draft) }.isFailure)
        assertEquals(original, repository.note(original.id))
        assertEquals(draft, repository.draft(draft.key))
        allowDraftCleanup()
        val updated = repository.save(draft)
        assertEquals("新内容", updated.content)
        assertEquals(original.createdAt, updated.createdAt)
    }

    @Test fun reopeningCreationResumesInputInsteadOfCreatingAnotherDraft() = runBlocking {
        val saved = input("未提交的正文", "未提交标题")
        reopen()
        clock.time = 20_000
        assertEquals(saved, repository.openCreation())
        assertEquals(saved, repository.openCreation())
        assertEquals(1, database.flashnoteDao().creationDrafts().size)
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun generatedIdCollisionAndUnpersistedSnapshotCannotOverwriteExistingData() = runBlocking {
        val original = create("必须保留")
        val colliding = FlashnoteRepository(database, clock, nextId = { original.id })
        assertTrue(runCatching { colliding.openCreation() }.isFailure)
        assertEquals(original, repository.note(original.id))
        val stored = input("已落库输入")
        assertTrue(runCatching { repository.save(stored.copy(content = "未落库的快照")) }.exceptionOrNull() is FlashnoteDraftConflict)
        assertEquals(stored, repository.draft(stored.key))
        assertEquals(listOf(original), repository.notes(FlashnoteStatus.PENDING))
    }

    private suspend fun input(content: String, title: String = ""): FlashnoteDraftEntity {
        val draft = repository.openCreation()
        return repository.saveInput(draft.key, title, content, draft.revision)
    }

    private suspend fun create(content: String, title: String = ""): FlashnoteEntity = repository.save(input(content, title))

    private fun reopen() {
        database.close()
        database = MebotDatabase.create(context)
        repository = FlashnoteRepository(database, clock)
    }

    private fun failDraftCleanup() {
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_flashnote_cleanup
            BEFORE DELETE ON flashnote_drafts BEGIN SELECT RAISE(ABORT, 'simulated cleanup failure'); END""")
    }

    private fun allowDraftCleanup() {
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_flashnote_cleanup")
    }

    private class FlashnoteClock : Clock() {
        @Volatile var time: Long = 1_000
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(time)
        override fun millis(): Long = time
    }
}
