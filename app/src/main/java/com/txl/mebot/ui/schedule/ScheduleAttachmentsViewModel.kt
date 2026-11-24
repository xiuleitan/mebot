package com.txl.mebot.ui.schedule

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.ScheduleLocalAttachment
import com.txl.mebot.data.local.ScheduleCoverJobEntity
import com.txl.mebot.data.local.ScheduleCoverState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class AttachmentFailure { IMPORT, READ, COVER, REMOVE, MISSING, NO_VIEWER, CAMERA }
data class ScheduleAttachmentsState(
    val sessionId: String? = null,
    val items: List<ScheduleLocalAttachment> = emptyList(),
    val loading: Boolean = false,
    val isImporting: Boolean = false,
    val importingIndex: Int = 0,
    val importingTotal: Int = 0,
    val busyAssetId: String? = null,
    val failure: AttachmentFailure? = null,
    val coverJob: ScheduleCoverJobEntity? = null,
    val isRetryingCover: Boolean = false,
    val coverRetryFailed: Boolean = false,
    val selectedSources: Map<String, String> = emptyMap(),
)

/** 选择器结果绑定发起选择时的会话，避免重建、切换或清空后误加到另一任务。 */
class ScheduleAttachmentsViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val container = (application as MebotApplication).container
    private val repository = container.scheduleAttachments
    private val mutableState = MutableStateFlow(ScheduleAttachmentsState())
    val state = mutableState.asStateFlow()
    private var contentJob: Job? = null
    private var importJob: Job? = null

    fun bind(sessionId: String?) {
        if (mutableState.value.sessionId == sessionId && contentJob?.isActive == true) return
        contentJob?.cancel()
        mutableState.update { it.copy(sessionId = sessionId, items = emptyList(), loading = sessionId != null, failure = null,
            coverJob = null, isRetryingCover = false, coverRetryFailed = false, selectedSources = emptyMap()) }
        if (sessionId == null) return
        contentJob = viewModelScope.launch {
            try {
                combine(repository.observe(sessionId), container.scheduleCovers.observe(sessionId)) { items, cover -> items to cover }.collect { (items, cover) ->
                    mutableState.update { if (it.sessionId == sessionId) it.copy(items = items, coverJob = cover, loading = false) else it }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { if (it.sessionId == sessionId) it.copy(loading = false, failure = AttachmentFailure.READ) else it } }
        }
    }

    fun preparePicker(asPending: Boolean = true): Boolean {
        val current = state.value
        val sessionId = current.sessionId ?: return false
        if (current.isImporting || current.busyAssetId != null || current.loading) return false
        savedState[PICKER_SESSION] = sessionId
        savedState["schedule_picker_pending"] = asPending
        return true
    }

    fun picked(uris: List<Uri>, imagesOnly: Boolean, temporaryCapture: File? = null) {
        val sessionId = savedState.remove<String>(PICKER_SESSION) ?: return
        val asPending = savedState.remove<Boolean>("schedule_picker_pending") ?: true
        if (uris.isEmpty() || importJob?.isActive == true) return
        mutableState.update { it.copy(isImporting = true, importingIndex = 0, importingTotal = uris.distinct().size, failure = null) }
        importJob = viewModelScope.launch {
            var failed = false
            try {
                for ((index, uri) in uris.distinct().withIndex()) {
                    mutableState.update { it.copy(importingIndex = index + 1) }
                    try {
                        val asset = repository.importUri(sessionId, uri, imagesOnly, asPending)
                        mutableState.update { if (it.sessionId == sessionId) it.copy(selectedSources = it.selectedSources + (uri.toString() to asset.id)) else it }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { failed = true }
                }
                clearSchedulePhotoCache()
                if (failed) mutableState.update { if (it.sessionId == sessionId) it.copy(failure = AttachmentFailure.IMPORT) else it }
            } finally {
                if (temporaryCapture != null) withContext(Dispatchers.IO) { temporaryCapture.delete() }
                mutableState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun chooseCover(assetId: String, expectedSessionId: String? = null) = mutate(assetId, AttachmentFailure.COVER, expectedSessionId) { session -> repository.chooseCover(session, assetId) }
    fun remove(assetId: String, expectedSessionId: String? = null) = mutate(assetId, AttachmentFailure.REMOVE, expectedSessionId) { session -> repository.remove(session, assetId) }
    fun discardPending(assetId: String, expectedSessionId: String? = null) = mutate(assetId, AttachmentFailure.REMOVE, expectedSessionId) { session -> repository.discardPending(session, assetId) }

    fun pickLocal(uri: Uri) {
        val selected = state.value.selectedSources[uri.toString()]?.let { id -> state.value.items.firstOrNull { it.asset.id == id && it.link.pendingSend } }
        if (selected != null) discardPending(selected.asset.id)
        else if (preparePicker()) picked(listOf(uri), true)
    }

    suspend fun prepareCamera(asPending: Boolean): Uri? {
        if (!preparePicker(asPending)) return null
        val file = withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val directory = File(app.filesDir, "media/originals/capture-staging").apply { mkdirs() }
            // 清理仅属于拍照暂存区的旧文件，不触及已导入原图。
            directory.listFiles()?.filter { it.name.endsWith(".jpg") && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
            File(directory, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
        }
        savedState["schedule_camera_file"] = file.name
        val app = getApplication<Application>()
        return FileProvider.getUriForFile(app, "${app.packageName}.attachments", file)
    }

    fun cameraResult(success: Boolean) {
        val name = savedState.remove<String>("schedule_camera_file") ?: run { picked(emptyList(), true); return }
        if (!Regex("[a-f0-9-]+\\.jpg").matches(name)) { picked(emptyList(), true); return }
        val app = getApplication<Application>()
        val file = File(app.filesDir, "media/originals/capture-staging/$name")
        if (success && file.isFile) picked(listOf(FileProvider.getUriForFile(app, "${app.packageName}.attachments", file)), true, file)
        else {
            picked(emptyList(), true)
            viewModelScope.launch(Dispatchers.IO) { file.delete() }
        }
    }

    suspend fun messageFileIntent(messageId: String, assetId: String): Intent? {
        val session = state.value.sessionId ?: return null
        val attachment = container.database.scheduleMediaDao().messageAttachment(session, messageId, assetId) ?: return null
        val asset = container.database.mediaDao().asset(assetId) ?: return null
        val file = container.media.fileFor(assetId) ?: run { report(AttachmentFailure.MISSING); return null }
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.attachments", file, attachment.displayName ?: asset.originalName ?: "附件")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, asset.mimeType).apply {
            clipData = ClipData.newRawUri("attachment", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun retryCover() {
        val current = state.value
        val session = current.sessionId ?: return
        if (current.isRetryingCover || current.coverJob?.state != ScheduleCoverState.FAILED) return
        mutableState.update { it.copy(isRetryingCover = true, coverRetryFailed = false) }
        viewModelScope.launch {
            try { container.scheduleCovers.retry(session)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { if (it.sessionId == session) it.copy(coverRetryFailed = true) else it }
            } finally { mutableState.update { if (it.sessionId == session) it.copy(isRetryingCover = false) else it } }
        }
    }

    fun regenerateCover(expectedSessionId: String?) {
        val current = state.value
        val session = current.sessionId ?: return
        if (session != expectedSessionId || current.loading || current.isImporting || current.busyAssetId != null || current.isRetryingCover) return
        if (current.coverJob?.state in setOf(ScheduleCoverState.QUEUED, ScheduleCoverState.GENERATING, ScheduleCoverState.DOWNLOADING)) return
        if (current.coverJob?.state == ScheduleCoverState.FAILED) { retryCover(); return }
        mutableState.update { it.copy(isRetryingCover = true, coverRetryFailed = false) }
        viewModelScope.launch {
            try { container.scheduleCovers.regenerate(session)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { if (it.sessionId == session) it.copy(coverRetryFailed = true) else it }
            } finally { mutableState.update { if (it.sessionId == session) it.copy(isRetryingCover = false) else it } }
        }
    }

    private fun mutate(assetId: String, failure: AttachmentFailure, expectedSessionId: String? = null, action: suspend (String) -> Unit) {
        val current = state.value
        val session = current.sessionId ?: return
        if (expectedSessionId != null && session != expectedSessionId) return
        if (current.isImporting || current.busyAssetId != null) return
        mutableState.update { it.copy(busyAssetId = assetId, failure = null) }
        viewModelScope.launch {
            try {
                action(session)
                clearSchedulePhotoCache()
                runCatching { container.media.cleanPendingDeletions() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { if (it.sessionId == session) it.copy(failure = failure) else it }
            } finally { mutableState.update { it.copy(busyAssetId = null) } }
        }
    }

    suspend fun fileIntent(item: ScheduleLocalAttachment): Intent? {
        return try {
            val session = state.value.sessionId ?: return null
            if (item.link.sessionId != session || state.value.items.none { it.asset.id == item.asset.id }) return null
            val file = container.media.fileFor(item.asset.id)
            if (file == null) { report(AttachmentFailure.MISSING); return null }
            val context = getApplication<Application>()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file, item.link.displayName)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.asset.mimeType).apply {
                clipData = ClipData.newRawUri("attachment", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { report(AttachmentFailure.MISSING); null }
    }

    fun report(failure: AttachmentFailure) { mutableState.update { it.copy(failure = failure) } }

    private companion object { const val PICKER_SESSION = "schedule_attachment_picker_session" }
}
