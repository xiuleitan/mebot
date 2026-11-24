package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.FlashnoteAttachmentRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.ScheduleAttachmentRepository
import com.txl.mebot.data.schedule.ScheduleRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FlashnoteAttachmentStorageTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var notes: FlashnoteRepository
    private lateinit var media: LocalMediaStore
    private lateinit var importer: FlashnoteAttachmentRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        database = MebotDatabase.create(context)
        connect()
    }
    private fun connect() {
        notes = FlashnoteRepository(database)
        media = LocalMediaStore(context, database)
        importer = FlashnoteAttachmentRepository(context, notes, media)
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        File(context.filesDir, "media").deleteRecursively()
    }
    private fun image(color: Int) = ByteArrayOutputStream().use {
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, it); recycle()
        }
        it.toByteArray()
    }
    private suspend fun add(draft: FlashnoteDraftEntity, bytes: ByteArray, name: String, mime: String = "image/png"): FlashnoteDraftEntity {
        var saved: FlashnoteDraftEntity? = null
        media.importDocument({ ByteArrayInputStream(bytes) }, name, mime, onImported = { saved = notes.attach(draft, it, name) })
        return requireNotNull(saved)
    }

    @Test fun systemUriIsCopiedAndMixedAttachmentsSurviveSourceRemovalAndReopen() = runBlocking {
        val source = File(context.filesDir, "media/originals/picker-source/用户图.png").apply { parentFile!!.mkdirs(); writeBytes(image(Color.RED)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source, "用户图.png")
        var draft = notes.openCreation()
        draft = add(draft, "local file".toByteArray(), "说明.txt", "text/plain")
        draft = importer.importUri(draft, uri, true).draft
        draft = add(draft, image(Color.BLUE), "第二张.png")
        draft = notes.saveInput(draft.key, "", "有图有文件\n完整的多行内容", draft.revision)
        val items = notes.draftAttachments(draft.key)
        assertEquals(listOf("说明.txt", "用户图.png", "第二张.png"), items.map { it.displayName })
        val originalBytes = source.readBytes()
        source.delete()
        val saved = notes.save(draft)
        database.close(); database = MebotDatabase.create(context); connect()
        val stored = notes.noteAttachments(saved.id)
        assertEquals(items.map { it.asset.id }, stored.map { it.asset.id })
        assertArrayEquals(originalBytes, requireNotNull(media.fileFor(stored[1].asset.id)).readBytes())
        val card = notes.observeCards(FlashnoteStatus.PENDING).first().single()
        assertEquals(3, card.attachmentCount)
        assertEquals(stored[1].asset.relativePath, card.coverRelativePath)
        assertEquals("有图有文件\n完整的多行内容", card.note.content)
    }

    @Test fun attachmentOnlySaveAndRetryKeepOneNoteAndTheExactMediaSnapshot() = runBlocking {
        val draft = add(notes.openCreation(), image(Color.RED), "只有图片.png")
        val note = notes.save(draft)
        assertEquals("图片闪念", note.title)
        assertEquals("", note.content)
        assertEquals(note, notes.save(draft))
        assertEquals(1, notes.notes(FlashnoteStatus.PENDING).size)
        assertEquals(1, notes.noteAttachments(note.id).size)
        assertNull(notes.draft(draft.key))
        val next = add(notes.openCreation(), "text file".toByteArray(), "待查看资料.txt", "text/plain")
        assertEquals("待查看资料.txt", notes.save(next).title)
    }

    @Test fun attachmentChangesInvalidateOldSubmitSnapshotsAndDuplicateSelectionDoesNotAddLinks() = runBlocking {
        val old = notes.openCreation()
        val changed = add(old, image(Color.RED), "一.png")
        assertTrue(changed.revision > old.revision)
        assertTrue(changed.attachmentSignature.isNotBlank())
        assertTrue(runCatching { notes.save(old) }.isFailure)
        val duplicate = add(changed, image(Color.RED), "另一个名字.png")
        assertEquals(changed, duplicate)
        assertEquals(listOf("一.png"), notes.draftAttachments(changed.key).map { it.displayName })
        assertEquals(1, notes.noteAttachments(notes.save(duplicate).id).size)
    }

    @Test fun unsavedMediaAndRawTextRestoreAfterDatabaseReopenWithoutCreatingANote() = runBlocking {
        var draft = add(notes.openCreation(), image(Color.BLUE), "草稿.png")
        draft = notes.saveInput(draft.key, "", "没有提交的输入\n第二行", draft.revision)
        database.close(); database = MebotDatabase.create(context); connect()
        assertEquals(draft, notes.openCreation())
        assertEquals("草稿.png", notes.draftAttachments(draft.key).single().displayName)
        assertTrue(notes.notes(FlashnoteStatus.PENDING).isEmpty())
    }

    @Test fun editingMediaCancelPreservesOriginalAndSavingUpdatesCoverWithoutChangingCreationOrStatus() = runBlocking {
        var draft = add(notes.openCreation(), image(Color.RED), "红.png")
        draft = add(draft, image(Color.BLUE), "蓝.png")
        draft = notes.saveInput(draft.key, "", "原记录", draft.revision)
        val note = notes.save(draft)
        val completed = notes.complete(note.id, note.revision)
        val originals = notes.noteAttachments(note.id)
        var editing = notes.openEditing(note.id)
        editing = notes.removeAttachment(editing, originals.first().asset.id)
        assertEquals(2, notes.noteAttachments(note.id).size)
        notes.discardDraft(editing.key, editing.revision)
        assertEquals(originals, notes.noteAttachments(note.id))
        editing = notes.openEditing(note.id)
        editing = notes.removeAttachment(editing, originals.first().asset.id)
        val saved = notes.save(editing)
        assertEquals(completed.createdAt, saved.createdAt)
        assertEquals(completed.completedAt, saved.completedAt)
        assertEquals(FlashnoteStatus.COMPLETED, saved.status)
        assertEquals(completed.revision + 1, saved.revision)
        val card = notes.observeCards(FlashnoteStatus.COMPLETED).first().single()
        assertEquals(1, card.attachmentCount)
        assertEquals(originals.last().asset.relativePath, card.coverRelativePath)
        media.cleanPendingDeletions()
        assertNull(media.fileFor(originals.first().asset.id))
    }

    @Test fun failedCommitRollsBackNoteAttachmentTransferAndLeavesFilesForRetry() = runBlocking {
        val draft = add(notes.openCreation(), image(Color.RED), "事务中的图片.png")
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_media_commit BEFORE DELETE ON flashnote_drafts BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { notes.save(draft) }.isFailure)
        assertTrue(notes.notes(FlashnoteStatus.PENDING).isEmpty())
        assertEquals(draft, notes.draft(draft.key))
        val item = notes.draftAttachments(draft.key).single()
        assertNotNull(media.fileFor(item.asset.id))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_media_commit")
        assertEquals(item.asset.id, notes.noteAttachments(notes.save(draft).id).single().asset.id)
    }

    @Test fun cancelledAndConsumedDraftsRejectLateImportsWithoutLeavingRegisteredOrphanMedia() = runBlocking {
        val cancelled = notes.openCreation()
        notes.discardDraft(cancelled.key, cancelled.revision)
        assertTrue(runCatching { add(cancelled, image(Color.RED), "迟到图.png") }.isFailure)
        val draft = notes.openCreation()
        val submitted = notes.save(notes.saveInput(draft.key, "", "已提交", draft.revision))
        assertTrue(runCatching { add(draft, image(Color.BLUE), "提交后迟到.png") }.isFailure)
        assertTrue(notes.noteAttachments(submitted.id).isEmpty())
        assertEquals(0, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); it.getInt(0) })
        media.cleanPendingDeletions()
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test fun deletingNoteCleansItsSavedAndEditingOnlyAttachmentsButDoesNotRestoreOnRetry() = runBlocking {
        val draft = add(notes.openCreation(), image(Color.RED), "正式图.png")
        val note = notes.save(draft)
        val editing = add(notes.openEditing(note.id), image(Color.BLUE), "仅在编辑草稿中的图片.png")
        val files = notes.draftAttachments(editing.key).map { requireNotNull(media.fileFor(it.asset.id)) }
        assertTrue(notes.delete(note.id, note.revision))
        assertNull(notes.draft(editing.key))
        assertTrue(notes.noteAttachments(note.id).isEmpty())
        assertTrue(runCatching { notes.save(draft) }.isFailure)
        assertTrue(runCatching { notes.save(editing) }.isFailure)
        media.cleanPendingDeletions()
        assertTrue(files.none { it.exists() })
    }

    @Test fun sharedScheduleAndFlashnoteFilesSurviveDeletionInEitherBotUntilTheLastReferenceIsRemoved() = runBlocking {
        val draft = add(notes.openCreation(), image(Color.RED), "共享图.png")
        val asset = notes.draftAttachments(draft.key).single().asset
        val note = notes.save(draft)
        val schedules = ScheduleRepository(database)
        val scheduleMedia = ScheduleAttachmentRepository(context, database, media)
        val session = schedules.openSession().id
        scheduleMedia.attach(session, asset)
        val file = requireNotNull(media.fileFor(asset.id))
        scheduleMedia.remove(session, asset.id)
        media.cleanPendingDeletions()
        assertTrue(file.isFile)
        assertEquals(asset.id, notes.noteAttachments(note.id).single().asset.id)
        scheduleMedia.attach(session, asset)
        notes.delete(note.id, note.revision)
        media.cleanPendingDeletions()
        assertTrue(file.isFile)
        assertEquals(asset.id, scheduleMedia.observe(session).first().single().asset.id)
        scheduleMedia.remove(session, asset.id)
        media.cleanPendingDeletions()
        assertFalse(file.exists())
    }

    @Test fun failedAssociationRollsBackMetadataAndQueuesOnlyUnreferencedFiles() = runBlocking {
        val draft = notes.openCreation()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_flash_attach BEFORE INSERT ON flashnote_draft_attachments BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { add(draft, image(Color.RED), "不能只存文件.png") }.isFailure)
        assertTrue(notes.draftAttachments(draft.key).isEmpty())
        assertEquals(draft, notes.draft(draft.key))
        assertEquals(0, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); it.getInt(0) })
        assertTrue(database.mediaDao().cleanupPaths(10).isNotEmpty())
        media.cleanPendingDeletions()
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
    }

    @Test fun oldSubmissionCannotReportSuccessAfterLaterAttachmentEditsWithTheSameText() = runBlocking {
        val draft = add(notes.openCreation(), image(Color.RED), "早先.png")
        val original = notes.save(draft)
        val later = add(notes.openEditing(original.id), image(Color.BLUE), "后来.png")
        val changed = notes.save(later)
        assertEquals(original.content, changed.content)
        assertEquals(original.title, changed.title)
        assertTrue(runCatching { notes.save(draft) }.isFailure)
        assertEquals(2, notes.noteAttachments(original.id).size)
    }
}
