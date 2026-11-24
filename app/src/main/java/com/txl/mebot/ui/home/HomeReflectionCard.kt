package com.txl.mebot.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.data.local.HomeReflectionMode
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.subscriptions.SubscriptionSheet
import com.txl.mebot.ui.subscriptions.SubscriptionSheetTitle

@Composable
internal fun HomeReflectionCard(state: HomeReflectionState, open: () -> Unit, modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    val content = state.record.content
    val imageMaxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() / 2 }
    // 图片独占通栏，文字保留与事项列表相同的左右留白。
    Box(modifier.fillMaxWidth()
        .then(if (content.mode == HomeReflectionMode.IMAGE) Modifier.heightIn(max = imageMaxHeight) else Modifier)
        .animateContentSize(tween(220)).heightIn(min = 48.dp).testTag("home_reflection_card")
        .clickable(interactionSource = null, indication = null, enabled = state.loaded || state.loadError != null,
            onClickLabel = "编辑写给自己的内容", onClick = hapticAction(open))) {
        Crossfade(content.mode to state.record.imageRelativePath, animationSpec = tween(220), label = "home_reflection") { (mode, path) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val photo = if (mode == HomeReflectionMode.IMAGE) localPhoto(path, maxDimension = 2048) else null
                if (photo != null) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val imageHeight = minOf(maxWidth * (photo.height / photo.width.toFloat()), imageMaxHeight)
                    HomeReflectionImage(photo, Modifier.fillMaxWidth().height(imageHeight))
                }
                else if (mode == HomeReflectionMode.TEXT && content.text.isNotBlank()) {
                    Text(content.text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("home_reflection_text"),
                        color = colors.ink, fontSize = 18.sp, lineHeight = 30.sp, textAlign = TextAlign.Start)
                } else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.loadError ?: if (!state.loaded) "读取寄语…" else "写给自己", color = colors.ink, fontSize = 18.sp,
                        lineHeight = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    if (state.loaded && state.loadError == null) Text(
                        if (mode == HomeReflectionMode.IMAGE) "点击添加或更换图片" else "留下一句话，或添加一张图片",
                        color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun HomeReflectionEditor(state: HomeReflectionState, model: HomeReflectionViewModel) {
    var pickerToken by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val token = pickerToken; pickerToken = null
        if (token != null) model.pickedPhoto(token, uri)
    }
    fun photo(entry: com.txl.mebot.data.local.HomeReflectionHistoryEntity?) {
        val request = model.preparePhoto(entry) ?: return
        pickerToken = request.token
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    com.txl.mebot.ui.common.SuccessHaptics(state.savedEvent.takeIf { it > 0 })
    val enabled = !state.busy && !state.historyLoading && !state.historyFailed && !state.closeRequested
    SubscriptionSheet(model::dismiss, dismissible = !state.busy) { close ->
        LaunchedEffect(state.closeRequested, state.busy) {
            if (state.closeRequested && !state.busy) close(null)
        }
        SubscriptionSheetTitle("写给自己", { close(null) }, !state.busy)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(hapticAction { model.editText() }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("home_reflection_add_text"), enabled = enabled) { Text("添加文字") }
            OutlinedButton(hapticAction { photo(null) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("home_reflection_add_image"), enabled = enabled) { Text("添加图片") }
        }
        HomeReflectionHistoryContent(state, model::refreshHistory, model::moreHistory, model::askDelete,
            edit = { if (it.mode == HomeReflectionMode.TEXT) model.editText(it) else photo(it) },
            use = { model.useHistory(it) })
    }
    state.textEditor?.let { HomeReflectionTextDialog(state, model) }
    state.deletion?.let { entry ->
        DetailDeleteConfirmationDialog("删除内容", model::cancelDelete, { model.delete() }, enabled = !state.busy,
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (com.txl.mebot.data.local.reflectionHistoryId(state.record.content) == entry.id)
                    "确定删除这条内容吗？首页展示的这条内容也会移除。" else "确定删除这条历史记录吗？")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp) }
            } })
    }
}
