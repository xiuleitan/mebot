package com.txl.mebot.ui.schedule

import android.content.ActivityNotFoundException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.common.ComposerPendingMediaRow
import com.txl.mebot.data.local.ScheduleLocalAttachment
import com.txl.mebot.data.local.ScheduleMessageAttachment
import kotlinx.coroutines.launch

@Composable
internal fun SchedulePendingMedia(
    model: ScheduleAttachmentsViewModel?, state: ScheduleAttachmentsState, enabled: Boolean,
    onAdd: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = scheduleColors()
    val pending = state.items.filter { it.link.pendingSend }
    var previewId by rememberSaveable(state.sessionId) { mutableStateOf<String?>(null) }
    var previewIsImage by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    ComposerPendingMediaRow(pending, key = { it.asset.id }, isImage = { it.isImage }, enabled = enabled,
        onAdd = onAdd, onRemove = { model?.discardPending(it.asset.id) }, modifier = modifier,
        thumbnail = { item, tileModifier ->
            MediaTile(item, { previewIsImage = item.isImage; previewId = item.asset.id }, tileModifier)
        })
    previewId?.let { id ->
        if (previewIsImage) ScheduleImagePreview(id, pending.filter { it.isImage }, model, state, enabled,
            onRemove = { model?.discardPending(it, state.sessionId) }, onDismiss = { previewId = null })
        else pending.firstOrNull { it.asset.id == id }?.let { item ->
            if (model != null) ScheduleAttachmentDialog(item, model, enabled, state.failure,
                onRemove = { model.discardPending(item.asset.id) }) { previewId = null }
        }
    }
}

@Composable
private fun MediaTile(item: ScheduleLocalAttachment, onClick: () -> Unit, modifier: Modifier) {
    com.txl.mebot.ui.common.ComposerMediaTile(item.asset, item.link.displayName, onClick, modifier)
}

@Composable
internal fun ScheduleMessageMedia(items: List<ScheduleMessageAttachment>, model: ScheduleAttachmentsViewModel?) {
    if (items.isEmpty()) return
    val colors = scheduleColors()
    var previewId by rememberSaveable(items.first().link.messageId) { mutableStateOf<String?>(null) }
    var previewIsImage by rememberSaveable(items.first().link.messageId) { mutableStateOf(false) }
    var attachedGallery by rememberSaveable(items.first().link.messageId) { mutableStateOf(false) }
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: ScheduleAttachmentsState()
    val taskImages = state.items.filter { state.sessionId == items.first().link.sessionId && it.isImage }
    LazyRow(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(items, key = { _, item -> item.asset.id }) { _, item ->
            MediaTile(item.asLocal(), {
                previewIsImage = item.isImage
                attachedGallery = taskImages.any { it.asset.id == item.asset.id }
                previewId = item.asset.id
            }, Modifier.width(if (item.isImage) 100.dp else 144.dp).fillMaxHeight())
        }
    }
    if (previewIsImage && previewId != null) {
        ScheduleImagePreview(requireNotNull(previewId),
            if (attachedGallery) taskImages else items.filter { it.isImage }.map { it.asLocal() },
            model, state, enabled = true, onDismiss = { previewId = null })
    } else items.firstOrNull { it.asset.id == previewId }?.let { item ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        Dialog(onDismissRequest = { previewId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = colors.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.link.displayName ?: item.asset.originalName ?: stringResource(R.string.schedule_image_preview), Modifier.weight(1f), color = colors.ink, fontSize = 14.sp, maxLines = 2)
                        IconButton(onClick = { previewId = null }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(22.dp), tint = colors.ink) }
                    }
                    Text(formatAttachmentSize(item.asset.byteCount), color = colors.secondary, fontSize = 12.sp)
                    Button(onClick = { scope.launch {
                        try { model?.messageFileIntent(item.link.messageId, item.asset.id)?.let(context::startActivity) }
                        catch (_: ActivityNotFoundException) { model?.report(AttachmentFailure.NO_VIEWER) }
                        catch (_: Exception) { model?.report(AttachmentFailure.MISSING) }
                    } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.schedule_open_file)) }
                }
            }
        }
    }
}
