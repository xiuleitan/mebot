package com.txl.mebot.ui.flashnote

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import com.txl.mebot.data.flashnote.FlashnoteAttachmentRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.flashnote.FlashnoteDraftConflict
import com.txl.mebot.data.flashnote.FlashnoteDraftNotFound
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.data.local.FlashnoteDraftEntity
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

data class FlashnoteMediaState(
    val draftKey: String? = null,
    val items: List<FlashnoteAttachment> = emptyList(),
    val isImporting: Boolean = false,
    val preparingCamera: Boolean = false,
    val importingIndex: Int = 0,
    val importingTotal: Int = 0,
    val busyAssetId: String? = null,
    val failure: AttachmentFailure? = null,
    val selectedSources: Map<String, String> = emptyMap(),
) {
    val busy: Boolean get() = isImporting || preparingCamera || busyAssetId != null
}

/** 选择器固定绑定发起时的草稿；媒体和文字共享写入队列，不能落到下一件闪念。 */
internal class FlashnoteAttachments(
    private val application: Application,
    private val savedState: SavedStateHandle,
    private val repository: FlashnoteRepository,
    private val importer: FlashnoteAttachmentRepository?,
    private val scope: CoroutineScope,
    private val writes: Mutex,
    private val currentDraft: () -> FlashnoteDraftEntity?,
    private val replaceDraft: (FlashnoteDraftEntity) -> Unit,
    private val editable: () -> Boolean,
    private val flushInput: suspend () -> Boolean,
    private val onChange: (FlashnoteMediaState) -> Unit,
    private val onDraftConflict: () -> Unit,
) {
    private var current = FlashnoteMediaState()
    private fun update(change: (FlashnoteMediaState) -> FlashnoteMediaState) { current = change(current); onChange(current) }

    fun bind(draft: FlashnoteDraftEntity, items: List<FlashnoteAttachment>) {
        update { it.copy(draftKey = draft.key, items = items, failure = null,
            selectedSources = if (it.draftKey == draft.key) it.selectedSources else emptyMap()) }
    }

    fun preparePicker(): Boolean {
        val draft = currentDraft() ?: return false
        if (importer == null || !editable() || current.busy || savedState.get<String>(PICKER) != null) return false
        savedState[PICKER] = draft.key
        return true
    }

    fun picked(uris: List<Uri>, imagesOnly: Boolean, temporaryCapture: File? = null) {
        val key = savedState.remove<String>(PICKER)
        if (key == null || uris.isEmpty() || current.busy) {
            temporaryCapture?.let { scope.launch(Dispatchers.IO) { it.delete() } }
            return
        }
        update { it.copy(isImporting = true, importingIndex = 0, importingTotal = uris.distinct().size, failure = null) }
        scope.launch {
            try {
                writes.withLock {
                    for ((index, uri) in uris.distinct().withIndex()) {
                        update { it.copy(importingIndex = index + 1) }
                        try {
                            check(currentDraft()?.key == key) { "原草稿已关闭" }
                            check(flushInput()) { "文字草稿尚未保存" }
                            val draft = requireNotNull(currentDraft()).takeIf { it.key == key } ?: error("原草稿已关闭")
                            val changed = requireNotNull(importer).importUri(draft, uri, imagesOnly)
                            replaceDraft(changed.draft)
                            val items = repository.draftAttachments(key)
                            update { it.copy(items = items, selectedSources = it.selectedSources + (uri.toString() to changed.assetId)) }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (failure: Exception) { reportWriteFailure(failure, AttachmentFailure.IMPORT) }
                    }
                }
            } finally {
                if (temporaryCapture != null) withContext(NonCancellable + Dispatchers.IO) { temporaryCapture.delete() }
                update { it.copy(isImporting = false) }
            }
        }
    }

    fun pickLocal(uri: Uri) {
        val selected = current.selectedSources[uri.toString()]?.takeIf { id -> current.items.any { it.asset.id == id } }
        if (selected != null) remove(selected)
        else if (preparePicker()) picked(listOf(uri), true)
    }

    fun remove(assetId: String) {
        if (!editable() || current.busy || current.items.none { it.asset.id == assetId }) return
        val key = currentDraft()?.key ?: return
        update { it.copy(busyAssetId = assetId, failure = null) }
        scope.launch {
            try {
                writes.withLock {
                    check(currentDraft()?.key == key && flushInput())
                    val changed = repository.removeAttachment(requireNotNull(currentDraft()), assetId)
                    replaceDraft(changed)
                    val items = repository.draftAttachments(key)
                    update { it.copy(items = items) }
                    runCatching { importer?.cleanPendingDeletions() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { reportWriteFailure(failure, AttachmentFailure.REMOVE)
            } finally { update { it.copy(busyAssetId = null) } }
        }
    }

    suspend fun prepareCamera(): Uri? {
        if (!preparePicker()) return null
        update { it.copy(preparingCamera = true, failure = null) }
        try {
            val file = withContext(Dispatchers.IO) {
                val directory = File(application.filesDir, CAPTURES).apply { mkdirs() }
                directory.listFiles()?.filter { it.name.endsWith(".jpg") && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
                File(directory, "${UUID.randomUUID()}.jpg").apply { check(createNewFile()) }
            }
            savedState[CAMERA] = file.name
            return FileProvider.getUriForFile(application, "${application.packageName}.attachments", file)
        } finally { update { it.copy(preparingCamera = false) } }
    }

    fun cameraResult(success: Boolean) {
        val name = savedState.remove<String>(CAMERA)
        val file = name?.takeIf { Regex("[a-f0-9-]+\\.jpg").matches(it) }?.let { File(application.filesDir, "$CAPTURES/$it") }
        if (success && file?.isFile == true) picked(listOf(FileProvider.getUriForFile(application, "${application.packageName}.attachments", file)), true, file)
        else { picked(emptyList(), true); file?.let { scope.launch(Dispatchers.IO) { it.delete() } } }
    }

    suspend fun fileIntent(item: FlashnoteAttachment): Intent? = withContext(Dispatchers.IO) {
        val path = item.asset.relativePath ?: return@withContext null
        val root = application.filesDir.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return@withContext null
        val uri = FileProvider.getUriForFile(application, "${application.packageName}.attachments", file, item.displayName)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.asset.mimeType).apply {
            clipData = ClipData.newRawUri("attachment", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun report(failure: AttachmentFailure) = update { it.copy(failure = failure) }

    private fun reportWriteFailure(failure: Exception, kind: AttachmentFailure) {
        if (failure is FlashnoteDraftConflict || failure is FlashnoteDraftNotFound) onDraftConflict()
        report(kind)
    }

    private companion object {
        const val PICKER = "flashnote.picker.draft"
        const val CAMERA = "flashnote.camera.file"
        const val CAPTURES = "media/originals/flashnote-capture-staging"
    }
}
