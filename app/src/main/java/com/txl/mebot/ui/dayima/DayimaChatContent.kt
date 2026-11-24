package com.txl.mebot.ui.dayima

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaChatFailure
import com.txl.mebot.data.local.DayimaMessageRole
import com.txl.mebot.data.local.DayimaOperationState
import com.txl.mebot.ui.common.BotChatMessage
import com.txl.mebot.ui.common.botChatContentPadding
import com.txl.mebot.ui.schedule.scheduleChatTextSize
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun DayimaChatContent(state: DayimaChatState, model: DayimaChatViewModel?, modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    val list = rememberLazyListState()
    val operationFailure = state.operation?.failure?.let { runCatching { DayimaChatFailure.valueOf(it) }.getOrNull() }
    val failure = state.failure ?: operationFailure
    val retry = failure != null || state.operation?.state == DayimaOperationState.FAILED || state.operation?.state == DayimaOperationState.PENDING && !state.processing
    LaunchedEffect(state.sessionId, state.messages.lastOrNull()?.id, state.processing, failure) {
        val target = state.messages.size + (if ((state.messages.firstOrNull()?.sequence ?: 0) > 1) 1 else 0) +
            (if (state.processing) 1 else 0) + (if (retry && !state.processing) 1 else 0) - 1
        if (target >= 0) list.animateScrollToItem(target)
    }
    Column(modifier.fillMaxWidth()) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = botChatContentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty() && !state.loading) item("intro") {
                BotChatMessage(stringResource(R.string.dayima_chat_intro), user = false)
            }
            if ((state.messages.firstOrNull()?.sequence ?: 0) > 1) item("older") {
                TextButton(onClick = { model?.loadOlder() }, enabled = !state.olderLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.olderFailed) R.string.dayima_chat_older_failed else R.string.dayima_chat_older), fontSize = scheduleChatTextSize(14))
                }
            }
            items(state.messages, key = { it.id }) { message ->
                val user = message.role == DayimaMessageRole.USER
                BotChatMessage(message.content, user) {
                    state.messageImages[message.id]?.let { DayimaImages("message:${message.id}", it,
                        thumbnailSize = DpSize(100.dp, 104.dp), spacing = 6.dp) }
                }
            }
            if (state.processing) item("working") {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.dayima_chat_working), fontSize = scheduleChatTextSize(13), color = colors.secondary)
                }
            }
            if (retry && !state.processing) item("failure") {
                ChatNotice((failure ?: DayimaChatFailure.INTERRUPTED).text(), R.string.dayima_chat_retry,
                    enabled = !state.loading && !state.sending) { model?.retryAnalysis() }
            }
        }
    }
}

@Composable
internal fun DayimaChatDraftNotice(state: DayimaChatState, model: DayimaChatViewModel?) {
    if (state.draftState in setOf(DayimaDraftState.FAILED, DayimaDraftState.CONFLICT)) ChatNotice(
        if (state.draftState == DayimaDraftState.CONFLICT) R.string.dayima_chat_draft_conflict else R.string.dayima_chat_draft_failed,
        R.string.dayima_chat_keep_retry) { model?.retryInput() }
}

@Composable
internal fun ChatNotice(text: Int, action: Int, enabled: Boolean = true, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(text), Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
            fontSize = scheduleChatTextSize(11), lineHeight = scheduleChatTextSize(16))
        TextButton(onClick = onAction, enabled = enabled) { Text(stringResource(action), fontSize = scheduleChatTextSize(14)) }
    }
}

private fun DayimaChatFailure.text(): Int = when (this) {
    DayimaChatFailure.CONFIGURATION -> R.string.dayima_chat_configuration
    DayimaChatFailure.CREDENTIAL -> R.string.dayima_chat_credential
    DayimaChatFailure.AUTHORIZATION -> R.string.dayima_chat_authorization
    DayimaChatFailure.BILLING -> R.string.dayima_chat_billing
    DayimaChatFailure.NETWORK -> R.string.dayima_chat_network
    DayimaChatFailure.TIMEOUT -> R.string.dayima_chat_timeout
    DayimaChatFailure.RATE_LIMIT -> R.string.dayima_chat_rate_limit
    DayimaChatFailure.SERVICE -> R.string.dayima_chat_service
    DayimaChatFailure.INVALID_RESULT -> R.string.dayima_chat_invalid
    DayimaChatFailure.LOCAL_SAVE -> R.string.dayima_chat_local_failed
    DayimaChatFailure.CHANGED -> R.string.dayima_chat_changed
    DayimaChatFailure.INTERRUPTED -> R.string.dayima_chat_interrupted
}
