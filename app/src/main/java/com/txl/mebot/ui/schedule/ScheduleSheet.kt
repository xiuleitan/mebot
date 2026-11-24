package com.txl.mebot.ui.schedule

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.ScheduleLocalAttachment
import com.txl.mebot.data.schedule.ScheduleEditScope
import com.txl.mebot.ui.common.DetailDeleteButton
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.common.botSheetScrim
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSheet(
    entry: ScheduleEntry?, width: Dp, height: Dp, onDismiss: () -> Unit, onToggleCompleted: (String) -> Unit,
    onEdit: ((ScheduleEntry) -> Unit)? = null,
    detailLoading: Boolean = false, detailFailed: Boolean = false,
    completionEnabled: Boolean = true, completionFailed: Boolean = false, onRetryDetail: () -> Unit = {},
    onDelete: ((ScheduleEntry, ScheduleEditScope) -> Unit)? = null, deletionFailed: Boolean = false,
    onStopRecurrence: ((ScheduleEntry) -> Unit)? = null, stoppingFailed: Boolean = false,
    attachmentModel: ScheduleAttachmentsViewModel? = null,
) {
    val colors = scheduleColors()
    val shape = RoundedCornerShape(26.dp)
    val density = LocalDensity.current
    // 首次测量直接使用展开锚点；避免默认从整屏外滑入与遮罩各自启动。
    val sheetState = remember(density) {
        SheetState(skipPartiallyExpanded = true, initialValue = SheetValue.Expanded,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 125.dp.toPx() } })
    }
    val entrance = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var completionTarget by rememberSaveable(entry?.id) { mutableStateOf<Boolean?>(null) }
    var closing by remember(entry?.id) { mutableStateOf(false) }
    val completionSucceeded = completionTarget != null && entry?.completed == completionTarget &&
        completionEnabled && !completionFailed && !detailLoading && !detailFailed
    com.txl.mebot.ui.common.SuccessHaptics(if (completionSucceeded) entry?.revision else null)
    fun close(after: () -> Unit = dismiss) {
        if (closing) return
        closing = true
        scope.launch {
            try { sheetState.hide(); if (!sheetState.isVisible) after() }
            finally { if (sheetState.isVisible) closing = false }
        }
    }
    LaunchedEffect(completionSucceeded) { if (completionSucceeded) close() }
    ModalBottomSheet(
        onDismissRequest = { close() },
        sheetGesturesEnabled = false,
        modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp).graphicsLayer {
            alpha = entrance.value
            translationY = (1f - entrance.value) * 28.dp.toPx()
            // 原生弹层在后续布局中放置到锚点，避免透明图层按初始边界裁掉内容和外阴影。
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }.dropShadow(shape) {
            radius = 24.dp.toPx()
            spread = 2.dp.toPx()
            color = Color.Black.copy(alpha = 0.28f)
            val positioned = sheetState.hasExpandedState
            alpha = if (positioned) 1f else 0f
            offset = Offset(0f, (if (positioned) sheetState.requireOffset() else 0f) + 6.dp.toPx())
        },
        sheetState = sheetState, sheetMaxWidth = width, shape = shape,
        containerColor = colors.surface, contentColor = colors.ink,
        scrimColor = botSheetScrim(entrance.value),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = null,
    ) {
        LaunchedEffect(entrance) { entrance.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        // 入场期间不再叠加加载占位到详情的高度动画；后续内容变化仍平滑调整。
        val resize = if (entrance.value == 1f) Modifier.animateContentSize(tween(220)) else Modifier
        Box(Modifier.fillMaxWidth().heightIn(max = height).then(resize)) {
            if (entry != null) ScheduleDetails(
                entry = entry, onToggleCompleted = {
                    if (completionEnabled && !closing) {
                        completionTarget = !entry.completed
                        onToggleCompleted(entry.id)
                    }
                },
                onClose = { close() }, closeEnabled = !closing,
                onEdit = onEdit?.let { action -> { close { action(entry) } } },
                completionEnabled = completionEnabled && !closing, completionFailed = completionFailed,
                onDelete = onDelete?.let { action -> { editScope -> action(entry, editScope) } }, deletionFailed = deletionFailed,
                onStopRecurrence = onStopRecurrence?.let { action -> { action(entry) } }, stoppingFailed = stoppingFailed,
                attachmentModel = attachmentModel,
            ) else {
                ScheduleDetailStatus(detailLoading, detailFailed, onRetryDetail)
                IconButton(onClick = { close() }, enabled = !closing,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp).size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(22.dp), tint = colors.ink)
                }
            }
        }
    }
}

@Composable
private fun ScheduleDetails(
    entry: ScheduleEntry, onToggleCompleted: () -> Unit, onClose: () -> Unit, closeEnabled: Boolean,
    onEdit: (() -> Unit)? = null,
    completionEnabled: Boolean = true, completionFailed: Boolean = false,
    onDelete: ((ScheduleEditScope) -> Unit)? = null, deletionFailed: Boolean = false,
    onStopRecurrence: (() -> Unit)? = null, stoppingFailed: Boolean = false,
    attachmentModel: ScheduleAttachmentsViewModel? = null,
) {
    val colors = scheduleColors()
    val attachments = attachmentModel?.state?.collectAsStateWithLifecycle()?.value ?: ScheduleAttachmentsState()
    val bound = attachments.sessionId == entry.sessionId
    val items = attachments.items.takeIf { bound }.orEmpty()
    val images = items.filter { it.isImage }
    var previewId by rememberSaveable(entry.id) { mutableStateOf<String?>(null) }
    var previewFileId by rememberSaveable(entry.id) { mutableStateOf<String?>(null) }
    var showAttachments by rememberSaveable(entry.id) { mutableStateOf(false) }
    var chooseDeleteScope by rememberSaveable(entry.id) { mutableStateOf(false) }
    var pendingDeleteScope by rememberSaveable(entry.id) { mutableStateOf<ScheduleEditScope?>(null) }
    val toggleLabel = stringResource(if (entry.completed) R.string.uncomplete_schedule else R.string.complete_schedule)
    val completionLabel = stringResource(if (entry.completed) R.string.schedule_completed else R.string.schedule_pending)
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                IconButton(onClick = hapticAction(onClose), enabled = closeEnabled, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(22.dp), tint = colors.ink)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_calendar), null, tint = colors.secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(entry.date.scheduleLabel() + (entry.time?.let { " ${it.scheduleLabel()}" } ?: ""),
                    Modifier.weight(1f), color = colors.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                // 压缩可见布局，触控范围仍由 Compose 按系统最小尺寸扩展。
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(onClick = { showAttachments = true }, enabled = entry.attachmentCount > 0,
                        modifier = Modifier.defaultMinSize(minHeight = 28.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.secondary, disabledContentColor = colors.secondary.copy(alpha = 0.6f))) {
                        Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.schedule_attachments_count, entry.attachmentCount), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.divider)
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScheduleDetailImages(entry, images, onOpenImage = { previewId = it })
            if (entry.detail.isNotBlank()) {
                Text(entry.detail.trim(), Modifier.fillMaxWidth(), color = colors.secondary, fontSize = 14.sp, lineHeight = 21.sp)
            }
            if (completionFailed) Text(stringResource(R.string.schedule_completion_failed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (deletionFailed) Text(stringResource(R.string.schedule_delete_failed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (stoppingFailed) Text(stringResource(R.string.schedule_stop_failed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.divider)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                DetailDeleteButton(onClick = {
                    if (entry.isRecurring) chooseDeleteScope = true else pendingDeleteScope = ScheduleEditScope.ALL
                }, enabled = onDelete != null && completionEnabled, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = chooseDeleteScope, onDismissRequest = { chooseDeleteScope = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.schedule_delete_once), color = colors.ink) },
                        onClick = { chooseDeleteScope = false; pendingDeleteScope = ScheduleEditScope.ONCE }, enabled = completionEnabled)
                    DropdownMenuItem(text = { Text(stringResource(R.string.schedule_delete_all), color = colors.ink) },
                        onClick = { chooseDeleteScope = false; pendingDeleteScope = ScheduleEditScope.ALL }, enabled = completionEnabled)
                    if (!entry.recurrenceStopped && onStopRecurrence != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_stop_recurrence), color = colors.ink) },
                        onClick = { chooseDeleteScope = false; onStopRecurrence() }, enabled = completionEnabled)
                }
            }
            Button(onClick = hapticAction { onEdit?.invoke() }, enabled = onEdit != null && completionEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDEBFF), contentColor = Color(0xFF245580))) {
                Text(stringResource(R.string.schedule_detail_edit), fontSize = 14.sp, lineHeight = 20.sp)
            }
            Button(onClick = onToggleCompleted, enabled = completionEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = "${entry.title}，$toggleLabel"; stateDescription = completionLabel
                }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDCEFE1), contentColor = Color(0xFF255F39))) {
                Text(stringResource(if (entry.completed) R.string.schedule_completed else R.string.schedule_detail_complete), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
    pendingDeleteScope?.let { deleteScope ->
        DetailDeleteConfirmationDialog(
            title = stringResource(R.string.schedule_delete_confirmation_title),
            onDismiss = { pendingDeleteScope = null }, enabled = completionEnabled && onDelete != null,
            onConfirm = {
                if (pendingDeleteScope == deleteScope && completionEnabled && onDelete != null) {
                    pendingDeleteScope = null
                    onDelete(deleteScope)
                }
            },
            text = {
                Text(when {
                    !entry.isRecurring -> stringResource(R.string.schedule_delete_confirmation_task, entry.title)
                    deleteScope == ScheduleEditScope.ONCE -> stringResource(
                        R.string.schedule_delete_confirmation_once, entry.date.scheduleLabel(), entry.title)
                    else -> stringResource(R.string.schedule_delete_confirmation_all, entry.title)
                })
            },
        )
    }
    previewId?.let { id ->
        ScheduleImagePreview(id, images, attachmentModel, attachments, enabled = completionEnabled,
            fallbackImage = entry.takeIf { id == it.id && images.isEmpty() && (it.image != null || it.coverRelativePath != null) },
            onDismiss = { previewId = null })
    }
    if (showAttachments) Dialog(onDismissRequest = { showAttachments = false }) {
        Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, contentColor = colors.ink) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.schedule_attachments_count, entry.attachmentCount), fontWeight = FontWeight.Medium)
                if (!bound || attachments.loading) CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                else if (items.isEmpty() && attachments.failure != null) {
                    Text(stringResource(requireNotNull(attachments.failure).label()), color = colors.secondary, fontSize = 12.sp)
                    TextButton(onClick = { attachmentModel?.bind(entry.sessionId) }) { Text(stringResource(R.string.retry)) }
                } else LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.asset.id }) { item ->
                        Surface(onClick = {
                            showAttachments = false
                            if (item.isImage) previewId = item.asset.id else previewFileId = item.asset.id
                        }, shape = RoundedCornerShape(12.dp), color = colors.backgroundTop) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(if (item.isImage) R.drawable.ic_image else R.drawable.ic_file), null, Modifier.size(22.dp), tint = colors.secondary)
                                Text(item.link.displayName, Modifier.padding(start = 10.dp), color = colors.ink, fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    items.firstOrNull { it.asset.id == previewFileId }?.let { item ->
        if (attachmentModel != null) ScheduleAttachmentDialog(item, attachmentModel,
            completionEnabled && !attachments.isImporting && attachments.busyAssetId == null, attachments.failure) { previewFileId = null }
    }
}

@Composable
private fun ScheduleDetailImages(entry: ScheduleEntry, images: List<ScheduleLocalAttachment>, onOpenImage: (String) -> Unit) {
    val colors = scheduleColors()
    if (images.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
        val imageHeight = (maxWidth / 2).coerceIn(104.dp, 180.dp)
        val imageWidth = if (images.size == 1) maxWidth else (maxWidth - 8.dp) / 2
        LazyRow(Modifier.height(imageHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images, key = { it.asset.id }) { item ->
                Surface(onClick = { onOpenImage(item.asset.id) }, shape = RoundedCornerShape(16.dp), color = colors.past,
                    modifier = Modifier.width(imageWidth).fillMaxHeight()) {
                    val photo = schedulePhoto(item.previewEntry(), 1024)
                    if (photo != null) Image(photo, stringResource(R.string.schedule_image_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_image), null, Modifier.size(30.dp), tint = colors.secondary) }
                }
            }
        }
    } else if (entry.image != null || entry.coverRelativePath != null) {
        Surface(onClick = { onOpenImage(entry.id) }, shape = RoundedCornerShape(16.dp), color = colors.past,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f)) {
            val photo = schedulePhoto(entry)
            if (photo != null) Image(photo, stringResource(R.string.schedule_image_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_image), null, Modifier.size(30.dp), tint = colors.secondary) }
        }
    }
}

@Composable
private fun ScheduleDetailStatus(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    val colors = scheduleColors()
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.dp)
        else Text(stringResource(if (failed) R.string.schedule_detail_failed else R.string.schedule_detail_unavailable), color = colors.secondary)
        if (failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = colors.accent) }
    }
}
