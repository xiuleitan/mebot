package com.txl.mebot.ui.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.local.ScheduleEditorDraftEntity
import com.txl.mebot.ui.common.*
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleEditorSheet(page: ScheduleEditorState, change: ((ScheduleEditorDraftEntity) -> ScheduleEditorDraftEntity) -> Unit,
    save: () -> Unit, remove: (String) -> Unit, importImages: (String, List<android.net.Uri>) -> Unit,
    reload: () -> Unit, beforeDismiss: suspend () -> Boolean, onDismiss: () -> Unit) {
    val colors = scheduleColors()
    val draft = page.draft
    val enabled = !page.loading && !page.busy && !page.saved
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    var reloadConfirm by remember { mutableStateOf(false) }
    var pickerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val request = pickerTarget; pickerTarget = null
        if (request != null && uris.isNotEmpty()) importImages(request, uris)
    }
    SuccessHaptics(if (page.saved) draft?.requestId else null)
    BotEditorSheet(onDismiss, dismissible = !page.busy, beforeDismiss = beforeDismiss) { close ->
        LaunchedEffect(page.saved) { if (page.saved) close(null) }
        BotEditorSheetTitle("编辑日程", { close(null) }, !page.busy)
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("schedule_editor")) {
            if (page.loading) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            if (draft != null) {
                if (draft.isRecurring) Text("仅修改本次日程，附件由同一周期共用", color = colors.secondary,
                    fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider.copy(alpha = .7f))) {
                    Column {
                        ScheduleEditorRow("标题") {
                            ScheduleEditorInput(draft.title, { value -> change { it.copy(title = value) } }, "日程标题", enabled && !page.conflict, multiline = true)
                        }
                        ScheduleEditorRow("日期") {
                            TextButton(hapticAction { dateOpen = true }, enabled = enabled && !page.conflict,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("schedule_editor_date")) {
                                Text(draft.date.toString(), Modifier.weight(1f), fontSize = 14.sp)
                                Icon(painterResource(R.drawable.ic_calendar), "选择日期", Modifier.size(20.dp))
                            }
                        }
                        ScheduleEditorRow("时间") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(hapticAction { timeOpen = true }, enabled = enabled && !page.conflict,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("schedule_editor_time")) {
                                    Text(draft.time?.toString() ?: "不设时间", Modifier.fillMaxWidth(), fontSize = 14.sp)
                                }
                                if (draft.time != null) TextButton(hapticAction { change { it.copy(time = null) } }, enabled = enabled && !page.conflict) { Text("清除") }
                            }
                        }
                        ScheduleEditorRow("正文", multiline = true) {
                            ScheduleEditorInput(draft.detail, { value -> change { it.copy(detail = value) } }, "日程正文", enabled && !page.conflict, multiline = true)
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("附件 ${page.attachments.size}", Modifier.weight(1f), color = colors.secondary, fontSize = 13.sp)
                            TextButton(hapticAction { pickerTarget = draft.requestId; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = enabled && !page.conflict) { Text("添加图片") }
                        }
                        if (page.attachments.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(page.attachments, key = { it.asset.id }) { item ->
                                Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    val photo = if (item.isImage) localPhoto(item.asset.relativePath, null, maxDimension = 256) else null
                                    Box(Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)).background(colors.selected.copy(alpha = .3f)), contentAlignment = Alignment.Center) {
                                        if (photo != null) Image(photo, item.asset.originalName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else Icon(painterResource(if (item.isImage) R.drawable.ic_image else R.drawable.ic_file), item.asset.originalName, Modifier.size(28.dp))
                                    }
                                    if (!item.isImage) Text(item.asset.originalName ?: "文件", fontSize = 11.sp, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    TextButton(hapticAction { remove(item.asset.id) }, enabled = enabled && !page.conflict,
                                        modifier = Modifier.testTag("schedule_editor_remove_${item.asset.id}")) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
        page.error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (page.conflict || (!page.loading && draft == null)) TextButton({ reloadConfirm = true }, enabled = !page.busy) { Text("读取最新记录") }
        HorizontalDivider(color = colors.divider.copy(alpha = .5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(hapticAction { close(null) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !page.busy) { Text("取消") }
            Button(hapticAction(save), Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && draft != null && !page.conflict,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                Text(if (page.busy) "保存中…" else "保存")
            }
        }
    }
    if (dateOpen && draft != null) {
        val state = rememberDatePickerState(draft.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1900..9999)
        DatePickerDialog(onDismissRequest = { dateOpen = false }, confirmButton = {
            TextButton({ state.selectedDateMillis?.let { value -> change { it.copy(date = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()) } }; dateOpen = false }) { Text("确定") }
        }, dismissButton = { TextButton({ dateOpen = false }) { Text("取消") } }) { DatePicker(state) }
    }
    if (timeOpen && draft != null) {
        val time = draft.time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { timeOpen = false }, title = { Text("时间") }, text = { TimeInput(state) },
            confirmButton = { TextButton({ change { it.copy(time = LocalTime.of(state.hour, state.minute)) }; timeOpen = false }) { Text("确定") } },
            dismissButton = { TextButton({ timeOpen = false }) { Text("取消") } })
    }
    if (reloadConfirm) AlertDialog(onDismissRequest = { reloadConfirm = false }, title = { Text("读取最新记录？") },
        text = { Text("这会替换当前表单及未保存的附件修改。") }, confirmButton = { TextButton({ reloadConfirm = false; reload() }) { Text("读取") } },
        dismissButton = { TextButton({ reloadConfirm = false }) { Text("取消") } })
}

@Composable
private fun ScheduleEditorRow(label: String, multiline: Boolean = false, content: @Composable () -> Unit) {
    val colors = scheduleColors()
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically) {
            Text(label, Modifier.width(80.dp).padding(horizontal = 12.dp, vertical = 14.dp), color = colors.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            Box(Modifier.weight(1f)) { content() }
        }
        HorizontalDivider(color = colors.divider.copy(alpha = .55f), thickness = .5.dp)
    }
}

@Composable
private fun ScheduleEditorInput(value: String, changed: (String) -> Unit, label: String, enabled: Boolean, multiline: Boolean) {
    val colors = scheduleColors()
    BasicTextField(value, changed, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
        enabled = enabled, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 6 else 1,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.ink, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(colors.accent), decorationBox = { field -> Box(Modifier.fillMaxWidth().padding(12.dp)) { field() } })
}
