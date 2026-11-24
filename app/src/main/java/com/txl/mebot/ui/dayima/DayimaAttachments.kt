package com.txl.mebot.ui.dayima

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import com.txl.mebot.data.dayima.DayimaAttachmentRepository
import com.txl.mebot.data.dayima.DayimaChatChanged
import com.txl.mebot.data.dayima.DayimaImage
import com.txl.mebot.data.local.DayimaChatDraftEntity
import com.txl.mebot.ui.schedule.AttachmentFailure
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DayimaMediaState(val sessionId: String? = null, val images: List<DayimaImage> = emptyList(),
    val importing: Boolean = false, val preparingCamera: Boolean = false, val removing: String? = null,
    val failure: AttachmentFailure? = null, val selectedSources: Map<String, String> = emptyMap()) {
    val busy get() = importing || preparingCamera || removing != null
}

/** 与文字共用写入队列，选择器始终绑定启动时的聊天草稿。 */
internal class DayimaAttachments(private val application: Application, private val savedState: SavedStateHandle,
    private val repository: DayimaAttachmentRepository?, private val scope: CoroutineScope, private val writes: Mutex,
    private val draft: () -> DayimaChatDraftEntity?, private val replaceDraft: (DayimaChatDraftEntity) -> Unit,
    private val editable: () -> Boolean, private val flushInput: suspend () -> Boolean,
    private val changed: (DayimaMediaState) -> Unit, private val conflict: () -> Unit) {
    private var state = DayimaMediaState()
    private fun update(change: (DayimaMediaState) -> DayimaMediaState) { state = change(state); changed(state) }

    fun bind(value: DayimaChatDraftEntity, images: List<DayimaImage>) = update {
        it.copy(sessionId = value.sessionId, images = images, failure = if (it.sessionId == value.sessionId) it.failure else null,
            selectedSources = if (it.sessionId == value.sessionId) it.selectedSources.filterValues { id -> images.any { image -> image.asset.id == id } } else emptyMap())
    }

    fun preparePicker(): Boolean {
        val current = draft() ?: return false
        if (repository == null || !editable() || state.busy || savedState.get<String>(PICKER) != null) return false
        savedState[PICKER] = current.sessionId
        return true
    }

    fun picked(uris: List<Uri>, imagesOnly: Boolean, capture: File? = null) {
        val owner = savedState.remove<String>(PICKER)
        if (owner == null || uris.isEmpty() || state.busy) {
            capture?.let { scope.launch(Dispatchers.IO) { it.delete() } }
            return
        }
        if (!imagesOnly) { report(AttachmentFailure.READ); return }
        update { it.copy(importing = true, failure = null) }
        scope.launch {
            try {
                writes.withLock {
                    for (uri in uris.distinct()) {
                        try {
                            check(draft()?.sessionId == owner) { "聊天已切换" }
                            check(flushInput())
                            val current = requireNotNull(draft()).takeIf { it.sessionId == owner } ?: throw DayimaChatChanged()
                            val imported = requireNotNull(repository).importUri(current, uri)
                            replaceDraft(imported.draft)
                            bind(imported.draft, repository.draftImages(owner))
                            update { it.copy(selectedSources = it.selectedSources + (uri.toString() to imported.assetId)) }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) {
                            if (error is DayimaChatChanged) conflict()
                            report(AttachmentFailure.IMPORT)
                        }
                    }
                }
            } finally {
                capture?.let { withContext(NonCancellable + Dispatchers.IO) { it.delete() } }
                update { it.copy(importing = false) }
            }
        }
    }

    fun pickLocal(uri: Uri) {
        val selected = state.selectedSources[uri.toString()]?.takeIf { id -> state.images.any { it.asset.id == id } }
        if (selected != null) remove(selected) else if (preparePicker()) picked(listOf(uri), true)
    }

    fun remove(assetId: String) {
        val owner = draft()?.sessionId ?: return
        if (repository == null || !editable() || state.busy || state.images.none { it.asset.id == assetId }) return
        update { it.copy(removing = assetId, failure = null) }
        scope.launch {
            try {
                writes.withLock {
                    check(draft()?.sessionId == owner && flushInput())
                    val current = requireNotNull(draft()).takeIf { it.sessionId == owner } ?: throw DayimaChatChanged()
                    val result = repository.removeDraft(current, assetId)
                    replaceDraft(result); bind(result, repository.draftImages(owner))
                    runCatching { repository.cleanPendingDeletions() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (error is DayimaChatChanged) conflict()
                report(AttachmentFailure.REMOVE)
            } finally { update { it.copy(removing = null) } }
        }
    }

    suspend fun prepareCamera(): Uri? {
        if (!preparePicker()) return null
        update { it.copy(preparingCamera = true, failure = null) }
        try {
            val file = withContext(Dispatchers.IO) {
                val directory = File(application.cacheDir, CAPTURES).apply { check(isDirectory || mkdirs()) }
                directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
                File(directory, "${UUID.randomUUID()}.jpg").apply { check(createNewFile()) }
            }
            savedState[CAMERA] = file.name
            return FileProvider.getUriForFile(application, "${application.packageName}.attachments", file)
        } finally { update { it.copy(preparingCamera = false) } }
    }

    fun cameraResult(success: Boolean) {
        val name = savedState.remove<String>(CAMERA)
        val file = name?.takeIf { Regex("[a-f0-9-]+\\.jpg").matches(it) }?.let { File(application.cacheDir, "$CAPTURES/$it") }
        if (success && file?.isFile == true) picked(listOf(FileProvider.getUriForFile(application, "${application.packageName}.attachments", file)), true, file)
        else {
            picked(emptyList(), true)
            file?.let { scope.launch(Dispatchers.IO) { it.delete() } }
            if (success) report(AttachmentFailure.CAMERA)
        }
    }

    fun report(failure: AttachmentFailure) = update { it.copy(failure = failure) }
    private companion object {
        const val PICKER = "dayima.picker.session"
        const val CAMERA = "dayima.camera.file"
        const val CAPTURES = "dayima_ui_camera"
    }
}
