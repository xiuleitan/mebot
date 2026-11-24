package com.txl.mebot.ui.dayima

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.R
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.*
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
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

/** 开发机使用真实 Room 验证 UI 状态和事务结果，不向手机植入样例数据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DayimaViewModelTest {
    private lateinit var application: Application
    private lateinit var database: MebotDatabase
    private lateinit var repository: DayimaRepository
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: DayimaViewModel
    private val today = LocalDate.of(2026, 10, 25)
    private val first = LocalDate.of(2026, 8, 1)

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(application, MebotDatabase::class.java).build()
        repository = DayimaRepository(database, Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)) { ZoneOffset.UTC }
        createModel()
    }

    private fun createModel(handle: SavedStateHandle = SavedStateHandle(), commit: suspend (DayimaChange) -> DayimaWriteResult = repository::execute) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DayimaViewModel(application, handle, repository, commit) as T
        }
        model = ViewModelProvider(activity.get(), factory)[DayimaViewModel::class.java]
        model.setVisible(true)
        await { model.state.value.overview != null && !model.state.value.loading }
    }

    private fun destroyModel() {
        model.setVisible(false)
        activity.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun tearDown() { destroyModel(); database.close() }

    @Test fun emptyPageHasNoImplicitPeriodsProfileOrConversations() = runBlocking {
        val overview = requireNotNull(model.state.value.overview)
        assertTrue(overview.records.periods.isEmpty())
        assertNull(overview.records.profile)
        assertTrue(overview.noteCounts.isEmpty())
        assertNull(overview.statistics.currentCycle)
        assertEquals(DayimaPredictionStatus.NO_RECORDS, overview.statistics.prediction.status)
        assertTrue(database.dayimaDao().sessions(30, 0).isEmpty())
    }

    @Test fun phaseEstimateRefreshesAfterManualCorrectionAndDeletion() = runBlocking {
        record(today.minusDays(50))
        val latest = record(today.minusDays(22))
        await { model.state.value.overview?.phases?.currentPhase == DayimaCyclePhase.LUTEAL }
        assertEquals(23L, model.state.value.overview?.phases?.cycleDay)
        open(latest.id); model.beginPeriodEdit(); model.changeDate(today.minusDays(14).toString()); model.saveEditor()
        await { !model.state.value.busy && model.state.value.overview?.phases?.cycleDay == 15L && model.state.value.detail?.period?.revision == 2L }
        assertEquals(DayimaCyclePhase.FOLLICULAR, model.state.value.overview?.phases?.currentPhase)
        model.askDeletePeriod(); model.confirmDelete()
        await { !model.state.value.busy && model.state.value.overview?.records?.periods?.size == 1 }
        assertEquals(DayimaPhaseEstimateStatus.INSUFFICIENT_HISTORY, model.state.value.overview?.phases?.status)
        assertNull(model.state.value.overview?.phases?.currentPhase)
        assertEquals(51L, model.state.value.overview?.phases?.cycleDay)
    }

    @Test fun birthDateCanBeSavedChangedAndRemovedWithoutAnyPeriod() = runBlocking {
        model.beginBirth(); model.changeDate("20000229"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor == null && model.state.value.overview?.records?.profile?.birthDate != null }
        assertEquals(LocalDate.of(2000, 2, 29), repository.profile()?.birthDate)
        model.beginBirth(); model.changeDate("1999-01-01"); model.saveEditor()
        await { model.state.value.overview?.records?.profile?.birthDate == LocalDate.of(1999, 1, 1) }
        model.beginBirth(); model.changeDate(""); model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor == null && model.state.value.overview?.records?.profile?.birthDate == null }
        assertNull(repository.profile()?.birthDate)
        assertTrue(repository.periods().isEmpty())
    }

    @Test fun validationKeepsInputAndNeverWritesInvalidFutureDatesOrBlankNotes() = runBlocking {
        model.beginRecord(false); model.changeDate("2026-02-30"); model.changeContent("保留我的输入"); model.saveEditor()
        assertEquals(R.string.dayima_invalid_date, model.state.value.editor?.error)
        assertEquals("保留我的输入", model.state.value.editor?.content)
        model.changeDate(today.plusDays(1).toString()); model.saveEditor()
        assertEquals(R.string.dayima_future_date, model.state.value.editor?.error)
        assertTrue(repository.periods().isEmpty())
        model.cancelEditor(); model.beginNote(independent = true); model.saveEditor()
        assertEquals(R.string.dayima_empty_note, model.state.value.editor?.error)
        assertEquals(0, repository.independentNotesPage().notes.size)
    }

    @Test fun doubleTapSavesOnePeriodAndOneNoteAndTodayActionOpensIt() = runBlocking {
        model.beginRecord(true); model.changeContent("今天开始，轻微不适"); model.saveEditor(); model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor == null && model.state.value.overview?.records?.periods?.size == 1 }
        val period = repository.periods().single()
        assertEquals(today, period.startDate)
        assertEquals(1, repository.notesForPeriod(period.id).size)
        assertEquals(1, model.state.value.overview?.noteCounts?.get(period.id))
        model.beginRecord(true)
        await { model.state.value.detail?.period?.id == period.id }
        assertNull(model.state.value.editor)
    }

    @Test fun duplicateDateShowsAccurateResultAndAddsOnlyAnExplicitNewNote() = runBlocking {
        record(first)
        await { model.state.value.overview?.records?.periods?.size == 1 }
        model.beginRecord(false); model.changeDate(first.toString()); model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor == null }
        assertEquals(R.string.dayima_duplicate_record, model.state.value.notice?.text)
        model.beginRecord(false); model.changeDate(first.toString()); model.changeContent("补充备注"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor == null }
        assertEquals(R.string.dayima_duplicate_note_saved, model.state.value.notice?.text)
        assertEquals(1, repository.periods().size)
        assertEquals(listOf("补充备注"), repository.notesForPeriod(repository.periods().single().id).map { it.content })
    }

    @Test fun editingDateUpdatesHistoryAndPredictionAndDuplicateEditDoesNotOverwrite() = runBlocking {
        val a = record(first)
        val b = record(first.plusDays(28))
        await { model.state.value.overview?.statistics?.period?.completedCycleCount == 1 }
        open(b.id); model.beginPeriodEdit(); model.changeDate(first.toString()); model.saveEditor()
        await { !model.state.value.busy }
        assertEquals(R.string.dayima_duplicate_date, model.state.value.editor?.error)
        assertEquals(first.plusDays(28), repository.period(b.id)?.startDate)
        model.changeDate(first.plusDays(30).toString()); model.saveEditor()
        await { !model.state.value.busy && model.state.value.overview?.statistics?.prediction?.prediction?.weightedCycleDays == 30L }
        assertEquals(30L, model.state.value.overview?.statistics?.period?.cycles?.single()?.days)
        assertEquals(a.id, repository.periods().first().id)
    }

    @Test fun deletingPeriodKeepsNotesAccessibleAndEditableAsIndependentNotes() = runBlocking {
        val period = (repository.execute(DayimaChange.RecordPeriod(first, "删除开始后也保留")) as DayimaWriteResult.PeriodSaved).period
        open(period.id); model.askDeletePeriod(); model.confirmDelete(); model.confirmDelete()
        await { !model.state.value.busy && model.state.value.detailId == null && model.state.value.overview?.independentNoteCount == 1 }
        model.selectHistory(DayimaHistory.NOTES)
        await { !model.state.value.notesLoading && model.state.value.independentNotes.size == 1 }
        val note = model.state.value.independentNotes.single()
        assertNull(note.periodId)
        assertEquals("删除开始后也保留", note.content)
        model.beginNote(note, independent = true); model.changeContent("仍可编辑"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.independentNotes.firstOrNull()?.content == "仍可编辑" }
        model.beginNote(model.state.value.independentNotes.single(), independent = true)
        model.askDeleteEditingNote(); model.confirmDelete()
        await { !model.state.value.busy && model.state.value.editor == null && model.state.value.overview?.independentNoteCount == 0 }
        assertTrue(repository.periods().isEmpty())
    }

    @Test fun failureAfterCommitRetriesSameRestoredRequestWithoutDuplicatingPeriodOrNote() = runBlocking {
        destroyModel()
        val handle = SavedStateHandle()
        val fail = AtomicBoolean(true)
        val requests = mutableListOf<String>()
        createModel(handle) { change ->
            requests += change.requestId
            val result = repository.execute(change)
            if (fail.getAndSet(false)) throw IOException("simulated lost acknowledgement")
            result
        }
        model.beginRecord(false); model.changeDate(first.toString()); model.changeContent("只能出现一次"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor?.error == R.string.dayima_save_failed }
        val request = model.state.value.editor?.requestId
        assertEquals("只能出现一次", model.state.value.editor?.content)
        destroyModel(); createModel(handle) { change -> requests += change.requestId; repository.execute(change) }
        assertEquals(request, model.state.value.editor?.requestId)
        model.saveEditor()
        await { !model.state.value.busy && model.state.value.editor == null }
        assertEquals(listOf(request, request), requests)
        assertEquals(1, repository.periods().size)
        assertEquals(1, repository.notesForPeriod(repository.periods().single().id).size)
    }

    @Test fun externalEditDoesNotReplaceEditorInputOrAllowStaleSave() = runBlocking {
        val period = record(first)
        open(period.id); model.beginPeriodEdit(); model.changeDate(first.plusDays(1).toString())
        repository.execute(DayimaChange.EditPeriod(period.id, period.revision, first.plusDays(2)))
        await { model.state.value.detail?.period?.revision == 2L }
        assertEquals(first.plusDays(1).toString(), model.state.value.editor?.date)
        model.saveEditor()
        await { !model.state.value.busy }
        assertEquals(R.string.dayima_conflict, model.state.value.editor?.error)
        assertEquals(first.plusDays(2), repository.period(period.id)?.startDate)
    }

    @Test fun hidingPageDoesNotCancelInFlightWriteAndReturningRefreshesFacts() = runBlocking {
        destroyModel()
        val release = CompletableDeferred<Unit>()
        createModel { change -> release.await(); repository.execute(change) }
        model.beginRecord(true); model.saveEditor()
        assertTrue(model.state.value.busy)
        model.setVisible(false); release.complete(Unit)
        await { !model.state.value.busy && model.state.value.editor == null }
        assertEquals(1, repository.periods().size)
        model.setVisible(true)
        await { model.state.value.overview?.records?.periods?.size == 1 }
    }

    @Test fun independentNotePaginationResetsOnWritesAndDoesNotLoadAttachedBodies() = runBlocking {
        for (i in 0 until 65) repository.execute(DayimaChange.AddNote(first, "独立备注 $i", requestId = "note-${i.toString().padStart(3, '0')}"))
        val period = (repository.execute(DayimaChange.RecordPeriod(first, "关联备注")) as DayimaWriteResult.PeriodSaved).period
        await { model.state.value.overview?.independentNoteCount == 65 }
        assertEquals(1, model.state.value.overview?.noteCounts?.get(period.id))
        model.selectHistory(DayimaHistory.NOTES)
        await { !model.state.value.notesLoading && model.state.value.independentNotes.size == 30 }
        assertTrue(model.state.value.notesHaveMore)
        model.loadNotes(); await { !model.state.value.notesLoading && model.state.value.independentNotes.size == 60 }
        model.loadNotes(); await { !model.state.value.notesLoading && model.state.value.independentNotes.size == 65 }
        assertFalse(model.state.value.notesHaveMore)
        assertEquals(65, model.state.value.independentNotes.map { it.id }.distinct().size)
        assertTrue(model.state.value.independentNotes.all { it.periodId == null })
        model.askDeleteNote(model.state.value.independentNotes.first()); model.confirmDelete()
        await { !model.state.value.busy && model.state.value.overview?.independentNoteCount == 64 && model.state.value.independentNotes.size == 30 }
        assertEquals(1, repository.notesForPeriod(period.id).size)
    }

    @Test fun spanControlsExcludeThenExposeStaleMarkerForRemovalAfterBackfill() = runBlocking {
        val a = record(first)
        record(first.plusDays(28))
        open(a.id); model.beginSpan(); model.changeContent("这段有漏记"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.overview?.statistics?.period?.excludedCycleCount == 1 }
        assertEquals(DayimaPredictionStatus.INSUFFICIENT_CONTINUOUS_HISTORY, model.state.value.overview?.statistics?.prediction?.status)
        record(first.plusDays(14))
        await { model.state.value.overview?.statistics?.period?.pendingReviewMarkerCount == 1 }
        assertEquals(2, model.state.value.overview?.statistics?.period?.eligibleCycleCount)
        val marker = model.state.value.overview!!.markers.single()
        assertTrue(marker.needsReview)
        model.askDeleteSpan(marker.marker); model.confirmDelete()
        await { !model.state.value.busy && model.state.value.overview?.markers?.isEmpty() == true }
        assertEquals(3, repository.periods().size)
    }

    @Test fun chartsFilterByStartDateKeepOutsideSuccessorAndLeaveOverviewPredictionUnchanged() = runBlocking {
        val a = record(first); val b = record(first.plusDays(28)); record(first.plusDays(56))
        awaitCharts()
        val originalPrediction = model.state.value.overview!!.statistics.prediction
        model.charts.selectRange(DayimaChartRange.CUSTOM)
        model.charts.changeFrom(first.toString()); model.charts.changeThrough(first.toString()); model.charts.applyRange()
        await { model.state.value.charts.data?.statistics?.period?.range == DayimaDateRange(first, first) }
        val chart = model.state.value.charts.data!!
        assertEquals(listOf(a.id), chart.statistics.period.cycles.map { it.startPeriodId })
        assertEquals(b.id, chart.statistics.period.cycles.single().endPeriodId)
        assertEquals(28L, chart.statistics.period.cycles.single().days)
        assertEquals(originalPrediction, model.state.value.overview!!.statistics.prediction)
        assertEquals(2, model.state.value.overview!!.statistics.period.completedCycleCount)
    }

    @Test fun chartDrillDownAndHistogramRefreshAfterCorrectionAndDeletionWithoutStaleRows() = runBlocking {
        val a = record(first); val b = record(first.plusDays(28)); record(first.plusDays(56))
        awaitCharts()
        val selection = model.state.value.charts.data!!.histogram.single().selection()
        model.charts.select(selection)
        open(b.id); model.beginPeriodEdit(); model.changeDate(first.plusDays(30).toString()); model.saveEditor()
        await { !model.state.value.busy && model.state.value.charts.data?.statistics?.period?.frequency?.map { it.days } == listOf(26L, 30L) }
        val edited = model.state.value.charts.data!!
        assertTrue(model.state.value.charts.selection!!.cycles(edited).isEmpty())
        assertEquals(model.state.value.overview!!.records.dataVersion, edited.statistics.dataVersion)
        model.openDetail(null)
        assertNotNull(model.state.value.charts.selection)
        model.charts.select(DayimaChartSelection(DayimaChartSelectionKind.POINTS, cycleStartIds = listOf(a.id)))
        open(a.id); model.askDeletePeriod(); model.confirmDelete()
        await { !model.state.value.busy && model.state.value.charts.data?.statistics?.period?.completedCycleCount == 1 }
        assertTrue(model.state.value.charts.selection!!.cycles(model.state.value.charts.data!!).isEmpty())
    }

    @Test fun chartAgeRefreshesAfterBirthChangesWhileLengthsAndFrequencyStayTheSame() = runBlocking {
        record(first); record(first.plusDays(28)); record(first.plusDays(56))
        awaitCharts()
        val original = model.state.value.charts.data!!
        assertTrue(original.ages.points.isEmpty())
        model.beginBirth(); model.changeDate("2000-01-01"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.charts.data?.ages?.points?.size == 2 }
        val known = model.state.value.charts.data!!
        assertEquals(original.histogram, known.histogram)
        assertEquals(listOf(26, 26), known.ages.points.map { it.cycle.ageYears })
        model.beginBirth(); model.changeDate("1999-01-01"); model.saveEditor()
        await { !model.state.value.busy && model.state.value.charts.data?.ages?.points?.all { it.cycle.ageYears == 27 } == true }
        assertEquals(original.histogram, model.state.value.charts.data!!.histogram)
    }

    @Test fun chartRestoreClearsRetiredFiltersAndDraftsWhilePreservingSelectionAndLatestResults() = runBlocking {
        destroyModel()
        val handle = SavedStateHandle(); createModel(handle)
        val old = record(first.minusYears(2))
        record(first); record(first.plusDays(28)); awaitCharts()
        model.charts.selectRange(DayimaChartRange.LAST_YEAR); awaitCharts()
        assertEquals(1, model.state.value.charts.data!!.statistics.period.completedCycleCount)
        model.charts.select(DayimaChartSelection(DayimaChartSelectionKind.HISTOGRAM, 28, 28))
        model.charts.beginRange()
        destroyModel(); createModel(handle); awaitCharts()
        assertEquals(DayimaChartRange.ALL, model.state.value.charts.options.range)
        assertNull(model.state.value.charts.rangeEditor)
        assertFalse(handle.contains("dayima.charts.options"))
        assertFalse(handle.contains("dayima.charts.range_editor"))
        assertEquals(2, model.state.value.charts.data!!.statistics.period.completedCycleCount)
        assertTrue(model.state.value.charts.data!!.statistics.period.cycles.any { it.startPeriodId == old.id })
        assertEquals(1, model.state.value.charts.selection!!.cycles(model.state.value.charts.data!!).size)
        model.closeSheet(); assertNull(model.state.value.charts.selection)
        model.charts.selectRange(DayimaChartRange.LAST_YEAR)
        model.charts.selectRange(DayimaChartRange.CUSTOM); model.charts.changeFrom(first.toString()); model.charts.changeThrough(first.toString()); model.charts.applyRange()
        model.charts.selectRange(DayimaChartRange.ALL)
        awaitCharts()
        assertEquals(DayimaChartRange.ALL, model.state.value.charts.options.range)
        assertNull(model.state.value.charts.data!!.statistics.period.range)
        assertEquals(model.state.value.overview!!.records.dataVersion, model.state.value.charts.data!!.statistics.dataVersion)
    }

    @Test fun chartRangeValidationDoesNotWriteBusinessData() = runBlocking {
        record(first); record(first.plusDays(28)); awaitCharts()
        val version = model.state.value.overview!!.records.dataVersion
        model.charts.selectRange(DayimaChartRange.CUSTOM)
        model.charts.changeFrom("2026-10-01"); model.charts.changeThrough("2026-01-01"); model.charts.applyRange()
        assertEquals(R.string.dayima_chart_range_order, model.state.value.charts.rangeEditor?.error)
        assertEquals(DayimaChartRange.ALL, model.state.value.charts.options.range)
        model.charts.changeFrom(first.toString()); model.charts.changeThrough(today.plusDays(1).toString()); model.charts.applyRange()
        assertEquals(R.string.dayima_chart_range_future, model.state.value.charts.rangeEditor?.error)
        model.charts.cancelRange()
        assertEquals(version, repository.recordsSnapshot().dataVersion)
    }

    private fun awaitCharts() {
        val committedVersion = runBlocking { repository.recordsSnapshot().dataVersion }
        await {
            val state = model.state.value
            !state.charts.loading && state.charts.data != null && state.charts.data.statistics.dataVersion == committedVersion &&
                state.overview?.records?.dataVersion == committedVersion
        }
    }

    private suspend fun record(date: LocalDate): DayimaPeriodEntity = (repository.execute(DayimaChange.RecordPeriod(date)) as DayimaWriteResult.PeriodSaved).period
    private fun open(id: String) {
        model.openDetail(id)
        await { !model.state.value.detailLoading && model.state.value.detail?.period?.id == id }
    }
    @Test fun detailEditorCancelsWithoutChangesAndRestoresThenSavesDateAndBodyTogether() = runBlocking {
        destroyModel(); val handle = SavedStateHandle(); createModel(handle)
        val period = (repository.execute(DayimaChange.RecordPeriod(first, "已有正文")) as DayimaWriteResult.PeriodSaved).period
        val note = repository.notesForPeriod(period.id).single()
        database.mediaDao().insert(MediaAssetEntity("edit-photo", MediaSource.IMPORTED,
            relativePath = "media/originals/edit-photo.png", mimeType = "image/png", byteCount = 8, width = 2, height = 2, createdAt = 1))
        database.dayimaMediaDao().insertPeriod(DayimaPeriodMediaEntity(period.id, "edit-photo", "图片", 0, null, 1))
        open(period.id); model.beginPeriodEdit(); model.changeDate(first.plusDays(1).toString())
        model.changeBodyNote(note.id, "先取消的修改"); model.removeEditingImage("edit-photo")
        model.cancelEditor()
        assertEquals(first, repository.period(period.id)!!.startDate)
        assertEquals("已有正文", repository.notesForPeriod(period.id).single().content)
        assertEquals(1, repository.periodDetail(period.id)!!.images.size)
        model.beginPeriodEdit(); model.changeDate(first.plusDays(1).toString())
        model.changeBodyNote(note.id, "修改后的正文"); model.removeEditingImage("edit-photo")
        destroyModel(); createModel(handle)
        assertEquals("修改后的正文", model.state.value.editor!!.bodyNotes.single().content)
        assertEquals(listOf("edit-photo"), model.state.value.editor!!.removedImageIds)
        model.saveEditor(); await { !model.state.value.busy && model.state.value.editor == null }
        assertEquals(first.plusDays(1), repository.period(period.id)!!.startDate)
        val notes = repository.notesForPeriod(period.id)
        assertEquals(1, notes.size)
        assertEquals(first, notes.single().occurredDate)
        assertEquals("修改后的正文", notes.single().content)
        assertTrue(repository.periodDetail(period.id)!!.images.isEmpty())
    }

    @Test fun anEmptyCardCanSaveDateAndOptionalBodyFromTheSameEditor() = runBlocking {
        val period = record(first)
        open(period.id); model.beginPeriodEdit()
        assertTrue(model.state.value.editor!!.bodyNotes.isEmpty())
        model.changeDate(first.plusDays(1).toString()); model.changeContent("补充一段正文")
        model.saveEditor(); await { !model.state.value.busy && model.state.value.editor == null }
        assertEquals(first.plusDays(1), repository.period(period.id)!!.startDate)
        val note = repository.notesForPeriod(period.id).single()
        assertEquals("补充一段正文", note.content); assertEquals(first.plusDays(1), note.occurredDate)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待大姨妈页面状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
