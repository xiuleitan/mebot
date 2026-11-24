package com.txl.mebot.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.txl.mebot.ui.schedule.*

internal typealias HomeComposerContent = @Composable (Dp) -> Unit
typealias HomeComposerHost = @Composable (Modifier, (String) -> Unit, HomeComposerContent) -> Unit

@Composable
internal fun HomeComposerWorkspace(model: HomeComposerViewModel?, onNotice: (String) -> Unit,
    modifier: Modifier = Modifier, content: @Composable (Dp) -> Unit) {
    var previewInput by rememberSaveable { mutableStateOf("") }
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: HomeComposerState(input = previewInput, loaded = true)
    com.txl.mebot.ui.common.SuccessHaptics(state.notice?.takeIf { it == "已保存闪念" })
    val colors = scheduleColors()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model?.savePendingInput() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model?.savePendingInput() }
    }
    LaunchedEffect(state.notice) { state.notice?.let { onNotice(it); model?.dismissNotice() } }
    val actions = attachmentPickerActions(prepare = { model?.preparePicker() == true },
        picked = { uris, images -> model?.picked(uris, images) }, prepareCamera = { model?.prepareCamera() },
        cameraResult = { model?.cameraResult(it) }, report = { model?.report(it) })
    val mediaEnabled = state.editable && !state.busy
    val selected = remember(state.items) { state.items.mapIndexedNotNull { index, item ->
        item.link.sourceUri?.let { it to index + 1 }
    }.toMap() }
    val previewNotice = stringResource(R.string.home_send_preview)
    BotComposerWorkspace(
        input = state.input, editable = state.editable, sending = state.dispatching, canSend = state.canSend,
        clearEnabled = false, expanded = false, hasPendingMedia = state.items.isNotEmpty(),
        hasConversation = false, working = false,
        onInputChanged = { if (model != null) model.changeInput(it) else previewInput = it },
        onSaveInput = { model?.savePendingInput() },
        onSend = { if (model != null) model.send() else onNotice(previewNotice) },
        onClear = {}, onExpand = {}, onDismiss = {}, inputLabel = stringResource(R.string.home_input_label),
        showClearAction = false, modifier = modifier,
        inputField = { fieldModifier, focused, send ->
            HomeBotTextField(state.input, state.editable, state.dispatching,
                changed = { if (model != null) model.changeInput(it) else previewInput = it },
                chooseBot = { if (model != null) model.chooseBot(it) else previewInput = com.txl.mebot.data.home.HomeBotMention.parse(previewInput).withBot(it) },
                onFocus = focused, onSend = send, label = stringResource(R.string.home_input_label), modifier = fieldModifier)
        },
        notices = {
            state.routeError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) }
            if (state.importingTotal > 0) Text(stringResource(R.string.schedule_attachment_importing,
                state.importingIndex, state.importingTotal), color = colors.secondary, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            state.failure?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            if (state.loadFailed || state.saveFailed) TextButton(onClick = { model?.retry() }) {
                Text(if (state.loadFailed) "首页草稿暂未读取成功，点击重试" else "草稿暂未保存，点击重试",
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        pendingMedia = { add -> HomePendingMedia(model, state, mediaEnabled, add) },
        mediaPanel = { panel -> DeviceMediaPanel(actions, model != null && mediaEnabled, selected,
            { model?.pickLocal(it) }, panel) },
        conversation = {}, content = content,
    )
}
