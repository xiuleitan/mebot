package com.txl.mebot.ui.subscriptions

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class SubscriptionDeleteIntent(val record: SubscriptionEntity,
    val token: String = UUID.randomUUID().toString())
data class SubscriptionsState(
    val records: List<SubscriptionEntity> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
    val loadError: String? = null,
    val spending: SubscriptionSpending? = null,
    val spendingLoading: Boolean = true,
    val spendingError: String? = null,
    val detailId: String? = null,
    val editor: SubscriptionEditor? = null,
    val busy: Boolean = false,
    val editorError: String? = null,
    val editorSaved: Boolean = false,
    val conflict: Boolean = false,
    val deletion: SubscriptionDeleteIntent? = null,
    val deleteError: String? = null,
    val imageError: String? = null,
    val notice: String? = null,
    val endedOpen: Boolean = false,
) {
    val ongoing get() = records.filter { !it.hasEnded(today) }
        .sortedWith(compareBy(nullsLast()) { if (it.autoRenew) it.nextBillingDate else it.validUntil })
    val ended get() = records.filter { it.hasEnded(today) }.sortedByDescending { it.updatedAt }
    val detail get() = records.firstOrNull { it.id == detailId }
}

class SubscriptionsViewModel internal constructor(application: Application, private val saved: SavedStateHandle,
    private val container: com.txl.mebot.data.AppContainer, chatInterpreter: SubscriptionInterpreter? = null) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved, (application as MebotApplication).container)
    private val records = container.subscriptions
    private val conversations = container.subscriptionConversations
    private val storage = container.subscriptionStorage
    private val lifecycle = container.subscriptionLifecycle
    private val statistics = SubscriptionStatisticsRepository(storage)
    private val spending = SubscriptionSpendingRepository(storage, records)
    internal val billing = SubscriptionBillingController(viewModelScope, storage, conversations,
        SubscriptionBillingRepository(storage, records, statistics), saved)
    internal val chat = SubscriptionChatController(application, viewModelScope, saved, container,
        chatInterpreter ?: QwenSubscriptionInterpreter(container.modelSettings))
    private val mutable = MutableStateFlow(SubscriptionsState())
    val state = mutable.asStateFlow()
    private val editorMutex = Mutex()
    private var observation: Job? = null
    private var statisticsJob: Job? = null
    private var detailRequest = 0L

    init { refresh() }

    fun refresh() {
        if (observation?.isActive == true) {
            viewModelScope.launch(Dispatchers.IO) {
                try { lifecycle.reconcile(); mutable.update { it.copy(today = LocalDate.now()) }; refreshStatistics(); billing.refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(notice = "日期暂未更新，请稍后重试") } }
            }
            return
        }
        observation = viewModelScope.launch(Dispatchers.IO) {
            try {
                lifecycle.reconcile()
                records.observeAll().collect { items ->
                    mutable.update { it.copy(records = items, today = LocalDate.now(), loading = false, loadError = null) }
                    refreshStatistics(); billing.refresh()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, loadError = "订阅读取失败，请重试") } }
        }
    }

    fun refreshStatistics() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            statisticsJob?.cancel()
            val today = LocalDate.now()
            mutable.update { it.copy(spendingLoading = true, spendingError = null) }
            statisticsJob = viewModelScope.launch {
                try {
                    val value = withContext(Dispatchers.IO) { spending.summary(today) }
                    mutable.update { it.copy(spending = value, spendingLoading = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(spendingLoading = false, spendingError = "消费统计暂时无法读取") } }
            }
        }
    }

    fun openDetail(id: String) {
        detailRequest++
        mutable.update { it.copy(detailId = id, endedOpen = false, imageError = null) }
    }
    fun dismissDetail() {
        detailRequest++
        if (!mutable.value.busy) mutable.update { it.copy(detailId = null, imageError = null) }
    }
    /** 先从主库核对通知目标；冷启动尚未读出列表时也不会打开其他订阅。 */
    fun openFromReminder(id: String) {
        val request = ++detailRequest
        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { storage.records.subscription(id) }
                if (request != detailRequest) return@launch
                if (record == null) { mutable.update { it.copy(notice = "这条订阅已不存在，通知可能已经过期") }; return@launch }
                if (mutable.value.busy || billing.state.value.busy) {
                    mutable.update { it.copy(notice = "当前操作正在保存，请稍后再打开订阅通知") }; return@launch
                }
                if (!flushEditor()) return@launch
                mutable.update { it.copy(busy = false) }
                if (!billing.closeForNavigation()) return@launch
                if (request != detailRequest) return@launch
                chat.dismissHistory()
                mutable.update { page -> page.copy(records = if (page.records.none { it.id == id }) page.records + record
                    else page.records.map { if (it.id == id) record else it }, editor = null, deletion = null, endedOpen = false) }
                openDetail(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = "暂时无法打开订阅，请在列表中重试") } }
        }
    }
    fun showEnded(show: Boolean) { mutable.update { it.copy(endedOpen = show) } }
    fun consumeNotice() { mutable.update { it.copy(notice = null) } }

    fun openEdit(item: SubscriptionEntity) = openEditor(item)
    private fun openEditor(item: SubscriptionEntity, reload: Boolean = false) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, editorError = null, conflict = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backup = withContext(Dispatchers.Main.immediate) { saved.get<String>(EDITOR_BACKUP) }?.let { raw ->
                    runCatching { SubscriptionEditor.decode(JSONObject(raw)) }.getOrNull()
                }?.takeIf { it.targetId == item.id }
                val session = conversations.forSubscription(item.id)
                val draft = requireNotNull(storage.conversations.draft(session.id))
                val json = JSONObject(draft.fieldsJson)
                val retained = if (reload) null else backup?.takeIf { it.conversationId == session.id }
                    ?: if (json.has("manualEditor")) SubscriptionEditor.decode(json.getJSONObject("manualEditor")) else null
                require(retained == null || retained.conversationId == session.id && retained.targetId == item.id) { "草稿与当前订阅不一致，原内容已保留" }
                var editor = retained ?: SubscriptionEditor.from(item, session.id, LocalDate.now())
                // 操作已提交但进程在清理表单前结束时，恢复已保存记录，不能重新创建。
                if (storage.records.operation(editor.requestId)?.state == SubscriptionOperationState.APPLIED) {
                    clearEditorDraft(editor)
                    withContext(Dispatchers.Main.immediate) { saved.remove<String>(EDITOR_BACKUP) }
                    editor = SubscriptionEditor.from(item, session.id, LocalDate.now())
                }
                // 旧版草稿只有封面字段，升级后补齐已保存图库，同时保留草稿里尚未提交的封面。
                if (editor.imageAssetIds == null) editor = editor.copy(imageAssetIds =
                    (records.imageIds(item.id) + listOfNotNull(editor.coverAssetId)).distinct())
                if (editor.baselinePurchaseDate == null) {
                    val purchase = spending.initial(item.id).date.toString()
                    editor = editor.copy(purchaseDate = purchase, baselinePurchaseDate = purchase)
                }
                persistEditor(editor)
                withContext(Dispatchers.Main.immediate) { saved[EDITOR_BACKUP] = editor.json().toString() }
                mutable.update { it.copy(editor = editor, editorSaved = false, busy = false, detailId = null, endedOpen = false,
                    conflict = editor.expectedRevision != item.revision,
                    editorError = if (editor.expectedRevision != item.revision) "记录已更新，输入草稿已保留。请读取最新记录后再编辑。" else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, notice = friendly(failure, "无法打开表单，请重试")) } }
        }
    }

    fun changeEditor(change: (SubscriptionEditor) -> SubscriptionEditor) {
        if (mutable.value.busy || mutable.value.editorSaved) return
        val current = mutable.value.editor ?: return
        val next = change(current)
        mutable.update { it.copy(editor = next, editorError = null) }
        saved[EDITOR_BACKUP] = next.json().toString()
        viewModelScope.launch(Dispatchers.IO) {
            try { persistLatest(next.conversationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { if (it.editor?.conversationId == next.conversationId)
                it.copy(editorError = "草稿暂未保存，请重试；当前输入仍保留") else it } }
        }
    }

    fun dismissEditor() { mutable.update { it.copy(editor = null, editorSaved = false, busy = false, editorError = null, conflict = false) } }
    suspend fun flushEditor(): Boolean {
        if (mutable.value.editorSaved) return true
        val editor = mutable.value.editor ?: return true
        if (mutable.value.busy) return false
        mutable.update { it.copy(busy = true) }
        return try { kotlinx.coroutines.withContext(Dispatchers.IO) { persistLatest(editor.conversationId) }; true }
        catch (cancelled: CancellationException) { mutable.update { it.copy(busy = false) }; throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(busy = false, editorError = "草稿保存失败，输入仍保留，请重试") }; false }
    }

    fun reloadEditor() { mutable.value.editor?.targetId?.let { id -> mutable.value.records.firstOrNull { it.id == id }?.let { openEditor(it, true) } } }

    fun saveEditor() {
        val editor = mutable.value.editor ?: return
        if (mutable.value.busy || mutable.value.conflict || mutable.value.editorSaved) return
        mutable.update { it.copy(busy = true, editorError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                persistEditor(editor)
                val item = storage.database.withTransaction {
                    val savedRecord = lifecycle.save(editor.requestId, requireNotNull(editor.targetId), requireNotNull(editor.expectedRevision), editor.values())
                    if (editor.purchaseDate != editor.baselinePurchaseDate) {
                        val purchase = try { LocalDate.parse(editor.purchaseDate.orEmpty().trim()) }
                            catch (_: Exception) { throw IllegalArgumentException("请填写正确的首次扣费日期") }
                        spending.correctInitialDate(editor.requestId + ":purchase", savedRecord.id,
                            Math.addExact(requireNotNull(editor.expectedRevision), 1), purchase, LocalDate.now())
                    }
                    requireNotNull(records.get(savedRecord.id))
                }
                mutable.update { it.copy(editorSaved = true, busy = false, notice = "已保存 ${item.title}") }
                withContext(Dispatchers.Main.immediate) { saved.remove<String>(EDITOR_BACKUP) }
                try { clearEditorDraft(editor) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* 业务已提交，重开表单由操作回执去重。 */ }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, editorError = friendly(failure, "保存失败，输入已保留，请重试"),
                conflict = failure is SubscriptionConflict) } }
        }
    }

    fun importImages(requestId: String, uris: List<Uri>) {
        val editor = mutable.value.editor ?: return
        if (uris.isEmpty() || mutable.value.busy || mutable.value.editorSaved || editor.requestId != requestId) return
        val conversationId = editor.conversationId
        mutable.update { it.copy(busy = true, editorError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (uri in uris.distinct()) {
                    editorMutex.withLock {
                        var imported: SubscriptionEditor? = null
                        container.subscriptionAttachments.importUri(conversationId, uri, asPending = false) { asset ->
                            val current = requireNotNull(mutable.value.editor?.takeIf { it.requestId == requestId })
                            val next = current.copy(coverAssetId = current.coverAssetId ?: asset.id,
                                imageAssetIds = (current.imageAssetIds.orEmpty() + asset.id).distinct())
                            // 图片、会话关联和表单草稿同一事务提交，进程中断不会丢掉刚添加的图片。
                            persistUnlocked(next)
                            imported = next
                        }
                        val next = requireNotNull(imported)
                        mutable.update { if (it.editor?.requestId == requestId) it.copy(editor = next) else it }
                        withContext(Dispatchers.Main.immediate) { saved[EDITOR_BACKUP] = next.json().toString() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(editorError = "部分图片未能导入，已添加的图片和输入已保留，请重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun observeImages(id: String) = records.observeImageIds(id)

    /** 图库动作立即写入主库，不经过日期或付款维护；旧版本冲突不会覆盖其他编辑。 */
    fun changeImage(card: SubscriptionEntity, assetId: String, remove: Boolean) {
        if (mutable.value.busy || mutable.value.detailId != card.id) return
        mutable.update { it.copy(busy = true, imageError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val images = records.imageIds(card.id)
                require(assetId in images) { "图片已不存在，请重新打开详情" }
                val remaining = if (remove) images - assetId else images
                val cover = if (remove) card.coverAssetId?.takeIf { it in remaining } ?: remaining.firstOrNull() else assetId
                val item = records.update(UUID.randomUUID().toString(), card.id, card.revision,
                    SubscriptionValues.from(card).copy(coverAssetId = cover, imageAssetIds = remaining))
                mutable.update { page -> page.copy(records = page.records.map { if (it.id == item.id) item else it }, busy = false) }
                try { container.media.cleanPendingDeletions() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 图库已保存；原图清理队列留给后续维护重试。 */ }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, imageError = friendly(failure, "图片修改失败，请重试")) } }
        }
    }
    suspend fun imageFile(assetId: String) = container.media.fileFor(assetId)

    fun requestDelete(item: SubscriptionEntity) { mutable.update { it.copy(deletion = SubscriptionDeleteIntent(item), deleteError = null) } }
    fun dismissDelete() { if (!mutable.value.busy) mutable.update { it.copy(deletion = null, deleteError = null) } }
    fun performDelete() {
        val intent = mutable.value.deletion ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, deleteError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                lifecycle.delete(intent.token, intent.record.id, intent.record.revision)
                mutable.update { it.copy(busy = false, deletion = null, detailId = null, notice = "已删除订阅") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, deleteError = friendly(failure, "删除失败，请重试")) } }
        }
    }

    private suspend fun persistLatest(id: String) = editorMutex.withLock {
        if (!mutable.value.editorSaved) mutable.value.editor?.takeIf { it.conversationId == id }?.let { persistUnlocked(it) }
    }
    private suspend fun persistEditor(editor: SubscriptionEditor) = editorMutex.withLock { persistUnlocked(editor) }
    private suspend fun persistUnlocked(editor: SubscriptionEditor) {
        repeat(3) { attempt ->
            val draft = requireNotNull(storage.conversations.draft(editor.conversationId))
            val json = JSONObject(draft.fieldsJson).put("manualEditor", editor.json())
            try { conversations.saveDraft(editor.conversationId, draft.inputText, json.toString(), draft.revision); return }
            catch (conflict: SubscriptionDraftConflict) { if (attempt == 2) throw conflict }
        }
    }
    private suspend fun clearEditorDraft(editor: SubscriptionEditor) = editorMutex.withLock {
        val draft = requireNotNull(storage.conversations.draft(editor.conversationId))
        if (JSONObject(draft.fieldsJson).optJSONObject("manualEditor")?.optString("requestId") != editor.requestId) return@withLock
        val json = JSONObject(draft.fieldsJson).apply { remove("manualEditor") }
        conversations.saveDraft(editor.conversationId, draft.inputText, json.toString(), draft.revision)
    }
    private fun friendly(failure: Exception, fallback: String) = when (failure) {
        is SubscriptionConflict -> "记录已改变，请读取最新记录。当前输入已保留。"
        is IllegalArgumentException -> failure.message ?: fallback
        else -> fallback
    }
    private companion object { const val EDITOR_BACKUP = "subscription_manual_editor" }
}
