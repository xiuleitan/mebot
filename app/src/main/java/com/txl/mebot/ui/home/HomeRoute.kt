package com.txl.mebot.ui.home

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.data.home.HomeSource
import com.txl.mebot.ui.dayima.DayimaRoute
import com.txl.mebot.ui.dayima.DayimaViewModel
import com.txl.mebot.ui.dayima.DayimaChatViewModel
import com.txl.mebot.ui.flashnote.FlashnoteRoute
import com.txl.mebot.ui.flashnote.FlashnoteViewModel
import com.txl.mebot.ui.schedule.*
import com.txl.mebot.ui.subscriptions.SubscriptionsRoute
import com.txl.mebot.ui.subscriptions.SubscriptionsViewModel

@Composable
fun HomeRoute(model: HomeViewModel, onOpenDrawer: () -> Unit, onOpenTrading: () -> Unit, onSearchArchive: (String) -> Unit,
    scheduleModel: ScheduleViewModel?, conversationModel: ScheduleConversationViewModel?,
    attachmentModel: ScheduleAttachmentsViewModel?, flashnoteModel: FlashnoteViewModel?,
    subscriptionsModel: SubscriptionsViewModel?, dayimaModel: DayimaViewModel?, detailsEnabled: Boolean = true,
    reflectionModel: HomeReflectionViewModel? = null, composerModel: HomeComposerViewModel? = null,
    dayimaChatModel: DayimaChatViewModel? = null, scheduleEditorModel: ScheduleEditorViewModel? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.visible(true)
            if (event == Lifecycle.Event.ON_STOP) model.visible(false)
        }
        lifecycle.addObserver(observer)
        model.visible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model.visible(false); model.close() }
    }
    val reflection = reflectionModel?.state?.collectAsStateWithLifecycle()?.value
    HomeScreen(onOpenDrawer = onOpenDrawer, onOpenTrading = onOpenTrading, today = state.today, state = state,
        onSearchArchive = onSearchArchive,
        onOpenCard = model::open, onSelectNote = model::selectNote, onPinNote = model::pinNote, onMore = model::more,
        onRetry = model::refresh, onNoticeConsumed = model::dismissNotice,
        composerModel = composerModel,
        composerHost = { mod, notice, body -> HomeRoutedComposer(composerModel, conversationModel, attachmentModel,
            dayimaChatModel, subscriptionsModel, model::open, notice, mod, body) },
        reflectionCard = if (reflection != null) { { HomeReflectionCard(reflection, reflectionModel::open) } } else null)
    if (detailsEnabled && reflection?.editing == true) HomeReflectionEditor(reflection, reflectionModel)
    // 只向原 Bot 传递稳定来源；详情、编辑、删除和媒体均由原流程负责。
    if (detailsEnabled) state.detail?.let { card -> key(card.id) {
        val close = model::close
        when (card.source) {
            HomeSource.SCHEDULE -> ScheduleRoute(scheduleModel, conversationModel, onOpenDrawer, close, attachmentModel,
                initialDetail = card.schedule?.asEntry(), detailsOnly = true, onDetailClosed = close, editorModel = scheduleEditorModel)
            HomeSource.FLASHNOTE -> FlashnoteRoute(flashnoteModel, onOpenDrawer, close,
                detailId = card.sourceId, onDetailClosed = close)
            HomeSource.SUBSCRIPTION -> SubscriptionsRoute(onOpenDrawer, close, subscriptionsModel,
                detailId = card.sourceId, reminderEventId = card.eventId, onDetailClosed = close)
            HomeSource.DAYIMA -> DayimaRoute(dayimaModel, onOpenDrawer, close,
                detailId = card.resolvedById ?: card.sourceId, reminderEventId = card.eventId, onDetailClosed = close)
        }
    } }
}
