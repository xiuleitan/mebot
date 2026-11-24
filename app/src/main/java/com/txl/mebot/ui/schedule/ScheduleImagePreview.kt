package com.txl.mebot.ui.schedule

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.ScheduleLocalAttachment
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.data.local.ScheduleCoverState
import com.txl.mebot.data.schedule.canDownload

/** 同一任务的图片全屏按比例显示，关闭及当前图片操作覆盖在画面上。 */
@Composable
internal fun ScheduleImagePreview(
    initialImageId: String,
    images: List<ScheduleLocalAttachment>,
    model: ScheduleAttachmentsViewModel?,
    state: ScheduleAttachmentsState,
    enabled: Boolean,
    onRemove: ((String) -> Unit)? = null,
    fallbackImage: ScheduleEntry? = null,
    onDismiss: () -> Unit,
) {
    val previewImages = images.map { it.previewEntry() }.ifEmpty { listOfNotNull(fallbackImage) }
    if (previewImages.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val colors = scheduleColors()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sessionId = images.firstOrNull()?.link?.sessionId ?: fallbackImage?.sessionId
    val pager = rememberPagerState(initialPage = previewImages.indexOfFirst { it.id == initialImageId }.coerceAtLeast(0)) { previewImages.size }
    com.txl.mebot.ui.common.PagerSelectionHaptics(pager) { previewImages.getOrNull(it)?.id }
    var actionRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    LaunchedEffect(state.sessionId) { if (model != null && state.sessionId != sessionId) onDismiss() }
    LaunchedEffect(previewImages.size) {
        if (pager.currentPage > previewImages.lastIndex) pager.scrollToPage(previewImages.lastIndex)
    }
    LaunchedEffect(actionRequested, state.busyAssetId, state.failure) {
        if (actionRequested && state.busyAssetId == null) {
            actionRequested = false
            state.failure?.let { Toast.makeText(context, context.getString(it.label()), Toast.LENGTH_SHORT).show() }
        }
    }
    val current = previewImages[pager.currentPage.coerceIn(0, previewImages.lastIndex)]
    val attached = state.items.firstOrNull { it.asset.id == current.id }
    val editable = model != null && enabled && state.sessionId == sessionId && attached != null &&
        !state.loading && !state.isImporting && state.busyAssetId == null
    val aiCover = images.isEmpty() && fallbackImage?.coverSource == MediaSource.AI_GENERATED
    val coverBusy = state.isRetryingCover || state.coverJob?.state in setOf(
        ScheduleCoverState.QUEUED, ScheduleCoverState.GENERATING, ScheduleCoverState.DOWNLOADING)
    val canRegenerate = aiCover && sessionId != null && model != null && enabled && state.sessionId == sessionId &&
        !state.loading && !state.isImporting && state.busyAssetId == null && !coverBusy
    LaunchedEffect(aiCover, state.coverJob?.attemptId, state.coverJob?.state, state.isRetryingCover, state.coverRetryFailed) {
        if (aiCover && state.sessionId == sessionId && !state.isRetryingCover) {
            val message = when {
                state.coverRetryFailed -> R.string.schedule_cover_retry_failed
                state.coverJob?.state == ScheduleCoverState.FAILED -> R.string.schedule_cover_regenerate_failed
                else -> null
            }
            message?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show() }
        }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, key = { previewImages[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                val image = previewImages.getOrNull(page) ?: return@HorizontalPager
                val photo = schedulePhoto(image, 2048)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (photo != null) Image(photo, stringResource(R.string.schedule_image_preview),
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Icon(painterResource(R.drawable.ic_image), stringResource(R.string.schedule_attachment_missing),
                        Modifier.size(40.dp), tint = Color.White.copy(alpha = 0.6f))
                }
            }
            IconButton(onClick = hapticAction(onDismiss),
                modifier = Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                    .padding(top = 8.dp, end = 12.dp).size(48.dp), shape = CircleShape,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(24.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (aiCover) model?.regenerateCover(sessionId)
                    else { actionRequested = true; model?.chooseCover(current.id, sessionId) }
                }, enabled = if (aiCover) canRegenerate else editable && attached?.link?.isCover != true,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.ink,
                        disabledContainerColor = colors.surface.copy(alpha = 0.55f), disabledContentColor = colors.secondary)) {
                    Text(stringResource(when {
                        !aiCover -> R.string.schedule_preview_set_cover
                        coverBusy -> R.string.schedule_cover_regenerating
                        state.coverJob?.state == ScheduleCoverState.FAILED && state.coverJob.canDownload(System.currentTimeMillis()) -> R.string.schedule_cover_retry_download
                        else -> R.string.schedule_cover_regenerate
                    }), fontSize = 12.sp, lineHeight = 16.sp)
                }
                OutlinedButton(onClick = {
                    actionRequested = true
                    if (onRemove != null) onRemove(current.id) else model?.remove(current.id, sessionId)
                }, enabled = editable, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface,
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = colors.surface.copy(alpha = 0.55f), disabledContentColor = colors.secondary)) {
                    Text(stringResource(R.string.schedule_preview_remove_image), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}
