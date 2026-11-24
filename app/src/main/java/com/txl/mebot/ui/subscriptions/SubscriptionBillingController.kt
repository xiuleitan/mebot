package com.txl.mebot.ui.subscriptions

import androidx.lifecycle.SavedStateHandle
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class SubscriptionVoidIntent(val payment: SubscriptionPaymentEntity, val revision: Long,
    val requestId: String = UUID.randomUUID().toString())
internal data class SubscriptionBillingState(val id: String? = null, val month: YearMonth = YearMonth.now(),
    val ledgerOpen: Boolean = false,
    val data: SubscriptionBillingMonth? = null, val loading: Boolean = false, val error: String? = null,
    val editor: SubscriptionPaymentEditor? = null, val busy: Boolean = false, val saved: Boolean = false,
    val editorError: String? = null, val conflict: Boolean = false, val voidIntent: SubscriptionVoidIntent? = null,
    val notice: String? = null)

/** 扣费表单和账单属于同一订阅，复用其 Room 草稿，不影响聊天输入和订阅编辑表单。 */
internal class SubscriptionBillingController(private val scope: CoroutineScope, private val storage: SubscriptionStorage,
    private val conversations: SubscriptionConversationRepository, private val repository: SubscriptionBillingRepository,
    private val savedState: SavedStateHandle) {
    private val mutable = MutableStateFlow(SubscriptionBillingState())
    val state = mutable.asStateFlow()
    private var load: Job? = null
    private val drafts = Mutex()

    fun open(id: String, month: YearMonth = YearMonth.from(repository.today())) {
        mutable.update { it.copy(id = id, month = month, data = null, error = null, ledgerOpen = true) }
        refresh()
    }
    fun hideLedger() { load?.cancel(); mutable.update { it.copy(ledgerOpen = false, loading = false) } }
    suspend fun closeForNavigation(): Boolean {
        if (!flush()) return false
        load?.cancel()
        // 未提交的内容已进入原订阅草稿；跳转不会带到通知对应的另一项订阅。
        mutable.update { SubscriptionBillingState() }
        return true
    }
    fun moveMonth(delta: Long) {
        if (mutable.value.busy) return
        val month = mutable.value.month.plusMonths(delta)
        if (month.year !in 1900..9998) return
        mutable.update { it.copy(month = month, data = null) }; refresh()
    }
    fun more() { if (!mutable.value.loading && mutable.value.data?.hasMore == true) loadPage(true) }
    fun refresh() = loadPage(false)
    private fun loadPage(append: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            val page = mutable.value
            val id = page.id ?: return@launch
            if (!page.ledgerOpen && page.editor == null) return@launch
            load?.cancel()
            mutable.update { it.copy(loading = true, error = null) }
            load = scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        val old = page.data?.takeIf { append }
                        val next = repository.month(id, page.month, offset = old?.payments?.size ?: 0)
                        if (old == null) next else if (old.record.revision == next.record.revision) next.copy(payments = old.payments + next.payments)
                            else repository.month(id, page.month)
                    }
                    mutable.update { if (it.id == id && it.month == page.month) it.copy(data = data, loading = false) else it }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(loading = false, error = "扣费记录读取失败，请重试") } }
            }
        }
    }
    fun consumeNotice() { mutable.update { it.copy(notice = null) } }
    fun openPayment(plan: SubscriptionPlannedCharge? = null, paymentId: String? = null) {
        val page = mutable.value
        val id = page.id ?: return
        if (page.busy || page.editor != null) return
        mutable.update { it.copy(busy = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val item = requireNotNull(storage.records.subscription(id))
                val payment = if (paymentId != null) requireNotNull(storage.records.payment(paymentId))
                    else plan?.let { storage.records.paymentForEvent(id, it.eventId) }
                require(payment == null || payment.subscriptionId == id)
                val key = payment?.let { "payment:${it.id}" } ?: plan?.let { "event:${it.eventId}" } ?: "extra"
                val session = conversations.forSubscription(id)
                val backup = withContext(Dispatchers.Main.immediate) { savedState.get<String>(BACKUP) }?.let {
                    runCatching { SubscriptionPaymentEditor.decode(JSONObject(it)) }.getOrNull()
                }?.takeIf { it.subscriptionId == id && it.key == key }
                val root = JSONObject(requireNotNull(storage.conversations.draft(session.id)).fieldsJson)
                val retained = backup ?: root.optJSONObject("paymentEditors")?.optJSONObject(key)?.let(SubscriptionPaymentEditor::decode)
                require(retained == null || retained.subscriptionId == id && retained.conversationId == session.id && retained.key == key)
                val currency = payment?.currencyCode ?: plan?.currencyCode ?: item.currencyCode
                val amount = payment?.amountMinor ?: plan?.amountMinor ?: item.amountMinor
                val editor = retained ?: SubscriptionPaymentEditor(id, session.id, key, item.revision, paymentId = payment?.id,
                    paymentOperationId = payment?.operationId, plan = plan,
                    amount = SubscriptionMoney.editable(amount, currency), currency = currency,
                    date = (payment?.paidOn ?: plan?.date?.coerceAtMost(repository.today()) ?: repository.today()).toString(), notes = payment?.notes.orEmpty())
                if (storage.records.operation(editor.requestId)?.state == SubscriptionOperationState.APPLIED) {
                    clear(editor)
                    withContext(Dispatchers.Main.immediate) { savedState.remove<String>(BACKUP) }
                    mutable.update { it.copy(busy = false, ledgerOpen = true, notice = "这笔扣费已保存") }; refresh()
                } else {
                    persist(editor)
                    withContext(Dispatchers.Main.immediate) { savedState[BACKUP] = editor.json().toString() }
                    val conflict = editor.expectedRevision != item.revision || editor.paymentOperationId != payment?.operationId
                    mutable.update { it.copy(editor = editor, busy = false, saved = false, conflict = conflict,
                        editorError = if (conflict) "记录已更新，草稿已保留。请读取最新记录后再确认。" else null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, ledgerOpen = true, error = friendly(failure, "无法打开扣费表单")) } }
        }
    }
    fun change(change: (SubscriptionPaymentEditor) -> SubscriptionPaymentEditor) {
        val page = mutable.value
        if (page.busy || page.saved) return
        val editor = page.editor?.let(change) ?: return
        mutable.update { it.copy(editor = editor, editorError = null) }
        savedState[BACKUP] = editor.json().toString()
        scope.launch(Dispatchers.IO) {
            try { persistLatest(editor.requestId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(editorError = "草稿暂未保存，输入仍保留，请重试") } }
        }
    }
    fun dismissEditor() { mutable.update { it.copy(editor = null, saved = false, busy = false, conflict = false, editorError = null, ledgerOpen = true) }; refresh() }
    suspend fun flush(): Boolean {
        val page = mutable.value
        if (page.saved || page.editor == null) return true
        if (page.busy) return false
        mutable.update { it.copy(busy = true) }
        return try { withContext(Dispatchers.IO) { persistLatest(page.editor.requestId) }; true }
        catch (cancelled: CancellationException) { mutable.update { it.copy(busy = false) }; throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(busy = false, editorError = "草稿保存失败，请重试") }; false }
    }
    fun reloadEditor() {
        val editor = mutable.value.editor ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val item = requireNotNull(storage.records.subscription(editor.subscriptionId))
                val payment = editor.paymentId?.let { requireNotNull(storage.records.payment(it)) }
                val next = editor.copy(expectedRevision = item.revision, paymentOperationId = payment?.operationId, requestId = UUID.randomUUID().toString())
                persist(next)
                withContext(Dispatchers.Main.immediate) { savedState[BACKUP] = next.json().toString() }
                mutable.update { it.copy(editor = next, busy = false, conflict = false, editorError = "已读取最新记录，请核对输入后确认") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(busy = false, editorError = "读取失败，草稿已保留") } }
        }
    }
    fun save() {
        val page = mutable.value
        val editor = page.editor ?: return
        if (page.busy || page.saved || page.conflict) return
        mutable.update { it.copy(busy = true, editorError = null) }
        scope.launch(Dispatchers.IO) {
            try {
                persist(editor)
                val values = editor.values()
                if (editor.paymentId == null) repository.record(editor.requestId, editor.subscriptionId, editor.expectedRevision, editor.plan, values)
                else {
                    val payment = requireNotNull(storage.records.payment(editor.paymentId)).copy(operationId = requireNotNull(editor.paymentOperationId))
                    repository.correct(editor.requestId, editor.subscriptionId, editor.expectedRevision, payment, values)
                }
                mutable.update { it.copy(busy = false, saved = true, notice = "实际扣费已确认") }
                withContext(Dispatchers.Main.immediate) { savedState.remove<String>(BACKUP) }
                try { clear(editor) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* 操作回执负责重试去重。 */ }
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, conflict = failure is SubscriptionConflict,
                editorError = friendly(failure, "保存失败，输入已保留")) } }
        }
    }
    fun requestVoid(payment: SubscriptionPaymentEntity) {
        val data = mutable.value.data ?: return
        mutable.update { it.copy(voidIntent = SubscriptionVoidIntent(payment, data.record.revision), error = null) }
    }
    fun dismissVoid() { if (!mutable.value.busy) mutable.update { it.copy(voidIntent = null) } }
    fun void() {
        val intent = mutable.value.voidIntent ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                repository.void(intent.requestId, intent.payment.subscriptionId, intent.revision, intent.payment)
                mutable.update { it.copy(busy = false, voidIntent = null, notice = "已撤销确认，原记录与变更历史保留") }; refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(busy = false, error = friendly(failure, "撤销失败，请重试")) } }
        }
    }
    private suspend fun persistLatest(request: String) = drafts.withLock {
        if (!mutable.value.saved) mutable.value.editor?.takeIf { it.requestId == request }?.let { merge(it, false) }
    }
    private suspend fun persist(editor: SubscriptionPaymentEditor) = drafts.withLock { merge(editor, false) }
    private suspend fun clear(editor: SubscriptionPaymentEditor) = drafts.withLock { merge(editor, true) }
    private suspend fun merge(editor: SubscriptionPaymentEditor, remove: Boolean) {
        repeat(3) { attempt ->
            val draft = requireNotNull(storage.conversations.draft(editor.conversationId))
            val root = JSONObject(draft.fieldsJson)
            val forms = root.optJSONObject("paymentEditors") ?: JSONObject()
            if (remove) {
                if (forms.optJSONObject(editor.key)?.optString("requestId") != editor.requestId) return
                forms.remove(editor.key)
            } else forms.put(editor.key, editor.json())
            if (forms.length() == 0) root.remove("paymentEditors") else root.put("paymentEditors", forms)
            try { conversations.saveDraft(editor.conversationId, draft.inputText, root.toString(), draft.revision); return }
            catch (conflict: SubscriptionDraftConflict) { if (attempt == 2) throw conflict }
        }
    }
    private fun friendly(failure: Exception, fallback: String) = when (failure) {
        is SubscriptionConflict -> "记录已改变，请读取最新记录后再核对。输入已保留。"
        is IllegalArgumentException -> failure.message ?: fallback
        else -> fallback
    }
    private companion object { const val BACKUP = "subscription_payment_editor" }
}
