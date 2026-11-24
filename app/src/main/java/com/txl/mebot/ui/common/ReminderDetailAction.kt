package com.txl.mebot.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.home.HomeCard
import com.txl.mebot.data.home.HomeSource
import com.txl.mebot.data.local.HomeEventKind
import kotlinx.coroutines.*

@Stable
internal class ReminderDetailState(private val container: AppContainer?, private val scope: CoroutineScope) {
    var card by mutableStateOf<HomeCard?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var saved by mutableIntStateOf(0)
    fun toggle() {
        val target = card ?: return
        if (busy || container == null) return
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { container.home.setCompleted(target, !target.completed) }
                container.dayimaReminders.requestRefresh(); container.subscriptionReminders.requestRefresh()
                saved++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "提醒处理未能保存，请重试" }
            finally { busy = false }
        }
    }
}

/** 两个入口使用相同的来源提醒和写入校验，处理提醒不生成付款或经期记录。 */
@Composable
internal fun rememberReminderDetail(source: HomeSource, id: String, revision: Long, eventId: String? = null): ReminderDetailState {
    val container = (LocalContext.current.applicationContext as? MebotApplication)?.container
    val scope = rememberCoroutineScope()
    val state = remember(source, id, eventId, container) { ReminderDetailState(container, scope) }
    LaunchedEffect(state, revision) {
        if (container != null) try { state.card = container.home.reminder(source, id, eventId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state.error = "提醒暂未读取成功" }
    }
    return state
}

@Composable
internal fun ReminderDetailAction(state: ReminderDetailState, enabled: Boolean, onSaved: () -> Unit,
    onRecord: (() -> Unit)? = null) {
    SuccessHaptics(state.saved.takeIf { it > 0 })
    val close by rememberUpdatedState(onSaved)
    LaunchedEffect(state.saved) { if (state.saved > 0 && !state.busy) close() }
    val card = state.card
    if (card != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("${card.title} · ${card.day}", fontSize = 13.sp)
        if (card.body.isNotBlank()) Text(card.body, fontSize = 12.sp, lineHeight = 18.sp)
        Button(onClick = {
            if (card.eventKind == HomeEventKind.DAYIMA_MISSING) onRecord?.invoke() else state.toggle()
        }, enabled = enabled && !state.busy && !(card.eventKind == HomeEventKind.DAYIMA_MISSING && card.completed),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDCEFE1), contentColor = Color(0xFF255F39))) {
            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(when {
                card.eventKind == HomeEventKind.DAYIMA_MISSING && card.completed -> "已补记"
                card.eventKind == HomeEventKind.DAYIMA_MISSING -> "补记开始日期"
                card.completed -> "恢复提醒"
                else -> "完成提醒"
            })
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
}
