package com.txl.mebot.ui.flashnote

import com.txl.mebot.data.flashnote.FlashnoteConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftNotFound
import com.txl.mebot.data.flashnote.FlashnoteNotFound
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FlashnoteEditorState(
    val noteId: String,
    val title: String = "",
    val content: String = "",
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val inputSaveState: FlashnoteInputSaveState = FlashnoteInputSaveState.SAVED,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val saveConflict: Boolean = false,
    val isCancelling: Boolean = false,
    val cancelFailed: Boolean = false,
    val attachments: List<FlashnoteAttachment> = emptyList(),
    val busyAssetId: String? = null,
    val attachmentFailed: Boolean = false,
) {
    val busy: Boolean get() = isSaving || isCancelling || busyAssetId != null
    val editable: Boolean get() = !loading && !loadFailed && !busy && !saveFailed
    val canSave: Boolean get() = !loading && !loadFailed && !busy && (content.isNotBlank() || attachments.isNotEmpty())
}

/** 编辑与快速输入使用独立草稿；共享写入队列，关闭浮层后也保留未提交的修改。 */
internal class FlashnoteEditor(
    private val repository: FlashnoteRepository,
    private val scope: CoroutineScope,
    private val writes: Mutex,
    private val commit: suspend (FlashnoteDraftEntity) -> FlashnoteEntity,
    private val onChange: (FlashnoteEditorState?) -> Unit,
    private val onSaved: (FlashnoteEntity) -> Unit,
    private val onOpenFailed: (String) -> Unit,
    private val cleanMedia: suspend () -> Unit = {},
) {
    private var current: FlashnoteEditorState? = null
    private var shown = false
    private var opening = false
    private var presentation = 0L
    private var draft: FlashnoteDraftEntity? = null
    private var submission: FlashnoteDraftEntity? = null
    private var saveDelay: Job? = null

    private fun update(change: (FlashnoteEditorState) -> FlashnoteEditorState) {
        current = current?.let(change)
        if (shown) onChange(current)
    }

    fun open(noteId: String) {
        if (opening || current?.busy == true) return
        if (current?.noteId == noteId) {
            shown = true
            onChange(current)
            if (current?.loadFailed == true) retryLoad()
            return
        }
        opening = true
        val request = ++presentation
        saveDelay?.cancel()
        scope.launch {
            try {
                writes.withLock {
                    if (submission == null && !flush()) { current?.noteId?.let(onOpenFailed); return@withLock }
                    draft = null
                    submission = null
                    current = FlashnoteEditorState(noteId)
                    shown = request == presentation
                    if (shown) onChange(current)
                    load()
                }
            } finally { opening = false }
        }
    }

    fun retryLoad() {
        if (current?.loadFailed != true) return
        update { it.copy(loading = true, loadFailed = false) }
        scope.launch { writes.withLock { load() } }
    }

    private suspend fun load() {
        val id = current?.noteId ?: return
        try {
            val loaded = repository.openEditing(id)
            draft = loaded
            val items = repository.draftAttachments(loaded.key)
            update { it.copy(title = loaded.title, content = loaded.content, attachments = items, loading = false, loadFailed = false) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { update { it.copy(loading = false, loadFailed = true) } }
    }

    fun changeTitle(text: String) = change { it.copy(title = text) }
    fun changeContent(text: String) = change { it.copy(content = text) }

    private fun change(transform: (FlashnoteEditorState) -> FlashnoteEditorState) {
        if (current?.editable != true) return
        update { transform(it).copy(inputSaveState = FlashnoteInputSaveState.SAVING) }
        saveDelay?.cancel()
        saveDelay = scope.launch { delay(180); savePendingInput() }
    }

    fun savePendingInput() {
        saveDelay?.cancel()
        scope.launch { writes.withLock { flushPending() } }
    }

    suspend fun flushPending() { if (submission == null) flush() }

    private suspend fun flush(): Boolean {
        val previous = draft ?: return true
        val input = current ?: return true
        if (previous.title == input.title && previous.content == input.content) {
            update { it.copy(inputSaveState = FlashnoteInputSaveState.SAVED) }
            return true
        }
        return try {
            draft = repository.saveInput(previous.key, input.title, input.content, previous.revision)
            update { it.copy(inputSaveState = if (it.title == input.title && it.content == input.content)
                FlashnoteInputSaveState.SAVED else FlashnoteInputSaveState.SAVING) }
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: FlashnoteDraftConflict) {
            update { it.copy(inputSaveState = FlashnoteInputSaveState.CONFLICT) }; false
        } catch (_: FlashnoteDraftNotFound) {
            update { it.copy(inputSaveState = FlashnoteInputSaveState.CONFLICT) }; false
        } catch (_: Exception) {
            update { it.copy(inputSaveState = FlashnoteInputSaveState.FAILED) }; false
        }
    }

    fun retryInput() {
        saveDelay?.cancel()
        scope.launch {
            writes.withLock {
                if (submission != null || current?.busy == true) return@withLock
                val previous = draft ?: return@withLock
                try {
                    draft = repository.draft(previous.key) ?: throw FlashnoteDraftNotFound()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { return@withLock }
                if (flush()) {
                    val items = draft?.let { repository.draftAttachments(it.key) }.orEmpty()
                    update { it.copy(saveFailed = false, saveConflict = false, attachments = items) }
                }
            }
        }
    }

    fun removeAttachment(assetId: String) {
        if (current?.editable != true || current?.attachments?.none { it.asset.id == assetId } != false) return
        saveDelay?.cancel()
        update { it.copy(busyAssetId = assetId, attachmentFailed = false) }
        scope.launch {
            writes.withLock {
                try {
                    check(flush())
                    draft = repository.removeAttachment(requireNotNull(draft), assetId)
                    val items = repository.draftAttachments(requireNotNull(draft).key)
                    update { it.copy(attachments = items) }
                    cleanMedia()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { update { it.copy(attachmentFailed = true) }
                } finally { update { it.copy(busyAssetId = null) } }
            }
        }
    }

    fun save() {
        if (current?.canSave != true) return
        saveDelay?.cancel()
        update { it.copy(isSaving = true, saveFailed = false, saveConflict = false, cancelFailed = false) }
        scope.launch {
            writes.withLock {
                try {
                    if (submission == null) {
                        if (!flush()) { update { it.copy(saveFailed = true) }; return@withLock }
                        submission = requireNotNull(draft)
                    }
                    val saved = commit(requireNotNull(submission))
                    clear()
                    onSaved(saved)
                    cleanMedia()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: FlashnoteConflict) {
                    update { it.copy(saveFailed = true, saveConflict = true) }
                } catch (_: FlashnoteDraftConflict) {
                    update { it.copy(saveFailed = true, saveConflict = true) }
                } catch (_: FlashnoteNotFound) {
                    update { it.copy(saveFailed = true, saveConflict = true) }
                } catch (_: Exception) { update { it.copy(saveFailed = true) }
                } finally { update { it.copy(isSaving = false) } }
            }
        }
    }

    fun cancel() {
        if (current == null || current?.busy == true || current?.loading == true) return
        saveDelay?.cancel()
        update { it.copy(isCancelling = true, cancelFailed = false) }
        scope.launch {
            writes.withLock {
                try {
                    draft?.let { repository.discardDraft(it.key, it.revision) }
                    clear()
                    cleanMedia()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { update { it.copy(cancelFailed = true) }
                } finally { update { it.copy(isCancelling = false) } }
            }
        }
    }

    fun close() {
        ++presentation
        shown = false
        onChange(null)
        savePendingInput()
    }

    fun forgetDeleted(noteId: String) { if (current?.noteId == noteId) clear() }

    private fun clear() {
        saveDelay?.cancel()
        draft = null
        submission = null
        current = null
        shown = false
        onChange(null)
    }
}
