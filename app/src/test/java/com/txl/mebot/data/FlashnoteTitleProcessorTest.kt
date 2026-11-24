package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.*
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.flashnote.asEntry
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FlashnoteTitleProcessorTest {
    private lateinit var database: MebotDatabase
    private lateinit var repository: FlashnoteRepository
    private lateinit var root: File
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(app, MebotDatabase::class.java).build()
        repository = FlashnoteRepository(database, Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC))
        root = File(app.filesDir, "flashnote-title-processor-test").apply { mkdirs() }
    }
    @After fun teardown() { database.close(); root.deleteRecursively() }

    private suspend fun create(content: String = "原始文字\n\n第二段完整保留", imageCount: Int = 0): FlashnoteEntity {
        var draft = repository.openCreation()
        draft = repository.saveInput(draft.key, "", content, draft.revision)
        repeat(imageCount) { position ->
            val id = "${draft.key}-$position"
            val file = File(root, id).apply { writeBytes("original-image-$position".toByteArray()) }
            val asset = MediaAssetEntity(id, MediaSource.IMPORTED, relativePath = file.relativeTo(requireNotNull(root.parentFile)).path,
                originalName = "原图$position.png", mimeType = "image/png", byteCount = file.length(), width = 16, height = 16,
                sha256 = id, createdAt = 1_000)
            database.mediaDao().insert(asset)
            draft = repository.attach(draft, asset)
        }
        return repository.save(draft)
    }
    private fun processor(generator: FlashnoteTitleGenerator) = FlashnoteTitleProcessor(repository, generator,
        prepareImages = { items -> items.filter { it.isImage }.map { FlashnoteTitleImage("data:image/jpeg;base64,ZmFrZQ==") } })
    private suspend fun failed(reason: FlashnoteTitleFailure, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue(error.toString(), error is FlashnoteTitleException)
        assertEquals(reason, (error as FlashnoteTitleException).failure)
    }

    @Test fun onlyTitleChangesAndExactTextImagesCreationTimeAndCompletionHistoryStayIntact() = runBlocking {
        val raw = "  原始首行\r\n\r\n具体内容  保留空格和标点。\n末尾  "
        val created = create(raw, 2)
        val before = repository.complete(created.id, created.revision)
        val attachments = repository.noteAttachments(before.id)
        val bytes = attachments.associate { it.asset.id to File(root.parentFile, it.asset.relativePath!!).readBytes() }
        processor(FlashnoteTitleGenerator { text, images ->
            assertEquals(raw, text); assertEquals(2, images.size); "整理未来事项"
        }).generate(before.id, before.revision)
        val after = requireNotNull(repository.note(before.id))
        assertEquals(before.copy(title = "整理未来事项", updatedAt = after.updatedAt, revision = before.revision + 1), after)
        assertEquals(attachments, repository.noteAttachments(before.id))
        attachments.forEach { assertArrayEquals(bytes[it.asset.id], File(root.parentFile, it.asset.relativePath!!).readBytes()) }
        assertEquals(1L, repository.observeCompletionDays(0, Long.MAX_VALUE).first().sumOf { it.count })
        assertEquals(raw, after.asEntry().summary)
        assertEquals(1, repository.notes(FlashnoteStatus.COMPLETED).size)
    }

    @Test fun imageOnlyRecordsUseImagesWithoutAddingAnyBodyText() = runBlocking {
        val note = create("", 1)
        processor(FlashnoteTitleGenerator { text, images -> assertEquals("", text); assertEquals(1, images.size); "夜景散步" })
            .generate(note.id, note.revision)
        assertEquals("", repository.note(note.id)!!.content)
        assertEquals("夜景散步", repository.note(note.id)!!.title)
        assertEquals(1, repository.noteAttachments(note.id).size)
    }

    @Test fun providerFailureAndInvalidTitleKeepTheSavedRecordAndAllowAnIndependentRetry() = runBlocking {
        val note = create()
        failed(FlashnoteTitleFailure.NETWORK) { processor(FlashnoteTitleGenerator { _, _ ->
            throw FlashnoteTitleException(FlashnoteTitleFailure.NETWORK)
        }).generate(note.id, note.revision) }
        assertEquals(note, repository.note(note.id))
        failed(FlashnoteTitleFailure.INVALID_RESULT) {
            processor(FlashnoteTitleGenerator { _, _ -> "一二三四五六七八九" }).generate(note.id, note.revision)
        }
        assertEquals(note, repository.note(note.id))
        processor(FlashnoteTitleGenerator { _, _ -> "整理想法" }).generate(note.id, note.revision)
        assertEquals("整理想法", repository.note(note.id)!!.title)
        assertEquals(note.content, repository.note(note.id)!!.content)
        assertEquals(1, repository.notes(FlashnoteStatus.PENDING).size)
    }

    @Test fun staleQueuedRevisionDoesNotUploadAnyTextOrImage() = runBlocking {
        val note = create()
        repository.complete(note.id, note.revision)
        var calls = 0
        failed(FlashnoteTitleFailure.CHANGED) {
            processor(FlashnoteTitleGenerator { _, _ -> calls++; "短标题" }).generate(note.id, note.revision)
        }
        assertEquals(0, calls)
        assertEquals(FlashnoteStatus.COMPLETED, repository.note(note.id)!!.status)
    }

    @Test fun editsWhileTheRequestIsRunningAreNeverOverwritten() = runBlocking {
        val note = create()
        failed(FlashnoteTitleFailure.CHANGED) { processor(FlashnoteTitleGenerator { _, _ ->
            val edit = repository.openEditing(note.id)
            repository.save(repository.saveInput(edit.key, "用户标题", "用户后来输入的完整正文", edit.revision))
            "迟到的AI标题"
        }).generate(note.id, note.revision) }
        assertEquals("用户标题", repository.note(note.id)!!.title)
        assertEquals("用户后来输入的完整正文", repository.note(note.id)!!.content)
    }

    @Test fun anOpenEditingDraftAndStatusChangesBlockLateResults() = runBlocking {
        val note = create()
        failed(FlashnoteTitleFailure.CHANGED) { processor(FlashnoteTitleGenerator { _, _ ->
            val edit = repository.openEditing(note.id)
            repository.saveInput(edit.key, "未提交标题", "编辑草稿", edit.revision)
            "短标题"
        }).generate(note.id, note.revision) }
        assertEquals(note, repository.note(note.id))
        assertEquals("编辑草稿", database.flashnoteDao().editingDraft(note.id)!!.content)
        val editing = database.flashnoteDao().editingDraft(note.id)!!
        repository.discardDraft(editing.key, editing.revision)
        failed(FlashnoteTitleFailure.CHANGED) { processor(FlashnoteTitleGenerator { _, _ ->
            repository.complete(note.id, note.revision); "短标题"
        }).generate(note.id, note.revision) }
        assertEquals(FlashnoteStatus.COMPLETED, repository.note(note.id)!!.status)
        assertEquals(note.title, repository.note(note.id)!!.title)
    }

    @Test fun deletedNotesStayDeletedWhenTheModelFinishes() = runBlocking {
        val note = create("要删除的内容", 1)
        failed(FlashnoteTitleFailure.CHANGED) { processor(FlashnoteTitleGenerator { _, _ ->
            repository.delete(note.id, note.revision); "短标题"
        }).generate(note.id, note.revision) }
        assertNull(repository.note(note.id))
        assertTrue(repository.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun changedAttachmentsAndDuplicateResultsCannotReplayTheSnapshot() = runBlocking {
        val note = create("原内容", 1)
        val snapshot = repository.titleSnapshot(note.id, note.revision)
        database.flashnoteMediaDao().clearNote(note.id)
        assertTrue(runCatching { repository.applyGeneratedTitle(snapshot, "短标题") }.exceptionOrNull() is FlashnoteConflict)
        assertEquals(note, repository.note(note.id))
        val fresh = repository.titleSnapshot(note.id, note.revision)
        repository.applyGeneratedTitle(fresh, "短标题")
        assertTrue(runCatching { repository.applyGeneratedTitle(fresh, "另一标题") }.exceptionOrNull() is FlashnoteConflict)
        assertEquals("短标题", repository.note(note.id)!!.title)
        assertEquals(note.content, repository.note(note.id)!!.content)
    }

    @Test fun tooManyPicturesAndCancellationNeverAlterTheOriginal() = runBlocking {
        val note = create("很多图", 9)
        var calls = 0
        failed(FlashnoteTitleFailure.LIMIT) { processor(FlashnoteTitleGenerator { _, _ -> calls++; "短标题" }).generate(note.id, note.revision) }
        assertEquals(0, calls)
        val plain = create()
        assertTrue(runCatching { processor(FlashnoteTitleGenerator { _, _ -> throw CancellationException("cancel") })
            .generate(plain.id, plain.revision) }.exceptionOrNull() is CancellationException)
        assertEquals(plain, repository.note(plain.id))
        assertEquals(note, repository.note(note.id))
    }
}
