package com.txl.mebot.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.data.home.HomeBot
import com.txl.mebot.data.home.HomeBotMention
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.ui.schedule.scheduleColors

/** 删除路由标记的任何一部分时，按整块删除；普通正文的选区和退格交给输入法。 */
internal fun atomicHomeMentionEdit(before: TextFieldValue, after: TextFieldValue): TextFieldValue {
    val mention = HomeBotMention.parse(before.text)
    if (!mention.hasMention && before.selection == TextRange.Zero && after.selection == TextRange(1) && after.text == "@${before.text}") {
        return TextFieldValue("@ ${before.text}", TextRange(1))
    }
    if (mention.bot == null || after.text.length >= before.text.length) return after
    val start = before.text.commonPrefixWith(after.text).length
    val suffix = before.text.substring(start).commonSuffixWith(after.text.substring(start)).length
    val removedEnd = before.text.length - suffix
    val insertedEnd = after.text.length - suffix
    if (start < mention.bodyStart && insertedEnd == start) {
        return TextFieldValue(before.text.substring(maxOf(mention.bodyStart, removedEnd)), TextRange.Zero)
    }
    return after
}

/** 自动插入／更换 @Bot 时，光标和中文组合输入继续跟随原正文。 */
internal fun homeMentionExternalValue(before: TextFieldValue, text: String): TextFieldValue {
    if (before.text == text) return before
    val old = HomeBotMention.parse(before.text)
    val next = HomeBotMention.parse(text)
    if (old.body == next.body) {
        val delta = next.bodyStart - old.bodyStart
        fun shift(offset: Int) = (if (offset >= old.bodyStart) offset + delta else next.bodyStart).coerceIn(0, text.length)
        return TextFieldValue(text, TextRange(shift(before.selection.start), shift(before.selection.end)),
            before.composition?.let { TextRange(shift(it.start), shift(it.end)) })
    }
    return TextFieldValue(text, TextRange(text.length))
}

@Composable
internal fun HomeBotTextField(input: String, editable: Boolean, sending: Boolean,
    changed: (String) -> Unit, chooseBot: (HomeBot) -> Unit,
    onFocus: () -> Unit, onSend: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(input, TextRange(input.length))) }
    val reconciled = homeMentionExternalValue(value, input)
    SideEffect { if (value != reconciled) value = reconciled }
    val mention = HomeBotMention.parse(input)
    val tint = mention.bot?.let { homeBotColor(it) } ?: colors.secondary
    val annotated = buildAnnotatedString {
        append(input)
        if (mention.bot != null) addStyle(SpanStyle(color = tint), 0, mention.tokenEnd)
    }
    val rendered = reconciled.copy(annotatedString = annotated)
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(input, editable, sending) {
        if (editable && !sending && mention.hasMention && mention.tokenEnd == 1) menu = true
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val maxHeight = with(LocalDensity.current) { 63.sp.toDp() + 16.dp }.coerceAtLeast(48.dp)
    Box(modifier.heightIn(min = 48.dp, max = maxHeight), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
            BasicTextField(value = rendered, onValueChange = { proposal ->
                val edited = atomicHomeMentionEdit(reconciled, proposal)
                if (edited.text == input || editable && !sending) {
                    value = edited.copy(annotatedString = AnnotatedString(edited.text))
                    if (edited.text != input) changed(edited.text)
                }
            }, enabled = editable, readOnly = sending,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = label
                    customActions = listOf(CustomAccessibilityAction("选择本次发送的 Bot") {
                        if (editable && !sending) { menu = true; true } else false
                    })
                }.testTag("home_route_input").onFocusChanged { if (it.isFocused) onFocus() },
                textStyle = LocalTextStyle.current.copy(color = colors.ink, fontSize = 14.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(colors.accent), onTextLayout = { layout = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth().pointerInput(input, editable, sending) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val result = layout
                                val hit = editable && !sending && mention.hasMention && result != null &&
                                    (0 until minOf(mention.bodyStart, result.layoutInput.text.length)).any {
                                        result.getBoundingBox(it).contains(down.position)
                                    }
                                if (hit) {
                                    down.consume()
                                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                    if (up != null) { up.consume(); menu = true }
                                }
                            }
                        }) { inner() }
                    }
                })
        }
        DropdownMenu(menu, { menu = false }) {
            HomeBot.entries.forEach { bot ->
                val color = homeBotColor(bot)
                DropdownMenuItem(text = { Text("@${bot.label}", color = color) }, enabled = editable && !sending,
                    onClick = hapticAction { chooseBot(bot); menu = false })
            }
        }
    }
}
