package com.txl.mebot.ui.schedule

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.theme.MebotTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import com.txl.mebot.data.schedule.ScheduleEditScope

@Composable
fun ScheduleRoute(
    model: ScheduleViewModel?, conversationModel: ScheduleConversationViewModel?,
    onOpenDrawer: () -> Unit, onReturnHome: () -> Unit,
    attachmentModel: ScheduleAttachmentsViewModel? = null,
    initialDetail: ScheduleEntry? = null, detailsOnly: Boolean = false, onDetailClosed: () -> Unit = {},
    editorModel: ScheduleEditorViewModel? = null,
) {
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: SchedulePageState(LocalDate.now(), loading = false)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model?.setVisible(true)
                Lifecycle.Event.ON_STOP -> model?.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model?.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            model?.setVisible(false)
        }
    }
    ScheduleScreen(
        state = state, onDateSelected = { model?.selectDate(it) }, onToggleCompleted = { model?.toggleCompleted(it) },
        onOpenDrawer = onOpenDrawer, onReturnHome = onReturnHome, conversationModel = conversationModel,
        attachmentModel = attachmentModel, editorModel = editorModel,
        onOpenDetail = { model?.openDetail(it) }, onVisibleDatesChanged = { from, through -> model?.showVisibleDates(from, through) },
        onRetryDay = { model?.retryDay() }, onRetryCounts = { model?.retryCounts() }, onRetryDetail = { model?.retryDetail() },
        onDeleteEntry = { id, scope -> model?.deleteEntry(id, scope) }, onConsumeDeletion = { model?.consumeDeletion(it) },
        onStopRecurrence = { model?.stopRecurrence(it) }, onConsumeStopped = { model?.consumeStopped(it) },
        onConsumeReminderOpen = { model?.consumeReminderOpen(it) },
        onDatePreload = { model?.preloadDate(it) },
        initialDetail = initialDetail, detailsOnly = detailsOnly, onDetailClosed = onDetailClosed,
    )
}

@Composable
fun ScheduleScreen(
    state: SchedulePageState,
    onDateSelected: (LocalDate) -> Unit,
    onToggleCompleted: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onReturnHome: () -> Unit,
    conversationModel: ScheduleConversationViewModel? = null,
    isPreview: Boolean = false,
    onOpenDetail: (String?) -> Unit = {},
    onVisibleDatesChanged: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    onRetryDay: () -> Unit = {},
    onRetryCounts: () -> Unit = {},
    onRetryDetail: () -> Unit = {},
    onDeleteEntry: ((String, ScheduleEditScope) -> Unit)? = null,
    onConsumeDeletion: (String) -> Unit = {},
    onStopRecurrence: ((String) -> Unit)? = null,
    onConsumeStopped: (String) -> Unit = {},
    attachmentModel: ScheduleAttachmentsViewModel? = null,
    onConsumeReminderOpen: (String) -> Unit = {},
    onDatePreload: (LocalDate?) -> Unit = {},
    initialDetail: ScheduleEntry? = null, detailsOnly: Boolean = false, onDetailClosed: () -> Unit = {},
    editorModel: ScheduleEditorViewModel? = null, onEditEntry: ((ScheduleEntry) -> Unit)? = null,
) {
    val selectedDate = state.selectedDate
    val dayPager = rememberPagerState(initialPage = selectedDate.schedulePageIndex()) { Int.MAX_VALUE }
    val dateNavigation = remember { ScheduleDateNavigationState() }
    val displayDate by remember(dayPager, dateNavigation) {
        derivedStateOf { dateNavigation.targetDate ?: scheduleDateAtPage(dayPager.currentPage) }
    }
    val entries = state.entries
    val editor = editorModel?.state?.collectAsStateWithLifecycle()?.value
    val conversationState = conversationModel?.state?.collectAsStateWithLifecycle()?.value
    com.txl.mebot.ui.common.SuccessHaptics(conversationState?.savedEvent?.messageId ?: conversationState?.deletedEvent?.messageId)
    val colors = scheduleColors()
    val today = LocalDate.now()
    val entryCounts = state.dateCounts
    var visiblePanel by rememberSaveable { mutableStateOf(initialDetail?.id) }
    var pendingRevealId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRevealDate by rememberSaveable { mutableStateOf<String?>(null) }
    var revealDateReached by rememberSaveable { mutableStateOf(false) }
    fun clearReveal() {
        pendingRevealId = null
        pendingRevealDate = null
        revealDateReached = false
    }
    LaunchedEffect(conversationState?.savedEvent?.messageId) {
        conversationState?.savedEvent?.let { event ->
            event.createdOccurrenceId?.let { id ->
                // 目标独立于聊天状态，收起后打开新草稿也不会丢失尚待定位的卡片。
                pendingRevealId = id
                pendingRevealDate = event.date.toString()
                revealDateReached = selectedDate == event.date
                if (visiblePanel == "composer" || visiblePanel == "conversation") visiblePanel = null
            }
            onDateSelected(event.date)
            conversationModel?.consumeSavedEvent(event.messageId)
        }
    }
    LaunchedEffect(selectedDate, pendingRevealId) {
        if (pendingRevealId != null) {
            if (selectedDate.toString() == pendingRevealDate) revealDateReached = true
            else if (revealDateReached) clearReveal()
        }
    }
    var openingEntry by remember { mutableStateOf(initialDetail) }
    fun openCard(entry: ScheduleEntry) {
        openingEntry = entry
        onOpenDetail(entry.id)
        visiblePanel = entry.id
    }
    LaunchedEffect(state.reminderOpenEvent?.token) {
        state.reminderOpenEvent?.let { event ->
            openingEntry = null
            visiblePanel = event.occurrenceId
            onConsumeReminderOpen(event.token)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val deletionLabel = stringResource(if (conversationState?.deletedEvent?.cleanupPending == true) R.string.schedule_deleted_media_pending else R.string.schedule_deleted_notice)
    LaunchedEffect(conversationState?.deletedEvent?.messageId) {
        conversationState?.deletedEvent?.let { event ->
            if (visiblePanel == "composer" || visiblePanel == "conversation") visiblePanel = null
            conversationModel?.consumeDeletedEvent(event.messageId)
            snackbar.showSnackbar(deletionLabel)
        }
    }
    val detailDeleteLabel = stringResource(when {
        state.deletionEvent?.cleanupPending == true -> R.string.schedule_deleted_media_pending
        state.deletionEvent?.wholeTask == false -> R.string.schedule_deleted_once_notice
        else -> R.string.schedule_deleted_notice
    })
    LaunchedEffect(state.deletionEvent?.token) {
        state.deletionEvent?.let { event ->
            if (visiblePanel == event.occurrenceId) visiblePanel = null
            if (event.wholeTask) conversationModel?.forgetDeletedSession(event.sessionId)
            onConsumeDeletion(event.token)
            snackbar.showSnackbar(detailDeleteLabel)
        }
    }
    val stoppedLabel = stringResource(R.string.schedule_stop_notice)
    LaunchedEffect(state.stoppedEvent?.token) {
        state.stoppedEvent?.let { event ->
            if (visiblePanel == event.occurrenceId) visiblePanel = null
            onConsumeStopped(event.token)
            snackbar.showSnackbar(stoppedLabel)
        }
    }
    val completionFailed = stringResource(when {
        state.writeFailure?.isStopping == true -> R.string.schedule_stop_failed
        state.writeFailure?.isDeletion == true -> R.string.schedule_delete_failed
        else -> R.string.schedule_completion_failed
    })
    LaunchedEffect(state.writeFailure?.token) {
        if (state.writeFailure != null) snackbar.showSnackbar(completionFailed)
    }
    LaunchedEffect(visiblePanel) {
        if (detailsOnly && visiblePanel == null) onDetailClosed()
        onOpenDetail(visiblePanel?.takeUnless { it == "composer" || it == "conversation" })
        if (visiblePanel == null || visiblePanel == "composer" || visiblePanel == "conversation") openingEntry = null
    }
    val detailVisible = visiblePanel != null && visiblePanel != "composer" && visiblePanel != "conversation"
    val displayedDetail = visiblePanel?.takeIf { detailVisible }?.let { id ->
        if (isPreview) entries.firstOrNull { it.id == id } else state.detailForDisplay(id, openingEntry)
    }
    val attachmentSession = if (detailVisible) displayedDetail?.sessionId else conversationState?.sessionId
    LaunchedEffect(attachmentSession) { attachmentModel?.bind(attachmentSession) }
    DisposableEffect(attachmentModel) { onDispose { attachmentModel?.bind(null) } }
    val pickerActions = attachmentPickerActions(attachmentModel, asPending = !detailVisible)
    var selectedGridState by remember { mutableStateOf<LazyGridState?>(null) }
    val toggleCompleted: (String) -> Unit = { id ->
        // 按索引保留当前视口，避免首张卡片排到末尾时列表跟着跳到底部。
        selectedGridState?.let { it.requestScrollToItem(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) }
        onToggleCompleted(id)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (!detailsOnly || visiblePanel == "conversation" || visiblePanel == "composer")
                Modifier.background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom))) else Modifier)
            // safeDrawing 已包含键盘区域；窗口使用 adjustResize，避免系统额外平移整个页面。
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val sheetWidth = (maxWidth - 32.dp).coerceAtMost(688.dp)
        val sheetHeight = (maxHeight * 0.55f).coerceAtLeast(300.dp).coerceAtMost(maxHeight - 16.dp)

        if (!detailsOnly || visiblePanel == "conversation" || visiblePanel == "composer") ScheduleComposerWorkspace(
            model = conversationModel, attachmentModel = attachmentModel, attachmentActions = pickerActions,
            expanded = visiblePanel == "composer" || visiblePanel == "conversation", detailVisible = detailVisible,
            onExpand = { visiblePanel = "composer" }, onDismiss = { visiblePanel = null },
            onOpenSavedSchedule = { entry -> onDateSelected(entry.date); openCard(entry) },
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize(),
        ) { bottomContentPadding ->
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScheduleNavigationButton(R.drawable.ic_menu, R.string.open_sidebar, onOpenDrawer)
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.schedule_title), color = colors.ink,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(if (isPreview) R.string.schedule_month_preview else R.string.schedule_month, displayDate.monthValue),
                            color = colors.secondary, fontSize = 11.sp,
                        )
                    }
                    ScheduleNavigationButton(R.drawable.ic_home, R.string.return_home, onReturnHome)
                }

                ScheduleDateStrip(
                    today = today,
                    selectedDate = displayDate,
                    datesWithEntries = entryCounts.keys,
                    entryCounts = entryCounts,
                    onDateSelected = onDateSelected,
                    onVisibleDatesChanged = onVisibleDatesChanged,
                    dayPager = dayPager,
                    navigation = dateNavigation,
                    onDatePreload = onDatePreload,
                )
                if (state.countsFailed) TextButton(onClick = onRetryCounts, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.schedule_counts_failed), color = colors.secondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))

                ScheduleDayPager(
                    pager = dayPager,
                    selectedDate = selectedDate,
                    onDateSelected = onDateSelected,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = visiblePanel == null,
                    navigation = dateNavigation,
                ) { date ->
                    val gridState = rememberLazyGridState()
                    SideEffect { if (date == selectedDate) selectedGridState = gridState }
                    val loaded = date == selectedDate || date in state.dayEntries || isPreview
                    // 数据层已按展示顺序输出；翻页重组不再重复排序、创建整页卡片列表。
                    val dayEntries = state.dayEntries[date] ?: remember(entries, date) {
                        entries.filter { it.date == date }.sortedForDisplay()
                    }
                    val (pendingEntries, completedEntries) = remember(dayEntries) {
                        dayEntries.partition { !it.completed }
                    }
                    val loading = !loaded || (date == selectedDate && state.loading)
                    val failed = date == selectedDate && state.loadFailed
                    val revealId = pendingRevealId?.takeIf { pendingRevealDate == date.toString() }
                    val revealIndex = revealId?.let { scheduleCardGridIndex(pendingEntries, completedEntries, it) } ?: -1
                    val revealReady = date == selectedDate && !loading && !dayPager.isScrollInProgress &&
                        dayPager.settledPage == date.schedulePageIndex()
                    LaunchedEffect(revealId, revealIndex, revealReady) {
                        if (revealId != null && revealIndex >= 0 && revealReady) {
                            gridState.animateScrollToItem(revealIndex)
                            if (pendingRevealId == revealId) clearReveal()
                        }
                    }
                    if (dayEntries.isEmpty() && (loading || failed)) {
                        ScheduleLoadStatus(loading, onRetryDay)
                    } else if (dayEntries.isEmpty()) {
                        ScheduleEmptyState()
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            if (failed) TextButton(onClick = onRetryDay, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.schedule_load_failed), color = colors.secondary)
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                state = gridState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = bottomContentPadding + 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(pendingEntries, key = { it.id }, contentType = { "schedule-card" }) { entry ->
                                    ScheduleCard(
                                        entry = entry,
                                        onClick = { openCard(entry) },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                                if (completedEntries.isNotEmpty()) {
                                    item(key = "schedule-completed-divider", span = { GridItemSpan(maxLineSpan) }, contentType = "schedule-section") {
                                        CompletedScheduleDivider(Modifier.animateItem())
                                    }
                                    items(completedEntries, key = { it.id }, contentType = { "schedule-card" }) { entry ->
                                        ScheduleCard(
                                            entry = entry,
                                            onClick = { openCard(entry) },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }

        SnackbarHost(
            snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 84.dp),
        )

        visiblePanel?.takeIf { detailVisible }?.let { panel ->
            if (editor != null && editor.occurrenceId == panel) {
                ScheduleEditorSheet(editor, editorModel::change, editorModel::save, editorModel::removeImage,
                    editorModel::importImages, editorModel::reload, editorModel::beforeDismiss, editorModel::closed)
            } else ScheduleSheet(
                entry = displayedDetail,
                width = sheetWidth,
                height = sheetHeight,
                onDismiss = { visiblePanel = null },
                onToggleCompleted = toggleCompleted,
                attachmentModel = attachmentModel,
                onEdit = onEditEntry ?: editorModel?.let { editorViewModel -> { entry -> editorViewModel.open(entry.id) } },
                detailLoading = state.detailLoading || state.detailId != panel,
                detailFailed = state.detailFailed,
                completionEnabled = panel !in state.updatingIds,
                completionFailed = state.writeFailure?.occurrenceId == panel && state.writeFailure.isDeletion == false && !state.writeFailure.isStopping,
                deletionFailed = state.writeFailure?.occurrenceId == panel && state.writeFailure.isDeletion,
                onDelete = onDeleteEntry?.let { action -> { entry, scope -> action(entry.id, scope) } },
                onStopRecurrence = onStopRecurrence?.let { action -> { entry -> action(entry.id) } },
                stoppingFailed = state.writeFailure?.occurrenceId == panel && state.writeFailure.isStopping,
                onRetryDetail = onRetryDetail,
            )
        }
    }
}

internal fun scheduleCardGridIndex(pending: List<ScheduleEntry>, completed: List<ScheduleEntry>, id: String): Int {
    val pendingIndex = pending.indexOfFirst { it.id == id }
    if (pendingIndex >= 0) return pendingIndex
    val completedIndex = completed.indexOfFirst { it.id == id }
    return if (completedIndex >= 0) pending.size + 1 + completedIndex else -1
}

@Composable
private fun CompletedScheduleDivider(modifier: Modifier = Modifier) {
    val colors = scheduleColors()
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = colors.divider)
        Text(stringResource(R.string.schedule_completed), color = colors.secondary, fontSize = 12.sp,
            fontWeight = FontWeight.Medium)
        HorizontalDivider(Modifier.weight(1f), color = colors.divider)
    }
}

@Composable
private fun ScheduleLoadStatus(loading: Boolean, onRetry: () -> Unit) {
    val colors = scheduleColors()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.dp)
            Text(stringResource(R.string.schedule_loading), Modifier.padding(top = 12.dp), color = colors.secondary, fontSize = 13.sp)
        } else TextButton(onClick = onRetry) { Text(stringResource(R.string.schedule_load_failed), color = colors.secondary) }
    }
}

@Composable
private fun ScheduleNavigationButton(icon: Int, label: Int, onClick: () -> Unit) {
    val colors = scheduleColors()
    IconButton(
        onClick = hapticAction(onClick),
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surface, contentColor = colors.ink),
    ) {
        Icon(painterResource(icon), stringResource(label), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ScheduleEmptyState() {
    val colors = scheduleColors()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_calendar), null, tint = colors.accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.schedule_empty_title), color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.schedule_empty_body), color = colors.secondary, fontSize = 13.sp)
    }
}

@Preview(name = "日程", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "深色日程", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleScreenPreview() {
    val today = LocalDate.of(2026, 10, 20)
    MebotTheme {
        ScheduleScreen(
            state = SchedulePageState(
                today, previewScheduleEntries(today).map { it.copy(completed = it.id in setOf("meeting", "fitness")) },
                previewScheduleEntries(today).groupingBy { it.date }.eachCount(), loading = false,
            ),
            isPreview = true,
            onDateSelected = {}, onToggleCompleted = {}, onOpenDrawer = {}, onReturnHome = {},
        )
    }
}
