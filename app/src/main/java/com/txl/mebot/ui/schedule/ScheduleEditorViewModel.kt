package com.txl.mebot.ui.schedule

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.ScheduleEditorAttachment
import com.txl.mebot.data.local.ScheduleEditorDraftEntity
import com.txl.mebot.data.schedule.ScheduleEditorConflict
import com.txl.mebot.data.schedule.ScheduleEditorRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScheduleEditorState(val occurrenceId: String, val draft: ScheduleEditorDraftEntity? = null,
    val attachments: List<ScheduleEditorAttachment> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val conflict: Boolean = false, val saved: Boolean = false)

class ScheduleEditorViewModel(application: Application, private val saved: SavedStateHandle,
    private val repository: ScheduleEditorRepository) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved,
        (application as MebotApplication).container.scheduleEditor)
    private val mutable = MutableStateFlow<ScheduleEditorState?>(null)
    val state = mutable.asStateFlow()
    private val writes = Mutex()
    private var delayedSave: Job? = null
    private var persisted: ScheduleEditorDraftEntity? = null

    init { saved.get<String>(KEY)?.let(::open) }

    fun open(id: String) {
        if (mutable.value?.busy == true || mutable.value?.occurrenceId == id) return
        saved[KEY] = id
        mutable.value = ScheduleEditorState(id)
        viewModelScope.launch { writes.withLock {
            try {
                val snapshot = repository.open(id)
                if (mutable.value?.occurrenceId == id) {
                    persisted = snapshot.draft
                    mutable.value = ScheduleEditorState(id, snapshot.draft, snapshot.attachments, loading = false,
                        conflict = snapshot.conflict, error = if (snapshot.conflict) "日程已被修改，请读取最新记录后再保存" else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { mutable.update { it?.copy(loading = false, error = failure.message ?: "读取日程失败，请重试") } }
        } }
    }

    fun change(transform: (ScheduleEditorDraftEntity) -> ScheduleEditorDraftEntity) {
        val page = mutable.value ?: return
        if (page.loading || page.busy || page.saved || page.conflict) return
        val draft = page.draft ?: return
        mutable.value = page.copy(draft = transform(draft), error = null)
        delayedSave?.cancel()
        delayedSave = viewModelScope.launch { delay(180); writes.withLock { runCatchingFlush() } }
    }

    private suspend fun flush() {
        val draft = mutable.value?.draft ?: return
        val old = persisted ?: return
        if (draft.requestId != old.requestId) return
        if (draft.title == old.title && draft.detail == old.detail && draft.date == old.date && draft.time == old.time) return
        persisted = repository.update(draft.copy(revision = old.revision))
    }

    private suspend fun runCatchingFlush(): Boolean = try { flush(); true
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { mutable.update { it?.copy(error = failure.message ?: "修改尚未保存，请重试") }; false }

    fun importImages(requestId: String, uris: List<Uri>) {
        val page = mutable.value ?: return
        if (page.draft?.requestId != requestId || page.busy || page.loading || page.saved) return
        mutate { id, request ->
            for (uri in uris.distinct()) repository.importImage(id, request, uri)
        }
    }

    fun removeImage(assetId: String) = mutate { id, request -> repository.remove(id, request, assetId) }

    private fun mutate(action: suspend (String, String) -> Unit) {
        val page = mutable.value ?: return
        val draft = page.draft ?: return
        if (page.loading || page.busy || page.saved) return
        mutable.value = page.copy(busy = true, error = null)
        delayedSave?.cancel()
        viewModelScope.launch { writes.withLock {
            try {
                flush()
                action(page.occurrenceId, draft.requestId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { mutable.update { it?.copy(error = failure.message ?: "附件修改失败，请重试") }
            } finally {
                val items = runCatching { repository.open(page.occurrenceId).attachments }.getOrNull()
                mutable.update { it?.copy(busy = false, attachments = items ?: it.attachments) }
                runCatching { repository.cleanMedia() }
            }
        } }
    }

    fun save() {
        val page = mutable.value ?: return
        val draft = page.draft ?: return
        if (page.loading || page.busy || page.saved || page.conflict) return
        mutable.value = page.copy(busy = true, error = null)
        delayedSave?.cancel()
        viewModelScope.launch { writes.withLock {
            try {
                flush()
                repository.save(page.occurrenceId, draft.requestId)
                clearSchedulePhotoCache()
                mutable.update { it?.copy(saved = true, busy = false) }
                runCatching { repository.cleanMedia() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { mutable.update { it?.copy(busy = false, error = failure.message ?: "保存失败，请重试",
                conflict = failure is ScheduleEditorConflict) } }
        } }
    }

    suspend fun beforeDismiss(): Boolean = writes.withLock {
        val page = mutable.value ?: return@withLock true
        if (page.busy) return@withLock false
        delayedSave?.cancel()
        try {
            if (!page.saved) repository.discard(page.occurrenceId)
            mutable.update { it?.copy(busy = true) }
            runCatching { repository.cleanMedia() }
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { mutable.update { it?.copy(error = "关闭失败，请重试") }; false }
    }

    fun closed() {
        mutable.value = null; persisted = null; saved.remove<String>(KEY)
    }

    fun reload() {
        val page = mutable.value ?: return
        if (page.busy || page.saved) return
        mutable.value = page.copy(busy = true)
        delayedSave?.cancel()
        viewModelScope.launch { writes.withLock {
            try {
                repository.discard(page.occurrenceId)
                val snapshot = repository.open(page.occurrenceId)
                persisted = snapshot.draft
                mutable.value = ScheduleEditorState(page.occurrenceId, snapshot.draft, snapshot.attachments, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { mutable.update { it?.copy(busy = false, error = failure.message ?: "重新读取失败") } }
        } }
    }

    private companion object { const val KEY = "schedule_manual_editor_id" }
}
