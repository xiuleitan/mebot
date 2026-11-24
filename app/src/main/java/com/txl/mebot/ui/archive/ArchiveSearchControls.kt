package com.txl.mebot.ui.archive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.data.archive.*
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun ArchiveSearchStatus(state: ArchivePageState, model: ArchiveViewModel) {
    val colors = scheduleColors()
    val summary = state.indexSummary
    val waiting = summary.queued + summary.running
    val enabled = state.indexSettings?.values?.enabled == true
    if (state.submittedQuery == null && state.indexFailure == null && (!enabled || waiting + summary.failed == 0)) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (enabled && (waiting > 0 || summary.failed > 0)) Text(
            if (summary.failed > 0) "${summary.failed} 份档案尚未准备好搜索，可在 App 设置 → 模型与 API 中查看。"
            else "正在为 $waiting 份档案准备搜索…", fontSize = 11.sp, color = colors.secondary)
        state.submittedQuery?.let {
            if (state.searchLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.localTextSearch) "正在本地查找…" else "正在语义搜索…", Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(model::cancelSearch) { Text("取消") }
                }
            } else state.searchFailure?.let { failure -> Text(failure.semanticMessage(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                ?: Text(if (state.localTextSearch) "本地文字匹配 · 按最近更新显示" else "按语义关联度排序 · ${summary.missing} 份档案尚未纳入", fontSize = 12.sp, color = colors.secondary)
            Row {
                if (state.searchFailure != null) TextButton({ model.search() }) { Text("重新搜索") }
                TextButton({ model.changeQuery("") }) { Text("全部档案") }
            }
        }
        if (state.submittedQuery == null && state.indexFailure != null) Text(state.indexFailure.semanticMessage(), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
    }
}
