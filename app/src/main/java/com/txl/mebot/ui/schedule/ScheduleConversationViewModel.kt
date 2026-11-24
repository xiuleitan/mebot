package com.txl.mebot.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.ScheduleConversationDraftEntity
import com.txl.mebot.data.local.ScheduleMessageEntity
import com.txl.mebot.data.local.SchedulePendingQuestionEntity
import com.txl.mebot.data.schedule.ConversationDraftConflict
import com.txl.mebot.data.schedule.ScheduleAnalysisException
import com.txl.mebot.data.schedule.ScheduleAnalysisFailure
import com.txl.mebot.data.schedule.ScheduleCreationCoordinator
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConversationSaveState { SAVED, SAVING, FAILED, CONFLICT }
data class ScheduleSavedEvent(val messageId: String, val date: LocalDate, val createdOccurrenceId: String? = null)
data class ScheduleDeletedEvent(val messageId: String, val cleanupPending: Boolean)

data class ScheduleConversationState(
    val sessionId: String? = null,
    val input: String = "",
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isAnalyzing: Boolean = false,
    val analysisAttempt: Int = 0,
    val pendingAnalysisId: String? = null,
    val analysisFailure: ScheduleAnalysisFailure? = null,
    val hasTask: Boolean = false,
    val taskEntry: ScheduleEntry? = null,
    val savedEvent: ScheduleSavedEvent? = null,
    val deletedEvent: ScheduleDeletedEvent? = null,
    val isDeleted: Boolean = false,
    val loadFailed: Boolean = false,
    val saveState: ConversationSaveState = ConversationSaveState.SAVED,
    val messages: List<ScheduleMessageEntity> = emptyList(),
    val messageAttachments: Map<String, List<com.txl.mebot.data.local.ScheduleMessageAttachment>> = emptyMap(),
    val pendingAttachmentCount: Int = 0,
    val questions: List<SchedulePendingQuestionEntity> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val historyFailed: Boolean = false,
)

/** 短暂防抖合并输入，打开、发送和保存串行执行；页面关闭不会取消待保存的文字。 */
class ScheduleConversationViewModel(
    application: Application, private val savedState: SavedStateHandle,
    private val creation: ScheduleCreationCoordinator,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(
        application, savedState, (application as MebotApplication).container.scheduleCreation,
    )
    private val repository = (application as MebotApplication).container.conversations
    private val schedules = (application as MebotApplication).container.schedules
    private val mutableState = MutableStateFlow(ScheduleConversationState())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var persistedDraft: ScheduleConversationDraftEntity? = null
    private var saveJob: Job? = null
    private var contentJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisSession: String? = null
    private var finishedAnalysisId: String? = null
    private var conversationVisible = false
    private var messageLimit = 40
    private var pendingMessageId: String? = null
    private var occurrenceContextId: String? = savedState[ACTIVE_OCCURRENCE]
    private var lastOpen: OpenTarget = if (savedState.get<Boolean>(EDITING_TASK) == true) {
        savedState.get<String>(ACTIVE_SESSION)?.let { OpenTarget.Session(it) } ?: OpenTarget.Creation
    } else OpenTarget.Creation

    private sealed interface OpenTarget {
        data object Creation : OpenTarget
        data object ClearCreation : OpenTarget
        data class Session(val id: String) : OpenTarget
        data class Occurrence(val id: String) : OpenTarget
    }

    init {
        if (savedState.get<String>(ACTIVE_SESSION) != null) open(lastOpen)
    }

    fun openCreation() = open(OpenTarget.Creation)
    fun clearCreation() {
        if (mutableState.value.isLoading || mutableState.value.isSending) return
        analysisJob?.cancel()
        analysisSession = null
        mutableState.update { it.copy(isAnalyzing = false) }
        open(OpenTarget.ClearCreation)
    }
    fun openConversation(id: String) = open(OpenTarget.Session(id))
    internal suspend fun acceptHomeMessage(id: String, messageId: String) {
        state.first { !it.isLoading && !it.isSending }
        conversationVisible = true
        openConversation(id)
        val page = state.first { it.loadFailed || it.sessionId == id && it.isLoaded && it.messages.isNotEmpty() }
        check(!page.loadFailed) { "会话暂未读取成功" }
        if (!page.isAnalyzing && page.messages.none { it.role == com.txl.mebot.data.local.MessageRole.ASSISTANT }) analyze(id, messageId)
    }

    fun openForOccurrence(id: String) = open(OpenTarget.Occurrence(id))

    private fun open(target: OpenTarget) {
        if (mutableState.value.isLoading || mutableState.value.isSending) return
        lastOpen = target
        saveJob?.cancel()
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            writes.withLock {
                persistedDraft?.let { previous ->
                    if (!schedules.sessionExists(previous.sessionId)) {
                        persistedDraft = null; pendingMessageId = null; occurrenceContextId = null
                        mutableState.update { it.copy(input = "") }
                    }
                }
                if (target != OpenTarget.ClearCreation && !flushInput()) {
                    mutableState.update { it.copy(isLoading = false) }
                    return@withLock
                }
                try {
                    val draft = when (target) {
                        OpenTarget.Creation -> repository.openCreation()
                        OpenTarget.ClearCreation -> repository.clearCreation()
                        is OpenTarget.Session -> if (schedules.sessionExists(target.id)) repository.openConversation(target.id) else repository.openCreation()
                        is OpenTarget.Occurrence -> repository.openForOccurrence(target.id)
                    }
                    persistedDraft = draft
                    occurrenceContextId = if (target is OpenTarget.Occurrence) target.id
                        else occurrenceContextId?.takeIf { target is OpenTarget.Session && target.id == draft.sessionId }
                    savedState[ACTIVE_OCCURRENCE] = occurrenceContextId
                    if (analysisSession != draft.sessionId) {
                        analysisJob?.cancel()
                        analysisSession = null
                    }
                    pendingMessageId = null
                    savedState[ACTIVE_SESSION] = draft.sessionId
                    val boundTarget = target is OpenTarget.Occurrence || target is OpenTarget.Session && target.id == draft.sessionId
                    savedState[EDITING_TASK] = boundTarget
                    if (target is OpenTarget.Session && target.id != draft.sessionId) lastOpen = OpenTarget.Creation
                    // 清空失败时可重试；成功后再恢复页面只应打开这份新草稿。
                    if (target == OpenTarget.ClearCreation) lastOpen = OpenTarget.Creation
                    messageLimit = 40
                    mutableState.value = ScheduleConversationState(
                        sessionId = draft.sessionId, input = draft.inputText, isLoaded = true,
                        isAnalyzing = analysisSession == draft.sessionId && analysisJob?.isActive == true,
                    )
                    observeContent()
                    if (target == OpenTarget.Creation || target == OpenTarget.ClearCreation) viewModelScope.launch {
                        runCatching { (getApplication<Application>() as MebotApplication).container.media.cleanPendingDeletions() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun changeInput(text: String) {
        val current = mutableState.value
        if (!current.isLoaded || current.isLoading || current.isSending || current.input == text) return
        pendingMessageId = null
        mutableState.update { it.copy(input = text, saveState = ConversationSaveState.SAVING) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(180)
            saveInputNow()
        }
    }

    // 防抖任务可以取消；已经开始的数据库写入不因后续输入而取消。
    private fun saveInputNow() {
        viewModelScope.launch { writes.withLock { flushInput() } }
    }

    fun savePendingInput() {
        saveJob?.cancel()
        saveInputNow()
    }

    private suspend fun flushInput(): Boolean {
        val draft = persistedDraft ?: return true
        val input = mutableState.value.input
        if (draft.inputText == input) {
            mutableState.update { it.copy(saveState = ConversationSaveState.SAVED) }
            return true
        }
        return try {
            val saved = repository.saveInput(draft.sessionId, input, draft.revision)
            if (mutableState.value.sessionId != draft.sessionId) return true
            persistedDraft = saved
            mutableState.update {
                it.copy(saveState = if (it.input == input) ConversationSaveState.SAVED else ConversationSaveState.SAVING)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ConversationDraftConflict) {
            mutableState.update { if (it.sessionId == draft.sessionId) it.copy(saveState = ConversationSaveState.CONFLICT) else it }
            false
        } catch (_: Exception) {
            mutableState.update { if (it.sessionId == draft.sessionId) it.copy(saveState = ConversationSaveState.FAILED) else it }
            false
        }
    }

    fun send() {
        val current = mutableState.value
        if (!current.isLoaded || current.isLoading || current.isSending || current.isAnalyzing ||
            current.input.isBlank() && current.pendingAttachmentCount == 0) return
        saveJob?.cancel()
        mutableState.update { it.copy(isSending = true) }
        val messageId = pendingMessageId ?: UUID.randomUUID().toString().also { pendingMessageId = it }
        viewModelScope.launch {
            writes.withLock {
                try {
                    if (!flushInput()) return@withLock
                    val draft = requireNotNull(persistedDraft)
                    val target = occurrenceContextId ?: mutableState.value.taskEntry?.id
                    val result = repository.sendUserMessage(draft.sessionId, messageId, mutableState.value.input, draft.revision, target)
                    persistedDraft = result
                    pendingMessageId = null
                    mutableState.update { it.copy(input = result.inputText, pendingAttachmentCount = 0, saveState = ConversationSaveState.SAVED) }
                    analyze(draft.sessionId, messageId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ConversationDraftConflict) {
                    mutableState.update { it.copy(saveState = ConversationSaveState.CONFLICT) }
                } catch (_: Exception) {
                    mutableState.update { it.copy(saveState = ConversationSaveState.FAILED) }
                } finally {
                    mutableState.update { it.copy(isSending = false) }
                }
            }
        }
    }

    fun retry() {
        when {
            mutableState.value.loadFailed || !mutableState.value.isLoaded -> open(lastOpen)
            mutableState.value.historyFailed -> observeContent()
            mutableState.value.saveState == ConversationSaveState.CONFLICT -> {
                // 仅在用户明确选择“保留当前文字”后，以最新保存版本重新提交。
                saveJob?.cancel()
                mutableState.update { it.copy(saveState = ConversationSaveState.SAVING) }
                viewModelScope.launch {
                    writes.withLock {
                        try {
                            val draft = persistedDraft ?: return@withLock
                            persistedDraft = repository.openConversation(draft.sessionId)
                            flushInput()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.update { it.copy(saveState = ConversationSaveState.FAILED) }
                        }
                    }
                }
            }
            pendingMessageId != null && (mutableState.value.input.isNotBlank() || mutableState.value.pendingAttachmentCount > 0) -> send()
            mutableState.value.pendingAnalysisId != null && !mutableState.value.isAnalyzing -> {
                val current = mutableState.value
                analyze(requireNotNull(current.sessionId), requireNotNull(current.pendingAnalysisId))
            }
            else -> {
                saveJob?.cancel()
                mutableState.update { it.copy(saveState = ConversationSaveState.SAVING) }
                saveInputNow()
            }
        }
    }

    private fun analyze(sessionId: String, messageId: String) {
        if (analysisJob?.isActive == true) return
        analysisSession = sessionId
        finishedAnalysisId = null
        mutableState.update { it.copy(isAnalyzing = true, analysisAttempt = 0, pendingAnalysisId = messageId, analysisFailure = null) }
        analysisJob = viewModelScope.launch {
            try {
                val result = creation.process(sessionId, messageId) { attempt ->
                    mutableState.update { if (it.sessionId == sessionId) it.copy(analysisAttempt = attempt) else it }
                }
                finishedAnalysisId = messageId
                if (result.deletedSessionId != null && mutableState.value.sessionId == sessionId) {
                    resetDeletedSession(ScheduleDeletedEvent(messageId, result.cleanupPending))
                    return@launch
                }
                if (result.deletedOccurrenceId == occurrenceContextId) { occurrenceContextId = null; savedState.remove<String>(ACTIVE_OCCURRENCE) }
                val bound = schedules.taskForSession(sessionId, includeDeleted = true) != null
                if (mutableState.value.sessionId == sessionId) {
                    if (bound) {
                        savedState[EDITING_TASK] = true
                        lastOpen = OpenTarget.Session(sessionId)
                    }
                    mutableState.update { it.copy(
                        pendingAnalysisId = null, analysisFailure = null, hasTask = bound,
                        savedEvent = result.savedDate?.let { date -> ScheduleSavedEvent(messageId, date, result.createdOccurrenceId) },
                    ) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: ScheduleAnalysisException) {
                if (mutableState.value.sessionId == sessionId) mutableState.update { it.copy(analysisFailure = failure.failure) }
            } catch (_: Exception) {
                if (mutableState.value.sessionId == sessionId) mutableState.update { it.copy(analysisFailure = ScheduleAnalysisFailure.LOCAL_SAVE) }
            } finally {
                if (analysisSession == sessionId) {
                    analysisSession = null
                    if (mutableState.value.sessionId == sessionId) mutableState.update { it.copy(isAnalyzing = false) }
                }
            }
        }
    }

    fun consumeSavedEvent(messageId: String) {
        mutableState.update { if (it.savedEvent?.messageId == messageId) it.copy(savedEvent = null) else it }
    }
    fun consumeDeletedEvent(messageId: String) {
        mutableState.update { if (it.deletedEvent?.messageId == messageId) it.copy(deletedEvent = null) else it }
    }
    fun forgetDeletedSession(sessionId: String) {
        if (mutableState.value.sessionId != sessionId) return
        analysisJob?.cancel(); analysisSession = null
        resetDeletedSession(null)
    }
    private fun resetDeletedSession(notice: ScheduleDeletedEvent?) {
        saveJob?.cancel(); contentJob?.cancel(); persistedDraft = null; pendingMessageId = null; occurrenceContextId = null
        savedState.remove<String>(ACTIVE_SESSION); savedState.remove<String>(ACTIVE_OCCURRENCE); savedState.remove<Boolean>(EDITING_TASK)
        lastOpen = OpenTarget.Creation
        mutableState.value = ScheduleConversationState(isLoaded = true, isDeleted = true, deletedEvent = notice)
        clearSchedulePhotoCache()
    }

    fun setConversationVisible(visible: Boolean) {
        conversationVisible = visible
        if (visible && !mutableState.value.isLoaded && !mutableState.value.isLoading && !mutableState.value.loadFailed) open(lastOpen)
        if (visible) observeContent() else contentJob?.cancel()
        // 收起时立即提交，不必等待防抖；协程属于 Activity 的 ViewModel。
        if (!visible) {
            savePendingInput()
        }
    }

    fun loadOlderMessages() {
        messageLimit += 40
        observeContent()
    }

    private fun observeContent() {
        contentJob?.cancel()
        val id = mutableState.value.sessionId ?: return
        if (!conversationVisible) return
        val limit = messageLimit
        contentJob = viewModelScope.launch {
            try {
                val textContent = combine(
                    repository.observeMessages(id, limit + 1), repository.observePendingQuestions(id),
                    repository.observeAwaitingAnalysis(id), schedules.observeTaskForSession(id), schedules.observeConversationRecord(id, occurrenceContextId),
                ) { messages, questions, pending, task, card ->
                    ConversationContent(messages, questions, pending?.id, task != null, card?.asEntry())
                }
                val media = (getApplication<Application>() as MebotApplication).container.database.scheduleMediaDao()
                combine(textContent, media.observeMessageAttachments(id, limit + 1), media.observeAttachments(id)) { content, attachments, items ->
                    content.copy(messageAttachments = attachments.groupBy { it.link.messageId }, pendingAttachmentCount = items.count { it.link.pendingSend })
                }.collect { content ->
                    if (content.hasTask && mutableState.value.sessionId == id) {
                        savedState[EDITING_TASK] = true
                        lastOpen = OpenTarget.Session(id)
                        occurrenceContextId = content.card?.id
                        savedState[ACTIVE_OCCURRENCE] = occurrenceContextId
                    }
                    val pending = content.pendingId?.takeUnless { it == finishedAnalysisId }
                    mutableState.update { current ->
                        if (current.sessionId != id) current else current.copy(
                            messages = content.messages.takeLast(limit), questions = content.questions,
                            messageAttachments = content.messageAttachments.filterKeys { key -> content.messages.takeLast(limit).any { it.id == key } },
                            pendingAttachmentCount = content.pendingAttachmentCount,
                            hasOlderMessages = content.messages.size > limit, historyFailed = false,
                            hasTask = content.hasTask, taskEntry = content.card, pendingAnalysisId = pending,
                            analysisFailure = when {
                                pending == null -> null
                                current.isAnalyzing || current.isSending -> current.analysisFailure
                                current.analysisFailure != null -> current.analysisFailure
                                else -> ScheduleAnalysisFailure.INTERRUPTED
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { if (it.sessionId == id) it.copy(historyFailed = true) else it }
            }
        }
    }

    companion object {
        private const val ACTIVE_SESSION = "schedule_active_session"
        private const val EDITING_TASK = "schedule_editing_task"
        private const val ACTIVE_OCCURRENCE = "schedule_active_occurrence"
    }
}

private data class ConversationContent(
    val messages: List<ScheduleMessageEntity>, val questions: List<SchedulePendingQuestionEntity>,
    val pendingId: String?, val hasTask: Boolean, val card: ScheduleEntry?,
    val messageAttachments: Map<String, List<com.txl.mebot.data.local.ScheduleMessageAttachment>> = emptyMap(),
    val pendingAttachmentCount: Int = 0,
)
