package com.txl.mebot.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.archive.ArchiveAnalysisStatus
import com.txl.mebot.ui.common.BotComposerWorkspace
import com.txl.mebot.ui.common.BotChatBubble
import com.txl.mebot.ui.common.BotChatHistoryButton
import com.txl.mebot.ui.common.BotChatMessage
import com.txl.mebot.ui.common.botChatContentPadding
import com.txl.mebot.ui.common.ComposerPendingMediaRow
import com.txl.mebot.ui.schedule.DeviceMediaPanel
import com.txl.mebot.ui.schedule.attachmentPickerActions
import com.txl.mebot.ui.schedule.scheduleChatTextSize
import com.txl.mebot.ui.schedule.scheduleChatBubbleTextSize
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun ArchiveComposer(state: ArchivePageState, model: ArchiveViewModel, modifier: Modifier,
    onHomeDismiss: (() -> Unit)? = null, content: @Composable (Dp) -> Unit) {
    val colors = scheduleColors()
    val current = state.context
    val editable = state.editable && (state.expanded || current?.record == null && !state.analyzing)
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = attachmentPickerActions(prepare = model::preparePicker, picked = { uris, _ -> model.picked(uris) },
        prepareCamera = model::prepareCamera, cameraResult = model::cameraResult, report = { model.attachmentFailure() })
    BotComposerWorkspace(input = state.input, editable = editable, sending = state.busy,
        canSend = editable && !state.analyzing && (state.input.isNotBlank() || state.pending.isNotEmpty()), clearEnabled = !state.busy && !state.loading,
        expanded = state.expanded, hasPendingMedia = state.pending.isNotEmpty(),
        hasConversation = !state.returningHome && (current?.messages?.isNotEmpty() == true || current?.record != null || state.candidate != null),
        working = state.analyzing, onInputChanged = model::changeInput, onSaveInput = { model.saveInput() }, onSend = { model.send() },
        onClear = { model.newSession() }, onExpand = model::expand, onDismiss = { model.collapse(); onHomeDismiss?.invoke() },
        inputLabel = stringResource(R.string.archive_input_label),
        placeholder = stringResource(if (state.expanded) R.string.archive_followup_placeholder else R.string.archive_input_placeholder),
        clearLabel = "新建档案会话", modifier = modifier,
        showClearAction = onHomeDismiss == null && current != null && current.record == null && !current.closed,
        resetAfterCollapse = onHomeDismiss == null && state.returningHome && !state.busy && state.error == null,
        onCollapsed = { model.finishReturnHome() },
        notices = {
            if (current?.closed == true) Text("这份档案已删除，可查看历史或新建会话。", color = colors.secondary, fontSize = 12.sp)
            if (state.busy || state.dirty) Row(Modifier.padding(start = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(7.dp))
                Text(if (state.busy) "正在处理本地内容…" else "正在保存草稿…", fontSize = 11.sp, color = colors.secondary)
            }
            state.error?.let { Text(it, Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (state.error != null) TextButton({ when {
                state.returningHome -> model.finishReturnHome()
                current == null -> model.retry()
                else -> model.saveInput()
            } }, enabled = !state.busy) { Text("重试") }
        },
        pendingMedia = { add ->
            ComposerPendingMediaRow(state.pending, key = { it.id }, isImage = { it.info.mimeType.startsWith("image/") },
                enabled = editable && !state.analyzing, onAdd = add, onRemove = { model.discard(it.id) },
                thumbnail = { asset, tileModifier ->
                    Surface(onClick = { preview = asset.id }, shape = RoundedCornerShape(13.dp), modifier = tileModifier) {
                        ArchivePicture(asset.id, model, Modifier.fillMaxSize(), label = asset.info.name)
                    }
                })
        },
        mediaPanel = { panel -> DeviceMediaPanel(actions, editable && !state.analyzing, model.selectedUris(), model::pickLocal, panel) },
        toolbarActions = { BotChatHistoryButton(enabled = !state.busy) { model.history() } },
        conversation = { ArchiveChatContent(state, model, { preview = it }, Modifier.weight(1f)) }, content = content)
    preview?.let { ArchiveAssetDialog(it, model, (state.context?.assets.orEmpty().map { asset -> asset.id } + it).distinct()) { preview = null } }
}

@Composable
internal fun ArchiveChatContent(state: ArchivePageState, model: ArchiveViewModel, onPreview: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    val current = state.context
    val list = rememberLazyListState()
    LaunchedEffect(current?.sessionId, current?.messages?.lastOrNull()?.id) {
        withFrameNanos { }
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0) list.animateScrollToItem(last)
    }

    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = botChatContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if ((current?.messages?.firstOrNull()?.sequence ?: 0) > 1) item {
            TextButton({ model.olderMessages() }, enabled = !state.busy) { Text("加载更早消息") }
        }
        if (current?.messages.isNullOrEmpty()) item {
            BotChatMessage(stringResource(R.string.archive_chat_hint), user = false)
        }
        items(current?.messages.orEmpty(), key = { it.id }) { message ->
            BotChatMessage(message.text, message.role == MessageRole.USER) {
                if (message.assetIds.isNotEmpty()) LazyRow(Modifier.height(104.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(message.assetIds, key = { it }) { id ->
                        val label = current?.assets?.find { it.id == id }?.info?.name ?: "附件"
                        Surface(onClick = { onPreview(id) }, modifier = Modifier.size(100.dp, 104.dp), shape = RoundedCornerShape(14.dp)) {
                            ArchivePicture(id, model, Modifier.fillMaxSize(), label = label)
                        }
                    }
                }
            }
        }
        if (state.analyzing) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在整理图文资料…", Modifier.weight(1f).padding(start = 8.dp), fontSize = scheduleChatTextSize(13))
                TextButton(model::stopAnalysis) { Text("取消") }
            }
        }
        if (!state.analyzing && state.analysis.status in setOf(ArchiveAnalysisStatus.FAILED, ArchiveAnalysisStatus.CANCELLED, ArchiveAnalysisStatus.INTERRUPTED)) item {
            Text(state.analysis.failure?.userMessage() ?: "已取消整理，原消息与附件仍保留。", color = colors.secondary,
                fontSize = scheduleChatTextSize(12), lineHeight = scheduleChatTextSize(19))
            TextButton({ model.retryAnalysis() }, enabled = state.editable && state.input.isBlank() && state.pending.isEmpty()) { Text("重新整理") }
            if (state.input.isNotBlank() || state.pending.isNotEmpty()) Text("有新资料时，请先发送当前输入。", fontSize = scheduleChatTextSize(11), color = colors.secondary)
        }
        if (state.analysis.status == ArchiveAnalysisStatus.READY && state.candidate != null) item {
            val candidate = state.candidate ?: return@item
            BotChatBubble(user = false) {
                Text(candidate.title, fontWeight = FontWeight.SemiBold, fontSize = scheduleChatBubbleTextSize(16))
                candidate.fields.take(8).forEach { field -> Text("${field.name}：${field.value}", fontSize = scheduleChatBubbleTextSize(14), lineHeight = scheduleChatBubbleTextSize(21)) }
                if (candidate.body.isNotBlank()) Text(candidate.body, maxLines = 5, overflow = TextOverflow.Ellipsis, fontSize = scheduleChatBubbleTextSize(14), lineHeight = scheduleChatBubbleTextSize(21))
                if (current?.draft?.content?.conflicts?.isNotEmpty() == true) Text("有 ${current.draft.content.conflicts.size} 项信息需核对", color = MaterialTheme.colorScheme.error, fontSize = scheduleChatBubbleTextSize(14))
            }
        }
        if (current != null && !current.closed && (state.candidate != null || current.record != null)) item {
            FilledTonalButton({ model.editCurrent() }, enabled = state.editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.analysis.status == ArchiveAnalysisStatus.READY && state.candidate != null) "核对整理草稿" else "手动编辑")
            }
        }
    }
}
