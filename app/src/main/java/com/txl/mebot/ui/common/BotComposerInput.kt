package com.txl.mebot.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleChatColors
import com.txl.mebot.ui.schedule.scheduleColors

/** 日程、快捷记录及会话 Bot 共用同一输入栏与键盘发送行为。 */
@Composable
internal fun BotComposerInput(
    input: String, editable: Boolean, sending: Boolean, canSend: Boolean,
    mediaExpanded: Boolean, onAdd: () -> Unit, onInputChanged: (String) -> Unit,
    onInputFocus: () -> Unit, onSend: () -> Unit, inputLabel: String,
    hasPendingMedia: Boolean, modifier: Modifier = Modifier, placeholder: String? = null,
    pendingMedia: @Composable () -> Unit = {},
    inputField: (@Composable (Modifier, () -> Unit, () -> Unit) -> Unit)? = null,
) {
    val colors = scheduleColors()
    val palette = scheduleChatColors()
    val border = MaterialTheme.colorScheme.outline
    val plusRotation by animateFloatAsState(if (mediaExpanded) 45f else 0f, tween(220), label = "bot_plus_rotation")
    val buttonColors = IconButtonDefaults.iconButtonColors(containerColor = colors.selected, contentColor = colors.ink,
        disabledContainerColor = colors.selected, disabledContentColor = colors.ink.copy(alpha = 0.38f))
    val haptics = rememberAppHaptics()
    val send = { if (canSend && !sending) { haptics.tap(); onSend() } }
    Surface(modifier.fillMaxWidth().animateContentSize(tween(220)), shape = RoundedCornerShape(30.dp),
        color = palette.input, border = BorderStroke(1.dp, colors.divider)) {
        Column {
            AnimatedVisibility(hasPendingMedia, enter = expandVertically(tween(220)) + fadeIn(),
                exit = shrinkVertically(tween(200)) + fadeOut()) { pendingMedia() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticAction(onAdd), enabled = editable && !sending,
                    modifier = Modifier.size(48.dp).shadow(2.dp, CircleShape).border(1.dp, border, CircleShape),
                    shape = CircleShape, colors = buttonColors) {
                    Icon(painterResource(R.drawable.ic_plus), stringResource(if (mediaExpanded) R.string.close_panel else R.string.schedule_add_media),
                        Modifier.size(24.dp).graphicsLayer { rotationZ = plusRotation })
                }
                if (inputField != null) inputField(Modifier.weight(1f), onInputFocus, send)
                else BasicTextField(value = input, onValueChange = onInputChanged, enabled = editable, readOnly = sending,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = inputLabel }
                        .onFocusChanged { if (it.isFocused) onInputFocus() },
                    textStyle = LocalTextStyle.current.copy(color = colors.ink, fontSize = 14.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(colors.accent), maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty() && placeholder != null) Text(placeholder, color = colors.secondary,
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    })
                IconButton(onClick = { send() }, enabled = canSend && !sending,
                    modifier = Modifier.size(48.dp).shadow(2.dp, CircleShape).border(1.dp, border, CircleShape),
                    shape = CircleShape, colors = buttonColors) {
                    Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send_message), Modifier.size(24.dp))
                }
            }
        }
    }
}

/** 统一待发送附件的尺寸、移除触控区及继续添加入口，附件读取仍由各 Bot 负责。 */
@Composable
internal fun <T> ComposerPendingMediaRow(
    items: List<T>, key: (T) -> Any, isImage: (T) -> Boolean,
    enabled: Boolean, onAdd: () -> Unit, onRemove: (T) -> Unit,
    modifier: Modifier = Modifier, thumbnail: @Composable (T, Modifier) -> Unit,
) {
    val colors = scheduleColors()
    LazyRow(modifier.fillMaxWidth().height(96.dp),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = key) { item ->
            Box(Modifier.animateItem().width(if (isImage(item)) 88.dp else 132.dp).fillMaxHeight()) {
                thumbnail(item, Modifier.fillMaxSize().padding(top = 10.dp, end = 10.dp))
                IconButton(onClick = hapticAction { onRemove(item) }, enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(48.dp), shape = CircleShape) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(23.dp), shape = CircleShape, color = colors.ink.copy(alpha = 0.85f), border = BorderStroke(1.dp, colors.surface)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.schedule_remove_attachment), Modifier.size(16.dp), tint = colors.surface)
                            }
                        }
                    }
                }
            }
        }
        item(key = "add") {
            Surface(onClick = hapticAction(onAdd), enabled = enabled, shape = RoundedCornerShape(13.dp), color = colors.past,
                modifier = Modifier.width(76.dp).fillMaxHeight().padding(top = 10.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.schedule_add_media), Modifier.size(28.dp), tint = colors.secondary)
                }
            }
        }
    }
}
