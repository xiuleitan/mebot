package com.txl.mebot.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.ui.common.BotChatMessage
import com.txl.mebot.ui.common.botChatContentPadding
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.schedule.ScheduleAnalysisFailure
import com.txl.mebot.data.schedule.ScheduleAnalysisMaxAttempts

@Composable
internal fun ScheduleConversationContent(
    model: ScheduleConversationViewModel?, modifier: Modifier = Modifier,
    onOpenSavedSchedule: (ScheduleEntry) -> Unit = {},
    attachmentModel: ScheduleAttachmentsViewModel? = null,
) {
    if (model == null) return
    val state by model.state.collectAsStateWithLifecycle()
    key(state.sessionId) {
        ScheduleComposer(state, model::changeInput, model::retry, model::loadOlderMessages, modifier,
            onOpenSavedSchedule, attachmentModel)
    }
}

@Composable
internal fun ScheduleComposer(
    state: ScheduleConversationState,
    onInputChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier,
    onOpenSavedSchedule: (ScheduleEntry) -> Unit,
    attachmentModel: ScheduleAttachmentsViewModel?,
) {
    val colors = scheduleColors()
    val palette = scheduleChatColors()
    if (state.isDeleted) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.schedule_deleted_notice), color = colors.secondary, fontSize = scheduleChatTextSize(14))
        }
        return
    }
    val listState = rememberLazyListState()
    val example = stringResource(R.string.schedule_composer_example)
    LaunchedEffect(state.messages.lastOrNull()?.id, state.messageAttachments, state.taskEntry?.id, state.taskEntry?.revision, state.isAnalyzing, state.questions.size) {
        if (state.messages.isNotEmpty()) {
            val last = state.messages.lastIndex + (if (state.hasOlderMessages) 1 else 0) +
                (if (state.questions.isNotEmpty()) 1 else 0) + (if (state.isAnalyzing) 1 else 0) + (if (state.taskEntry != null) 1 else 0)
            withFrameNanos { }
            listState.animateScrollToItem(last)
        }
    }
    Column(modifier.fillMaxWidth()) {
        state.taskEntry?.takeIf { it.isRecurring }?.let { entry ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.schedule_recurrence_context, entry.date.scheduleLabel()), color = colors.accent, fontSize = scheduleChatTextSize(12), fontWeight = FontWeight.Medium)
                Text(stringResource(if (entry.recurrenceStopped) R.string.schedule_recurrence_stopped else R.string.schedule_recurrence_edit_hint), color = colors.secondary, fontSize = scheduleChatTextSize(11), lineHeight = scheduleChatTextSize(16))
            }
        }
        if (state.isLoading || !state.isLoaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.loadFailed) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.schedule_conversation_load_failed), color = colors.accent, fontSize = scheduleChatTextSize(14)) }
                } else CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = botChatContentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.hasOlderMessages) {
                    item(key = "older") {
                        TextButton(onClick = onLoadOlder, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.schedule_conversation_older), color = colors.accent, fontSize = scheduleChatTextSize(14))
                        }
                    }
                }
                if (state.messages.isEmpty()) {
                    item(key = "intro") {
                        Column(Modifier.background(palette.assistant, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.schedule_composer_intro), color = colors.ink, fontSize = scheduleChatBubbleTextSize(16))
                            TextButton(onClick = { onInputChanged(example.removePrefix("例如：")) }, contentPadding = PaddingValues(0.dp)) {
                                Text(example, color = colors.secondary, fontSize = scheduleChatBubbleTextSize(13))
                            }
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    val user = message.role == MessageRole.USER
                    BotChatMessage(message.content, user) {
                        ScheduleMessageMedia(state.messageAttachments[message.id].orEmpty(), attachmentModel)
                    }
                }
                if (state.questions.isNotEmpty()) {
                    item(key = "questions") {
                        Column(
                            modifier = Modifier.fillMaxWidth().background(palette.assistant, RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(stringResource(R.string.schedule_conversation_pending), color = colors.accent, fontSize = scheduleChatBubbleTextSize(12), fontWeight = FontWeight.Medium)
                            state.questions.forEach { Text(it.question, color = colors.ink, fontSize = scheduleChatBubbleTextSize(13), lineHeight = scheduleChatBubbleTextSize(21)) }
                        }
                    }
                }
                if (state.isAnalyzing) item(key = "analyzing") {
                    Row(Modifier.animateItem().fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (state.analysisAttempt > 1) stringResource(R.string.schedule_ai_retrying, state.analysisAttempt, ScheduleAnalysisMaxAttempts)
                            else stringResource(R.string.schedule_ai_working), color = colors.secondary, fontSize = scheduleChatTextSize(13))
                    }
                }
                state.taskEntry?.let { entry ->
                    item(key = "saved-task") {
                        Row(Modifier.animateItem().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ScheduleCard(
                                entry = entry,
                                onClick = { onOpenSavedSchedule(entry) },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val failed = state.loadFailed || state.historyFailed || state.saveState == ConversationSaveState.FAILED ||
            state.saveState == ConversationSaveState.CONFLICT || state.analysisFailure != null
        val label = when {
            state.loadFailed || state.historyFailed -> R.string.schedule_conversation_read_error
            state.saveState == ConversationSaveState.CONFLICT -> R.string.schedule_conversation_conflict
            state.saveState == ConversationSaveState.FAILED -> R.string.schedule_conversation_save_failed
            state.analysisFailure != null -> state.analysisFailure.label()
            state.isAnalyzing -> if (state.analysisAttempt > 1) R.string.schedule_ai_retrying else R.string.schedule_ai_working
            state.isSending -> R.string.schedule_conversation_sending
            state.saveState == ConversationSaveState.SAVING -> R.string.schedule_conversation_saving
            else -> null
        }
        val interrupted = label == R.string.schedule_ai_interrupted
        if (label != null && (state.isLoaded || failed) && !(interrupted && state.hasTask)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // 未创建任务的中断草稿保留重试入口，省去冗长的未解析提示文字。
                if (interrupted) Spacer(Modifier.weight(1f))
                else Text(if (label == R.string.schedule_ai_retrying) stringResource(label, state.analysisAttempt, ScheduleAnalysisMaxAttempts)
                    else stringResource(label), Modifier.weight(1f), color = colors.secondary, fontSize = scheduleChatTextSize(11), lineHeight = scheduleChatTextSize(16))
                if (failed) TextButton(onClick = onRetry, enabled = !state.isSending && !state.isAnalyzing) {
                    Text(stringResource(when {
                        state.saveState == ConversationSaveState.CONFLICT -> R.string.schedule_conversation_keep_input
                        state.analysisFailure != null -> R.string.schedule_ai_retry
                        else -> R.string.retry
                    }), color = colors.accent, fontSize = scheduleChatTextSize(14))
                }
            }
        }
    }
}

private fun ScheduleAnalysisFailure.label(): Int = when (this) {
    ScheduleAnalysisFailure.CONFIGURATION -> R.string.schedule_ai_configuration
    ScheduleAnalysisFailure.CREDENTIAL -> R.string.schedule_ai_credential
    ScheduleAnalysisFailure.AUTHORIZATION -> R.string.schedule_ai_authorization
    ScheduleAnalysisFailure.BILLING -> R.string.schedule_ai_billing
    ScheduleAnalysisFailure.NETWORK -> R.string.schedule_ai_network
    ScheduleAnalysisFailure.TIMEOUT -> R.string.schedule_ai_timeout
    ScheduleAnalysisFailure.RATE_LIMIT -> R.string.schedule_ai_rate_limit
    ScheduleAnalysisFailure.SERVICE -> R.string.schedule_ai_service
    ScheduleAnalysisFailure.INVALID_RESULT -> R.string.schedule_ai_invalid
    ScheduleAnalysisFailure.LOCAL_SAVE -> R.string.schedule_ai_local_save
    ScheduleAnalysisFailure.CHANGED -> R.string.schedule_ai_changed
    ScheduleAnalysisFailure.INTERRUPTED -> R.string.schedule_ai_interrupted
}
