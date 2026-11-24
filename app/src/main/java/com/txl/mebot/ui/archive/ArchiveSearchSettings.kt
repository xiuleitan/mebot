package com.txl.mebot.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.data.archive.*
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.settings.settingsColors

/** App 设置中的搜索配置入口，独立于档案首页和每份档案的聊天。 */
@Composable
internal fun ArchiveSearchSettingsEntry() {
    val context = LocalContext.current
    val owner = remember(context) { context.archiveActivity() }
    val model = remember(owner) { ViewModelProvider(owner)[ArchiveSearchSettingsViewModel::class.java] }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.start()
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.start()
        onDispose { lifecycle.removeObserver(observer); model.stop(); model.close() }
    }
    val colors = settingsColors()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surface, contentColor = colors.ink) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("档案语义搜索", Modifier.semantics { heading() }, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("开启后，保存或修改档案会自动准备搜索，无需每次手动操作。", color = colors.secondary, fontSize = 13.sp, lineHeight = 20.sp)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else state.settings?.let {
                Text(if (it.values.enabled) "已启用 · ${it.values.model}" else "尚未启用", color = colors.secondary, fontSize = 13.sp)
            }
            if (!state.open) state.failure?.let { Text(it.semanticMessage(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            TextButton(if (state.settings == null) model::reload else model::open, enabled = !state.loading && !state.busy) {
                Text(if (state.settings == null) "重新加载搜索设置" else "配置语义搜索")
            }
        }
    }
    if (state.open) ArchiveSearchSettings(state, model)
}

@Composable
private fun ArchiveSearchSettings(state: ArchiveSearchSettingsState, model: ArchiveSearchSettingsViewModel) {
    val stored = state.settings ?: return
    val colors = scheduleColors()
    var values by remember(stored.revision, state.formGeneration) { mutableStateOf(stored.values) }
    var dimension by remember(stored.revision, state.formGeneration) { mutableStateOf(values.dimensions.toString()) }
    var sharedKey by remember(stored.revision, state.formGeneration) { mutableStateOf(!stored.hasOwnKey) }
    var key by remember(state.formGeneration) { mutableStateOf("") } // 凭据不进入可保存UI状态。
    val editable = !state.busy && !state.saved
    ArchiveSheet("档案语义搜索", model::close, state.busy) { close ->
        LaunchedEffect(state.saved, state.busy) { if (state.saved && !state.busy) { key = ""; close {} } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用语义搜索", Modifier.weight(1f))
                    Switch(values.enabled, { values = values.copy(enabled = it) }, enabled = editable)
                }
                Text("开启并保存一次即可。已有档案会自动准备搜索，以后新建或修改档案也会自动更新，无需逐次操作。向量请求只使用整理后的必要文字，不包含原图或聊天记录。", color = colors.secondary, fontSize = 12.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveEmbeddingProtocol.entries.forEach { protocol -> FilterChip(values.protocol == protocol, {
                        val endpoint = when (protocol) {
                            ArchiveEmbeddingProtocol.DASHSCOPE -> values.endpoint.replace("/compatible-mode/v1/embeddings", "/api/v1/services/embeddings/text-embedding/text-embedding")
                            ArchiveEmbeddingProtocol.OPENAI_COMPATIBLE -> values.endpoint.replace("/api/v1/services/embeddings/text-embedding/text-embedding", "/compatible-mode/v1/embeddings")
                        }
                        values = values.copy(protocol = protocol, endpoint = endpoint)
                    }, label = { Text(if (protocol == ArchiveEmbeddingProtocol.DASHSCOPE) "DashScope" else "兼容 API") }, enabled = editable) }
                }
            }
            item { OutlinedTextField(values.endpoint, { values = values.copy(endpoint = it) }, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text("向量接口完整 HTTPS 地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            item { OutlinedTextField(values.model, { values = values.copy(model = it) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true, label = { Text("向量模型") }) }
            item {
                OutlinedTextField(dimension, { dimension = it }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                    label = { Text("向量维数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("默认 1024；须与模型实际返回一致。地址、模型、维数或协议改变时会重建索引。", color = colors.secondary, fontSize = 12.sp)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sharedKey, { sharedKey = it; key = "" }, enabled = editable)
                    Text("复用上方已保存的 API Key", Modifier.weight(1f))
                }
                Text("请求发送到上方配置的地址；也可单独保存这个接口的密钥。", color = colors.secondary, fontSize = 12.sp)
                if (!sharedKey) CompositionLocalProvider(LocalSaveableStateRegistry provides null) { OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                    label = { Text(if (stored.hasOwnKey) "独立 API Key（留空保留）" else "独立 API Key") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)) }
            }
            item {
                val status = state.summary
                Text("已索引 ${status.ready} / ${status.total} · 处理中 ${status.running} · 待处理 ${status.queued} · 失败 ${status.failed}", fontSize = 13.sp)
                (state.failure ?: status.failure)?.let { Text(it.semanticMessage(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Row {
                    TextButton({ model.retry() }, enabled = editable && stored.values.enabled) { Text("重试未完成索引") }
                    TextButton({ model.retry(rebuild = true) }, enabled = editable && stored.values.enabled) { Text("重建全部索引") }
                }
                Text("索引可重建，清理或重建不会删除原档案、草稿和附件。", color = colors.secondary, fontSize = 12.sp)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            state.failure?.let {
                Text(if (it == ArchiveSemanticFailure.CHANGED) "搜索设置已变化，请重新载入后再保存。" else it.semanticMessage(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (it == ArchiveSemanticFailure.CHANGED) TextButton(model::reload, enabled = !state.busy) { Text("重新载入搜索设置") }
            }
            Button({
                val change = if (sharedKey) ArchiveEmbeddingKeyChange.UseShared else if (key.isNotBlank()) ArchiveEmbeddingKeyChange.Replace(key) else ArchiveEmbeddingKeyChange.Keep
                model.save(values.copy(dimensions = dimension.toIntOrNull() ?: 0), change)
            }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = editable && (sharedKey || stored.hasOwnKey || key.isNotBlank())) {
                Text("保存搜索设置")
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
