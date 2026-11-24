package com.txl.mebot.ui.dayima

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.local.DayimaOperationState
import com.txl.mebot.ui.common.BotComposerWorkspace
import com.txl.mebot.ui.common.BotChatHistoryButton
import com.txl.mebot.ui.schedule.AttachmentPickerActions
import com.txl.mebot.ui.schedule.DeviceMediaPanel
import com.txl.mebot.ui.schedule.label

/** 输入栏、媒体面板及本次任务浮窗直接复用日程组件。 */
@Composable
internal fun DayimaComposerWorkspace(state: DayimaChatState, model: DayimaChatViewModel?, actions: AttachmentPickerActions,
    modifier: Modifier = Modifier, onHomeDismiss: (() -> Unit)? = null, content: @Composable (Dp) -> Unit) {
    val editable = state.composerEditable && model != null
    BotComposerWorkspace(
        input = state.input, editable = editable, sending = state.sending, canSend = editable && state.canSend,
        clearEnabled = model != null && !state.loading && !state.sending && !state.deleting && !state.media.busy,
        expanded = state.expanded, hasPendingMedia = state.media.images.isNotEmpty(),
        hasConversation = state.messages.isNotEmpty() || state.processing, working = state.processing,
        onInputChanged = { model?.changeInput(it) }, onSaveInput = { model?.savePendingInput() },
        onSend = { model?.send() }, onClear = { model?.askDelete(state.sessionId) },
        onExpand = { model?.expand() }, onDismiss = { model?.expand(false); onHomeDismiss?.invoke() },
        inputLabel = stringResource(R.string.dayima_chat_hint), modifier = modifier,
        clearLabel = stringResource(R.string.dayima_chat_delete),
        showClearAction = state.loaded && !state.loading && state.operation?.state != DayimaOperationState.SUCCEEDED,
        resetAfterCollapse = onHomeDismiss == null && state.readyForNewTask, onCollapsed = { model?.finishTask() },
        notices = {
            if (state.loadFailed) ChatNotice(R.string.dayima_chat_load_failed, R.string.dayima_retry) { model?.retryLoad() }
            DayimaChatDraftNotice(state, model)
            if (state.sendFailed) ChatNotice(R.string.dayima_chat_send_failed, R.string.dayima_chat_retry_send) {
                model?.expand(); model?.send()
            }
            state.media.failure?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
            if (state.media.importing || state.media.preparingCamera) LinearProgressIndicator(Modifier.fillMaxWidth())
        },
        pendingMedia = { add ->
            DayimaImages("draft:${state.sessionId}", state.media.images,
                dense = true, enabled = editable && !state.processing,
                onRemove = { model?.removeDraftImage(it) }, onAdd = add)
        },
        mediaPanel = { panelModifier ->
            val selected = state.media.selectedSources.mapNotNull { (uri, id) ->
                state.media.images.indexOfFirst { it.asset.id == id }.takeIf { it >= 0 }?.let { uri to it + 1 }
            }.toMap()
            DeviceMediaPanel(actions, editable && !state.processing, selected, { model?.pickLocalImage(it) }, panelModifier)
        },
        toolbarActions = { BotChatHistoryButton(enabled = !state.loading && !state.deleting && model != null) { model?.history(true) } },
        conversation = { DayimaChatContent(state, model, Modifier.weight(1f)) }, content = content,
    )
}
