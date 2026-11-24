package com.txl.mebot.ui.home

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.home.HomeComposerRepository
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.HomeComposerAttachment
import com.txl.mebot.data.local.HomeDispatchEntity
import com.txl.mebot.ui.schedule.AttachmentFailure
import com.txl.mebot.ui.schedule.clearSchedulePhotoCache
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HomeComposerState(
    val input: String = "", val revision: Long = 0, val loaded: Boolean = false,
    val items: List<HomeComposerAttachment> = emptyList(), val busy: Boolean = false,
    val importingIndex: Int = 0, val importingTotal: Int = 0,
    val loadFailed: Boolean = false, val saveFailed: Boolean = false,
    val failure: AttachmentFailure? = null, val notice: String? = null,
    val manualBot: Boolean = false,
    val deciding: Boolean = false, val routeError: String? = null,
    val dispatching: Boolean = false, val activeTask: HomeDispatchEntity? = null,
) {
    val editable: Boolean get() = loaded && !loadFailed && !dispatching && activeTask == null
    val mention: HomeBotMention get() = HomeBotMention.parse(input)
    val selectedBot: HomeBot? get() = mention.bot
    val canSend: Boolean get() = editable && !busy && mention.body.isNotBlank() && selectedBot != null && !deciding
}

/** 首页拥有自己的输入草稿；选择器不借用日程会话，也不执行 Bot 业务。 */
class HomeComposerViewModel internal constructor(app: Application, private val saved: SavedStateHandle,
    private val repository: HomeComposerRepository, private val router: HomeTextRouter? = null,
    private val dispatcher: HomeDispatchCoordinator? = null, private val settings: HomeRoutingSettings? = null) : AndroidViewModel(app) {
    constructor(app: Application, saved: SavedStateHandle) : this(app, saved, (app as MebotApplication).container.homeComposer,
        app.container.homeTextRouter, app.container.homeDispatch, app.container.homeRoutingSettings)
    private val mutable = MutableStateFlow(HomeComposerState())
    val state = mutable.asStateFlow()
    private val writes = Mutex()
    private var debounce: Job? = null
    private var observation: Job? = null
    private var storedText = ""
    private var decision: Job? = null
    private var decisionGeneration = 0L
    private var routeSuppressed = false
    init {
        load()
        settings?.let { config -> viewModelScope.launch {
            config.read()
            config.observe().drop(1).collect { if (state.value.loaded) decide() }
        } }
    }
    private fun load() {
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                val draft = repository.read()
                storedText = draft.inputText
                // 系统保存的界面快照可能早于最后一次数据库提交；不能把旧快照写回覆盖新草稿。
                val restored = saved.get<String>(INPUT)?.takeIf { saved.get<Long>(REVISION) == draft.revision }
                if (restored == null) { saved.remove<String>(INPUT); saved.remove<Long>(REVISION) }
                val manual = HomeBot.fromId(saved.get<String>(BOT)?.takeIf { saved.get<Long>(BOT_REVISION) == draft.revision })
                val input = restored ?: draft.inputText
                val migratedInput = if (manual != null && !HomeBotMention.parse(input).hasMention)
                    HomeBotMention.parse(input).withBot(manual) else input
                routeSuppressed = saved.get<Boolean>(SUPPRESS_ROUTE) == true &&
                    saved.get<String>(SUPPRESS_BODY) == HomeBotMention.parse(input).body
                val active = dispatcher?.active()
                mutable.update { it.copy(input = migratedInput, revision = draft.revision,
                    loaded = false, loadFailed = false, manualBot = manual != null, activeTask = active) }
                repository.observeAttachments().collect { items ->
                    val first = !mutable.value.loaded
                    mutable.update { it.copy(items = items, loaded = true, loadFailed = false) }
                    if (first) { if (restored != null || migratedInput != draft.inputText) savePendingInput(); decide() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loadFailed = true) } }
        }
    }
    fun changeInput(text: String) {
        if (!state.value.editable || text == state.value.input) return
        val before = state.value.mention
        val after = HomeBotMention.parse(text)
        val bodyChanged = before.body != after.body
        decisionGeneration++; decision?.cancel()
        routeSuppressed = !bodyChanged
        saved[SUPPRESS_ROUTE] = routeSuppressed
        if (routeSuppressed) saved[SUPPRESS_BODY] = after.body else saved.remove<String>(SUPPRESS_BODY)
        saved.remove<String>(BOT)
        saved[BOT_REVISION] = state.value.revision
        saved[INPUT] = text
        saved[REVISION] = state.value.revision
        mutable.update { it.copy(input = text, manualBot = !bodyChanged && after.bot != null, deciding = false, routeError = null) }
        debounce?.cancel()
        debounce = viewModelScope.launch { delay(180); writes.withLock { flushInput() } }
        if (bodyChanged) decide(force = true)
    }
    fun savePendingInput(): Job {
        debounce?.cancel()
        return viewModelScope.launch { writes.withLock { flushInput() } }
    }
    private suspend fun flushInput(): Boolean = withContext(NonCancellable) {
        val before = state.value
        if (!before.loaded || before.loadFailed) return@withContext false
        if (before.input == storedText) {
            saved.remove<String>(INPUT); saved.remove<Long>(REVISION)
            return@withContext true
        }
        // 开始写入后一起提交 revision 和界面状态；下一次打字取消 debounce 不能丢掉已提交的版本。
        try {
            val result = repository.saveInput(before.input, before.revision)
            storedText = result.inputText
            mutable.update { it.copy(revision = result.revision, saveFailed = false) }
            if (state.value.input == result.inputText) { saved.remove<String>(INPUT); saved.remove<Long>(REVISION) }
            else saved[REVISION] = result.revision
            saved[BOT_REVISION] = result.revision
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(saveFailed = true) }; false }
    }
    fun retry() { if (state.value.loadFailed) load() else savePendingInput() }
    fun preparePicker(): Boolean {
        if (!state.value.editable || state.value.busy) return false
        savePendingInput()
        saved[PICKER] = true
        return true
    }
    fun picked(uris: List<Uri>, imagesOnly: Boolean, capture: File? = null) {
        val prepared = saved.remove<Boolean>(PICKER) == true
        if (!prepared || uris.isEmpty() || state.value.busy) {
            capture?.let { viewModelScope.launch(Dispatchers.IO) { it.delete() } }
            return
        }
        mutable.update { it.copy(busy = true, importingTotal = uris.distinct().size, importingIndex = 0, failure = null) }
        viewModelScope.launch {
            try {
                writes.withLock {
                    for ((index, uri) in uris.distinct().withIndex()) {
                        mutable.update { it.copy(importingIndex = index + 1) }
                        try { repository.importUri(uri, imagesOnly) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { report(AttachmentFailure.IMPORT) }
                    }
                }
                clearSchedulePhotoCache()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { capture?.delete() }
                mutable.update { it.copy(busy = false, importingIndex = 0, importingTotal = 0) }
            }
        }
    }
    fun pickLocal(uri: Uri) {
        val selected = state.value.items.firstOrNull { it.link.sourceUri == uri.toString() }
        if (selected != null) remove(selected.asset.id)
        else if (preparePicker()) picked(listOf(uri), true)
    }
    fun remove(id: String) {
        if (!state.value.editable || state.value.busy) return
        mutable.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            try {
                writes.withLock { repository.remove(id) }
                clearSchedulePhotoCache()
                runCatching { repository.cleanPendingDeletions() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report(AttachmentFailure.REMOVE) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    suspend fun prepareCamera(): Uri? {
        if (!preparePicker()) return null
        val app = getApplication<Application>()
        val file = withContext(Dispatchers.IO) {
            val directory = File(app.filesDir, "media/originals/home-capture-staging").apply { mkdirs() }
            directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
            File(directory, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
        }
        saved[CAMERA] = file.name
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file)
    }
    fun cameraResult(success: Boolean) {
        val name = saved.remove<String>(CAMERA)
        if (name == null || !Regex("[a-f0-9-]+\\.jpg").matches(name)) { picked(emptyList(), true); return }
        val app = getApplication<Application>()
        val file = File(app.filesDir, "media/originals/home-capture-staging/$name")
        if (success) picked(listOf(FileProvider.getUriForFile(app, "${app.packageName}.attachments", file)), true, file)
        else picked(emptyList(), true, file)
    }
    suspend fun fileIntent(item: HomeComposerAttachment): Intent? {
        val file = repository.fileFor(item.asset.id) ?: run { report(AttachmentFailure.MISSING); return null }
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.attachments", file, item.link.displayName)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.asset.mimeType).apply {
            clipData = ClipData.newRawUri("attachment", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun report(failure: AttachmentFailure) { mutable.update { it.copy(failure = failure) } }
    fun chooseBot(bot: HomeBot) {
        if (!state.value.editable) return
        decisionGeneration++; decision?.cancel()
        routeSuppressed = true; saved[SUPPRESS_ROUTE] = true
        saved[SUPPRESS_BODY] = state.value.mention.body
        saved[BOT] = bot.id; saved[BOT_REVISION] = state.value.revision
        mutable.update { it.copy(input = it.mention.withBot(bot), manualBot = true, deciding = false, routeError = null) }
        saved[INPUT] = state.value.input; saved[REVISION] = state.value.revision
        savePendingInput()
    }
    fun decide() = decide(force = false)
    private fun decide(force: Boolean) {
        if (state.value.activeTask != null || state.value.dispatching || !state.value.loaded) return
        if (!force && (routeSuppressed || state.value.selectedBot != null)) return
        val generation = ++decisionGeneration
        decision?.cancel()
        val text = state.value.mention.body
        mutable.update { it.copy(manualBot = false, deciding = text.isNotBlank() && router != null, routeError = null) }
        if (text.isBlank() || router == null) return
        decision = viewModelScope.launch {
            // 每次文字变化都重新计时；连续 1.5 秒未变化才发起一次判断。
            delay(1_500)
            try {
                val bot = router.choose(text)
                if (decisionGeneration == generation && state.value.mention.body == text && state.value.activeTask == null) {
                    mutable.update { it.copy(input = it.mention.withBot(bot), deciding = false, routeError = null) }
                    saved[INPUT] = state.value.input; saved[REVISION] = state.value.revision
                    savePendingInput()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (decisionGeneration == generation) {
                mutable.update { it.copy(input = it.mention.body, deciding = false,
                    routeError = (error as? HomeRouteFailure)?.userMessage ?: "暂时无法判断，可手动输入 @Bot") }
                saved[INPUT] = state.value.input; saved[REVISION] = state.value.revision
                savePendingInput()
            } }
        }
    }
    fun send() {
        val before = state.value
        val bot = before.selectedBot ?: return
        if (!before.canSend || dispatcher == null) return
        decisionGeneration++; decision?.cancel(); debounce?.cancel()
        mutable.update { it.copy(dispatching = true, failure = null, routeError = null) }
        viewModelScope.launch { writes.withLock {
            try {
                if (!flushInput()) return@withLock
                val current = state.value
                val ticket = dispatcher.dispatch(bot, HomeSendSnapshot(current.input, current.revision, current.items))
                val draft = repository.read()
                storedText = draft.inputText
                saved.remove<String>(INPUT); saved.remove<Long>(REVISION); saved.remove<String>(BOT); saved.remove<Long>(BOT_REVISION)
                saved.remove<Boolean>(SUPPRESS_ROUTE); saved.remove<String>(SUPPRESS_BODY)
                routeSuppressed = false
                mutable.update { it.copy(input = draft.inputText, revision = draft.revision, items = emptyList(),
                    manualBot = false, deciding = false,
                    activeTask = ticket.takeUnless { record -> record.dismissed }, notice = if (bot == HomeBot.FLASHNOTE) "已保存闪念" else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(routeError = error.message?.takeIf { msg -> msg.length < 130 } ?: "发送未成功，文字和附件已保留，请重试") } }
            finally { mutable.update { it.copy(dispatching = false) } }
        } }
    }
    fun closeTask() {
        val ticket = state.value.activeTask ?: return
        viewModelScope.launch {
            try { dispatcher?.dismiss(ticket.id); mutable.update { if (it.activeTask?.id == ticket.id) it.copy(activeTask = null) else it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话暂未关闭，请重试") } }
        }
    }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    companion object {
        private const val INPUT = "home_composer_input"
        private const val REVISION = "home_composer_input_revision"
        private const val PICKER = "home_composer_picker"
        private const val CAMERA = "home_composer_camera"
        private const val BOT = "home_composer_bot"
        private const val BOT_REVISION = "home_composer_bot_revision"
        private const val SUPPRESS_ROUTE = "home_composer_route_suppressed"
        private const val SUPPRESS_BODY = "home_composer_route_suppressed_body"
    }
}
