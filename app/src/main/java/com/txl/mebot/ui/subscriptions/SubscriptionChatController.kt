package com.txl.mebot.ui.subscriptions

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class SubscriptionChatState(val sessionId: String? = null, val input: String = "", val loading: Boolean = true,
    val draftRevision: Long = 0,
    val loadError: Boolean = false, val sending: Boolean = false, val analyzing: Boolean = false, val confirming: Boolean = false,
    val importing: Boolean = false, val error: String? = null, val inputError: Boolean = false,
    val record: SubscriptionEntity? = null, val messages: List<SubscriptionMessageEntity> = emptyList(),
    val media: List<SubscriptionLocalAttachment> = emptyList(), val messageMedia: Map<String, List<SubscriptionMessageMediaEntity>> = emptyMap(),
    val proposal: SubscriptionChatProposal? = null, val analysisId: String? = null, val failure: SubscriptionAnalysisFailure? = null,
    val hasOlder: Boolean = false, val olderLoading: Boolean = false,
    val historyOpen: Boolean = false, val chats: List<SubscriptionChatRow> = emptyList(), val historyLoading: Boolean = false, val historyMore: Boolean = false,
    val selectedSources: Map<String, String> = emptyMap()) {
    val pending get() = media.filter { it.link.pendingSend }
    val busy get() = loading || sending || confirming || importing
}
private data class SubscriptionChatInput(val id: String, val text: String, val token: String = UUID.randomUUID().toString())

internal class SubscriptionChatController(private val app: Application, private val scope: CoroutineScope,
    private val saved: SavedStateHandle, private val container: AppContainer, interpreter: SubscriptionInterpreter) {
    private val storage = container.subscriptionStorage
    private val conversations = container.subscriptionConversations
    private val attachments = container.subscriptionAttachments
    private val dao = storage.conversations
    private val coordinator = SubscriptionChatCoordinator(storage, container.subscriptions, conversations, container.subscriptionLifecycle, interpreter, container.media::fileFor)
    private val mutable = MutableStateFlow(SubscriptionChatState())
    val state = mutable.asStateFlow()
    private val pendingInput = MutableStateFlow<SubscriptionChatInput?>(null)
    private val inputMutex = Mutex()
    private val inputRevisions = mutableMapOf<String, Long>()
    private var inputJob: Job? = null
    private var contentJob: Job? = null
    private var analysisJob: Job? = null
    private var switchJob: Job? = null

    init { openCreation() }

    fun openCreation(forceNew: Boolean = false) {
        val retainedId = saved.get<String>(SESSION)
        switch {
            if (forceNew) conversations.newConversation() else {
                val retained = retainedId?.let { dao.conversation(it) }?.takeIf { it.subscriptionId == null } ?: dao.latestCreationChat()
                retained ?: conversations.newConversation()
            }
        }
    }
    fun openSubscription(id: String) = switch { conversations.forSubscription(id) }
    fun openSession(id: String) = switch { requireNotNull(dao.conversation(id)) }
    internal suspend fun acceptHomeMessage(id: String, messageId: String) {
        state.first { !it.loading && !it.busy }
        openSession(id)
        val page = state.first { it.loadError || it.sessionId == id && !it.loading && it.messages.isNotEmpty() }
        check(!page.loadError) { "会话暂未读取成功" }
        if (!page.analyzing && page.messages.none { it.role == MessageRole.ASSISTANT }) analyze(id, messageId)
    }

    private fun switch(find: suspend () -> SubscriptionConversationEntity) {
        if (mutable.value.sending || mutable.value.confirming || mutable.value.importing || switchJob?.isActive == true) return
        mutable.update { it.copy(loading = true, loadError = false, error = null, historyOpen = false) }
        switchJob = scope.launch {
            try {
                if (!saveInputNow()) { mutable.update { it.copy(loading = false) }; return@launch }
                val session = withContext(Dispatchers.IO) { find() }
                if (mutable.value.sessionId == session.id && contentJob?.isActive == true) {
                    mutable.update { it.copy(loading = false) }; return@launch
                }
                if (mutable.value.sessionId != session.id) {
                    analysisJob?.cancelAndJoin()
                    contentJob?.cancel()
                }
                saved[SESSION] = session.id
                val backup = saved.get<String>(INPUT)?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optString("id") == session.id }
                if (backup != null) withContext(Dispatchers.IO) {
                    storage.database.withTransaction {
                        val draft = requireNotNull(dao.draft(session.id))
                        val fields = JSONObject(draft.fieldsJson)
                        if (fields.optString("chatLastSentToken") != backup.getString("token")) {
                            fields.put("chatInputToken", backup.getString("token"))
                            conversations.saveDraft(session.id, backup.getString("text"), fields.toString(), draft.revision)
                        }
                    }
                }
                saved.remove<String>(INPUT)
                mutable.update { SubscriptionChatState(sessionId = session.id, loading = true, importing = it.importing) }
                observe(session.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, loadError = true, error = "会话暂时无法读取，请重试") } }
        }
    }
    private fun observe(id: String) {
        contentJob?.cancel()
        contentJob = scope.launch {
            try {
                combine(dao.observeConversation(id), dao.observeDraft(id), dao.observeMessages(id, 50), attachments.observe(id), dao.observeChatRecord(id))
                    { _, _, _, _, _ -> Unit }.conflate().collectLatest {
                        val snapshot = withContext(Dispatchers.IO) { snapshot(id) }
                        mutable.update { old ->
                            if (old.sessionId != id) old else {
                                val messages = (old.messages + snapshot.messages).distinctBy { it.id }.sortedBy { it.sequence }
                                val input = pendingInput.value?.takeIf { it.id == id }?.text
                                    ?: if (snapshot.draftRevision < (inputRevisions[id] ?: 0)) old.input else snapshot.input
                                old.copy(input = input, loading = false, loadError = false, record = snapshot.record, messages = messages,
                                    media = snapshot.media, messageMedia = old.messageMedia + snapshot.messageMedia, proposal = snapshot.proposal,
                                    analysisId = snapshot.analysisId, failure = snapshot.failure, draftRevision = snapshot.draftRevision,
                                    hasOlder = messages.firstOrNull()?.sequence?.let { it > 1 } == true)
                            }
                        }
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { if (it.sessionId == id) it.copy(loading = false, loadError = true, error = "会话读取失败，请重试") else it } }
        }
    }
    private suspend fun snapshot(id: String): SubscriptionChatState = storage.database.withTransaction {
        val draft = requireNotNull(dao.draft(id))
        val session = requireNotNull(dao.conversation(id))
        val meta = SubscriptionChatMeta.read(draft.fieldsJson)
        val messages = dao.messages(id, 50).asReversed()
        val media = dao.mediaLinks(id).map { SubscriptionLocalAttachment(requireNotNull(storage.media.asset(it.assetId)), it) }
        val proposal = meta.proposalId?.let { storage.records.operation(it) }?.takeIf { it.state == SubscriptionOperationState.PENDING }
            ?.let { SubscriptionChatProposal(it, SubscriptionValues.decode(JSONObject(it.payloadJson))) }
        SubscriptionChatState(id, draft.inputText, draftRevision = draft.revision, record = session.subscriptionId?.let { container.subscriptions.get(it) }, messages = messages,
            media = media, messageMedia = dao.messageMediaFor(id, messages.map { it.id }).groupBy { it.messageId },
            proposal = proposal, analysisId = meta.analysisId,
            failure = if (meta.status == "pending" && analysisJob?.isActive != true) SubscriptionAnalysisFailure.INTERRUPTED else meta.failure)
    }
    fun changeInput(text: String) {
        val page = mutable.value
        val id = page.sessionId ?: return
        if (page.busy || text.length > 5_000) return
        val input = SubscriptionChatInput(id, text)
        pendingInput.value = input
        saved[INPUT] = JSONObject().put("id", id).put("text", text).put("token", input.token).toString()
        mutable.update { it.copy(input = text, inputError = false) }
        inputJob?.cancel()
        inputJob = scope.launch { delay(180); saveInputNow() }
    }
    fun saveInput() { scope.launch { saveInputNow() } }
    private suspend fun saveInputNow(): Boolean = inputMutex.withLock {
        val input = pendingInput.value ?: return@withLock true
        try {
            val revision = withContext(Dispatchers.IO) { storage.database.withTransaction {
                val draft = requireNotNull(dao.draft(input.id))
                val fields = JSONObject(draft.fieldsJson).put("chatInputToken", input.token)
                conversations.saveDraft(input.id, input.text, fields.toString(), draft.revision).revision
            } }
            inputRevisions[input.id] = revision
            if (pendingInput.compareAndSet(input, null)) saved.remove<String>(INPUT)
            mutable.update { it.copy(inputError = false, error = if (it.inputError) null else it.error) }; true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(inputError = true, error = "输入暂未保存，请重试") }; false }
    }
    fun send() {
        val page = mutable.value
        val id = page.sessionId ?: return
        if (page.busy || page.analyzing || page.input.isBlank() && page.pending.isEmpty()) return
        mutable.update { it.copy(sending = true, error = null, failure = null) }
        scope.launch {
            try {
                if (!saveInputNow()) return@launch
                val (needsAnalysis, revision) = withContext(Dispatchers.IO) { storage.database.withTransaction {
                    var draft = requireNotNull(dao.draft(id))
                    var fields = JSONObject(draft.fieldsJson)
                    val token = fields.optString("chatInputToken").ifBlank { UUID.randomUUID().toString() }
                    if (!fields.has("chatInputToken")) {
                        fields.put("chatInputToken", token)
                        draft = conversations.saveDraft(id, draft.inputText, fields.toString(), draft.revision)
                    }
                    val analyze = coordinator.send(id, "subscription-user:$token", page.input, page.pending.map { it.asset.id }, draft.revision, token)
                    analyze to requireNotNull(dao.draft(id)).revision
                } }
                inputRevisions[id] = revision
                saved.remove<String>(INPUT)
                mutable.update { if (it.sessionId == id) it.copy(input = "", media = it.media.map { media -> media.copy(link = media.link.copy(pendingSend = false)) }) else it }
                if (needsAnalysis) {
                    val messageId = withContext(Dispatchers.IO) { SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson).analysisId }
                    if (messageId != null) analyze(id, messageId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = friendly(failure, "发送失败，输入和截图已保留，请重试")) } }
            finally { mutable.update { it.copy(sending = false) } }
        }
    }
    private fun analyze(id: String, messageId: String) {
        analysisJob?.cancel()
        mutable.update { it.copy(analyzing = true, failure = null, error = null) }
        analysisJob = scope.launch {
            try { withContext(Dispatchers.IO) { coordinator.analyze(id, messageId) } }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { coordinator.fail(id, messageId, SubscriptionAnalysisFailure.INTERRUPTED) } }
                throw cancelled
            } catch (failure: Exception) {
                val kind = (failure as? SubscriptionAnalysisException)?.failure ?: SubscriptionAnalysisFailure.LOCAL_SAVE
                withContext(Dispatchers.IO) { runCatching { coordinator.fail(id, messageId, kind) } }
                mutable.update { if (it.sessionId == id) it.copy(failure = kind) else it }
            } finally { mutable.update { if (it.sessionId == id) it.copy(analyzing = false) else it } }
        }
    }
    fun retry() {
        val page = mutable.value
        val id = page.sessionId
        if (page.loadError || id == null) { openCreation(); return }
        if (page.busy || page.analyzing) return
        mutable.update { it.copy(sending = true, error = null) }
        scope.launch {
            try {
                val messageId = withContext(Dispatchers.IO) { coordinator.retry(id) }
                if (messageId != null) analyze(id, messageId) else observe(id)
            } catch (_: Exception) { mutable.update { it.copy(error = "暂时无法重试，原会话仍保留") } }
            finally { mutable.update { it.copy(sending = false) } }
        }
    }
    fun confirm() {
        val page = mutable.value
        val id = page.sessionId ?: return
        val proposal = page.proposal ?: return
        if (page.busy || page.analyzing) return
        if (page.input.isNotBlank() || page.pending.isNotEmpty()) {
            mutable.update { it.copy(error = "请先发送当前输入，再确认最新信息") }; return
        }
        mutable.update { it.copy(confirming = true, error = null) }
        scope.launch {
            try { withContext(Dispatchers.IO) { coordinator.confirm(id, proposal.operation.id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = friendly(failure, "保存失败，待确认内容已保留，请重试")) } }
            finally { mutable.update { it.copy(confirming = false) } }
        }
    }
    fun older() {
        val page = mutable.value
        val id = page.sessionId ?: return
        val before = page.messages.firstOrNull()?.sequence ?: return
        if (page.olderLoading) return
        mutable.update { it.copy(olderLoading = true) }
        scope.launch {
            try {
                val (messages, links) = withContext(Dispatchers.IO) { storage.database.withTransaction {
                    val messages = dao.messagesBefore(id, before, 50)
                    messages to dao.messageMediaFor(id, messages.map { it.id }).groupBy { it.messageId }
                } }
                mutable.update { if (it.sessionId == id) it.copy(messages = (messages + it.messages).distinctBy { it.id }.sortedBy { it.sequence },
                    messageMedia = links + it.messageMedia, hasOlder = messages.lastOrNull()?.sequence?.let { sequence -> sequence > 1 } == true) else it }
            } catch (_: Exception) { mutable.update { it.copy(error = "更早的消息读取失败，请重试") } }
            finally { mutable.update { it.copy(olderLoading = false) } }
        }
    }
    fun history(more: Boolean = false) {
        val page = mutable.value
        if (page.historyLoading) return
        mutable.update { it.copy(historyOpen = true, historyLoading = true) }
        scope.launch {
            try {
                val old = if (more) page.chats else emptyList()
                val rows = withContext(Dispatchers.IO) { dao.chatRows(21, old.size) }
                mutable.update { it.copy(chats = old + rows.take(20), historyMore = rows.size > 20) }
            } catch (_: Exception) { mutable.update { it.copy(error = "会话记录读取失败，请重试") } }
            finally { mutable.update { it.copy(historyLoading = false) } }
        }
    }
    fun dismissHistory() { mutable.update { it.copy(historyOpen = false) } }
    fun preparePicker(): Boolean {
        val page = mutable.value
        if (page.busy || page.analyzing || page.sessionId == null) return false
        saved[PICKER] = page.sessionId; return true
    }
    fun picked(uris: List<Uri>, capture: File? = null) {
        val target = saved.remove<String>(PICKER) ?: return
        if (uris.isEmpty()) { capture?.delete(); return }
        import(target, uris, capture)
    }
    private fun import(id: String, uris: List<Uri>, capture: File? = null) {
        if (mutable.value.importing) return
        mutable.update { it.copy(importing = true, error = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val current = dao.mediaLinks(id).count { it.pendingSend }
                    require(current + uris.distinct().size <= 4) { "每次最多发送4张截图，请先发送当前图片" }
                    for (uri in uris.distinct()) {
                        val asset = attachments.importUri(id, uri)
                        mutable.update { if (it.sessionId == id) it.copy(selectedSources = it.selectedSources + (uri.toString() to asset.id)) else it }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = friendly(failure, "图片导入失败，请重新选择订阅截图")) } }
            finally { withContext(NonCancellable + Dispatchers.IO) { capture?.delete() }; mutable.update { it.copy(importing = false) } }
        }
    }
    fun pickLocal(uri: Uri) {
        val page = mutable.value
        val selected = page.selectedSources[uri.toString()]?.takeIf { asset -> page.pending.any { it.asset.id == asset } }
        if (selected != null) discard(selected) else if (preparePicker()) picked(listOf(uri))
    }
    fun discard(assetId: String) {
        val page = mutable.value
        val id = page.sessionId ?: return
        if (page.busy || page.analyzing) return
        mutable.update { it.copy(importing = true, error = null) }
        scope.launch {
            try { withContext(Dispatchers.IO) { attachments.discardPending(id, assetId); container.media.cleanPendingDeletions() } }
            catch (_: Exception) { mutable.update { it.copy(error = "图片移除失败，请重试") } }
            finally { mutable.update { it.copy(importing = false) } }
        }
    }
    suspend fun prepareCamera(): Uri? {
        if (!preparePicker()) return null
        val file = withContext(Dispatchers.IO) {
            val directory = File(app.filesDir, "media/originals/capture-staging").apply { mkdirs() }
            File(directory, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
        }
        saved[CAMERA] = file.name
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file)
    }
    fun cameraResult(success: Boolean) {
        val name = saved.remove<String>(CAMERA) ?: run { picked(emptyList()); return }
        if (!Regex("[a-f0-9-]+\\.jpg").matches(name)) { picked(emptyList()); return }
        val file = File(app.filesDir, "media/originals/capture-staging/$name")
        if (success && file.isFile) picked(listOf(FileProvider.getUriForFile(app, "${app.packageName}.attachments", file)), file)
        else { picked(emptyList()); scope.launch(Dispatchers.IO) { file.delete() } }
    }
    fun reportPickerFailure() { mutable.update { it.copy(error = "无法读取图片，请重新选择") } }
    private fun friendly(failure: Exception, fallback: String) = when (failure) {
        is SubscriptionConflict, is SubscriptionDraftConflict -> "记录已改变，原输入已保留。请继续在聊天中说明需要的修改，再确认最新内容。"
        is IllegalArgumentException -> failure.message ?: fallback
        else -> fallback
    }
    private companion object { const val SESSION = "subscription_chat_session"; const val INPUT = "subscription_chat_input"; const val PICKER = "subscription_chat_picker"; const val CAMERA = "subscription_chat_camera" }
}
