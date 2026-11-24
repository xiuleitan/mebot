package com.txl.mebot.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.common.DetailDeleteButton
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ColumnScope.HomeReflectionHistoryContent(state: HomeReflectionState, retry: () -> Unit, more: () -> Unit,
    remove: (HomeReflectionHistoryEntity) -> Unit, edit: (HomeReflectionHistoryEntity) -> Unit, use: (String) -> Unit) {
    val colors = scheduleColors()
    val list = rememberLazyListState()
    val id = reflectionHistoryId(state.record.content)
    val active = id?.let { key -> state.history.firstOrNull { it.content.id == key } ?: HomeReflectionHistoryRecord(
        HomeReflectionHistoryEntity(key, state.record.content.mode,
            text = if (state.record.content.mode == HomeReflectionMode.TEXT) state.record.content.text else "",
            imageAssetId = if (state.record.content.mode == HomeReflectionMode.IMAGE) state.record.content.imageAssetId else null,
            firstUsedAt = state.record.content.updatedAt, lastUsedAt = state.record.content.updatedAt), state.record.imageRelativePath) }
    val previous = state.history.filter { it.content.id != id }
    val enabled = !state.busy && !state.historyLoading && !state.historyFailed && !state.closeRequested
    LaunchedEffect(id, state.closeRequested) { if (!state.closeRequested) list.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("home_reflection_history_list"), state = list,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "current") {
            if (active != null) ReflectionRecordCard(active, current = true, enabled, remove, edit, use,
                Modifier.animateItem())
            else Text("还没有展示内容，添加一段文字或图片吧", Modifier.fillMaxWidth().padding(vertical = 24.dp), color = colors.secondary, fontSize = 14.sp, lineHeight = 22.sp)
        }
        items(previous, key = { it.content.id }) { record -> ReflectionRecordCard(record, current = false, enabled, remove, edit, use, Modifier.animateItem()) }
        if (state.busy || state.historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message -> item { Column {
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp)
            if (state.historyFailed) TextButton(hapticAction(retry), enabled = !state.busy && !state.historyLoading) { Text("重新加载") }
        } } }
        if (state.historyHasMore) item { TextButton(hapticAction(more), Modifier.fillMaxWidth(), enabled = enabled) { Text("加载更多") } }
    }
}

@Composable
private fun ReflectionRecordCard(record: HomeReflectionHistoryRecord, current: Boolean, enabled: Boolean,
    remove: (HomeReflectionHistoryEntity) -> Unit, edit: (HomeReflectionHistoryEntity) -> Unit, use: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    val entry = record.content
    Surface(onClick = hapticAction { use(entry.id) }, enabled = enabled, shape = RoundedCornerShape(16.dp), color = colors.past,
        modifier = modifier.fillMaxWidth().animateContentSize().testTag("home_reflection_history_${entry.id}")) {
        Column(Modifier.fillMaxWidth().padding(12.dp).then(if (current) Modifier.testTag("home_reflection_current") else Modifier), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (current) "当前展示" else Instant.ofEpochMilli(entry.lastUsedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")), color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
            if (entry.mode == HomeReflectionMode.TEXT) Text(entry.text, color = colors.ink, fontSize = 16.sp, lineHeight = 24.sp)
            else {
                val photo = localPhoto(record.imageRelativePath)
                if (photo != null) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    val aspect = photo.width / photo.height.toFloat()
                    val width = minOf(maxWidth, 300.dp * aspect)
                    Image(photo, if (current) "当前展示的图片" else "历史图片", Modifier.size(width, width / aspect)
                        .clip(RoundedCornerShape(12.dp)).testTag("home_reflection_history_image_${entry.id}"), contentScale = ContentScale.Fit)
                }
                else Text("图片暂时无法读取", color = colors.secondary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                DetailDeleteButton({ remove(entry) }, Modifier.weight(1f).testTag("home_reflection_delete_${entry.id}"), enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp))
                Button(hapticAction { edit(entry) }, enabled = enabled, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home_reflection_edit_${entry.id}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDEBFF), contentColor = Color(0xFF245580))) { Text("编辑", fontSize = 14.sp) }
            }
        }
    }
}
