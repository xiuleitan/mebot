package com.txl.mebot.ui.home

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.data.home.*
import com.txl.mebot.ui.archive.*
import com.txl.mebot.ui.dayima.*
import com.txl.mebot.ui.schedule.*
import com.txl.mebot.ui.subscriptions.*
import kotlinx.coroutines.*
import java.time.LocalDate

/** 直接承载原 Bot 的输入和聊天组件；决策模型不参与已交接任务的后续对话。 */
@Composable
internal fun HomeRoutedComposer(home: HomeComposerViewModel?, schedule: ScheduleConversationViewModel?,
    media: ScheduleAttachmentsViewModel?, dayima: DayimaChatViewModel?, subscriptions: SubscriptionsViewModel?,
    onOpenCard: (HomeCard) -> Unit, onNotice: (String) -> Unit, modifier: Modifier, content: HomeComposerContent) {
    val page = home?.state?.collectAsStateWithLifecycle()?.value
    val ticket = page?.activeTask
    if (ticket == null) { HomeComposerWorkspace(home, onNotice, modifier, content); return }
    val bot = HomeBot.fromId(ticket.botId) ?: return
    val scope = rememberCoroutineScope()
    var expanded by remember(ticket.id) { mutableStateOf(true) }
    var ready by remember(ticket.id) { mutableStateOf(false) }
    val close = {
        expanded = false
        scope.launch { delay(230); home.closeTask() }
        Unit
    }
    LaunchedEffect(page.notice) { page.notice?.let { onNotice(it); home.dismissNotice() } }
    fun openRecord(source: HomeSource, id: String, title: String = "") {
        onOpenCard(HomeCard(id, source, id, LocalDate.now(), title, "", false, 0))
    }
    when (bot) {
        HomeBot.SCHEDULE -> {
            val state = schedule?.state?.collectAsStateWithLifecycle()?.value
            val actions = attachmentPickerActions(media)
            LaunchedEffect(ticket.id) {
                try {
                    withTimeout(15_000) { requireNotNull(schedule).acceptHomeMessage(ticket.targetId, ticket.messageId) }
                    media?.bind(ticket.targetId); ready = true
                } catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; onNotice("会话已保存，请在日程中继续查看"); home.closeTask() }
                catch (_: Exception) { onNotice("会话已保存，请在日程中继续查看"); home.closeTask() }
            }
            if (ready && state?.sessionId == ticket.targetId) ScheduleComposerWorkspace(schedule, media, actions,
                expanded, false, { expanded = true }, close, { entry -> close(); openRecord(HomeSource.SCHEDULE, entry.id, entry.title) },
                modifier, homeTask = true, content = content)
            else HomeComposerWorkspace(home, onNotice, modifier, content)
        }
        HomeBot.DAYIMA -> {
            val state = dayima?.state?.collectAsStateWithLifecycle()?.value
            DisposableEffect(dayima) { onDispose { dayima?.setVisible(false) } }
            val actions = attachmentPickerActions(prepare = { dayima?.prepareImagePicker() == true },
                picked = { uris, images -> dayima?.pickedImages(uris, images) }, prepareCamera = { dayima?.prepareCamera() },
                cameraResult = { dayima?.cameraResult(it) }, report = { dayima?.reportMedia(it) })
            LaunchedEffect(ticket.id) {
                try { withTimeout(15_000) { requireNotNull(dayima).acceptHomeMessage(ticket.targetId, ticket.messageId) }; ready = true }
                catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; onNotice("会话已保存，请在大姨妈中继续查看"); home.closeTask() }
                catch (_: Exception) { onNotice("会话已保存，请在大姨妈中继续查看"); home.closeTask() }
            }
            if (ready && state?.sessionId == ticket.targetId) {
                DayimaComposerWorkspace(state, dayima, actions, modifier, onHomeDismiss = close, content = content)
                DayimaChatHistory(state, dayima)
            } else HomeComposerWorkspace(home, onNotice, modifier, content)
        }
        HomeBot.SUBSCRIPTIONS -> {
            val state = subscriptions?.chat?.state?.collectAsStateWithLifecycle()?.value
            DisposableEffect(subscriptions) { onDispose { subscriptions?.chat?.saveInput() } }
            LaunchedEffect(ticket.id) {
                try { withTimeout(15_000) { requireNotNull(subscriptions).chat.acceptHomeMessage(ticket.targetId, ticket.messageId) }; ready = true }
                catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; onNotice("会话已保存，请在订阅管理中继续查看"); home.closeTask() }
                catch (_: Exception) { onNotice("会话已保存，请在订阅管理中继续查看"); home.closeTask() }
            }
            if (ready && state?.sessionId == ticket.targetId) SubscriptionComposerWorkspace(subscriptions, expanded, false,
                { expanded = true }, close, modifier, onHomeRecord = { openRecord(HomeSource.SUBSCRIPTION, it) }, content = content)
            else HomeComposerWorkspace(home, onNotice, modifier, content)
        }
        HomeBot.ARCHIVE -> {
            val owner = LocalContext.current.archiveActivity()
            val model = remember(owner) { ViewModelProvider(owner)[ArchiveViewModel::class.java] }
            val state by model.state.collectAsStateWithLifecycle()
            DisposableEffect(model) { onDispose { model.saveInput() } }
            LaunchedEffect(ticket.id) {
                try { withTimeout(15_000) { model.acceptHomeMessage(ticket.targetId, ticket.messageId) }; ready = true }
                catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; onNotice("会话已保存，请在档案中继续查看"); home.closeTask() }
                catch (_: Exception) { onNotice("会话已保存，请在档案中继续查看"); home.closeTask() }
            }
            if (ready && state.context?.sessionId == ticket.targetId) {
                ArchiveComposer(state, model, modifier, onHomeDismiss = close, content = content)
                if (state.editorOpen) ArchiveEditor(state, model)
                if (state.historyOpen) ArchiveHistory(state, model)
                state.detail?.let { ArchiveDetail(it, state, model) }
                LaunchedEffect(state.returningHome) { if (state.returningHome) { delay(230); home.closeTask() } }
            } else HomeComposerWorkspace(home, onNotice, modifier, content)
        }
        HomeBot.FLASHNOTE -> LaunchedEffect(ticket.id) { home.closeTask() }
    }
}
