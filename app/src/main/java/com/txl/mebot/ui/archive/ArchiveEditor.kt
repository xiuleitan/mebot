package com.txl.mebot.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.archive.ArchiveContent
import com.txl.mebot.data.archive.ArchiveField
import com.txl.mebot.data.archive.ArchiveLink
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun ArchiveEditor(state: ArchivePageState, model: ArchiveViewModel) {
    val colors = scheduleColors()
    var last by remember { mutableStateOf(state.candidate ?: ArchiveContent("")) }
    SideEffect { state.candidate?.let { last = it } }
    val value = state.candidate ?: last
    com.txl.mebot.ui.common.SuccessHaptics(if (state.editorSaved && !state.busy) "saved" else null)
    var reload by rememberSaveable { mutableStateOf(false) }
    ArchiveSheet(if (state.context?.record == null) "新建档案" else "编辑档案", model::editorClosed,
        busy = state.busy || state.dirty, beforeClose = model::flushForNavigation) { close ->
        LaunchedEffect(state.editorSaved, state.busy) { if (state.editorSaved && !state.busy) close {} }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.context?.record?.content?.let { original -> item {
                val changed = archiveChangedSections(original, value)
                Text(if (changed.isEmpty()) "草稿与已保存内容一致" else "本次将修改：${changed.joinToString("、")}", color = colors.secondary, fontSize = 13.sp)
            } }
            if (state.context?.draft?.content?.conflicts?.isNotEmpty() == true) {
                item { Text("待确认信息", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                itemsIndexed(state.context.draft.content.conflicts) { index, conflict ->
                    Column {
                        Text(conflict, fontSize = 14.sp)
                        TextButton({ model.resolveConflict(index) }, enabled = state.editable) { Text("已核对这一项") }
                    }
                }
            }
            item { EditorInput("标题", value.title, state.editable, singleLine = true) { text -> model.changeEditor { it.copy(title = text) } } }
            item { EditorInput("正文", value.body, state.editable, minLines = 4) { text -> model.changeEditor { it.copy(body = text) } } }
            item { EditorInput("类型（可选）", value.type, state.editable, singleLine = true) { text -> model.changeEditor { it.copy(type = text) } } }
            item { EditorInput("标签（每行一个，可选）", value.tags.joinToString("\n"), state.editable) { text -> model.changeEditor { it.copy(tags = text.split('\n')) } } }
            item { Text("具体信息", fontWeight = FontWeight.SemiBold) }
            itemsIndexed(value.fields) { index, field ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EditorInput("字段名称", field.name, state.editable, Modifier.weight(1f), singleLine = true) { text ->
                            model.changeEditor { it.copy(fields = it.fields.mapIndexed { i, old -> if (i == index) old.copy(name = text) else old }) }
                        }
                        IconButton({ model.changeEditor { it.copy(fields = it.fields.filterIndexed { i, _ -> i != index }) } }, enabled = state.editable) {
                            Icon(painterResource(R.drawable.ic_close), "移除字段", Modifier.size(20.dp))
                        }
                    }
                    EditorInput("内容", field.value, state.editable) { text ->
                        model.changeEditor { it.copy(fields = it.fields.mapIndexed { i, old -> if (i == index) old.copy(value = text) else old }) }
                    }
                }
            }
            item { TextButton({ model.changeEditor { it.copy(fields = it.fields + ArchiveField("", "")) } }, enabled = state.editable && value.fields.size < 200) { Text("添加字段") } }
            item { Text("链接", fontWeight = FontWeight.SemiBold) }
            itemsIndexed(value.links) { index, link ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EditorInput("链接名称（可选）", link.label, state.editable, Modifier.weight(1f), singleLine = true) { text ->
                            model.changeEditor { it.copy(links = it.links.mapIndexed { i, old -> if (i == index) old.copy(label = text) else old }) }
                        }
                        IconButton({ model.changeEditor { it.copy(links = it.links.filterIndexed { i, _ -> i != index }) } }, enabled = state.editable) {
                            Icon(painterResource(R.drawable.ic_close), "移除链接", Modifier.size(20.dp))
                        }
                    }
                    EditorInput("完整网址", link.url, state.editable, keyboard = KeyboardType.Uri) { text ->
                        model.changeEditor { it.copy(links = it.links.mapIndexed { i, old -> if (i == index) old.copy(url = text) else old }) }
                    }
                }
            }
            item { TextButton({ model.changeEditor { it.copy(links = it.links + ArchiveLink("")) } }, enabled = state.editable && value.links.size < 100) { Text("添加链接") } }
            if (state.context?.assets?.isNotEmpty() == true) {
                item { Text("档案附件", fontWeight = FontWeight.SemiBold) }
                itemsIndexed(state.context.assets) { _, asset ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(asset.id in value.assetIds, onCheckedChange = { checked -> model.changeEditor { current ->
                                val ids = if (checked) (current.assetIds + asset.id).distinct() else current.assetIds - asset.id
                                current.copy(assetIds = ids, coverAssetId = current.coverAssetId?.takeIf { it in ids }
                                    ?: ids.firstOrNull { id -> state.context.assets.any { it.id == id && it.info.mimeType.startsWith("image/") } })
                            } }, enabled = state.editable)
                            ArchivePicture(asset.id, model, Modifier.size(56.dp), label = asset.info.name)
                            Text(asset.info.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp, maxLines = 3)
                        }
                        if (asset.info.mimeType.startsWith("image/") && asset.id in value.assetIds) TextButton({ model.changeEditor { it.copy(coverAssetId = asset.id) } }, enabled = state.editable) {
                            Text(if (value.coverAssetId == asset.id) "已设为封面" else "设为封面")
                        }
                    }
                }
            }
            item { Text("关闭后保留这份草稿。要登记另一份资料，请新建会话。", color = colors.secondary, fontSize = 12.sp) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (state.conflict || state.context?.record?.revision != state.context?.draft?.baseRecordRevision) TextButton({ reload = true }, enabled = !state.busy) { Text("重新载入已保存内容") }
            if (state.context?.draft?.content?.conflicts?.isNotEmpty() == true) Text("请先逐项核对待确认信息", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Button({ model.saveEditor() }, enabled = state.editable && state.context?.draft?.content?.conflicts.isNullOrEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.context?.record == null) "保存档案" else "保存修改")
            }
            if (state.busy || state.dirty) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null && state.dirty) TextButton({ model.saveInput() }, enabled = !state.busy) { Text("重试保存草稿") }
        }
        if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("重新载入已保存内容？") },
            text = { Text("这会替换当前未保存的编辑草稿；原有聊天记录仍会保留。") },
            confirmButton = { TextButton({ reload = false; model.reloadEditor() }) { Text("重新载入") } },
            dismissButton = { TextButton({ reload = false }) { Text("取消") } })
    }
}

internal fun archiveChangedSections(original: ArchiveContent, candidate: ArchiveContent): List<String> = buildList {
    if (original.title != candidate.title) add("标题")
    if (original.type != candidate.type) add("类型")
    if (original.tags != candidate.tags) add("标签")
    if (original.body != candidate.body) add("正文")
    if (original.fields != candidate.fields) add("具体信息")
    if (original.links != candidate.links) add("链接")
    if (original.assetIds != candidate.assetIds || original.coverAssetId != candidate.coverAssetId) add("附件与封面")
    if (original.rawInput != candidate.rawInput) add("原始说明")
}

@Composable
private fun EditorInput(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    singleLine: Boolean = false, minLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) },
        singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else 10, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
