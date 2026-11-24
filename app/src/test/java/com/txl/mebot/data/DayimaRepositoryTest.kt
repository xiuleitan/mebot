package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.DayimaChange
import com.txl.mebot.data.dayima.DayimaConflict
import com.txl.mebot.data.dayima.DayimaDateConflict
import com.txl.mebot.data.dayima.DayimaNotFound
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.dayima.DayimaRequestConflict
import com.txl.mebot.data.dayima.DayimaWriteResult
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import com.txl.mebot.data.local.MebotDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
class DayimaRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var clock: DayimaClock
    private lateinit var repository: DayimaRepository
    private val first = LocalDate.of(2026, 9, 1)
    private val next = first.plusDays(28)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        clock = DayimaClock()
        database = MebotDatabase.create(context)
        repository = DayimaRepository(database, clock) { zone }
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun recordTodayUsesPhoneLocalDateAndCommitsFullNoteWithThePeriod() = runBlocking {
        val text = "  腰有点酸\n完整保留 | 数字 123  "
        val request = repository.recordTodayRequest(text)
        assertEquals(LocalDate.of(2026, 10, 25), request.date)
        val result = repository.execute(request) as DayimaWriteResult.PeriodSaved
        assertTrue(result.created)
        assertTrue(result.changed)
        assertFalse(result.replayed)
        assertEquals(request.date, result.period.startDate)
        assertEquals(clock.millis(), result.period.createdAt)
        assertEquals(text, result.note?.content)
        assertEquals(result.period.id, result.note?.periodId)
        assertEquals(1L, result.dataVersion)
        assertTrue(database.dayimaDao().sessions(50, 0).isEmpty())
    }

    @Test fun concurrentRetriesCreateOnePeriodOneNoteAndOneReceipt() = runBlocking {
        val request = DayimaChange.RecordPeriod(first, "同次记录的备注")
        val results = coroutineScope { List(12) { async(Dispatchers.Default) { repository.execute(request) } }.awaitAll() }
        assertEquals(1, results.count { !it.replayed })
        val period = repository.periods().single()
        assertEquals(1, repository.notesForPeriod(period.id).size)
        assertEquals(1L, repository.recordsSnapshot().dataVersion)
        assertTrue(results.all { (it as DayimaWriteResult.PeriodSaved).period == period })
    }

    @Test fun aNewRequestForTheSameDayReusesThePeriodWithoutRewritingItsTimes() = runBlocking {
        val original = record(first)
        clock.time += 10_000
        val repeated = repository.execute(DayimaChange.RecordPeriod(first)) as DayimaWriteResult.PeriodSaved
        assertFalse(repeated.created)
        assertFalse(repeated.changed)
        assertEquals(original, repeated.period)
        val withNote = repository.execute(DayimaChange.RecordPeriod(first, "补充情况")) as DayimaWriteResult.PeriodSaved
        assertFalse(withNote.created)
        assertTrue(withNote.changed)
        assertEquals(original, withNote.period)
        assertEquals(1, repository.periods().size)
        assertEquals("补充情况", repository.notesForPeriod(original.id).single().content)
    }

    @Test fun midnightAndReopenRetriesKeepTheOriginalDateAndDoNotRepeatTheWrite() = runBlocking {
        val request = repository.recordTodayRequest("本次情况")
        clock.time += 86_400_000
        val saved = repository.execute(request) as DayimaWriteResult.PeriodSaved
        assertEquals(LocalDate.of(2026, 10, 25), saved.period.startDate)
        reopen()
        clock.time += 86_400_000
        val retry = repository.execute(request) as DayimaWriteResult.PeriodSaved
        assertTrue(retry.replayed)
        assertFalse(retry.changed)
        assertEquals(saved.period, retry.period)
        assertEquals(saved.note, retry.note)
        assertEquals(saved.dataVersion, retry.dataVersion)
    }

    @Test fun reusedRequestIdCannotSubmitDifferentDataOrAnotherAction() = runBlocking {
        val request = DayimaChange.RecordPeriod(first, "原文\n包含分隔符:|")
        val saved = repository.execute(request) as DayimaWriteResult.PeriodSaved
        assertTrue(runCatching { repository.execute(request.copy(note = "不同内容")) }.exceptionOrNull() is DayimaRequestConflict)
        assertTrue(runCatching { repository.execute(request.copy(date = next)) }.exceptionOrNull() is DayimaRequestConflict)
        assertTrue(runCatching { repository.execute(DayimaChange.DeletePeriod(saved.period.id, 1, request.requestId)) }.exceptionOrNull() is DayimaRequestConflict)
        assertEquals(listOf(saved.period), repository.periods())
        assertEquals(1L, repository.recordsSnapshot().dataVersion)
    }

    @Test fun futureDatesAndBlankNotesFailWithoutPartialRecordsOrReceipts() = runBlocking {
        val future = LocalDate.of(2026, 10, 26)
        val changes = listOf(
            DayimaChange.RecordPeriod(future), DayimaChange.RecordPeriod(first, " \n\t"),
            DayimaChange.SaveBirthDate(future), DayimaChange.AddNote(future, "情况"), DayimaChange.AddNote(first, " "),
        )
        for (change in changes) {
            assertTrue(runCatching { repository.execute(change) }.exceptionOrNull() is IllegalArgumentException)
            assertNull(database.dayimaWriteDao().receipt(change.requestId))
        }
        assertTrue(repository.periods().isEmpty())
        assertNull(repository.profile())
        assertTrue(repository.notesBetween(first, future).isEmpty())
        assertEquals(0L, repository.recordsSnapshot().dataVersion)
    }

    @Test fun editingPreservesIdAndCreationTimeAndSameRequestRetryIsReadOnly() = runBlocking {
        val original = record(first, "备注日期独立保存")
        clock.time += 20_000
        val change = DayimaChange.EditPeriod(original.id, original.revision, first.minusDays(1))
        val saved = repository.execute(change) as DayimaWriteResult.PeriodSaved
        assertEquals(original.id, saved.period.id)
        assertEquals(original.createdAt, saved.period.createdAt)
        assertEquals(clock.millis(), saved.period.updatedAt)
        assertEquals(2L, saved.period.revision)
        assertEquals(first, repository.notesForPeriod(original.id).single().occurredDate)
        clock.time += 20_000
        val retry = repository.execute(change) as DayimaWriteResult.PeriodSaved
        assertEquals(saved.period, retry.period)
        assertFalse(retry.changed)
        assertEquals(saved.dataVersion, retry.dataVersion)
    }

    @Test fun conflictingDayStaleRevisionAndFutureEditNeverOverwriteExistingRecords() = runBlocking {
        val a = record(first)
        val b = record(next)
        val before = repository.recordsSnapshot()
        assertTrue(runCatching { repository.execute(DayimaChange.EditPeriod(a.id, 1, next)) }.exceptionOrNull() is DayimaDateConflict)
        assertTrue(runCatching { repository.execute(DayimaChange.EditPeriod(a.id, 2, first.minusDays(1))) }.exceptionOrNull() is DayimaConflict)
        assertTrue(runCatching { repository.execute(DayimaChange.EditPeriod(a.id, 1, LocalDate.of(2026, 10, 26))) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(a, b), repository.periods())
        assertEquals(before, repository.recordsSnapshot())
    }

    @Test fun oldCommittedCreateAndEditRequestsCannotRestoreDeletedOrLaterEditedData() = runBlocking {
        val create = DayimaChange.RecordPeriod(first, "原备注")
        val original = (repository.execute(create) as DayimaWriteResult.PeriodSaved).period
        val edit = DayimaChange.EditPeriod(original.id, 1, first.minusDays(1))
        val edited = (repository.execute(edit) as DayimaWriteResult.PeriodSaved).period
        val newer = (repository.execute(DayimaChange.EditPeriod(edited.id, 2, first.minusDays(2))) as DayimaWriteResult.PeriodSaved).period
        assertTrue(runCatching { repository.execute(edit) }.exceptionOrNull() is DayimaConflict)
        repository.execute(DayimaChange.DeletePeriod(newer.id, newer.revision))
        reopen()
        assertTrue(runCatching { repository.execute(create) }.exceptionOrNull() is DayimaConflict)
        assertTrue(runCatching { repository.execute(edit) }.exceptionOrNull() is DayimaConflict)
        assertTrue(repository.periods().isEmpty())
        assertEquals(4L, repository.recordsSnapshot().dataVersion)
    }

    @Test fun deletingPeriodKeepsNotesAndSpanForReviewAndInvalidatesOldChildEdits() = runBlocking {
        val a = record(first, "保留的备注")
        val b = record(next)
        val marked = mark(a, b)
        val note = repository.notesForPeriod(a.id).single()
        val result = repository.execute(DayimaChange.DeletePeriod(a.id, 1)) as DayimaWriteResult.Deleted
        assertTrue(result.existed)
        assertNull(repository.period(a.id))
        assertEquals(b, repository.period(b.id))
        val detached = repository.note(note.id)!!
        assertEquals(note.content, detached.content)
        assertNull(detached.periodId)
        assertEquals(2L, detached.revision)
        val span = repository.spanMarkers().single()
        assertEquals(marked.startDate, span.marker.startDate)
        assertNull(span.marker.startPeriodId)
        assertTrue(span.needsReview)
        assertTrue(span.marker.needsReview)
        assertEquals(2L, span.marker.revision)
        assertTrue(runCatching { repository.execute(DayimaChange.EditNote(note.id, 1, first, "过时编辑", null)) }.exceptionOrNull() is DayimaConflict)
        assertTrue(database.dayimaDao().sessions(50, 0).isEmpty())
    }

    @Test fun deletionRetryDoesNotDeleteANewRecordOnTheSameDate() = runBlocking {
        val original = record(first)
        val deletion = DayimaChange.DeletePeriod(original.id, 1)
        repository.execute(deletion)
        val nextRecord = record(first)
        val version = repository.recordsSnapshot().dataVersion
        val retry = repository.execute(deletion) as DayimaWriteResult.Deleted
        assertTrue(retry.replayed)
        assertFalse(retry.changed)
        assertTrue(retry.existed)
        assertEquals(version, retry.dataVersion)
        assertEquals(listOf(nextRecord), repository.periods())
        assertFalse((repository.execute(DayimaChange.DeletePeriod(original.id, 1)) as DayimaWriteResult.Deleted).existed)
    }

    @Test fun birthDateCanBeSavedChangedClearedAndRecoveredAfterReopen() = runBlocking {
        val create = DayimaChange.SaveBirthDate(LocalDate.of(1992, 2, 29))
        val original = (repository.execute(create) as DayimaWriteResult.BirthDateSaved).profile
        reopen()
        assertEquals(original, (repository.execute(create) as DayimaWriteResult.BirthDateSaved).profile)
        val change = DayimaChange.SaveBirthDate(LocalDate.of(1991, 3, 1), 1)
        val edited = (repository.execute(change) as DayimaWriteResult.BirthDateSaved).profile
        assertEquals(2L, edited.revision)
        assertTrue(runCatching { repository.execute(DayimaChange.SaveBirthDate(null, 1)) }.exceptionOrNull() is DayimaConflict)
        assertTrue(runCatching { repository.execute(create) }.exceptionOrNull() is DayimaConflict)
        val cleared = repository.execute(DayimaChange.SaveBirthDate(null, 2)) as DayimaWriteResult.BirthDateSaved
        assertNull(cleared.profile.birthDate)
        assertEquals(3L, cleared.profile.revision)
    }

    @Test fun standaloneAndLinkedNotesCanBeEditedAndDeletedWithoutChangingPeriods() = runBlocking {
        val period = record(first)
        val add = DayimaChange.AddNote(first.plusDays(1), "  完整备注\n第二行  ")
        val original = (repository.execute(add) as DayimaWriteResult.NoteSaved).note
        assertNull(original.periodId)
        val edit = DayimaChange.EditNote(original.id, 1, first.plusDays(2), "新的备注", period.id)
        val saved = (repository.execute(edit) as DayimaWriteResult.NoteSaved).note
        assertEquals(original.createdAt, saved.createdAt)
        assertEquals(2L, saved.revision)
        assertEquals(listOf(saved), repository.notesForPeriod(period.id))
        assertTrue(runCatching { repository.execute(add) }.exceptionOrNull() is DayimaConflict)
        val delete = DayimaChange.DeleteNote(saved.id, 2)
        repository.execute(delete)
        assertNull(repository.note(saved.id))
        assertTrue((repository.execute(delete) as DayimaWriteResult.Deleted).replayed)
        assertTrue(runCatching { repository.execute(edit) }.exceptionOrNull() is DayimaConflict)
        assertEquals(period, repository.period(period.id))
    }

    @Test fun missingParentBlankEditedNoteAndStaleNoteVersionLeaveOriginalInputAndDataUntouched() = runBlocking {
        val add = DayimaChange.AddNote(first, "原内容")
        val original = (repository.execute(add) as DayimaWriteResult.NoteSaved).note
        assertTrue(runCatching { repository.execute(DayimaChange.AddNote(first, "备注", "missing")) }.exceptionOrNull() is DayimaNotFound)
        assertTrue(runCatching { repository.execute(DayimaChange.EditNote(original.id, 1, first, " ", null)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.execute(DayimaChange.EditNote(original.id, 2, first, "修改", null)) }.exceptionOrNull() is DayimaConflict)
        assertEquals("原内容", add.content)
        assertEquals(original, repository.note(original.id))
        assertEquals(1L, repository.recordsSnapshot().dataVersion)
    }

    @Test fun noteWriteFailureRollsBackTheNewPeriodAndSameRequestCanRetry() = runBlocking {
        val change = DayimaChange.RecordPeriod(first, "保留输入")
        failInserts("dayima_notes")
        assertTrue(runCatching { repository.execute(change) }.isFailure)
        assertTrue(repository.periods().isEmpty())
        assertEquals(0L, repository.recordsSnapshot().dataVersion)
        assertNull(database.dayimaWriteDao().receipt(change.requestId))
        assertEquals("保留输入", change.note)
        allowInserts()
        val saved = repository.execute(change) as DayimaWriteResult.PeriodSaved
        assertEquals(change.note, saved.note?.content)
        assertEquals(1L, saved.dataVersion)
    }

    @Test fun receiptWriteFailureRollsBackProfileAndDoesNotClaimSuccess() = runBlocking {
        val change = DayimaChange.SaveBirthDate(LocalDate.of(1990, 1, 1))
        failInserts("dayima_write_receipts")
        assertTrue(runCatching { repository.execute(change) }.isFailure)
        assertNull(repository.profile())
        assertEquals(0L, repository.recordsSnapshot().dataVersion)
        allowInserts()
        assertEquals(change.date, (repository.execute(change) as DayimaWriteResult.BirthDateSaved).profile.birthDate)
    }

    @Test fun failedDeletionRestoresParentChildLinksAndMarkerVersionsTogether() = runBlocking {
        val a = record(first, "保留备注")
        val b = record(next)
        val span = mark(a, b)
        val note = repository.notesForPeriod(a.id).single()
        val before = repository.recordsSnapshot()
        val deletion = DayimaChange.DeletePeriod(a.id, 1)
        failInserts("dayima_write_receipts")
        assertTrue(runCatching { repository.execute(deletion) }.isFailure)
        assertEquals(before, repository.recordsSnapshot())
        assertEquals(note, repository.note(note.id))
        assertEquals(span, repository.spanMarkers().single().marker)
        allowInserts()
        repository.execute(deletion)
        assertNull(repository.period(a.id))
        assertNull(repository.note(note.id)?.periodId)
    }

    @Test fun spanRequiresAdjacentOrderedRecordsAndCurrentEndpointVersions() = runBlocking {
        val a = record(first)
        val b = record(first.plusDays(14))
        val c = record(next)
        val skip = DayimaChange.SaveSpan(a.id, c.id, 1, 1, DayimaSpanReason.MISSING_RECORDS)
        assertTrue(runCatching { repository.execute(skip) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.execute(skip.copy(startPeriodId = c.id, endPeriodId = a.id)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.execute(skip.copy(endPeriodId = b.id, startPeriodRevision = 2)) }.exceptionOrNull() is DayimaConflict)
        val saved = mark(a, b)
        assertFalse(repository.spanMarkers().single().needsReview)
        assertTrue(runCatching { repository.execute(DayimaChange.SaveSpan(a.id, b.id, 1, 1, DayimaSpanReason.SPECIAL_PHASE)) }.exceptionOrNull() is DayimaConflict)
        assertEquals(saved, repository.spanMarkers().single().marker)
    }

    @Test fun insertingThenDeletingAMiddleRecordDoesNotReactivateAnOldSpan() = runBlocking {
        val a = record(first)
        val b = record(next)
        mark(a, b)
        val middle = record(first.plusDays(14))
        val invalidated = repository.spanMarkers().single()
        assertTrue(invalidated.needsReview)
        repository.execute(DayimaChange.DeletePeriod(middle.id, 1))
        reopen()
        val stillInvalid = repository.spanMarkers().single()
        assertTrue(stillInvalid.needsReview)
        val review = DayimaChange.SaveSpan(a.id, b.id, 1, 1, DayimaSpanReason.MISSING_RECORDS,
            id = stillInvalid.marker.id, expectedRevision = stillInvalid.marker.revision)
        repository.execute(review)
        assertFalse(repository.spanMarkers().single().needsReview)
    }

    @Test fun editingDatesInvalidatesBoundaryAndCoveringSpansUntilExplicitReview() = runBlocking {
        val a = record(first)
        val b = record(first.plusDays(14))
        val c = record(next)
        val marked = mark(a, b)
        val moved = (repository.execute(DayimaChange.EditPeriod(c.id, 1, first.plusDays(7))) as DayimaWriteResult.PeriodSaved).period
        assertTrue(repository.spanMarkers().single().needsReview)
        assertEquals(marked.startDate, repository.spanMarkers().single().marker.startDate)
        val state = repository.spanMarkers().single().marker
        repository.execute(DayimaChange.SaveSpan(a.id, moved.id, 1, 2, DayimaSpanReason.SPECIAL_PHASE,
            id = state.id, expectedRevision = state.revision))
        assertFalse(repository.spanMarkers().single().needsReview)
        repository.execute(DayimaChange.EditPeriod(a.id, 1, first.minusDays(1)))
        assertTrue(repository.spanMarkers().single().needsReview)
    }

    @Test fun spanInvalidationRollsBackWhenItsNewPeriodCannotCommit() = runBlocking {
        val a = record(first)
        val b = record(next)
        val marked = mark(a, b)
        val before = repository.recordsSnapshot()
        val change = DayimaChange.RecordPeriod(first.plusDays(14))
        failInserts("dayima_write_receipts")
        assertTrue(runCatching { repository.execute(change) }.isFailure)
        assertEquals(before, repository.recordsSnapshot())
        assertEquals(marked, repository.spanMarkers().single().marker)
        allowInserts()
        repository.execute(change)
        assertTrue(repository.spanMarkers().single().needsReview)
    }

    @Test fun removingSpanIsIdempotentAndItsOldSaveCannotBringItBack() = runBlocking {
        val a = record(first)
        val b = record(next)
        val change = DayimaChange.SaveSpan(a.id, b.id, 1, 1, DayimaSpanReason.MISSING_RECORDS)
        val saved = (repository.execute(change) as DayimaWriteResult.SpanSaved).marker
        val deletion = DayimaChange.DeleteSpan(saved.id, saved.revision)
        repository.execute(deletion)
        assertTrue((repository.execute(deletion) as DayimaWriteResult.Deleted).replayed)
        assertTrue(runCatching { repository.execute(change) }.exceptionOrNull() is DayimaConflict)
        assertTrue(repository.spanMarkers().isEmpty())
    }

    @Test fun dataVersionDoesNotDependOnClockAndSnapshotMatchesTheCommittedState() = runBlocking {
        val a = record(first)
        val before = repository.recordsSnapshot()
        clock.time -= 10_000
        val saved = repository.execute(DayimaChange.EditPeriod(a.id, 1, first.minusDays(1))) as DayimaWriteResult.PeriodSaved
        val snapshot = repository.recordsSnapshot()
        assertTrue(snapshot.dataVersion > before.dataVersion)
        assertEquals(saved.dataVersion, snapshot.dataVersion)
        assertEquals(listOf(saved.period), snapshot.periods)
        assertEquals(snapshot.dataVersion, repository.observeDataVersion().first())
        assertEquals(snapshot.periods, repository.observePeriods().first())
    }

    private suspend fun record(date: LocalDate, note: String? = null) =
        (repository.execute(DayimaChange.RecordPeriod(date, note)) as DayimaWriteResult.PeriodSaved).period

    private suspend fun mark(a: DayimaPeriodEntity, b: DayimaPeriodEntity): DayimaSpanMarkerEntity =
        (repository.execute(DayimaChange.SaveSpan(a.id, b.id, a.revision, b.revision, DayimaSpanReason.MISSING_RECORDS)) as DayimaWriteResult.SpanSaved).marker

    private fun reopen() {
        database.close()
        database = MebotDatabase.create(context)
        repository = DayimaRepository(database, clock) { zone }
    }

    private fun failInserts(table: String) {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_dayima_write BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
    }
    private fun allowInserts() { database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_dayima_write") }

    private class DayimaClock : Clock() {
        var time = Instant.parse("2026-10-24T17:30:00Z").toEpochMilli()
        override fun instant(): Instant = Instant.ofEpochMilli(time)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }
}
