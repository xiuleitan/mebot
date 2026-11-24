package com.txl.mebot.ui.archive

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.archive.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ArchiveCardData(val record: ArchiveRecord, val coverId: String?, val snippet: String? = null)
internal data class ArchivePageState(
    val loading: Boolean = true, val busy: Boolean = false, val dirty: Boolean = false,
    val cards: List<ArchiveCardData> = emptyList(), val moreRecords: Boolean = false,
    val context: ArchiveContext? = null, val expanded: Boolean = false, val returningHome: Boolean = false,
    val detail: ArchiveRecord? = null, val editorOpen: Boolean = false, val editorSaved: Boolean = false,
    val historyOpen: Boolean = false, val sessions: List<ArchiveSessionSummary> = emptyList(), val moreSessions: Boolean = false,
    val error: String? = null, val conflict: Boolean = false, val notice: String? = null,
    val inFlight: Set<String> = emptySet(),
    val indexSummary: ArchiveIndexSummary = ArchiveIndexSummary(), val indexFailure: ArchiveSemanticFailure? = null,
    val indexSettings: ArchiveSemanticSettings? = null,
    val query: String = "", val submittedQuery: String? = null, val searchLoading: Boolean = false,
    val searchFailure: ArchiveSemanticFailure? = null, val localTextSearch: Boolean = false,
    val searchCards: List<ArchiveCardData> = emptyList(), val moreSearch: Boolean = false,
) {
    val editable get() = !loading && !busy && !returningHome && !editorSaved && context != null && context.closed.not()
    val input get() = context?.draft?.content?.input.orEmpty()
    val candidate get() = context?.draft?.content?.candidate
    val pending get() = context?.assets.orEmpty().filter { it.pending }
    val analyzing get() = context?.sessionId in inFlight
    val analysis get() = context?.draft?.content?.analysis ?: ArchiveAnalysisMeta()
    val displayedCards get() = if (submittedQuery != null) searchCards else cards
}

/** 串行提交本地变更；键入立即更新 UI，Room 成功返回才清除 dirty 标记。 */
class ArchiveViewModel internal constructor(application: Application, private val saved: SavedStateHandle,
    private val records: ArchiveRepository, internal val attachments: ArchiveAttachmentRepository,
    private val analysisCoordinator: ArchiveAnalysisCoordinator? = null,
    private val semantic: ArchiveSemanticEngine? = null) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved,
        (application as MebotApplication).container.archives, application.container.archiveAttachments,
        ArchiveAnalysisCoordinator(application.container.archives, application.container.archiveAttachments, QwenArchiveInterpreter(application.container.modelSettings)),
        application.container.archiveSemantic)

    private val mutable = MutableStateFlow(ArchivePageState())
    internal val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var editSequence = 0L
    private var workCount = 0
    private var saveAttempt: Triple<String, String, Long>? = null
    private var savedEditorRecord: ArchiveRecord? = null
    private var searchJob: Job? = null
    private var searchEpoch = 0L
    private var searchSnapshot: ArchiveSearchSnapshot? = null
    private var searchOffset = 0
    private var searchNeedsRefresh = false
    private class AnalysisSlot(val work: ArchiveAnalysisWork) {
        lateinit var job: Job
        var cancelledStatus = ArchiveAnalysisStatus.INTERRUPTED
        var cancelledFailure: ArchiveAnalysisFailure? = ArchiveAnalysisFailure.INTERRUPTED
    }
    private val analyses = mutableMapOf<String, AnalysisSlot>()
    private val sources = mutableMapOf<Pair<String, String>, String>()
    internal val media = ArchiveMediaFiles(application, attachments)

    init {
        viewModelScope.launch {
            try { media.cleanExpiredExports() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
        retry()
        if (semantic != null) viewModelScope.launch {
            try {
                semantic.start()
                semantic.index.changes().collectLatest {
                    val previousSettings = mutable.value.indexSettings
                    updateIndexStatus()
                    val current = mutable.value
                    current.submittedQuery?.let { query ->
                        when {
                            previousSettings?.revision != current.indexSettings?.revision -> startSearch(query)
                            current.searchLoading -> searchNeedsRefresh = true
                            else -> startSearch(query, localOnly = true)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(indexFailure = semanticFailure(error)) } }
        }
    }

    private fun operation(flush: Boolean = true, queued: Boolean = false, block: suspend () -> Unit): Job? {
        if (mutable.value.busy && !queued) return null
        workCount++
        mutable.update { it.copy(busy = true, error = null, conflict = false) }
        return viewModelScope.launch {
            try { mutex.withLock { if (flush) flushLocked(); block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
            finally { workCount--; mutable.update { it.copy(busy = workCount > 0, loading = false) } }
        }
    }

    fun retry() = operation {
        refreshRecords()
        val wanted = mutable.value.context?.sessionId ?: saved.get<String>("archive_session")
        if (wanted != null && saved.get<Boolean>("archive_chat_open") == true) {
            loadSession(wanted); showConversation()
        } else openCreationLocked()
    }
    fun refreshList() = operation { refreshRecords(); updateIndexStatus(); semantic?.nudge() }

    private suspend fun updateIndexStatus() {
        val engine = semantic ?: return
        try {
            val settings = engine.settings.load(); val summary = engine.index.summary()
            mutable.update { it.copy(indexSummary = summary, indexSettings = settings, indexFailure = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(indexFailure = semanticFailure(error)) } }
    }

    private suspend fun openCreationLocked() {
        val id = records.creationSession(saved.get<String>("archive_home_session") ?: saved.get<String>("archive_session"))
        loadSession(id)
        saved["archive_home_session"] = id
        saved["archive_chat_open"] = false
        mutable.update { it.copy(expanded = false, returningHome = false) }
    }
    private fun showConversation() {
        saved["archive_chat_open"] = true
        mutable.update { it.copy(expanded = true, returningHome = false) }
    }

    private suspend fun loadSession(id: String) {
        var loaded = records.openSession(id)
        val active = loaded.draft.activeRequestId
        if (active != null && analyses[id]?.work?.request?.requestId != active) {
            records.cancelAnalysis(id, active, ArchiveAnalysisStatus.INTERRUPTED, ArchiveAnalysisFailure.INTERRUPTED)
            loaded = records.context(id)
        }
        saved["archive_session"] = id
        editSequence++
        mutable.update { it.copy(context = loaded, dirty = false, error = null, conflict = false) }
    }
    private suspend fun refreshRecords() {
        val page = records.records(50)
        val loaded = cards(page)
        mutable.update { it.copy(cards = loaded, moreRecords = page.size == 50) }
    }
    private suspend fun cards(rows: List<ArchiveRecord>) = rows.map { record ->
        val image = (listOfNotNull(record.content.coverAssetId) + record.content.assetIds).distinct().firstOrNull { id ->
            attachments.metadata(id).mimeType.startsWith("image/")
        }
        ArchiveCardData(record, image)
    }
    fun moreRecords() = operation {
        val page = records.records(50, mutable.value.cards.size)
        val loaded = cards(page)
        mutable.update { it.copy(cards = it.cards + loaded, moreRecords = page.size == 50) }
    }
    fun newSession(edit: Boolean = false) = operation {
        val id = records.createSession()
        loadSession(id)
        saved["archive_chat_open"] = !edit
        mutable.update { it.copy(expanded = !edit, returningHome = false, editorOpen = false, detail = null, historyOpen = false) }
        if (edit) prepareEditor()
    }
    fun selectSession(id: String) = operation {
        loadSession(id)
        showConversation()
        mutable.update { it.copy(historyOpen = false, editorOpen = false, detail = null) }
    }
    internal suspend fun acceptHomeMessage(id: String, messageId: String) {
        state.first { !it.busy && !it.loading }
        selectSession(id)?.join()
        val page = state.value
        val conversation = page.context
        if (conversation?.sessionId == id && !page.analyzing && conversation.messages.any { it.id == messageId } &&
            conversation.messages.none { it.role == com.txl.mebot.data.local.MessageRole.ASSISTANT }) retryAnalysis()
    }
    fun history(more: Boolean = false) = operation {
        val page = records.sessions(50, if (more) mutable.value.sessions.size else 0)
        mutable.update { it.copy(historyOpen = true, sessions = if (more) it.sessions + page else page, moreSessions = page.size == 50) }
    }
    fun dismissHistory() { mutable.update { it.copy(historyOpen = false) } }
    fun expand() { if (!mutable.value.returningHome) showConversation() }
    fun collapse() {
        saveInput()
        saved["archive_chat_open"] = false
        mutable.update { it.copy(expanded = false, returningHome = true, error = null) }
    }
    /** 等旧会话的退场动画结束，再切换首页输入目标，避免动画中闪换聊天内容。 */
    fun finishReturnHome() = operation(queued = true) {
        if (mutable.value.returningHome && !mutable.value.expanded) openCreationLocked()
    }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    fun changeQuery(text: String) {
        if (text == mutable.value.query) return
        searchJob?.cancel(); searchEpoch++; searchSnapshot = null; searchNeedsRefresh = false
        mutable.update { it.copy(query = text, submittedQuery = null, searchCards = emptyList(), searchLoading = false, searchFailure = null, moreSearch = false, localTextSearch = false) }
    }
    fun search() {
        if (semantic == null) { mutable.update { it.copy(notice = "语义搜索尚未配置") }; return }
        val query = mutable.value.query.trim()
        if (query.isBlank()) changeQuery("") else startSearch(query)
    }
    fun cancelSearch() {
        searchJob?.cancel(); searchEpoch++; searchNeedsRefresh = false
        mutable.update { it.copy(searchLoading = false, searchFailure = ArchiveSemanticFailure.INTERRUPTED, moreSearch = false) }
    }
    private fun startSearch(query: String, localOnly: Boolean = false) {
        val engine = semantic ?: return
        searchJob?.cancel(); val epoch = ++searchEpoch; searchSnapshot = null; searchNeedsRefresh = false
        mutable.update { it.copy(submittedQuery = query, searchLoading = true, searchFailure = null, searchCards = emptyList(),
            moreSearch = false, localTextSearch = it.indexSettings?.values?.enabled != true) }
        searchJob = viewModelScope.launch {
            try {
                // 首页与档案页共用当前配置，查询时重新读取，避免使用过期的开关状态。
                val localText = !engine.settings.load().values.enabled
                currentCoroutineContext().ensureActive()
                if (epoch != searchEpoch) return@launch
                mutable.update { it.copy(localTextSearch = localText) }
                val snapshot = if (localText) engine.localSearch(query) else engine.search(query, localOnly)
                val (items, offset) = engine.page(snapshot, 0)
                val loaded = searchCards(items)
                currentCoroutineContext().ensureActive()
                if (epoch == searchEpoch) {
                    searchSnapshot = snapshot; searchOffset = offset
                    mutable.update { it.copy(searchCards = loaded, moreSearch = offset < snapshot.hits.size) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == searchEpoch) mutable.update { it.copy(searchFailure = semanticFailure(error)) } }
            finally { finishSearch(epoch) }
        }
    }
    private suspend fun searchCards(items: List<ArchiveSearchItem>) = cards(items.map { it.record }).mapIndexed { position, card -> card.copy(snippet = items[position].snippet) }
    private fun finishSearch(epoch: Long) {
        if (epoch != searchEpoch) return
        mutable.update { it.copy(searchLoading = false) }
        if (searchNeedsRefresh && mutable.value.searchFailure == null) {
            searchNeedsRefresh = false
            mutable.value.submittedQuery?.let { startSearch(it, localOnly = true) }
        }
    }
    fun moreSearch() {
        val engine = semantic ?: return; val snapshot = searchSnapshot ?: return
        if (mutable.value.searchLoading || !mutable.value.moreSearch) return
        val epoch = searchEpoch
        mutable.update { it.copy(searchLoading = true) }
        searchJob = viewModelScope.launch {
            try {
                val (items, offset) = engine.page(snapshot, searchOffset)
                val loaded = searchCards(items)
                currentCoroutineContext().ensureActive()
                if (epoch == searchEpoch) {
                    searchOffset = offset
                    mutable.update { it.copy(searchCards = it.searchCards + loaded, moreSearch = offset < snapshot.hits.size) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == searchEpoch) mutable.update { it.copy(searchCards = emptyList(), moreSearch = false, searchFailure = semanticFailure(error)) } }
            finally { finishSearch(epoch) }
        }
    }
    fun changeInput(text: String) = changeDraft { it.copy(input = text) }
    internal fun changeEditor(change: (ArchiveContent) -> ArchiveContent) = changeDraft { value ->
        value.copy(candidate = value.candidate?.let(change), analysis = ArchiveAnalysisMeta())
    }
    fun resolveConflict(index: Int) = changeDraft { value -> value.copy(conflicts = value.conflicts.filterIndexed { i, _ -> i != index }) }
    private fun changeDraft(change: (ArchiveDraftContent) -> ArchiveDraftContent) {
        val current = mutable.value
        if (!current.editable) return
        val context = current.context ?: return
        invalidateAnalysis(context.sessionId)
        editSequence++; saveAttempt = null
        var content = change(context.draft.content)
        if (context.draft.activeRequestId != null) content = content.copy(analysis = context.draft.content.analysis.copy(status = ArchiveAnalysisStatus.FAILED, failure = ArchiveAnalysisFailure.CHANGED))
        mutable.update { it.copy(context = context.copy(draft = context.draft.copy(content = content)), dirty = true) }
        saveInput()
    }
    fun saveInput(): Job = viewModelScope.launch {
        try { mutex.withLock { flushLocked() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { report(error) }
    }
    private suspend fun flushLocked() {
        while (mutable.value.dirty) {
            val context = requireNotNull(mutable.value.context)
            val sequence = editSequence
            val desired = context.draft.content
            val result = try { records.saveDraft(context.sessionId, context.draft.revision, desired) }
                catch (conflict: ArchiveConflict) {
                    val latest = records.draft(context.sessionId)
                    // 自己的响应刚好提交时，保留已返回候选，只合并用户新输入；不覆盖其他会话/编辑者。
                    if (desired.analysis.failure != ArchiveAnalysisFailure.CHANGED || context.draft.activeRequestId == null ||
                        latest.content.analysis.requestId != context.draft.activeRequestId) throw conflict
                    records.saveDraft(context.sessionId, latest.revision, latest.content.copy(input = desired.input, analysis = desired.analysis))
                }
            mutable.update { state ->
                val current = requireNotNull(state.context)
                check(current.sessionId == result.sessionId)
                state.copy(context = current.copy(draft = result.copy(content = if (editSequence == sequence) result.content else current.draft.content)),
                    dirty = editSequence != sequence, error = null)
            }
        }
    }

    fun send() = operation {
        val current = requireNotNull(mutable.value.context)
        invalidateAnalysis(current.sessionId)
        records.sendMessage(current.sessionId, UUID.randomUUID().toString(), current.draft.revision, current.draft.content.input,
            current.assets.filter { it.pending }.map { it.id })
        if (saved.get<String>("archive_home_session") == current.sessionId) saved.remove<String>("archive_home_session")
        loadSession(current.sessionId)
        if (!mutable.value.returningHome) showConversation()
        mutable.update { it.copy(notice = "消息与附件已保存在本机") }
        startAnalysisLocked(current.sessionId)
    }
    fun retryAnalysis() = operation {
        val current = requireNotNull(mutable.value.context)
        if (current.draft.content.input.isNotBlank() || current.assets.any { it.pending }) throw ArchiveFormError("请先发送当前输入，再整理最新资料")
        invalidateAnalysis(current.sessionId)
        startAnalysisLocked(current.sessionId)
    }
    fun stopAnalysis() {
        val current = mutable.value.context ?: return
        val token = current.draft.activeRequestId ?: return
        analyses[current.sessionId]?.let { it.cancelledStatus = ArchiveAnalysisStatus.CANCELLED; it.cancelledFailure = null; it.job.cancel() }
        operation(queued = true) {
            records.cancelAnalysis(current.sessionId, token)
            mutable.update { it.copy(inFlight = it.inFlight - current.sessionId) }
            if (mutable.value.context?.sessionId == current.sessionId && !mutable.value.dirty) loadSession(current.sessionId)
        }
    }
    private fun invalidateAnalysis(id: String) {
        analyses[id]?.let { slot -> slot.cancelledStatus = ArchiveAnalysisStatus.FAILED; slot.cancelledFailure = ArchiveAnalysisFailure.CHANGED; slot.job.cancel() }
        mutable.update { it.copy(inFlight = it.inFlight - id) }
    }
    private suspend fun invalidateStoredAnalysis(id: String) {
        invalidateAnalysis(id)
        records.draft(id).activeRequestId?.let { records.cancelAnalysis(id, it, ArchiveAnalysisStatus.FAILED, ArchiveAnalysisFailure.CHANGED) }
    }
    private suspend fun startAnalysisLocked(id: String) {
        val coordinator = analysisCoordinator ?: return
        val work = coordinator.begin(id)
        val slot = AnalysisSlot(work)
        slot.job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val (context, result) = coordinator.interpret(work)
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (analyses[id] !== slot) return@withLock
                    if (mutable.value.context?.sessionId == id && mutable.value.dirty) { flushLocked(); coordinator.fail(work, ArchiveAnalysisFailure.CHANGED) }
                    else coordinator.complete(work, context, result)
                    if (mutable.value.context?.sessionId == id && !mutable.value.dirty) loadSession(id)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    try { mutex.withLock {
                        if (mutable.value.context?.sessionId == id && mutable.value.dirty) flushLocked()
                        records.cancelAnalysis(id, work.request.requestId, slot.cancelledStatus, slot.cancelledFailure)
                        if (mutable.value.context?.sessionId == id && !mutable.value.dirty) loadSession(id)
                    } } catch (_: Exception) { }
                }
                throw cancelled
            } catch (error: Exception) {
                try { mutex.withLock {
                    coordinator.fail(work, (error as? ArchiveAnalysisException)?.failure ?: ArchiveAnalysisFailure.LOCAL_SAVE)
                    if (mutable.value.context?.sessionId == id && !mutable.value.dirty) loadSession(id)
                } } catch (failure: Exception) { report(failure) }
            } finally {
                if (analyses[id] === slot) {
                    analyses.remove(id)
                    mutable.update { it.copy(inFlight = it.inFlight - id) }
                }
            }
        }
        analyses[id] = slot
        mutable.update { it.copy(inFlight = it.inFlight + id) }
        loadSession(id)
        slot.job.start()
    }
    fun olderMessages() = operation {
        val current = requireNotNull(mutable.value.context)
        val first = current.messages.firstOrNull()?.sequence ?: return@operation
        val older = records.context(current.sessionId, first, 50)
        mutable.update { it.copy(context = current.copy(messages = older.messages + current.messages)) }
    }
    fun editCurrent() = operation { prepareEditor() }
    private suspend fun prepareEditor(reload: Boolean = false) {
        val id = requireNotNull(mutable.value.context).sessionId
        invalidateAnalysis(id)
        records.draft(id).activeRequestId?.let { records.cancelAnalysis(id, it) }
        records.prepareManualEditor(id, reload)
        loadSession(id)
        savedEditorRecord = null
        mutable.update { it.copy(editorOpen = true, editorSaved = false, detail = null) }
    }
    fun reloadEditor() = operation { prepareEditor(reload = true) }
    fun closeEditor() = operation { mutable.update { it.copy(editorOpen = false) } }
    internal suspend fun flushForNavigation(): Boolean = try { mutex.withLock { flushLocked() }; true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { report(error); false }
    fun editorClosed() {
        val record = savedEditorRecord
        if (record != null) saved["archive_chat_open"] = false
        mutable.update { it.copy(editorOpen = false, editorSaved = false, detail = record, error = null,
            expanded = if (record != null) false else it.expanded,
            returningHome = record != null || !it.expanded) }
        savedEditorRecord = null
    }
    fun openDetail(id: String) = operation {
        val record = requireNotNull(records.record(id))
        mutable.update { it.copy(detail = record) }
    }
    fun closeDetail() { mutable.update { it.copy(detail = null) } }
    fun editRecord(id: String, manual: Boolean = false) = operation {
        val session = records.conversationForRecord(id)
        loadSession(session)
        showConversation()
        mutable.update { it.copy(detail = null) }
        if (manual) prepareEditor()
    }
    fun saveEditor() = if (mutable.value.editorSaved) null else operation {
        var current = requireNotNull(mutable.value.context)
        val original = requireNotNull(current.draft.content.candidate)
        val candidate = original.copy(fields = original.fields.filterNot { it.name.isBlank() && it.value.isBlank() },
            links = original.links.filterNot { it.url.isBlank() && it.label.isBlank() })
        if (candidate.title.isBlank()) throw ArchiveFormError("请填写档案标题")
        if (current.draft.content.conflicts.isNotEmpty()) throw ArchiveFormError("请先逐项核对待确认信息，再保存档案")
        if (candidate.fields.any { it.name.isBlank() }) throw ArchiveFormError("请填写字段名称，或移除该字段")
        if (candidate.links.any { !validArchiveLink(it.url) }) throw ArchiveFormError("链接请填写完整的 http:// 或 https:// 地址")
        if (candidate != original) {
            records.saveDraft(current.sessionId, current.draft.revision, current.draft.content.copy(candidate = candidate))
            loadSession(current.sessionId); current = requireNotNull(mutable.value.context)
        }
        val attempt = saveAttempt?.takeIf { it.first == current.sessionId } ?: Triple(current.sessionId, UUID.randomUUID().toString(), current.draft.revision).also { saveAttempt = it }
        val receipt = records.confirm(attempt.first, attempt.second, attempt.third)
        loadSession(current.sessionId); refreshRecords(); saveAttempt = null
        savedEditorRecord = records.record(receipt.recordId)
        mutable.update { it.copy(editorSaved = true, notice = "档案已保存到本机") }
        try { semantic?.nudge() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(indexFailure = ArchiveSemanticFailure.LOCAL_STORAGE) } }
    }
    fun delete(record: ArchiveRecord) = operation {
        val session = records.conversationForRecord(record.id)
        records.delete(session, UUID.randomUUID().toString(), record.revision)
        analyses.filterValues { it.work.source.record?.id == record.id }.keys.toList().forEach(::invalidateAnalysis)
        mutable.value.context?.sessionId?.let { loadSession(it) }
        refreshRecords(); mutable.update { it.copy(detail = null, notice = "档案已删除，会话历史仍保留") }
    }

    fun preparePicker(): Boolean {
        val current = mutable.value
        if (!current.editable) return false
        saved["archive_picker_session"] = current.context!!.sessionId
        return true
    }
    fun picked(uris: List<Uri>) {
        val target = saved.get<String>("archive_picker_session") ?: return
        saved.remove<String>("archive_picker_session")
        if (uris.isNotEmpty()) import(target, uris)
    }
    fun pickLocal(uri: Uri) {
        val target = mutable.value.context?.sessionId ?: return
        val existing = sources[target to uri.toString()]?.takeIf { id -> mutable.value.pending.any { it.id == id } }
        if (existing != null) discard(existing) else import(target, listOf(uri))
    }
    private fun import(target: String, uris: List<Uri>, cameraPath: String? = null) = operation(queued = true) {
        invalidateStoredAnalysis(target)
        var failed = 0; var imported = 0
        val added = mutableListOf<String>()
        for (uri in uris) {
            try {
                val id = media.import(target, uri)
                sources[target to uri.toString()] = id; added += id; imported++
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed++ }
        }
        val targetDraft = records.draft(target)
        targetDraft.content.candidate?.let { candidate ->
            val assets = (candidate.assetIds + added).distinct()
            val cover = candidate.coverAssetId ?: added.firstOrNull { attachments.metadata(it).mimeType.startsWith("image/") }
            records.saveDraft(target, targetDraft.revision, targetDraft.content.copy(candidate = candidate.copy(assetIds = assets, coverAssetId = cover)))
        }
        if (mutable.value.context?.sessionId == target) loadSession(target)
        if (cameraPath != null && failed == 0) media.deleteCamera(cameraPath)
        mutable.update { it.copy(notice = if (failed == 0) "已将 $imported 份附件保存到原会话" else "$imported 份附件已保存，$failed 份导入失败，请重新选择") }
    }
    internal fun selectedUris(): Map<String, Int> {
        val current = mutable.value.context ?: return emptyMap()
        val positions = current.assets.filter { it.pending }.mapIndexed { index, item -> item.id to index + 1 }.toMap()
        return sources.filterKeys { it.first == current.sessionId }.mapNotNull { (key, value) -> positions[value]?.let { key.second to it } }.toMap()
    }
    fun discard(id: String) = operation {
        val sessionId = requireNotNull(mutable.value.context).sessionId
        invalidateStoredAnalysis(sessionId)
        val current = records.context(sessionId)
        val content = current.draft.content
        if (content.candidate?.assetIds?.contains(id) == true) {
            val candidate = content.candidate.copy(assetIds = content.candidate.assetIds - id,
                coverAssetId = content.candidate.coverAssetId?.takeUnless { it == id })
            records.saveDraft(current.sessionId, current.draft.revision, content.copy(candidate = candidate))
        }
        attachments.discardPending(current.sessionId, id); loadSession(current.sessionId)
    }
    suspend fun prepareCamera(): Uri? {
        if (!preparePicker()) return null
        val prepared = media.prepareCamera()
        saved["archive_camera_session"] = saved.get<String>("archive_picker_session")
        saved["archive_camera_path"] = prepared.first
        return prepared.second
    }
    fun cameraResult(success: Boolean) {
        val target = saved.get<String>("archive_camera_session")
        val path = saved.get<String>("archive_camera_path")
        saved.remove<String>("archive_camera_session"); saved.remove<String>("archive_camera_path")
        if (success && target != null && path != null) import(target, listOf(media.cameraUri(path)), path)
        else if (path != null) viewModelScope.launch { media.deleteCamera(path) }
    }
    fun attachmentFailure() { mutable.update { it.copy(error = "附件读取失败，请重新选择") } }
    fun noViewer() { mutable.update { it.copy(error = "手机上没有能打开这类文件的应用") } }
    fun openFile(id: String, open: (Uri, String) -> Unit) = operation {
        val exported = media.exportForViewing(id)
        open(exported.first, exported.second)
    }

    private fun report(error: Exception) {
        val message = when (error) {
            is ArchiveConflict -> "档案已被其他会话更新，草稿已保留。请重新载入已保存内容后核对。"
            is ArchiveKeyUnavailable -> "无法读取本机档案密钥，请保留应用数据后排查。"
            is ArchiveIntegrityException -> "这份档案或附件未通过完整性检查，原数据已保留。"
            is ArchiveFormError -> error.message
            is IllegalArgumentException -> when {
                mutable.value.input.length > 200_000 -> "输入内容过长，请缩短到 20 万字以内"
                (mutable.value.candidate?.title?.length ?: 0) > 300 -> "档案标题最多 300 字，请缩短后保存"
                (mutable.value.candidate?.body?.length ?: 0) > 200_000 -> "正文最多 20 万字，请缩短后保存"
                else -> "部分字段超出允许长度，请缩短后重试"
            }
            else -> "本地操作未能完成，已有内容会保留，请重试。"
        }
        mutable.update { it.copy(error = message, conflict = error is ArchiveConflict) }
    }
}

private class ArchiveFormError(message: String) : IllegalArgumentException(message)
internal fun validArchiveLink(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank()
}.getOrDefault(false)
