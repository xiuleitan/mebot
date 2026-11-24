package com.txl.mebot.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleChatColors
import com.txl.mebot.ui.schedule.scheduleColors

/** 各会话 Bot 共用日程的输入、媒体面板与会话浮窗；业务数据由各自调用方负责。 */
@Composable
internal fun BotComposerWorkspace(
    input: String,
    editable: Boolean,
    sending: Boolean,
    canSend: Boolean,
    clearEnabled: Boolean,
    expanded: Boolean,
    hasPendingMedia: Boolean,
    hasConversation: Boolean,
    working: Boolean,
    onInputChanged: (String) -> Unit,
    onSaveInput: () -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    inputLabel: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    showClearAction: Boolean = true,
    clearLabel: String = stringResource(R.string.schedule_clear_draft),
    resetAfterCollapse: Boolean = false,
    onCollapsed: () -> Unit = {},
    notices: @Composable ColumnScope.() -> Unit = {},
    inputField: (@Composable (Modifier, () -> Unit, () -> Unit) -> Unit)? = null,
    pendingMedia: @Composable (onAdd: () -> Unit) -> Unit = {},
    mediaPanel: @Composable (Modifier) -> Unit,
    toolbarActions: @Composable RowScope.() -> Unit = {},
    conversation: @Composable ColumnScope.() -> Unit,
    content: @Composable (bottomContentPadding: Dp) -> Unit,
) {
    val colors = scheduleColors()
    val chatColors = scheduleChatColors()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(76.dp) }
    val chatVisibility = remember { MutableTransitionState(false) }
    SideEffect { chatVisibility.targetState = expanded }
    val chatTransition = rememberTransition(chatVisibility, label = "bot_chat_workspace")
    var mediaExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            onSaveInput()
            mediaExpanded = false
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(expanded, chatVisibility.isIdle, chatVisibility.currentState, resetAfterCollapse) {
        if (!expanded && chatVisibility.isIdle && !chatVisibility.currentState && resetAfterCollapse) onCollapsed()
    }
    val close = {
        onSaveInput()
        mediaExpanded = false
        focus.clearFocus()
        keyboard?.hide()
        onDismiss()
    }
    val send = {
        mediaExpanded = false
        focus.clearFocus()
        keyboard?.hide()
        onExpand()
        onSend()
    }
    val addMedia: () -> Unit = {
        if (editable && !sending) { focus.clearFocus(); keyboard?.hide(); mediaExpanded = !mediaExpanded }
    }
    BackHandler(enabled = expanded || mediaExpanded) { if (mediaExpanded) mediaExpanded = false else close() }
    BoxWithConstraints(modifier) {
        val targetHeight = maxHeight * 0.75f
        val pickerHeight = (maxHeight * 0.4f).coerceIn(180.dp, 340.dp)
            .coerceAtMost((maxHeight - 160.dp).coerceAtLeast(0.dp))
        Box(Modifier.fillMaxSize()) { content(dockHeight) }
        chatTransition.AnimatedVisibility(
            visible = { it }, modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(220)), exit = fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize().background(botSheetScrim())
                .clickable(interactionSource = null, indication = null, onClick = close))
        }
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val bodyHeight = maxHeight
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    chatTransition.AnimatedVisibility(
                        visible = { it }, modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 8 },
                        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 8 },
                    ) {
                        Surface(
                            Modifier.fillMaxWidth().height(targetHeight.coerceAtMost((bodyHeight - 20.dp).coerceAtLeast(0.dp))),
                            shape = RoundedCornerShape(22.dp), color = chatColors.panel, contentColor = colors.ink,
                            shadowElevation = 18.dp, border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.65f)),
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
                                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    toolbarActions()
                                    if (showClearAction) {
                                        IconButton(onClick = onClear, enabled = clearEnabled,
                                            modifier = Modifier.size(48.dp), shape = RoundedCornerShape(12.dp),
                                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent,
                                                contentColor = MaterialTheme.colorScheme.error,
                                                disabledContainerColor = Color.Transparent, disabledContentColor = colors.pastInk)) {
                                            Icon(painterResource(R.drawable.ic_delete), clearLabel, Modifier.size(22.dp))
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    IconButton(onClick = close, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(12.dp),
                                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = colors.ink)) {
                                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(21.dp))
                                    }
                                }
                                conversation()
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { dockHeight = with(density) { it.height.toDp() } }) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    notices()
                    if (!expanded && hasConversation) {
                        FilledTonalButton(onClick = onExpand, modifier = Modifier.align(Alignment.End),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(stringResource(if (working) R.string.schedule_ai_working else R.string.schedule_open_chat), fontSize = 12.sp)
                        }
                    }
                    BotComposerInput(input, editable, sending, canSend, mediaExpanded, addMedia,
                        onInputChanged, { mediaExpanded = false }, send, inputLabel, hasPendingMedia,
                        placeholder = placeholder, pendingMedia = { pendingMedia(addMedia) }, inputField = inputField)
                }
                AnimatedVisibility(mediaExpanded, enter = expandVertically(tween(260)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(150))) {
                    mediaPanel(Modifier.fillMaxWidth().height(pickerHeight))
                }
            }
        }
    }
}
