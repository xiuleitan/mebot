package com.txl.mebot.ui.dayima

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayimaChatHistory(state: DayimaChatState, model: DayimaChatViewModel?) {
    val colors = scheduleColors()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mounted by remember { mutableStateOf(false) }
    var lastSessions by remember { mutableStateOf(state.sessions) }
    SideEffect { if (state.historyOpen) lastSessions = state.sessions }
    LaunchedEffect(state.historyOpen) {
        if (state.historyOpen) { mounted = true; if (sheet.hasExpandedState) sheet.show() }
        else if (mounted) { sheet.hide(); mounted = false }
    }
    if (mounted) {
        val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.85f }
        ModalBottomSheet(onDismissRequest = { model?.history(false) }, sheetState = sheet, sheetMaxWidth = 688.dp,
            scrimColor = botSheetScrim(), sheetGesturesEnabled = false, dragHandle = null,
            containerColor = colors.surface, contentColor = colors.ink) {
            Column(Modifier.fillMaxWidth().heightIn(max = height).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dayima_chat_history), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { model?.history(false) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.dayima_close), Modifier.size(22.dp))
                    }
                }
                DayimaChatDraftNotice(state, model)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.historyFailed) ChatNotice(R.string.dayima_chat_history_failed, R.string.dayima_retry) { model?.history(true) }
                val rows = if (state.historyOpen) state.sessions else lastSessions
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (rows.isEmpty() && !state.historyFailed) item { Text(stringResource(R.string.dayima_chat_history_empty), color = colors.secondary) }
                    items(rows, key = { it.id }) { session ->
                        Surface(onClick = { model?.selectChat(session.id) }, enabled = !state.loading && !state.deleting,
                            color = if (session.id == state.sessionId) colors.selected else colors.past, shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.padding(14.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(session.title.ifBlank { stringResource(R.string.dayima_chat_untitled) }, maxLines = 2, color = colors.ink)
                                    Text(Instant.ofEpochMilli(session.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")),
                                        color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                                    if (session.id == state.sessionId) Text(stringResource(R.string.dayima_chat_current), color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { model?.askDelete(session.id) }, enabled = !state.deleting && !state.loading, modifier = Modifier.size(48.dp)) {
                                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.dayima_chat_delete), Modifier.size(21.dp))
                                }
                            }
                        }
                    }
                    if (state.moreSessions) item { TextButton(onClick = { model?.moreSessions() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.dayima_chat_more_sessions))
                    } }
                }
            }
        }
    }
    if (state.deleteId != null) AlertDialog(onDismissRequest = { model?.askDelete(null) },
        title = { Text(stringResource(R.string.dayima_chat_delete_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dayima_chat_delete_body))
            if (state.deleteFailed) Text(stringResource(R.string.dayima_chat_delete_failed), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { model?.deleteChat() }, enabled = !state.deleting) { Text(stringResource(R.string.dayima_delete)) } },
        dismissButton = { TextButton(onClick = { model?.askDelete(null) }, enabled = !state.deleting) { Text(stringResource(R.string.dayima_cancel)) } })
}
