package com.txl.mebot.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.home.HomeReflectionRepository
import com.txl.mebot.data.local.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class ReflectionTextEditor(val text: String, val original: HomeReflectionHistoryEntity? = null)
data class ReflectionPhotoRequest(val token: String, val original: HomeReflectionHistoryEntity? = null)
data class HomeReflectionState(val record: HomeReflectionRecord = HomeReflectionRecord(), val loaded: Boolean = false,
    val loadError: String? = null, val editing: Boolean = false, val token: String? = null,
    val busy: Boolean = false, val error: String? = null, val history: List<HomeReflectionHistoryRecord> = emptyList(),
    val historyHasMore: Boolean = false, val historyLoading: Boolean = false, val historyFailed: Boolean = false,
    val textEditor: ReflectionTextEditor? = null, val photoRequest: ReflectionPhotoRequest? = null,
    val deletion: HomeReflectionHistoryEntity? = null, val savedEvent: Long = 0, val closeRequested: Boolean = false)

/** 同一弹层展示当前内容和历史；文字草稿与已保存记录分开，历史编辑不会自动切换首页。 */
class HomeReflectionViewModel internal constructor(app: Application, private val saved: SavedStateHandle,
    private val repository: HomeReflectionRepository) : AndroidViewModel(app) {
    constructor(app: Application, saved: SavedStateHandle) : this(app, saved, (app as MebotApplication).container.homeReflection)
    private val mutable = MutableStateFlow(HomeReflectionState())
    val state = mutable.asStateFlow()
    private val writes = Mutex()
    private var observation: Job? = null
    private var historyLoad: Job? = null
    init { observe() }
    private fun observe() {
        observation?.cancel()
        observation = viewModelScope.launch {
            try { repository.observe().collect { record ->
                if (!mutable.value.loaded) {
                    val draft = saved.get<String>(DRAFT)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    // 升级前尚未保存的文字输入仍可继续编辑，不能随界面改版丢掉。
                    val legacy = saved.get<String>(LEGACY_INPUT)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val photo = saved.get<String>(PHOTO)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    mutable.value = HomeReflectionState(record, loaded = true, editing = saved[OPEN] ?: false,
                        token = saved[TOKEN], textEditor = draft?.let { ReflectionTextEditor(it.getString("text"), decodeEntry(it.optJSONObject("original"))) }
                            ?: legacy?.let { ReflectionTextEditor(it.getString("text")) },
                        photoRequest = photo?.let { ReflectionPhotoRequest(it.getString("token"), decodeEntry(it.optJSONObject("original"))) })
                    if (mutable.value.editing) refreshHistory()
                } else publish(record)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loadError = "寄语暂时无法读取，点击重试") } }
        }
    }
    private fun publish(record: HomeReflectionRecord) {
        mutable.update { if (record.content.revision >= it.record.content.revision) it.copy(record = record, loadError = null) else it }
    }
    fun open() {
        if (mutable.value.loadError != null) { observe(); return }
        if (!mutable.value.loaded || mutable.value.editing || mutable.value.busy) return
        val token = UUID.randomUUID().toString()
        mutable.update { it.copy(editing = true, token = token, error = null, deletion = null, closeRequested = false) }
        saved[OPEN] = true; saved[TOKEN] = token
        refreshHistory()
    }
    fun dismiss() {
        if (mutable.value.busy) return
        historyLoad?.cancel()
        mutable.update { it.copy(editing = false, token = null, deletion = null, photoRequest = null, historyLoading = false) }
        saved[OPEN] = false; saved.remove<String>(TOKEN); saved.remove<String>(PHOTO)
    }
    fun refreshHistory() = loadHistory(reset = true)
    fun moreHistory() = loadHistory(reset = false)
    private fun loadHistory(reset: Boolean) {
        val before = mutable.value
        if (!before.editing || before.busy || before.historyLoading || (!reset && !before.historyHasMore)) return
        mutable.update { it.copy(historyLoading = true, error = null) }
        historyLoad = viewModelScope.launch {
            try {
                val (record, page) = withContext(Dispatchers.IO) { writes.withLock {
                    if (reset) repository.rememberCurrent()
                    repository.libraryPage(PAGE_SIZE + 1, if (reset) 0 else before.history.size)
                } }
                if (mutable.value.editing && mutable.value.token == before.token) {
                    publish(record)
                    mutable.update { it.copy(history = (if (reset) emptyList() else it.history) + page.take(PAGE_SIZE),
                        historyHasMore = page.size > PAGE_SIZE, historyFailed = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(historyFailed = true, error = "历史记录暂时无法读取，请重试") } }
            finally { mutable.update { if (it.token == before.token) it.copy(historyLoading = false) else it } }
        }
    }
    private fun available() = mutable.value.run { editing && loaded && !busy && !historyLoading && !historyFailed && !closeRequested }
    fun editText(original: HomeReflectionHistoryEntity? = null) {
        if (!available() || original?.mode == HomeReflectionMode.IMAGE) return
        setEditor(ReflectionTextEditor(original?.text ?: "", original))
    }
    fun text(value: String) {
        val editor = mutable.value.textEditor ?: return
        if (mutable.value.busy) return
        setEditor(editor.copy(text = value))
    }
    private fun setEditor(editor: ReflectionTextEditor?) {
        mutable.update { it.copy(textEditor = editor, error = null) }
        if (editor == null) { saved.remove<String>(DRAFT); saved.remove<String>(LEGACY_INPUT) }
        else saved[DRAFT] = JSONObject().put("text", editor.text).put("original", encodeEntry(editor.original)).toString()
    }
    fun cancelText() { if (!mutable.value.busy) setEditor(null) }
    fun saveText(): Job? {
        val editor = mutable.value.textEditor ?: return null
        return mutate(onSaved = { setEditor(null) }) {
            if (editor.original == null) repository.addText(editor.text) else repository.editText(editor.original, editor.text)
        }
    }
    fun preparePhoto(original: HomeReflectionHistoryEntity? = null): ReflectionPhotoRequest? {
        if (!available() || original?.mode == HomeReflectionMode.TEXT) return null
        val request = ReflectionPhotoRequest(UUID.randomUUID().toString(), original)
        mutable.update { it.copy(photoRequest = request, error = null) }
        saved[PHOTO] = JSONObject().put("token", request.token).put("original", encodeEntry(original)).toString()
        return request
    }
    fun pickedPhoto(token: String, uri: Uri?): Job? {
        val request = mutable.value.photoRequest?.takeIf { it.token == token && mutable.value.editing } ?: return null
        if (uri == null) { clearPhoto(); return null }
        if (mutable.value.historyLoading) return viewModelScope.launch {
            // 系统照片选择器可在进程恢复、首批历史仍读取时返回，不能丢掉这次选择。
            historyLoad?.join()
            pickedPhoto(token, uri)?.join()
        }
        return mutate(onSaved = ::clearPhoto) {
            val current = { mutable.value.editing && mutable.value.photoRequest?.token == token }
            if (request.original == null) repository.importImage(uri, current) else repository.editImage(request.original, uri, current)
        }
    }
    private fun clearPhoto() { mutable.update { it.copy(photoRequest = null) }; saved.remove<String>(PHOTO) }
    fun askDelete(entry: HomeReflectionHistoryEntity) { if (available()) mutable.update { it.copy(deletion = entry, error = null) } }
    fun cancelDelete() { if (!mutable.value.busy) mutable.update { it.copy(deletion = null, error = null) } }
    fun delete(): Job? {
        val entry = mutable.value.deletion ?: return null
        return mutate(onSaved = { mutable.update { it.copy(deletion = null) } }) { repository.deleteEntry(entry) }
    }
    fun useHistory(id: String) = mutate(onSaved = {
        saved[OPEN] = false
        mutable.update { it.copy(closeRequested = true) }
    }) { repository.useHistory(id) }

    private fun mutate(onSaved: () -> Unit = {}, action: suspend () -> HomeReflectionRecord): Job? {
        if (!available()) return null
        val limit = maxOf(PAGE_SIZE, mutable.value.history.size)
        mutable.update { it.copy(busy = true, error = null) }
        return viewModelScope.launch {
            try {
                val (record, refreshed) = withContext(Dispatchers.IO) { writes.withLock {
                    val committed = action()
                    // 列表刷新失败不把已经成功的写入误报为失败，更不能重放删除或编辑。
                    committed to runCatching { repository.librarySnapshot(limit + 1) }.onFailure { if (it is CancellationException) throw it }
                } }
                publish(record)
                onSaved()
                mutable.update { current -> refreshed.fold(onSuccess = { (_, rows) -> current.copy(history = rows.take(limit),
                    historyHasMore = rows.size > limit, historyFailed = false, error = null, savedEvent = current.savedEvent + 1) },
                    onFailure = { current.copy(historyFailed = true, error = "内容已保存，列表暂未刷新，请重试", savedEvent = current.savedEvent + 1) }) }
                withContext(Dispatchers.IO) { repository.cleanImages() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = if (failure is IllegalArgumentException)
                failure.message ?: "保存失败，请重试" else "保存失败，内容已保留，请重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun encodeEntry(value: HomeReflectionHistoryEntity?): JSONObject? = value?.let {
        JSONObject().put("id", it.id).put("mode", it.mode.name).put("text", it.text).put("image", it.imageAssetId)
            .put("first", it.firstUsedAt).put("last", it.lastUsedAt)
    }
    private fun decodeEntry(value: JSONObject?): HomeReflectionHistoryEntity? = value?.let {
        HomeReflectionHistoryEntity(it.getString("id"), HomeReflectionMode.valueOf(it.getString("mode")), it.getString("text"),
            it.optString("image").takeIf(String::isNotBlank), it.getLong("first"), it.getLong("last"))
    }
    companion object {
        private const val OPEN = "home.reflection.open"
        private const val TOKEN = "home.reflection.token"
        private const val LEGACY_INPUT = "home.reflection.input"
        private const val DRAFT = "home.reflection.editor"
        private const val PHOTO = "home.reflection.photo"
        private const val PAGE_SIZE = 40
    }
}
