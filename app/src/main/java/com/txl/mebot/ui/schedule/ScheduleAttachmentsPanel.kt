package com.txl.mebot.ui.schedule

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.local.ScheduleLocalAttachment
import com.txl.mebot.data.local.ScheduleCoverState
import com.txl.mebot.data.local.ScheduleCoverFailure
import com.txl.mebot.data.schedule.canDownload
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch

internal data class AttachmentPickerActions(val images: () -> Unit, val files: () -> Unit, val camera: () -> Unit = {})

/** 注册在日程页面，输入栏、详情与会话切换不会丢失系统选择器的返回结果。 */
@Composable
internal fun attachmentPickerActions(model: ScheduleAttachmentsViewModel?, asPending: Boolean = true): AttachmentPickerActions {
    return attachmentPickerActions(prepare = { model?.preparePicker(asPending) == true },
        picked = { uris, onlyImages -> model?.picked(uris, onlyImages) },
        prepareCamera = { model?.prepareCamera(asPending) }, cameraResult = { model?.cameraResult(it) },
        report = { model?.report(it) })
}

/** 日程与闪念共用系统选择器，所属草稿由各自的数据层在启动时固定。 */
@Composable
internal fun attachmentPickerActions(
    prepare: () -> Boolean, picked: (List<android.net.Uri>, Boolean) -> Unit,
    prepareCamera: suspend () -> android.net.Uri?, cameraResult: (Boolean) -> Unit,
    report: (AttachmentFailure) -> Unit,
): AttachmentPickerActions {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { picked(it, true) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it, false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { cameraResult(it) }
    fun failed() { picked(emptyList(), false); report(AttachmentFailure.READ) }
    return AttachmentPickerActions(
        images = { if (prepare()) try { focus.clearFocus(); images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: Exception) { failed() } },
        files = { if (prepare()) try { focus.clearFocus(); files.launch(arrayOf("*/*")) } catch (_: Exception) { failed() } },
        camera = { scope.launch {
            try { focus.clearFocus(); prepareCamera()?.let { camera.launch(it) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { cameraResult(false); report(AttachmentFailure.CAMERA) }
        } },
    )
}

@Composable
internal fun ScheduleCoverStatus(
    model: ScheduleAttachmentsViewModel?, expectedSessionId: String?, modifier: Modifier = Modifier, enabled: Boolean = true,
    smallText: Boolean = false,
) {
    if (model == null) return
    val state by model.state.collectAsStateWithLifecycle()
    val colors = scheduleColors()
    val busy = state.loading || state.isImporting || state.busyAssetId != null
    Column(modifier.fillMaxWidth()) {
        val cover = state.coverJob
        AnimatedVisibility(state.sessionId == expectedSessionId && cover != null && cover.state !in setOf(ScheduleCoverState.READY, ScheduleCoverState.SKIPPED),
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (cover != null) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cover.state != ScheduleCoverState.FAILED || state.isRetryingCover) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(7.dp))
                }
                Text(stringResource(when (cover.state) {
                    ScheduleCoverState.QUEUED -> R.string.schedule_cover_queued
                    ScheduleCoverState.GENERATING -> R.string.schedule_cover_generating
                    ScheduleCoverState.DOWNLOADING -> if (cover.failure == null) R.string.schedule_cover_downloading else R.string.schedule_cover_download_waiting
                    else -> cover.failure.coverFailureLabel()
                }), Modifier.weight(1f), color = colors.secondary, fontSize = if (smallText) scheduleChatTextSize(11) else 11.sp,
                    lineHeight = if (smallText) scheduleChatTextSize(16) else 16.sp)
                if (cover.state == ScheduleCoverState.FAILED) TextButton(onClick = model::retryCover, enabled = enabled && !busy && !state.isRetryingCover) {
                    Text(stringResource(if (cover.canDownload(System.currentTimeMillis())) R.string.schedule_cover_retry_download else R.string.schedule_cover_retry_generate),
                        fontSize = if (smallText) scheduleChatTextSize(11) else 11.sp)
                }
            }
        }
        if (state.coverRetryFailed) Text(stringResource(R.string.schedule_cover_retry_failed), color = MaterialTheme.colorScheme.error,
            fontSize = if (smallText) scheduleChatTextSize(11) else 11.sp)
    }
}

private fun ScheduleCoverFailure?.coverFailureLabel(): Int = when (this) {
    ScheduleCoverFailure.CONFIGURATION -> R.string.schedule_cover_configuration_failed
    ScheduleCoverFailure.AUTH -> R.string.schedule_cover_auth_failed
    ScheduleCoverFailure.BILLING -> R.string.schedule_cover_billing_failed
    ScheduleCoverFailure.NETWORK -> R.string.schedule_cover_network_failed
    ScheduleCoverFailure.DOWNLOAD -> R.string.schedule_cover_download_failed
    ScheduleCoverFailure.INTERRUPTED -> R.string.schedule_cover_interrupted
    else -> R.string.schedule_cover_failed
}

@Composable
internal fun ScheduleAttachmentDialog(
    item: ScheduleLocalAttachment, model: ScheduleAttachmentsViewModel, enabled: Boolean,
    failure: AttachmentFailure?, onRemove: (() -> Unit)? = null, onDismiss: () -> Unit,
) {
    val colors = scheduleColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { windowHeight.toDp() * 0.88f }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight), shape = RoundedCornerShape(24.dp), color = colors.surface, contentColor = colors.ink) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.schedule_file_details), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(22.dp)) }
                }
                Text(item.link.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(formatAttachmentSize(item.asset.byteCount), color = colors.secondary, fontSize = 12.sp)
                TextButton(onClick = {
                    scope.launch {
                        model.fileIntent(item)?.let { intent ->
                            try { context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) { model.report(AttachmentFailure.NO_VIEWER)
                            } catch (_: Exception) { model.report(AttachmentFailure.MISSING) }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.schedule_open_file)) }
                TextButton(onClick = onRemove ?: { model.remove(item.asset.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.schedule_remove_attachment), color = MaterialTheme.colorScheme.error)
                }
                failure?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Text(stringResource(R.string.schedule_attachments_local), color = colors.secondary, fontSize = 11.sp)
            }
        }
    }
}

internal fun ScheduleLocalAttachment.previewEntry() = ScheduleEntry(
    id = asset.id, title = link.displayName, date = LocalDate.of(2000, 1, 1), detail = "", coverRelativePath = asset.relativePath,
)

internal fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

internal fun AttachmentFailure.label(): Int = when (this) {
    AttachmentFailure.CAMERA -> R.string.schedule_camera_failed
    AttachmentFailure.IMPORT -> R.string.schedule_attachment_import_failed
    AttachmentFailure.READ -> R.string.schedule_attachment_read_failed
    AttachmentFailure.COVER -> R.string.schedule_attachment_cover_failed
    AttachmentFailure.REMOVE -> R.string.schedule_attachment_remove_failed
    AttachmentFailure.MISSING -> R.string.schedule_attachment_missing
    AttachmentFailure.NO_VIEWER -> R.string.schedule_attachment_no_viewer
}
