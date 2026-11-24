package com.txl.mebot.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.ui.common.BotComposerWorkspace

/** 业务状态仍由日程持有，输入、媒体面板与浮窗交互使用共用组件。 */
@Composable
internal fun ScheduleComposerWorkspace(
    model: ScheduleConversationViewModel?,
    attachmentModel: ScheduleAttachmentsViewModel?,
    attachmentActions: AttachmentPickerActions,
    expanded: Boolean,
    detailVisible: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSavedSchedule: (ScheduleEntry) -> Unit,
    modifier: Modifier = Modifier,
    homeTask: Boolean = false,
    content: @Composable (bottomContentPadding: Dp) -> Unit,
) {
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: ScheduleConversationState(isLoaded = true)
    val media = attachmentModel?.state?.collectAsStateWithLifecycle()?.value ?: ScheduleAttachmentsState()
    val colors = scheduleColors()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model?.setConversationVisible(true)
                Lifecycle.Event.ON_STOP -> model?.setConversationVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model?.setConversationVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model?.setConversationVisible(false) }
    }
    val sameSession = media.sessionId == state.sessionId
    val mediaBusy = media.loading || media.isImporting || media.busyAssetId != null || !sameSession
    val editable = state.isLoaded && !state.isLoading && !state.loadFailed && !state.isDeleted &&
        (expanded || !state.hasTask && !state.isAnalyzing)
    val canSend = editable && !state.isSending && !state.isAnalyzing && !mediaBusy &&
        (state.input.isNotBlank() || state.pendingAttachmentCount > 0)
    val displayMedia = media.takeIf { sameSession && !detailVisible } ?: ScheduleAttachmentsState()
    BotComposerWorkspace(
        input = state.input, editable = editable, sending = state.isSending, canSend = canSend,
        clearEnabled = !state.isLoading && !state.isSending && !mediaBusy,
        expanded = expanded, hasPendingMedia = displayMedia.items.any { it.link.pendingSend },
        hasConversation = state.messages.isNotEmpty() || state.isAnalyzing, working = state.isAnalyzing,
        onInputChanged = { model?.changeInput(it) }, onSaveInput = { model?.savePendingInput() },
        onSend = { model?.send() }, onClear = { model?.clearCreation() }, onExpand = onExpand, onDismiss = onDismiss,
        inputLabel = stringResource(R.string.schedule_input_label), modifier = modifier,
        showClearAction = !homeTask && state.isLoaded && !state.isLoading && !state.hasTask && !state.isDeleted,
        resetAfterCollapse = !homeTask && !detailVisible && !state.isAnalyzing && !state.isSending && !state.isLoading &&
            (state.hasTask || state.isDeleted),
        onCollapsed = { model?.openCreation() },
        notices = {
            if (sameSession && media.isImporting) Text(
                stringResource(R.string.schedule_attachment_importing, media.importingIndex, media.importingTotal),
                color = colors.secondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            if (sameSession) media.failure?.let {
                Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            if (state.loadFailed) TextButton(onClick = { model?.retry() }) {
                Text(stringResource(R.string.schedule_conversation_load_failed), color = colors.accent)
            }
        },
        pendingMedia = { addMedia ->
            SchedulePendingMedia(attachmentModel, displayMedia,
                editable && !state.isSending && !state.isAnalyzing && !mediaBusy, addMedia)
        },
        mediaPanel = { panelModifier ->
            ScheduleDeviceMediaPanel(attachmentModel, displayMedia, attachmentActions,
                editable && !state.isSending && !state.isAnalyzing && !mediaBusy, panelModifier)
        },
        conversation = {
            ScheduleConversationContent(model, Modifier.weight(1f), onOpenSavedSchedule, attachmentModel)
            ScheduleCoverStatus(attachmentModel, state.sessionId, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), smallText = true)
            Spacer(Modifier.height(8.dp))
        },
        content = content,
    )
}
