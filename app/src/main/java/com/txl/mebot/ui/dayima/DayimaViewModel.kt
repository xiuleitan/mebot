package com.txl.mebot.ui.dayima

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.R
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DayimaViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val repository: DayimaRepository,
    private val commit: suspend (DayimaChange) -> DayimaWriteResult = repository::execute,
    private val attachments: DayimaAttachmentRepository? = null,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(
        application, savedState, (application as MebotApplication).container.dayima, attachments = application.container.dayimaAttachments)

    private val mutableState = MutableStateFlow(DayimaPageState(
        detailId = savedState[DETAIL],
        editor = DayimaEditor.restore(savedState.get<Bundle>(EDITOR)),
        deletion = DayimaDeleteTarget.restore(savedState.get<Bundle>(DELETION)),
        charts = DayimaChartController.restore(savedState),
    ))
    internal val state = mutableState.asStateFlow()
    internal val charts = DayimaChartController(viewModelScope, savedState, { mutableState.value.charts }) { value ->
        mutableState.update { it.copy(charts = value) }
    }
    private var visible = false
    private var overviewJob: Job? = null
    private var detailJob: Job? = null
    private var notesJob: Job? = null
    private var detailGeneration = 0L
    private var notesGeneration = 0L

    init { savedState.remove<String>(HISTORY) }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) refresh() else {
            charts.stop()
            overviewJob?.cancel(); detailJob?.cancel(); notesJob?.cancel()
            ++detailGeneration; ++notesGeneration
        }
    }

    fun refresh() {
        if (!visible) return
        overviewJob?.cancel()
        mutableState.update { it.copy(loading = it.overview == null, loadFailed = false) }
        overviewJob = viewModelScope.launch {
            try {
                repository.observeOverview().collect { overview ->
                    val changed = mutableState.value.overview?.records?.dataVersion != overview.records.dataVersion
                    mutableState.update { it.copy(overview = overview, today = overview.statistics.asOfDate, loading = false, loadFailed = false,
                        charts = it.charts.copy(data = null, loading = true, failed = false)) }
                    charts.updateSource(overview)
                    refreshDetail()
                    if (changed || mutableState.value.notesLoading || mutableState.value.notesFailed) loadNotes(reset = true)
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.update { it.copy(loading = false, loadFailed = true) } }
        }
        refreshDetail()
    }

    internal fun selectHistory(value: DayimaHistory) {
        savedState[HISTORY] = value.name
        mutableState.update { it.copy(history = value) }
        if (value == DayimaHistory.NOTES) loadNotes(reset = true)
    }

    internal fun loadNotes(reset: Boolean = false) {
        val state = mutableState.value
        if (!visible || state.history != DayimaHistory.NOTES || state.overview == null) return
        if (!reset && (state.notesLoading || !state.notesHaveMore && state.independentNotes.isNotEmpty())) return
        val version = state.overview.records.dataVersion
        val previous = if (reset) emptyList() else state.independentNotes
        val generation = ++notesGeneration
        notesJob?.cancel()
        mutableState.update { it.copy(independentNotes = previous, notesLoading = true, notesFailed = false) }
        notesJob = viewModelScope.launch {
            try {
                val page = repository.independentNotesPage(offset = previous.size)
                if (generation != notesGeneration) return@launch
                if (page.dataVersion != version) { refresh(); return@launch }
                mutableState.update { it.copy(independentNotes = previous + page.notes, notesLoading = false,
                    notesFailed = false, notesHaveMore = page.hasMore) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (generation == notesGeneration) mutableState.update { it.copy(notesLoading = false, notesFailed = true) }
            }
        }
    }

    internal fun openDetail(id: String?) {
        if (mutableState.value.busy) return
        savedState[DETAIL] = id
        mutableState.update { it.copy(detailId = id, detail = null, detailLoading = id != null, detailFailed = false) }
        refreshDetail()
    }

    internal fun refreshDetail() {
        detailJob?.cancel()
        val generation = ++detailGeneration
        val id = mutableState.value.detailId ?: return
        if (!visible) return
        detailJob = viewModelScope.launch {
            try {
                repository.observePeriodDetail(id).collect { detail ->
                    if (generation == detailGeneration) mutableState.update { it.copy(detail = detail, detailLoading = false, detailFailed = false) }
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (generation == detailGeneration) mutableState.update { it.copy(detailLoading = false, detailFailed = true) }
            }
        }
    }

    internal fun removeImage(assetId: String) {
        val id = mutableState.value.detail?.period?.id ?: return
        if (attachments == null || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { attachments.removePeriodImage(id, assetId); runCatching { attachments.cleanPendingDeletions() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = DayimaNotice(R.string.schedule_attachment_remove_failed)) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    internal fun beginRecord(today: Boolean) {
        val state = mutableState.value
        if (state.busy) return
        val date = repository.recordTodayRequest().date
        if (today) state.overview?.records?.periods?.firstOrNull { it.startDate == date }?.let { openDetail(it.id); return }
        setEditor(DayimaEditor(DayimaEditorKind.RECORD, date.toString()))
    }

    internal fun beginBirth() {
        val state = mutableState.value
        val overview = state.overview ?: return
        if (!state.busy) setEditor(DayimaEditor(DayimaEditorKind.BIRTH,
            overview.records.profile?.birthDate?.toString().orEmpty(), revision = overview.records.profile?.revision ?: 0))
    }

    internal fun beginPeriodEdit() {
        val detail = mutableState.value.detail ?: return
        if (!mutableState.value.busy) setEditor(DayimaEditor(DayimaEditorKind.PERIOD, detail.period.startDate.toString(),
            targetId = detail.period.id, revision = detail.period.revision,
            bodyNotes = detail.notes.map { DayimaBodyNoteEdit(it.id, it.revision, it.content, it.content) }))
    }

    internal fun beginNote(note: DayimaNoteEntity? = null, independent: Boolean = false) {
        val state = mutableState.value
        if (state.busy) return
        val parent = state.detail?.period?.takeUnless { independent }
        setEditor(DayimaEditor(DayimaEditorKind.NOTE,
            (note?.occurredDate ?: parent?.startDate ?: repository.recordTodayRequest().date).toString(), note?.content.orEmpty(),
            note?.id, note?.revision ?: 0, if (note != null) note.periodId else parent?.id))
    }

    internal fun beginSpan() {
        val state = mutableState.value
        val detail = state.detail ?: return
        val end = detail.nextPeriod ?: return
        if (state.busy) return
        val marker = detail.marker?.marker
        setEditor(DayimaEditor(DayimaEditorKind.SPAN, date = "${detail.period.startDate} → ${end.startDate}", content = marker?.detail.orEmpty(), targetId = marker?.id,
            revision = marker?.revision ?: 0, periodId = detail.period.id, endPeriodId = end.id,
            startRevision = detail.period.revision, endRevision = end.revision,
            reason = marker?.reason ?: DayimaSpanReason.MISSING_RECORDS))
    }

    internal fun changeDate(value: String) = edit { copy(date = value) }
    internal fun changeContent(value: String) = edit { copy(content = value) }
    internal fun changeBodyNote(id: String, value: String) = edit {
        copy(bodyNotes = bodyNotes.map { if (it.id == id) it.copy(content = value) else it })
    }
    internal fun removeEditingImage(id: String) {
        if (mutableState.value.detail?.images?.none { it.asset.id == id } != false) return
        edit { if (kind == DayimaEditorKind.PERIOD) copy(removedImageIds = (removedImageIds + id).distinct()) else this }
    }
    internal fun changeReason(value: DayimaSpanReason) = edit { copy(reason = value) }
    private fun edit(change: DayimaEditor.() -> DayimaEditor) {
        if (mutableState.value.busy) return
        mutableState.value.editor?.let { setEditor(it.change().copy(requestId = UUID.randomUUID().toString(), error = null)) }
    }
    private fun setEditor(value: DayimaEditor?) {
        savedState[EDITOR] = value?.bundle()
        mutableState.update { it.copy(editor = value) }
    }
    internal fun cancelEditor() { if (!mutableState.value.busy) setEditor(null) }
    internal fun closeSheet() {
        if (mutableState.value.busy) return
        setEditor(null); openDetail(null)
        charts.select(null)
        mutableState.update { it.copy(reviewingMarkers = false) }
    }
    internal fun reviewMarkers() { mutableState.update { it.copy(reviewingMarkers = true) } }

    internal fun saveEditor() {
        val state = mutableState.value
        val editor = state.editor ?: return
        if (state.busy) return
        val date = parseDayimaDate(editor.date)
        val optionalDate = editor.kind == DayimaEditorKind.BIRTH && editor.date.isBlank()
        val needsDate = editor.kind != DayimaEditorKind.SPAN
        val today = repository.recordTodayRequest().date
        val invalid = when {
            needsDate && date == null && !optionalDate -> R.string.dayima_invalid_date
            needsDate && date != null && date > today -> R.string.dayima_future_date
            editor.kind == DayimaEditorKind.NOTE && editor.content.isBlank() -> R.string.dayima_empty_note
            else -> null
        }
        if (invalid != null) { mutableState.update { it.copy(editor = editor.copy(error = invalid)) }; return }
        val text = editor.content.trim()
        val change = when (editor.kind) {
            DayimaEditorKind.RECORD -> DayimaChange.RecordPeriod(requireNotNull(date), text.takeIf { it.isNotEmpty() }, editor.requestId)
            DayimaEditorKind.BIRTH -> DayimaChange.SaveBirthDate(date, editor.revision, editor.requestId)
            DayimaEditorKind.PERIOD -> DayimaChange.EditPeriodDetails(requireNotNull(editor.targetId), editor.revision, requireNotNull(date),
                editor.bodyNotes, editor.content, editor.removedImageIds, editor.requestId)
            DayimaEditorKind.NOTE -> if (editor.targetId == null) DayimaChange.AddNote(requireNotNull(date), text, editor.periodId, editor.requestId)
                else DayimaChange.EditNote(editor.targetId, editor.revision, requireNotNull(date), text, editor.periodId, editor.requestId)
            DayimaEditorKind.SPAN -> DayimaChange.SaveSpan(requireNotNull(editor.periodId), requireNotNull(editor.endPeriodId),
                editor.startRevision, editor.endRevision, editor.reason, text, editor.targetId, editor.revision, editor.requestId)
        }
        mutableState.update { it.copy(busy = true, editor = editor.copy(error = null)) }
        viewModelScope.launch {
            try {
                val result = commit(change)
                val message = when (result) {
                    is DayimaWriteResult.PeriodSaved -> if (editor.kind == DayimaEditorKind.PERIOD) R.string.dayima_details_saved
                        else if (result.created) R.string.dayima_record_saved else if (result.note != null) R.string.dayima_duplicate_note_saved else R.string.dayima_duplicate_record
                    is DayimaWriteResult.BirthDateSaved -> if (result.profile.birthDate == null) R.string.dayima_birth_removed else R.string.dayima_birth_saved
                    is DayimaWriteResult.NoteSaved -> R.string.dayima_note_saved
                    else -> R.string.dayima_span_saved
                }
                setEditor(null)
                mutableState.update { it.copy(busy = false, notice = DayimaNotice(message)) }
                if (editor.removedImageIds.isNotEmpty()) runCatching { attachments?.cleanPendingDeletions() }
                refreshDetail()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false, editor = editor.copy(error = failure(error))) }
            }
        }
    }

    internal fun askDeletePeriod() { mutableState.value.detail?.period?.let {
        askDelete(DayimaDeleteTarget(DayimaDeleteKind.PERIOD, it.id, it.revision, it.startDate.toString()))
    } }
    internal fun askDeleteNote(note: DayimaNoteEntity) = askDelete(DayimaDeleteTarget(DayimaDeleteKind.NOTE, note.id, note.revision, note.occurredDate.toString()))
    internal fun askDeleteEditingNote() { mutableState.value.editor?.takeIf { it.kind == DayimaEditorKind.NOTE }?.let {
        it.targetId?.let { id -> askDelete(DayimaDeleteTarget(DayimaDeleteKind.NOTE, id, it.revision, it.date)) }
    } }
    internal fun askDeleteSpan(marker: DayimaSpanMarkerEntity) = askDelete(DayimaDeleteTarget(DayimaDeleteKind.SPAN, marker.id, marker.revision, "${marker.startDate} → ${marker.endDate}"))
    private fun askDelete(target: DayimaDeleteTarget) {
        if (mutableState.value.busy) return
        savedState[DELETION] = target.bundle()
        mutableState.update { it.copy(deletion = target) }
    }
    internal fun cancelDelete() {
        if (mutableState.value.busy) return
        savedState[DELETION] = null
        mutableState.update { it.copy(deletion = null) }
    }
    internal fun confirmDelete() {
        val target = mutableState.value.deletion ?: return
        if (mutableState.value.busy) return
        val change = when (target.kind) {
            DayimaDeleteKind.PERIOD -> DayimaChange.DeletePeriod(target.id, target.revision, target.requestId)
            DayimaDeleteKind.NOTE -> DayimaChange.DeleteNote(target.id, target.revision, target.requestId)
            DayimaDeleteKind.SPAN -> DayimaChange.DeleteSpan(target.id, target.revision, target.requestId)
        }
        mutableState.update { it.copy(busy = true, deletion = target.copy(error = null)) }
        viewModelScope.launch {
            try {
                commit(change)
                savedState[DELETION] = null
                if (target.kind == DayimaDeleteKind.PERIOD && mutableState.value.detailId == target.id) {
                    savedState[DETAIL] = null
                    mutableState.update { it.copy(detailId = null) }
                }
                if (target.kind == DayimaDeleteKind.NOTE && mutableState.value.editor?.targetId == target.id) setEditor(null)
                mutableState.update { it.copy(busy = false, deletion = null, notice = DayimaNotice(
                    if (target.kind == DayimaDeleteKind.SPAN) R.string.dayima_span_removed else R.string.dayima_deleted)) }
                refreshDetail()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { mutableState.update { it.copy(busy = false, deletion = target.copy(error = failure(error))) } }
        }
    }

    internal fun consumeNotice(token: String) { mutableState.update { if (it.notice?.token == token) it.copy(notice = null) else it } }

    private fun failure(error: Exception): Int = when (error) {
        is DayimaDateConflict -> R.string.dayima_duplicate_date
        is DayimaConflict, is DayimaRequestConflict -> R.string.dayima_conflict
        is DayimaNotFound -> R.string.dayima_record_missing
        else -> R.string.dayima_save_failed
    }

    private companion object {
        const val DETAIL = "dayima.detail"
        const val EDITOR = "dayima.editor"
        const val DELETION = "dayima.deletion"
        const val HISTORY = "dayima.history"
    }
}
