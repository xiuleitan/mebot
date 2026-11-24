package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
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
class DayimaRecordEditingTest {
    private lateinit var db: MebotDatabase
    private lateinit var records: DayimaRepository
    private val today = LocalDate.of(2026, 10, 28)
    private val first = today.minusDays(12)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MebotDatabase::class.java).build()
        records = DayimaRepository(db, clock) { ZoneOffset.UTC }
    }
    @After fun cleanup() { db.close() }
    private suspend fun seed() = (records.execute(DayimaChange.RecordPeriod(first, "第一段正文")) as DayimaWriteResult.PeriodSaved).period
    private suspend fun edits(id: String) = records.notesForPeriod(id).map { DayimaBodyNoteEdit(it.id, it.revision, it.content, it.content) }
    private suspend fun image(period: String): MediaAssetEntity {
        val asset = MediaAssetEntity(id = "record-photo", source = MediaSource.IMPORTED,
            relativePath = "media/originals/record-editing-test.png", mimeType = "image/png", byteCount = 8,
            width = 2, height = 2, createdAt = clock.millis())
        db.mediaDao().insert(asset)
        db.dayimaMediaDao().insertPeriod(DayimaPeriodMediaEntity(period, asset.id, "图片", 0, null, clock.millis()))
        return asset
    }

    @Test fun dateTextAndImageRemovalCommitTogetherPreservingEachOriginalNoteDateAndSharedMedia() = runBlocking {
        val period = seed()
        val second = (records.execute(DayimaChange.AddNote(first.plusDays(2), "第二段正文", period.id)) as DayimaWriteResult.NoteSaved).note
        val photo = image(period.id)
        val session = DayimaConversationRepository(db, clock).open(new = true).session
        db.dayimaMediaDao().insertDraft(DayimaDraftMediaEntity(session.id, photo.id, "图片", 0, clock.millis()))
        val originalNotes = records.notesForPeriod(period.id)
        val change = DayimaChange.EditPeriodDetails(period.id, period.revision, first.minusDays(1),
            edits(period.id).map { if (it.id == second.id) it.copy(content = "修改后的第二段") else it },
            "新增正文", listOf(photo.id))
        val saved = records.execute(change) as DayimaWriteResult.PeriodSaved
        assertEquals(first.minusDays(1), saved.period.startDate)
        assertEquals(2L, saved.period.revision)
        val notes = records.notesForPeriod(period.id).associateBy { it.id }
        originalNotes.forEach { assertEquals(it.occurredDate, notes.getValue(it.id).occurredDate) }
        assertEquals("修改后的第二段", notes.getValue(second.id).content)
        assertEquals(first.minusDays(1), notes.getValue(change.requestId).occurredDate)
        assertTrue(records.periodDetail(period.id)!!.images.isEmpty())
        assertNotNull(db.mediaDao().asset(photo.id))
        assertTrue(records.execute(change).replayed)
        assertEquals(3, records.notesForPeriod(period.id).size)
    }

    @Test fun receiptFailureRollsBackDateTextImagesAndCleanupQueueThenSameRequestRetriesOnce() = runBlocking {
        val period = seed(); val original = records.notesForPeriod(period.id).single(); val photo = image(period.id)
        val change = DayimaChange.EditPeriodDetails(period.id, period.revision, first.minusDays(1),
            edits(period.id).map { it.copy(content = "更新正文") }, "补充一段", listOf(photo.id), requestId = "details-retry")
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_details_receipt BEFORE INSERT ON dayima_write_receipts
            WHEN NEW.requestId = 'details-retry' BEGIN SELECT RAISE(ABORT, 'fixture'); END""")
        assertTrue(runCatching { records.execute(change) }.isFailure)
        assertEquals(period, records.period(period.id)); assertEquals(listOf(original), records.notesForPeriod(period.id))
        assertEquals(photo.id, records.periodDetail(period.id)!!.images.single().asset.id)
        assertNotNull(db.mediaDao().asset(photo.id)); assertTrue(db.mediaDao().cleanupPaths(128).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_details_receipt")
        assertFalse(records.execute(change).replayed); assertTrue(records.execute(change).replayed)
        assertEquals(2, records.notesForPeriod(period.id).size)
        assertNull(db.mediaDao().asset(photo.id))
        assertEquals(listOf(photo.relativePath), db.mediaDao().cleanupPaths(128))
    }

    @Test fun addedOrChangedBodyAfterOpeningTheEditorRejectsTheWholeStaleSave() = runBlocking {
        val period = seed(); val photo = image(period.id)
        val stale = DayimaChange.EditPeriodDetails(period.id, period.revision, first.minusDays(1), edits(period.id),
            removedImageIds = listOf(photo.id))
        records.execute(DayimaChange.AddNote(first.plusDays(1), "对话刚保存的内容", period.id))
        assertTrue(runCatching { records.execute(stale) }.exceptionOrNull() is DayimaConflict)
        assertEquals(first, records.period(period.id)!!.startDate)
        assertEquals(2, records.notesForPeriod(period.id).size)
        assertEquals(1, records.periodDetail(period.id)!!.images.size)
        val fresh = DayimaChange.EditPeriodDetails(period.id, period.revision, first, edits(period.id))
        records.execute(fresh)
        val note = records.notesForPeriod(period.id).first()
        records.execute(DayimaChange.EditNote(note.id, note.revision, note.occurredDate, "又修改了正文", period.id))
        assertTrue(runCatching { records.execute(fresh) }.exceptionOrNull() is DayimaConflict)
    }

    @Test fun emptyBodyIsAllowedAndClearingTextDoesNotDeleteThePeriodOrCreateAnEmptyNote() = runBlocking {
        val period = seed()
        val change = DayimaChange.EditPeriodDetails(period.id, period.revision, first, edits(period.id).map { it.copy(content = "  ") })
        records.execute(change); assertTrue(records.execute(change).replayed)
        assertNotNull(records.period(period.id)); assertTrue(records.notesForPeriod(period.id).isEmpty())
        val unchanged = records.execute(DayimaChange.EditPeriodDetails(period.id, 2L, first, emptyList(), "  "))
        assertFalse(unchanged.changed); assertTrue(records.notesForPeriod(period.id).isEmpty())
    }

    @Test fun movingDateWithoutEditingBodyKeepsExistingTextWhitespaceAndOccurrenceDates() = runBlocking {
        val period = (records.execute(DayimaChange.RecordPeriod(first, "  原有正文\n")) as DayimaWriteResult.PeriodSaved).period
        val original = records.notesForPeriod(period.id).single()
        val change = DayimaChange.EditPeriodDetails(period.id, period.revision, first.minusDays(1), edits(period.id))
        records.execute(change)
        assertEquals(original, records.notesForPeriod(period.id).single())
        assertTrue(records.execute(change).replayed)
    }
}
