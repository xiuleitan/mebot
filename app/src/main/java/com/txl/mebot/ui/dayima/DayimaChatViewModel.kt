package com.txl.mebot.ui.dayima

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DayimaDraftState { SAVED, SAVING, FAILED, CONFLICT }
internal data class DayimaChatState(
    val sessionId: String? = null, val title: String = "", val input: String = "",
    val loaded: Boolean = false, val loading: Boolean = false, val loadFailed: Boolean = false,
    val expanded: Boolean = false, val sending: Boolean = false, val processing: Boolean = false, val sendFailed: Boolean = false,
    val draftState: DayimaDraftState = DayimaDraftState.SAVED,
    val messages: List<DayimaChatMessageEntity> = emptyList(), val operation: DayimaChatOperationEntity? = null,
    val failure: DayimaChatFailure? = null, val olderLoading: Boolean = false, val olderFailed: Boolean = false,
    val historyOpen: Boolean = false, val sessions: List<DayimaChatSessionEntity> = emptyList(), val historyFailed: Boolean = false,
    val moreSessions: Boolean = false,
    val deleteId: String? = null, val deleting: Boolean = false, val deleteFailed: Boolean = false,
    val media: DayimaMediaState = DayimaMediaState(), val messageImages: Map<String, List<DayimaImage>> = emptyMap(),
) {
    val editable get() = loaded && !loading && !sending && !sendFailed && !deleting && !media.busy
    val canSend get() = editable && !processing && (input.isNotBlank() || media.images.isNotEmpty()) && draftState != DayimaDraftState.CONFLICT
    val composerEditable get() = editable && (expanded || !processing &&
        (messages.isEmpty() || input.isNotBlank() || media.images.isNotEmpty()))
    val readyForNewTask get() = loaded && !loading && !sending && !processing && !deleting && !media.busy &&
        !sendFailed && messages.isNotEmpty() && input.isBlank() && media.images.isEmpty()
}

/** 草稿写入与发送串行；分析绑定会话和请求，切换时使旧结果失效。 */
class DayimaChatViewModel(application: Application, private val savedState: SavedStateHandle,
    private val chats: DayimaConversationRepository, private val coordinator: DayimaChatCoordinator,
    attachments: DayimaAttachmentRepository? = null) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState,
        (application as MebotApplication).container.dayimaConversations, application.container.dayimaChat, application.container.dayimaAttachments)
    private val mutableState = MutableStateFlow(DayimaChatState(expanded = savedState[EXPANDED] ?: false))
    internal val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var persisted: DayimaChatDraftEntity? = null
    private val media = DayimaAttachments(application, savedState, attachments, viewModelScope, writes,
        draft = { persisted?.takeIf { it.sessionId == mutableState.value.sessionId } },
        replaceDraft = { persisted = it }, editable = { mutableState.value.editable && mutableState.value.draftState != DayimaDraftState.CONFLICT },
        flushInput = { flushInput() }, changed = { value -> mutableState.update { it.copy(media = value) } },
        conflict = { mutableState.update { it.copy(draftState = DayimaDraftState.CONFLICT) }; recovery() })
    private var visible = false
    private var saveDelay: Job? = null
    private var contentJob: Job? = null
    private var historyJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisKey: String? = null
    private var generation = 0L
    private var historyLimit = 30
    private var pendingMessageId: String? = savedState[PENDING]

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) {
            if (!mutableState.value.loaded) open() else { observe(); observeHistory() }
        } else {
            ++generation; contentJob?.cancel(); historyJob?.cancel(); savePendingInput()
        }
    }
    internal fun expand(value: Boolean = true) {
        savedState[EXPANDED] = value; mutableState.update { it.copy(expanded = value) }
        if (value && !mutableState.value.loaded && !mutableState.value.loading) open()
    }
    internal fun newChat() = open(new = true)
    internal fun finishTask() {
        if (!mutableState.value.expanded && mutableState.value.readyForNewTask) open(new = true, showConversation = false)
    }
    internal fun selectChat(id: String) = open(id)
    internal suspend fun acceptHomeMessage(id: String, messageId: String) {
        state.first { !it.loading && !it.sending && !it.media.busy }
        visible = true
        open(id, showConversation = true)
        val page = state.first { it.loadFailed || it.sessionId == id && it.loaded && it.messages.isNotEmpty() }
        check(!page.loadFailed) { "会话暂未读取成功" }
        if (!page.processing && page.messages.none { it.role == DayimaMessageRole.ASSISTANT }) analyze(id, messageId, retry = true)
    }

    internal fun retryLoad() = open()
    internal fun prepareImagePicker() = media.preparePicker()
    internal fun pickedImages(uris: List<android.net.Uri>, onlyImages: Boolean) {
        restoreBeforePickerResult(); media.picked(uris, onlyImages)
    }
    internal suspend fun prepareCamera() = media.prepareCamera()
    internal fun cameraResult(success: Boolean) {
        restoreBeforePickerResult(); media.cameraResult(success)
    }
    private fun restoreBeforePickerResult() {
        // 系统可能在页面 ON_START 前交付相册／相机结果，先把草稿加载排到同一写队列。
        if (!mutableState.value.loaded && !mutableState.value.loading) open()
    }
    internal fun pickLocalImage(uri: android.net.Uri) = media.pickLocal(uri)
    internal fun removeDraftImage(id: String) = media.remove(id)
    internal fun reportMedia(failure: com.txl.mebot.ui.schedule.AttachmentFailure) = media.report(failure)

    private fun open(id: String? = null, new: Boolean = false, showConversation: Boolean? = null) {
        if (mutableState.value.loading || mutableState.value.sending || mutableState.value.deleting || mutableState.value.media.busy) return
        saveDelay?.cancel()
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch { writes.withLock {
            if (!flushInput()) { mutableState.update { it.copy(loading = false) }; return@withLock }
            val target = if (new) null else id ?: savedState[SESSION]
            val sameRunning = target == mutableState.value.sessionId && analysisJob?.isActive == true
            if (!sameRunning) cancelAnalysis()
            ++generation; contentJob?.cancel()
            try {
                val snapshot = chats.open(target, new, recover = !sameRunning)
                val pending = savedState.get<String>(PENDING)?.takeIf { !new && snapshot.session.id == savedState.get<String>(SESSION) }
                val alreadySent = pending?.let { chats.sentMessage(snapshot.session.id, it) }
                persisted = snapshot.draft
                var input = snapshot.draft.inputText
                var draftState = DayimaDraftState.SAVED
                if (alreadySent == null && savedState.get<String>(RECOVERY_SESSION) == snapshot.session.id) {
                    val recovery = savedState.get<String>(RECOVERY_INPUT)
                    if (recovery != null && recovery != input) {
                        input = recovery
                        draftState = if (savedState.get<Long>(RECOVERY_REVISION) == snapshot.draft.revision) DayimaDraftState.SAVING else DayimaDraftState.CONFLICT
                    }
                }
                savedState[SESSION] = snapshot.session.id
                val expanded = showConversation ?: if (new || id != null) true else mutableState.value.expanded
                savedState[EXPANDED] = expanded
                mutableState.value = DayimaChatState(snapshot.session.id, snapshot.session.title, input, loaded = true,
                    expanded = expanded, draftState = draftState,
                    messages = snapshot.messages, operation = snapshot.operation, processing = sameRunning, messageImages = snapshot.messageImages)
                media.bind(snapshot.draft, snapshot.images)
                pendingMessageId = pending.takeIf { alreadySent == null }
                savedState[PENDING] = pendingMessageId
                if (alreadySent != null) clearRecovery()
                observe(); observeHistory()
                if (draftState == DayimaDraftState.SAVING) saveInputNow()
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.update { it.copy(loading = false, loadFailed = true) } }
        } }
    }

    private fun observe() {
        contentJob?.cancel()
        val id = mutableState.value.sessionId ?: return
        if (!visible) return
        val request = ++generation
        contentJob = viewModelScope.launch {
            try { chats.observe(id).collect { snapshot ->
                if (request != generation) return@collect
                if (snapshot == null || snapshot.session.status != DayimaChatStatus.ACTIVE) {
                    mutableState.update { it.copy(loaded = false, loadFailed = true) }; return@collect
                }
                mutableState.update { it.copy(title = snapshot.session.title,
                    messages = (it.messages + snapshot.messages).distinctBy { msg -> msg.id }.sortedBy { msg -> msg.sequence },
                    operation = snapshot.operation, messageImages = it.messageImages + snapshot.messageImages) }
                media.bind(snapshot.draft, snapshot.images)
            } } catch (error: CancellationException) { throw error
            } catch (_: Exception) { if (request == generation) mutableState.update { it.copy(loadFailed = true) } }
        }
    }

    internal fun changeInput(text: String) {
        if (!mutableState.value.editable || text.length > 6000 || text == mutableState.value.input) return
        pendingMessageId = null; savedState[PENDING] = null
        mutableState.update { it.copy(input = text, draftState = DayimaDraftState.SAVING) }
        recovery()
        saveDelay?.cancel()
        saveDelay = viewModelScope.launch { delay(180); saveInputNow() }
    }
    private fun recovery() {
        savedState[RECOVERY_SESSION] = mutableState.value.sessionId
        savedState[RECOVERY_INPUT] = mutableState.value.input
        savedState[RECOVERY_REVISION] = persisted?.revision
    }
    private fun clearRecovery() { savedState[RECOVERY_SESSION] = null; savedState[RECOVERY_INPUT] = null; savedState[RECOVERY_REVISION] = null }
    private fun saveInputNow() { viewModelScope.launch { writes.withLock { flushInput() } } }
    fun savePendingInput() { saveDelay?.cancel(); if (persisted != null) saveInputNow() }
    private suspend fun flushInput(): Boolean {
        while (true) {
            val draft = persisted ?: return true
            val input = mutableState.value.input
            if (draft.inputText == input) {
                mutableState.update { it.copy(draftState = DayimaDraftState.SAVED) }; clearRecovery(); return true
            }
            try {
                persisted = chats.saveInput(draft.sessionId, input, draft.revision)
                recovery()
                if (mutableState.value.input == input) {
                    mutableState.update { it.copy(draftState = DayimaDraftState.SAVED) }; clearRecovery(); return true
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(draftState = if (error is DayimaChatChanged) DayimaDraftState.CONFLICT else DayimaDraftState.FAILED) }
                recovery(); return false
            }
        }
    }
    internal fun retryInput() { viewModelScope.launch { writes.withLock {
        val id = mutableState.value.sessionId ?: return@withLock
        try { persisted = chats.snapshot(id)?.draft ?: throw DayimaChatChanged(); flushInput() }
        catch (_: Exception) { mutableState.update { it.copy(draftState = DayimaDraftState.FAILED) } }
    } } }

    internal fun send() {
        val current = mutableState.value
        if ((!current.canSend && !current.sendFailed) || current.loading || current.sending || current.processing) return
        val id = current.sessionId ?: return
        val messageId = pendingMessageId ?: UUID.randomUUID().toString().also { pendingMessageId = it; savedState[PENDING] = it }
        saveDelay?.cancel(); mutableState.update { it.copy(sending = true, sendFailed = false) }
        viewModelScope.launch { writes.withLock {
            try {
                val existing = chats.sentMessage(id, messageId)
                val message = if (existing != null) {
                    if (existing.content != mutableState.value.input) throw DayimaChatChanged()
                    existing
                } else {
                    if (!flushInput()) { mutableState.update { it.copy(sending = false) }; return@withLock }
                    val draft = requireNotNull(persisted)
                    chats.send(id, mutableState.value.input, messageId, draft.revision)
                }
                val latest = chats.snapshot(id) ?: throw DayimaChatChanged()
                persisted = latest.draft
                media.bind(latest.draft, latest.images)
                clearRecovery(); savedState[PENDING] = null; pendingMessageId = null
                mutableState.update { it.copy(input = requireNotNull(persisted).inputText, sending = false, sendFailed = false, draftState = DayimaDraftState.SAVED, failure = null) }
                analyze(id, message.id)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.update { it.copy(sending = false, sendFailed = true) }; recovery() }
        } }
    }
    internal fun retryAnalysis() {
        val state = mutableState.value
        val operation = state.operation ?: return
        if (state.processing || state.loading || state.sending) return
        analyze(operation.sessionId, operation.requestMessageId, retry = true)
    }
    private fun analyze(id: String, messageId: String, retry: Boolean = false) {
        val key = UUID.randomUUID().toString(); analysisKey = key
        mutableState.update { it.copy(processing = true, failure = null) }
        analysisJob = viewModelScope.launch {
            try {
                if (retry) chats.interrupt(id)
                coordinator.process(id, messageId)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (analysisKey == key && mutableState.value.sessionId == id) mutableState.update { it.copy(
                    failure = (error as? DayimaChatException)?.failure ?: DayimaChatFailure.LOCAL_SAVE) }
            } finally {
                if (analysisKey == key && mutableState.value.sessionId == id) mutableState.update { it.copy(processing = false) }
            }
        }
    }
    private fun cancelAnalysis() {
        val running = analysisJob?.isActive == true
        analysisKey = null; analysisJob?.cancel(); analysisJob = null
        mutableState.update { it.copy(processing = false, failure = if (running) DayimaChatFailure.INTERRUPTED else it.failure) }
    }

    internal fun loadOlder() {
        val state = mutableState.value; val id = state.sessionId ?: return
        val first = state.messages.firstOrNull()?.sequence ?: return
        if (first <= 1 || state.olderLoading) return
        val request = generation
        mutableState.update { it.copy(olderLoading = true, olderFailed = false) }
        viewModelScope.launch {
            try {
                val rows = chats.older(id, first)
                val images = chats.messageImages(id, rows.map { it.id })
                if (request == generation) mutableState.update { it.copy(messages = (rows + it.messages).distinctBy { msg -> msg.id }.sortedBy { msg -> msg.sequence },
                    messageImages = images + it.messageImages, olderLoading = false) }
            } catch (_: Exception) { if (request == generation) mutableState.update { it.copy(olderLoading = false, olderFailed = true) } }
        }
    }
    internal fun history(open: Boolean) { mutableState.update { it.copy(historyOpen = open) }; observeHistory() }
    internal fun moreSessions() { historyLimit += 30; observeHistory() }
    private fun observeHistory() {
        historyJob?.cancel()
        if (!visible || !mutableState.value.historyOpen) return
        historyJob = viewModelScope.launch {
            try { chats.observeSessions(historyLimit).collect { rows -> mutableState.update { it.copy(sessions = rows, historyFailed = false, moreSessions = rows.size >= historyLimit) } } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.update { it.copy(historyFailed = true) } }
        }
    }
    internal fun askDelete(id: String?) { if (!mutableState.value.deleting && !mutableState.value.media.busy) mutableState.update { it.copy(deleteId = id, deleteFailed = false) } }
    internal fun deleteChat() {
        val id = mutableState.value.deleteId ?: return
        if (mutableState.value.deleting || mutableState.value.media.busy) return
        val current = id == mutableState.value.sessionId
        if (current) { cancelAnalysis(); ++generation; contentJob?.cancel(); saveDelay?.cancel() }
        mutableState.update { it.copy(deleting = true, deleteFailed = false) }
        viewModelScope.launch { writes.withLock {
            try {
                chats.delete(id)
                if (current) {
                    persisted = null; clearRecovery(); savedState[SESSION] = null; savedState[PENDING] = null
                    mutableState.value = DayimaChatState(expanded = true)
                } else mutableState.update { it.copy(deleting = false, deleteId = null) }
                if (current) open(new = true)
            } catch (_: Exception) { mutableState.update { it.copy(deleting = false, deleteFailed = true) }; observe() }
        } }
    }
    private companion object {
        const val SESSION = "dayima.chat.session"; const val EXPANDED = "dayima.chat.expanded"; const val PENDING = "dayima.chat.pending"
        const val RECOVERY_SESSION = "dayima.chat.recovery_session"; const val RECOVERY_INPUT = "dayima.chat.recovery_input"; const val RECOVERY_REVISION = "dayima.chat.recovery_revision"
    }
}
