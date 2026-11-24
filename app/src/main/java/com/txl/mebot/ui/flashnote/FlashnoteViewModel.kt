package com.txl.mebot.ui.flashnote

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.flashnote.FlashnoteConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftNotFound
import com.txl.mebot.data.flashnote.FlashnoteNotFound
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.flashnote.FlashnoteAttachmentRepository
import com.txl.mebot.data.flashnote.AndroidFlashnoteTitleWork
import com.txl.mebot.data.flashnote.FlashnoteTitleWork
import com.txl.mebot.data.flashnote.FlashnoteTitleState
import com.txl.mebot.data.flashnote.FlashnoteTitlePhase
import com.txl.mebot.data.flashnote.FlashnoteTitleFailure
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.data.local.FlashnoteCardRecord
import com.txl.mebot.ui.schedule.AttachmentFailure
import kotlinx.coroutines.flow.combine
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import java.util.UUID
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FlashnoteInputSaveState { SAVED, SAVING, FAILED, CONFLICT }
data class FlashnoteSavedEvent(val id: String, val token: String = UUID.randomUUID().toString())
enum class FlashnoteAction { COMPLETE, RESTORE, DELETE }
data class FlashnoteOperation(val entry: FlashnoteEntry, val action: FlashnoteAction)
data class FlashnoteActionFailure(val operation: FlashnoteOperation?, val conflict: Boolean = false, val editingNoteId: String? = null)
data class FlashnotePageState(
    val status: FlashnoteStatus = FlashnoteStatus.PENDING,
    val pages: Map<FlashnoteStatus, List<FlashnoteEntry>> = emptyMap(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val counts: Map<FlashnoteStatus, Int> = emptyMap(),
    val countsLoaded: Boolean = false,
    val countsFailed: Boolean = false,
    val input: String = "",
    val draftLoaded: Boolean = false,
    val draftLoading: Boolean = false,
    val draftLoadFailed: Boolean = false,
    val inputSaveState: FlashnoteInputSaveState = FlashnoteInputSaveState.SAVED,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val saveConflict: Boolean = false,
    val savedEvent: FlashnoteSavedEvent? = null,
    val detailId: String? = null,
    val detail: FlashnoteEntry? = null,
    val detailLoading: Boolean = false,
    val detailFailed: Boolean = false,
    val editor: FlashnoteEditorState? = null,
    val pendingOperations: Set<String> = emptySet(),
    val actionFailure: FlashnoteActionFailure? = null,
    val detailDeleted: Boolean = false,
    val media: FlashnoteMediaState = FlashnoteMediaState(),
    val detailAttachments: List<FlashnoteAttachment> = emptyList(),
    val statistics: FlashnoteStatisticsState = FlashnoteStatisticsState(),
    val titleStates: Map<String, FlashnoteTitleState> = emptyMap(),
) {
    val entries: List<FlashnoteEntry> get() = pages[status].orEmpty()
    val canSave: Boolean get() = draftLoaded && !draftLoading && !isSaving && !media.busy && (input.isNotBlank() || media.items.isNotEmpty())
    val inputEditable: Boolean get() = draftLoaded && !draftLoading && !isSaving && !saveFailed
}

/** 页面仅在可见时订阅；草稿和正式写入串行，离开页面不取消已开始的保存。 */
class FlashnoteViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val repository: FlashnoteRepository,
    private val commit: suspend (FlashnoteDraftEntity) -> FlashnoteEntity = repository::save,
    private val attachmentRepository: FlashnoteAttachmentRepository? = null,
    statisticsClock: Clock = Clock.systemUTC(),
    statisticsZone: () -> ZoneId = ZoneId::systemDefault,
    private val titleWork: FlashnoteTitleWork? = null,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(
        application, savedState, (application as MebotApplication).container.flashnotes,
        attachmentRepository = (application as MebotApplication).container.flashnoteAttachments,
        titleWork = AndroidFlashnoteTitleWork(application),
    )

    private val initialStatus = savedState.get<String>(STATUS)?.let {
        runCatching { FlashnoteStatus.valueOf(it) }.getOrNull()
    } ?: FlashnoteStatus.PENDING
    private val mutableState = MutableStateFlow(FlashnotePageState(status = initialStatus, detailId = savedState[DETAIL]))
    val state = mutableState.asStateFlow()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Mutex()
    private val statistics = FlashnoteStatistics(viewModelScope, savedState, repository, statisticsClock, statisticsZone) { value ->
        mutableState.update { it.copy(statistics = value) }
    }
    private val media = FlashnoteAttachments(application, savedState, repository, attachmentRepository, writeScope, writes,
        currentDraft = { persistedDraft }, replaceDraft = { persistedDraft = it },
        editable = { mutableState.value.inputEditable }, flushInput = { flushInput() },
        onChange = { state -> mutableState.update { it.copy(media = state) } },
        onDraftConflict = { mutableState.update { it.copy(inputSaveState = FlashnoteInputSaveState.CONFLICT) } })
    private val editor = FlashnoteEditor(repository, writeScope, writes, commit,
        onChange = { editing ->
            if (editing == null || editing.noteId == mutableState.value.detailId) {
                savedState[EDITING] = editing?.noteId
                mutableState.update { it.copy(editor = editing) }
            }
        },
        onSaved = { note ->
            mutableState.update { if (it.detailId == note.id) it.copy(detail = note.asEntry(), actionFailure = null) else it }
            observeTitles()
        },
        onOpenFailed = { id -> mutableState.update { it.copy(actionFailure = FlashnoteActionFailure(null, editingNoteId = id)) } },
        cleanMedia = { runCatching { attachmentRepository?.cleanPendingDeletions() }; Unit },
    )
    private var persistedDraft: FlashnoteDraftEntity? = null
    private var pendingSubmission: FlashnoteDraftEntity? = null
    private var saveDelay: Job? = null
    private var listJob: Job? = null
    private var countsJob: Job? = null
    private var detailJob: Job? = null
    private var titleJob: Job? = null
    private var observedTitleVersions: Map<String, Long>? = null
    private var localTitleStates = emptyMap<String, Pair<Long, FlashnoteTitleState>>()
    private var listGeneration = 0L
    private var detailGeneration = 0L
    private var visible = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        statistics.setVisible(value)
        if (value) {
            loadDraft()
            observeList()
            observeCounts()
            observeDetail()
            savedState.get<String>(EDITING)?.takeIf { it == mutableState.value.detailId }?.let(editor::open)
        } else {
            ++listGeneration; ++detailGeneration
            listJob?.cancel(); countsJob?.cancel(); detailJob?.cancel()
            titleJob?.cancel(); observedTitleVersions = null
            savePendingInput()
            editor.savePendingInput()
        }
    }

    fun selectStatus(status: FlashnoteStatus) {
        if (mutableState.value.status == status) return
        savedState[STATUS] = status.name
        mutableState.update { it.copy(status = status, loading = true, loadFailed = false, savedEvent = null) }
        observeList()
    }

    fun retryList() { observeList(); observeCounts() }
    fun retryDetail() = observeDetail()
    fun setStatisticsExpanded(value: Boolean) = statistics.setExpanded(value)
    fun selectStatisticsMonth(step: Int) = statistics.selectMonth(step)
    fun selectStatisticsDay(day: Int) = statistics.selectDay(day)
    fun retryStatistics() = statistics.retry()
    fun refreshStatisticsDate() = statistics.refreshDate(force = true)

    fun generateTitle() {
        val current = mutableState.value
        val entry = current.detail ?: return
        if (current.detailLoading || current.detailDeleted || current.editor != null || entry.id in current.pendingOperations ||
            current.titleStates[entry.id]?.busy == true) return
        viewModelScope.launch { enqueueTitle(entry.id, entry.revision) }
    }

    private suspend fun enqueueTitle(id: String, revision: Long) {
        val work = titleWork ?: return
        try {
            val note = repository.note(id) ?: return
            if (note.revision != revision) return
            if (note.content.isBlank() && repository.noteAttachments(id).none { it.isImage }) return
            val waiting = FlashnoteTitleState(FlashnoteTitlePhase.WAITING)
            localTitleStates = localTitleStates + (id to (revision to waiting))
            mutableState.update { it.copy(titleStates = it.titleStates + (id to waiting)) }
            work.enqueue(id, revision)
            localTitleStates = localTitleStates - id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val failed = FlashnoteTitleState(FlashnoteTitlePhase.FAILED, FlashnoteTitleFailure.LOCAL_SAVE)
            localTitleStates = localTitleStates + (id to (revision to failed))
            mutableState.update { it.copy(titleStates = it.titleStates + (id to failed)) }
        }
    }

    private fun observeTitles() {
        val work = titleWork ?: return
        if (!visible) return
        val current = mutableState.value
        val notes = buildMap {
            current.entries.forEach { put(it.id, it.revision) }
            current.detail?.takeIf { it.id == current.detailId }?.let { put(it.id, it.revision) }
        }
        if (observedTitleVersions == notes && titleJob?.isActive == true) return
        observedTitleVersions = notes
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            try {
                work.observe(notes).collect { states ->
                    if (!visible || observedTitleVersions != notes) return@collect
                    val local = localTitleStates.filter { (id, value) -> notes[id] == value.first }.mapValues { it.value.second }
                    mutableState.update { it.copy(titleStates = states + local) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visible && observedTitleVersions == notes) mutableState.update { it.copy(titleStates = notes.mapValues {
                    FlashnoteTitleState(FlashnoteTitlePhase.FAILED, FlashnoteTitleFailure.LOCAL_SAVE)
                }) }
            }
        }
    }

    fun beginEditing() {
        val current = mutableState.value
        val note = current.detail ?: return
        if (current.detailLoading || current.detailDeleted || note.id in current.pendingOperations) return
        mutableState.update { it.copy(actionFailure = null) }
        editor.open(note.id)
    }

    fun changeEditTitle(text: String) = editor.changeTitle(text)
    fun changeEditContent(text: String) = editor.changeContent(text)
    fun retryEditLoad() = editor.retryLoad()
    fun retryEditInput() = editor.retryInput()
    fun saveEdit() = editor.save()
    fun cancelEdit() = editor.cancel()
    fun removeEditAttachment(assetId: String) = editor.removeAttachment(assetId)
    fun preparePicker() = media.preparePicker()
    fun picked(uris: List<Uri>, imagesOnly: Boolean) = media.picked(uris, imagesOnly)
    fun pickLocal(uri: Uri) = media.pickLocal(uri)
    fun removeAttachment(assetId: String) = media.remove(assetId)
    suspend fun prepareCamera() = media.prepareCamera()
    fun cameraResult(success: Boolean) = media.cameraResult(success)
    fun reportMedia(failure: AttachmentFailure) = media.report(failure)
    suspend fun fileIntent(item: FlashnoteAttachment) = media.fileIntent(item)

    fun toggleCompleted(entry: FlashnoteEntry) = operate(FlashnoteOperation(entry,
        if (entry.completed) FlashnoteAction.RESTORE else FlashnoteAction.COMPLETE))

    fun delete(entry: FlashnoteEntry) = operate(FlashnoteOperation(entry, FlashnoteAction.DELETE))

    fun retryAction() {
        val failure = mutableState.value.actionFailure ?: return
        if (failure.conflict) {
            mutableState.update { it.copy(actionFailure = null) }
            retryList()
            retryDetail()
        } else if (failure.operation != null) operate(failure.operation)
        else failure.editingNoteId?.let { id ->
            openDetail(id)
            mutableState.update { it.copy(actionFailure = null) }
            editor.open(id)
        }
    }

    private fun operate(operation: FlashnoteOperation) {
        val entry = operation.entry
        val current = mutableState.value
        if (entry.id in current.pendingOperations || current.editor?.noteId == entry.id) return
        mutableState.update { it.copy(pendingOperations = it.pendingOperations + entry.id, actionFailure = null) }
        writeScope.launch {
            writes.withLock {
                try {
                    val updated = when (operation.action) {
                        FlashnoteAction.COMPLETE -> repository.complete(entry.id, entry.revision)
                        FlashnoteAction.RESTORE -> repository.restore(entry.id, entry.revision)
                        FlashnoteAction.DELETE -> {
                            repository.delete(entry.id, entry.revision)
                            editor.forgetDeleted(entry.id)
                            runCatching { attachmentRepository?.cleanPendingDeletions() }
                            mutableState.update { if (it.detailId == entry.id) it.copy(detailDeleted = true) else it }
                            null
                        }
                    }
                    if (updated != null) mutableState.update {
                        if (it.detailId == updated.id) it.copy(detail = updated.asEntry()) else it
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: FlashnoteConflict) {
                    mutableState.update { it.copy(actionFailure = FlashnoteActionFailure(operation, conflict = true)) }
                } catch (_: FlashnoteNotFound) {
                    mutableState.update { it.copy(actionFailure = FlashnoteActionFailure(operation, conflict = true)) }
                } catch (_: Exception) {
                    mutableState.update { it.copy(actionFailure = FlashnoteActionFailure(operation)) }
                } finally { mutableState.update { it.copy(pendingOperations = it.pendingOperations - entry.id) } }
            }
        }
    }

    private fun observeList() {
        listJob?.cancel()
        val generation = ++listGeneration
        if (!visible) return
        val status = mutableState.value.status
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        observeTitles()
        listJob = viewModelScope.launch {
            try {
                repository.observeCards(status).collect { notes ->
                    if (visible && generation == listGeneration && mutableState.value.status == status) {
                        mutableState.update { it.copy(pages = it.pages + (status to notes.map(FlashnoteCardRecord::asEntry)), loading = false, loadFailed = false) }
                        observeTitles()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (visible && generation == listGeneration) mutableState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private fun observeCounts() {
        countsJob?.cancel()
        if (!visible) return
        countsJob = viewModelScope.launch {
            try {
                repository.observeCounts().collect { counts ->
                    mutableState.update { it.copy(counts = counts.associate { row -> row.status to row.count }, countsLoaded = true, countsFailed = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(countsFailed = true) } }
        }
    }

    private fun loadDraft() {
        if (mutableState.value.draftLoaded || mutableState.value.draftLoading) return
        mutableState.update { it.copy(draftLoading = true, draftLoadFailed = false) }
        writeScope.launch { writes.withLock { readCreation() } }
    }

    private suspend fun readCreation() {
        try {
            val draft = repository.openCreation()
            persistedDraft = draft
            media.bind(draft, repository.draftAttachments(draft.key))
            mutableState.update { it.copy(input = draft.content, draftLoaded = true, draftLoading = false,
                draftLoadFailed = false, inputSaveState = FlashnoteInputSaveState.SAVED) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { mutableState.update { it.copy(draftLoading = false, draftLoadFailed = true) } }
    }

    fun retryDraftLoad() = loadDraft()

    fun changeInput(text: String) {
        val current = mutableState.value
        if (!current.inputEditable || current.input == text) return
        mutableState.update { it.copy(input = text, inputSaveState = FlashnoteInputSaveState.SAVING) }
        saveDelay?.cancel()
        saveDelay = writeScope.launch { delay(180); enqueueInputSave() }
    }

    private fun enqueueInputSave() {
        writeScope.launch { writes.withLock { if (pendingSubmission == null) flushInput() } }
    }

    fun savePendingInput() { saveDelay?.cancel(); enqueueInputSave() }

    fun retryInputSave() {
        saveDelay?.cancel()
        writeScope.launch {
            writes.withLock {
                if (pendingSubmission != null) return@withLock
                val previous = persistedDraft ?: return@withLock
                // 用户已明确选择保留当前文字重试；继续输入后也必须刷新旧版本。
                try {
                    persistedDraft = repository.draft(previous.key) ?: throw FlashnoteDraftNotFound()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { mutableState.update { it.copy(inputSaveState = FlashnoteInputSaveState.CONFLICT) }; return@withLock }
                if (flushInput()) {
                    persistedDraft?.let { media.bind(it, repository.draftAttachments(it.key)) }
                    mutableState.update { it.copy(saveFailed = false, saveConflict = false) }
                }
            }
        }
    }

    private suspend fun flushInput(): Boolean {
        val draft = persistedDraft ?: return false
        val text = mutableState.value.input
        if (draft.content == text) {
            mutableState.update { it.copy(inputSaveState = FlashnoteInputSaveState.SAVED) }
            return true
        }
        return try {
            val saved = repository.saveInput(draft.key, "", text, draft.revision)
            persistedDraft = saved
            mutableState.update { it.copy(inputSaveState = if (it.input == text) FlashnoteInputSaveState.SAVED else FlashnoteInputSaveState.SAVING) }
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: FlashnoteDraftConflict) {
            mutableState.update { it.copy(inputSaveState = FlashnoteInputSaveState.CONFLICT) }; false
        } catch (_: FlashnoteDraftNotFound) {
            mutableState.update { it.copy(inputSaveState = FlashnoteInputSaveState.CONFLICT) }; false
        } catch (_: Exception) {
            mutableState.update { it.copy(inputSaveState = FlashnoteInputSaveState.FAILED) }; false
        }
    }

    fun save() {
        if (!mutableState.value.canSave) return
        saveDelay?.cancel()
        mutableState.update { it.copy(isSaving = true, saveFailed = false, saveConflict = false) }
        writeScope.launch {
            writes.withLock {
                try {
                    if (pendingSubmission == null) {
                        if (!flushInput()) { mutableState.update { it.copy(saveFailed = true) }; return@withLock }
                        pendingSubmission = requireNotNull(persistedDraft)
                    }
                    val note = commit(requireNotNull(pendingSubmission))
                    pendingSubmission = null
                    persistedDraft = null
                    savedState[STATUS] = FlashnoteStatus.PENDING.name
                    mutableState.update { it.copy(input = "", draftLoaded = false, draftLoading = true, draftLoadFailed = false,
                        inputSaveState = FlashnoteInputSaveState.SAVED, saveFailed = false, saveConflict = false,
                        status = FlashnoteStatus.PENDING, loading = FlashnoteStatus.PENDING !in it.pages,
                        savedEvent = FlashnoteSavedEvent(note.id)) }
                    observeList()
                    readCreation()
                    enqueueTitle(note.id, note.revision)
                    runCatching { attachmentRepository?.cleanPendingDeletions() }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: FlashnoteConflict) {
                    mutableState.update { it.copy(saveFailed = true, saveConflict = true) }
                } catch (_: FlashnoteDraftConflict) {
                    mutableState.update { it.copy(saveFailed = true, saveConflict = true) }
                } catch (_: Exception) {
                    mutableState.update { it.copy(saveFailed = true) }
                } finally { mutableState.update { it.copy(isSaving = false) } }
            }
        }
    }

    fun consumeSavedEvent(token: String) {
        mutableState.update { if (it.savedEvent?.token == token) it.copy(savedEvent = null) else it }
    }

    fun openDetail(id: String?) {
        if (mutableState.value.detailId == id) return
        editor.close()
        savedState[DETAIL] = id
        mutableState.update { current -> current.copy(detailId = id,
            detail = id?.let { key -> current.pages.values.asSequence().flatten().filter { it.id == key }.maxByOrNull { it.revision } },
            detailLoading = id != null, detailFailed = false, detailDeleted = false, actionFailure = null, detailAttachments = emptyList()) }
        observeDetail()
        observeTitles()
    }

    private fun observeDetail() {
        detailJob?.cancel()
        val generation = ++detailGeneration
        val id = mutableState.value.detailId ?: return
        if (!visible) return
        mutableState.update { it.copy(detailLoading = true, detailFailed = false) }
        detailJob = viewModelScope.launch {
            try {
                combine(repository.observeNote(id), repository.observeNoteAttachments(id)) { note, items -> note to items }.collect { (note, items) ->
                    if (visible && generation == detailGeneration && mutableState.value.detailId == id) {
                        mutableState.update { it.copy(detail = note?.asEntry()?.copy(
                            coverRelativePath = items.firstOrNull { item -> item.isImage }?.asset?.relativePath, attachmentCount = items.size),
                            detailAttachments = if (note == null) emptyList() else items, detailLoading = false, detailFailed = false) }
                        observeTitles()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (visible && generation == detailGeneration) mutableState.update { it.copy(detail = null, detailAttachments = emptyList(), detailLoading = false, detailFailed = true) }
            }
        }
    }

    override fun onCleared() {
        visible = false
        ++listGeneration; ++detailGeneration
        saveDelay?.cancel()
        writeScope.launch {
            try { writes.withLock { if (pendingSubmission == null) flushInput(); editor.flushPending() } }
            finally { writeScope.cancel() }
        }
        super.onCleared()
    }

    companion object {
        private const val STATUS = "flashnote.status"
        private const val DETAIL = "flashnote.detail"
        private const val EDITING = "flashnote.editing"
    }
}
