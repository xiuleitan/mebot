package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.*
import java.io.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleEditorRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var attachments: ScheduleAttachmentRepository
    private lateinit var media: LocalMediaStore
    private lateinit var editor: ScheduleEditorRepository
    private val day = LocalDate.now()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        context.deleteDatabase(MebotDatabase.FILE_NAME); connect()
    }
    private fun connect() {
        db = MebotDatabase.create(context); schedules = ScheduleRepository(db); media = LocalMediaStore(context, db)
        attachments = ScheduleAttachmentRepository(context, db, media)
        editor = ScheduleEditorRepository(context, db, schedules, attachments, media)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }
    private suspend fun task(recurring: Boolean = false): ScheduleOccurrenceEntity {
        val session = schedules.openSession().id
        val created = schedules.createTask(session, UUID.randomUUID().toString(), ScheduleDraft("原始标题", "原始正文", day,
            LocalTime.of(19, 0), if (recurring) RecurrenceRule(RecurrencePattern.DAILY, endDate = day.plusDays(2)) else null,
            reminderMinutes = listOf(60, 0)))
        schedules.materializeRange(day, day.plusDays(2))
        return db.scheduleDao().occurrencesForTask(created.id).first { it.date == day }
    }
    private fun bytes(color: Int) = ByteArrayOutputStream().use { stream ->
        Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, stream); recycle() }
        stream.toByteArray()
    }
    private suspend fun original(item: ScheduleOccurrenceEntity): MediaAssetEntity = media.importImage({ ByteArrayInputStream(bytes(Color.RED)) }, originalName = "原图.png") {
        attachments.attach(requireNotNull(db.scheduleDao().task(item.taskId)).sessionId, it, asPending = false)
    }
    private suspend fun added(draft: ScheduleEditorDraftEntity): MediaAssetEntity = media.importImage({ ByteArrayInputStream(bytes(Color.BLUE)) }, originalName = "新增.png") {
        editor.attach(draft.occurrenceId, draft.requestId, it.id)
    }

    @Test fun unsavedTextAndImagesStaySeparateSurviveReopenAndCancelPreservesOriginal() = runBlocking {
        val occurrence = task(); val old = original(occurrence)
        val first = editor.open(occurrence.id).draft
        editor.update(first.copy(title = "还未保存", detail = "正文修改", date = day.plusDays(1)))
        val fresh = added(first)
        editor.remove(occurrence.id, first.requestId, old.id)
        assertEquals("原始标题", db.scheduleDao().occurrence(occurrence.id)!!.title)
        assertEquals(listOf(old.id), db.scheduleMediaDao().attachments(first.sessionId).map { it.asset.id })
        assertEquals(0, db.mediaDao().deleteIfUnused(fresh.id))
        db.close(); connect()
        val restored = editor.open(occurrence.id)
        assertEquals("还未保存", restored.draft.title)
        assertEquals(listOf(fresh.id), restored.attachments.map { it.asset.id })
        editor.discard(occurrence.id); editor.cleanMedia()
        assertNull(db.scheduleEditor().draft(occurrence.id)); assertNull(media.fileFor(fresh.id))
        assertNotNull(media.fileFor(old.id)); assertEquals("原始正文", db.scheduleDao().occurrence(occurrence.id)!!.detail)
    }

    @Test fun saveMovesOrdinaryScheduleAndReplacesPicturesWithoutChangingTimeOrReminderOffsets() = runBlocking {
        val occurrence = task(); val old = original(occurrence)
        val draft = editor.open(occurrence.id).draft
        val new = added(draft)
        editor.remove(occurrence.id, draft.requestId, old.id)
        editor.update(draft.copy(title = "手动修改", detail = "新的正文", date = day.plusDays(1)))
        val result = editor.save(occurrence.id, draft.requestId)
        assertEquals("手动修改", result.title); assertEquals("新的正文", result.detail)
        assertEquals(day.plusDays(1), result.date); assertEquals(LocalTime.of(19, 0), result.time)
        assertEquals(listOf(60, 0), schedules.occurrenceDraft(result).reminderMinutes)
        assertEquals(new.id, result.coverAssetId)
        assertEquals(listOf(new.id), db.scheduleMediaDao().attachments(draft.sessionId).map { it.asset.id })
        assertEquals(result.title, schedules.taskForSession(draft.sessionId)!!.title)
        assertNull(db.scheduleEditor().draft(result.id))
        val pending = schedules.pendingReminders(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            day.plusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()).filter { it.occurrenceId == result.id }
        assertEquals(setOf(18, 19).map { hour -> result.date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.toSet(), pending.map { it.triggerAt }.toSet())
    }

    @Test fun failedFinalTransactionKeepsAllOriginalFieldsAndPicturesAndCanRetryOnce() = runBlocking {
        val occurrence = task(); val old = original(occurrence)
        val draft = editor.open(occurrence.id).draft; val new = added(draft)
        editor.remove(occurrence.id, draft.requestId, old.id)
        editor.update(draft.copy(title = "提交内容", date = day.plusDays(1)))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_manual_edit BEFORE INSERT ON schedule_operations BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END")
        assertTrue(runCatching { editor.save(occurrence.id, draft.requestId) }.isFailure)
        assertEquals(occurrence.title, db.scheduleDao().occurrence(occurrence.id)!!.title)
        assertEquals(occurrence.date, db.scheduleDao().occurrence(occurrence.id)!!.date)
        assertEquals(listOf(old.id), db.scheduleMediaDao().attachments(draft.sessionId).map { it.asset.id })
        assertNotNull(db.scheduleEditor().draft(occurrence.id)); assertNotNull(media.fileFor(new.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_manual_edit")
        assertEquals("提交内容", editor.save(occurrence.id, draft.requestId).title)
        assertEquals(2, schedules.operationHistory(draft.sessionId).size)
    }

    @Test fun concurrentCompletionOrAttachmentChangeCannotBeOverwrittenByOldForm() = runBlocking {
        val occurrence = task(); val old = original(occurrence)
        var draft = editor.open(occurrence.id).draft
        schedules.setCompleted(draft.sessionId, occurrence.id, true, "completion", occurrence.revision)
        assertTrue(editor.open(occurrence.id).conflict)
        assertTrue(runCatching { editor.save(occurrence.id, draft.requestId) }.exceptionOrNull() is ScheduleEditorConflict)
        editor.discard(occurrence.id)
        draft = editor.open(occurrence.id).draft
        attachments.remove(draft.sessionId, old.id)
        assertTrue(runCatching { editor.save(occurrence.id, draft.requestId) }.exceptionOrNull() is ScheduleEditorConflict)
        assertTrue(db.scheduleMediaDao().attachments(draft.sessionId).isEmpty())
        assertNotNull(db.scheduleDao().occurrence(occurrence.id)!!.completedAt)
    }

    @Test fun recurringEditMovesOnlyChosenOccurrencePastSeriesEndAndKeepsOtherDays() = runBlocking {
        val occurrence = task(recurring = true)
        val originalOthers = db.scheduleDao().occurrencesForTask(occurrence.taskId).filter { it.id != occurrence.id }
        val draft = editor.open(occurrence.id).draft
        editor.update(draft.copy(title = "本次变更", date = day.plusDays(5)))
        val moved = editor.save(occurrence.id, draft.requestId)
        assertTrue(moved.isException); assertEquals(day.plusDays(5), moved.date)
        assertEquals(originalOthers, db.scheduleDao().occurrencesForTask(occurrence.taskId).filter { it.id != occurrence.id })
        assertEquals("原始标题", schedules.taskForSession(draft.sessionId)!!.title)
        assertEquals(listOf(60, 0), schedules.occurrenceDraft(moved).reminderMinutes)
    }

    @Test fun invalidTitleDoesNotCommitMediaAndClearingTimeRemovesReminders() = runBlocking {
        val occurrence = task(); val draft = editor.open(occurrence.id).draft
        val image = added(draft)
        val invalid = editor.update(draft.copy(title = " "))
        assertTrue(runCatching { editor.save(occurrence.id, draft.requestId) }.isFailure)
        assertTrue(db.scheduleMediaDao().attachments(draft.sessionId).isEmpty())
        editor.update(invalid.copy(title = "仅日期", time = null))
        val result = editor.save(occurrence.id, draft.requestId)
        assertNull(result.time); assertTrue(schedules.occurrenceDraft(result).reminderMinutes!!.isEmpty())
        assertEquals(image.id, result.coverAssetId)
    }

    @Test fun deletingOriginalWithOpenEditorReleasesDraftMediaAndCannotResurrectTask() = runBlocking {
        val occurrence = task(); val draft = editor.open(occurrence.id).draft
        val image = added(draft)
        schedules.deleteTask(draft.sessionId, "delete", draft.expectedTaskRevision)
        assertNull(db.scheduleEditor().draft(occurrence.id)); assertNull(db.mediaDao().asset(image.id))
        editor.cleanMedia(); assertNull(media.fileFor(image.id))
        assertTrue(runCatching { editor.open(occurrence.id) }.isFailure)
    }

    @Test fun selectedImageIsCopiedImmediatelyAndDuplicatePickerResultsShareOneDraftAttachment() = runBlocking {
        val occurrence = task(); val draft = editor.open(occurrence.id).draft
        val source = File(context.filesDir, "media/originals/picker-source.png").apply { parentFile!!.mkdirs(); writeBytes(bytes(Color.GREEN)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source)
        editor.importImage(occurrence.id, draft.requestId, uri); editor.importImage(occurrence.id, draft.requestId, uri)
        source.delete(); db.close(); connect()
        val image = editor.open(occurrence.id).attachments.single()
        assertNotNull(media.fileFor(image.asset.id))
        assertTrue(db.scheduleMediaDao().attachments(draft.sessionId).isEmpty())
    }
}
