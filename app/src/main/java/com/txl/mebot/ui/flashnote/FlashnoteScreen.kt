package com.txl.mebot.ui.flashnote

import android.content.res.Configuration
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.ui.schedule.AttachmentPickerActions
import com.txl.mebot.ui.common.BotComposerInput
import com.txl.mebot.ui.schedule.DeviceMediaPanel
import com.txl.mebot.ui.schedule.attachmentPickerActions
import com.txl.mebot.ui.schedule.label
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.theme.MebotTheme

@Composable
fun FlashnoteRoute(model: FlashnoteViewModel?, onOpenDrawer: () -> Unit, onReturnHome: () -> Unit,
    detailId: String? = null, onDetailClosed: () -> Unit = {}) {
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: FlashnotePageState(loading = false)
    if (detailId != null) com.txl.mebot.ui.common.BotDetailEntry(detailId, state.detailId != null,
        open = { model?.openDetail(detailId) }, onClosed = onDetailClosed)
    val mediaActions = attachmentPickerActions(prepare = { model?.preparePicker() == true },
        picked = { uris, onlyImages -> model?.picked(uris, onlyImages) }, prepareCamera = { model?.prepareCamera() },
        cameraResult = { model?.cameraResult(it) }, report = { model?.reportMedia(it) })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    DisposableEffect(model, lifecycle, context) {
        val dateChanges = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { model?.refreshStatisticsDate() }
        }
        ContextCompat.registerReceiver(context, dateChanges, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model?.setVisible(true)
                Lifecycle.Event.ON_RESUME -> model?.refreshStatisticsDate()
                Lifecycle.Event.ON_STOP -> model?.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model?.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(dateChanges); model?.setVisible(false) }
    }
    FlashnoteScreen(state, onOpenDrawer, onReturnHome,
        onStatusSelected = { model?.selectStatus(it) }, onInputChanged = { model?.changeInput(it) },
        onSave = { model?.save() }, onRetryList = { model?.retryList() },
        onRetryInput = { model?.retryInputSave() }, onRetryDraftLoad = { model?.retryDraftLoad() },
        onOpenDetail = { model?.openDetail(it) }, onRetryDetail = { model?.retryDetail() },
        onConsumeSaved = { model?.consumeSavedEvent(it) }, onToggleCompleted = { model?.toggleCompleted(it) },
        onEdit = { model?.beginEditing() }, onDelete = { model?.delete(it) }, onRetryAction = { model?.retryAction() },
        onEditTitle = { model?.changeEditTitle(it) }, onEditContent = { model?.changeEditContent(it) },
        onSaveEdit = { model?.saveEdit() }, onCancelEdit = { model?.cancelEdit() },
        onRetryEditLoad = { model?.retryEditLoad() }, onRetryEditInput = { model?.retryEditInput() },
        attachmentActions = mediaActions, onPickLocal = { model?.pickLocal(it) },
        onRemoveAttachment = { model?.removeAttachment(it) }, onRemoveEditAttachment = { model?.removeEditAttachment(it) },
        onOpenFile = { model?.fileIntent(it) }, onStatisticsExpanded = { model?.setStatisticsExpanded(it) },
        onStatisticsMonth = { model?.selectStatisticsMonth(it) }, onStatisticsDay = { model?.selectStatisticsDay(it) },
        onRetryStatistics = { model?.retryStatistics() }, onGenerateTitle = { model?.generateTitle() }, detailsOnly = detailId != null)
}

@Composable
internal fun FlashnoteScreen(
    state: FlashnotePageState,
    onOpenDrawer: () -> Unit,
    onReturnHome: () -> Unit,
    onStatusSelected: (FlashnoteStatus) -> Unit = {},
    onInputChanged: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onRetryList: () -> Unit = {},
    onRetryInput: () -> Unit = {},
    onRetryDraftLoad: () -> Unit = {},
    onOpenDetail: (String?) -> Unit = {},
    onRetryDetail: () -> Unit = {},
    onConsumeSaved: (String) -> Unit = {},
    onToggleCompleted: (FlashnoteEntry) -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: (FlashnoteEntry) -> Unit = {},
    onRetryAction: () -> Unit = {},
    onEditTitle: (String) -> Unit = {},
    onEditContent: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onRetryEditLoad: () -> Unit = {},
    onRetryEditInput: () -> Unit = {},
    attachmentActions: AttachmentPickerActions = AttachmentPickerActions({}, {}),
    onPickLocal: (Uri) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onRemoveEditAttachment: (String) -> Unit = {},
    onOpenFile: suspend (FlashnoteAttachment) -> Intent? = { null },
    onStatisticsExpanded: (Boolean) -> Unit = {},
    onStatisticsMonth: (Int) -> Unit = {},
    onStatisticsDay: (Int) -> Unit = {},
    onRetryStatistics: () -> Unit = {},
    onGenerateTitle: () -> Unit = {},
    detailsOnly: Boolean = false,
) {
    val colors = scheduleColors()
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(88.dp) }
    val pendingList = rememberLazyListState()
    val completedList = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var mediaExpanded by rememberSaveable { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible, mediaExpanded) {
        if (imeVisible || mediaExpanded) onStatisticsExpanded(false)
    }
    BackHandler(enabled = mediaExpanded && state.detailId == null) { mediaExpanded = false }
    LaunchedEffect(state.detailId) { if (state.detailId != null) mediaExpanded = false }
    val pendingEntries = state.pages[FlashnoteStatus.PENDING].orEmpty()
    com.txl.mebot.ui.common.SuccessHaptics(state.savedEvent?.token)
    LaunchedEffect(state.savedEvent?.token, pendingEntries, state.status, state.loading) {
        val event = state.savedEvent ?: return@LaunchedEffect
        if (state.status == FlashnoteStatus.PENDING && !state.loading) {
            val index = pendingEntries.indexOfFirst { it.id == event.id }
            if (index >= 0) { pendingList.animateScrollToItem(index); onConsumeSaved(event.token) }
        }
    }
    val save: () -> Unit = { mediaExpanded = false; focus.clearFocus(); keyboard?.hide(); onSave() }

    if (!detailsOnly) BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        val pickerHeight = (maxHeight * 0.4f).coerceIn(180.dp, 340.dp).coerceAtMost((maxHeight - 160.dp).coerceAtLeast(0.dp))
        val statisticsHeight = (maxHeight * 0.58f).coerceAtMost(420.dp)
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FlashnoteNavigationButton(R.drawable.ic_menu, R.string.open_sidebar, onOpenDrawer)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.flashnote_title), color = colors.ink,
                            fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FlashnoteNavigationButton(R.drawable.ic_home, R.string.return_home, onReturnHome)
                }
                FlashnoteStatisticsSection(state.statistics, state.status,
                    pending = if (state.countsLoaded && !state.countsFailed) state.counts[FlashnoteStatus.PENDING] ?: 0 else null,
                    completed = if (state.countsLoaded && !state.countsFailed) state.counts[FlashnoteStatus.COMPLETED] ?: 0 else null,
                    maxHeight = statisticsHeight, onStatus = onStatusSelected,
                    onToggle = {
                        focus.clearFocus(); keyboard?.hide(); mediaExpanded = false
                        onStatisticsExpanded(!state.statistics.expanded)
                    }, onMonth = onStatisticsMonth, onDay = onStatisticsDay, onRetry = onRetryStatistics,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                if (state.countsFailed) TextButton(onClick = onRetryList, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.flashnote_counts_failed), color = colors.secondary, fontSize = 12.sp)
                }
                if (state.actionFailure != null && state.detailId == null) FlashnoteActionNotice(state.actionFailure, onRetryAction)
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = state.status, modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        val direction = if (targetState == FlashnoteStatus.COMPLETED) 1 else -1
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 18 * direction })
                            .togetherWith(fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -it / 18 * direction })
                    }, label = "flashnote_status_list",
                ) { status ->
                    // 历史快照只用于退出动画，选中列表以本次数据库查询为准。
                    val entries = if (status == state.status && state.loading) emptyList() else state.pages[status].orEmpty()
                    val loading = status !in state.pages || status == state.status && state.loading
                    val failed = status == state.status && state.loadFailed
                    Column(Modifier.fillMaxSize()) {
                        if (failed && entries.isNotEmpty()) TextButton(onClick = onRetryList, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.flashnote_load_failed), color = colors.secondary)
                        }
                        if (entries.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = dockHeight)) {
                                when {
                                    failed -> FlashnoteLoadStatus(false, onRetryList)
                                    loading -> FlashnoteLoadStatus(true, onRetryList)
                                    else -> FlashnoteEmptyState(status)
                                }
                            }
                        } else LazyColumn(
                            state = if (status == FlashnoteStatus.COMPLETED) completedList else pendingList,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, top = 5.dp, end = 16.dp, bottom = dockHeight + 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries, key = { it.id }) { entry ->
                                FlashnoteCard(entry, onClick = {
                                    focus.clearFocus(); keyboard?.hide(); onOpenDetail(entry.id)
                                }, modifier = Modifier.animateItem(),
                                    titleState = state.titleStates[entry.id])
                            }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }) {
                FlashnoteComposer(state, onInputChanged, onAdd = {
                    onStatisticsExpanded(false)
                    focus.clearFocus(); keyboard?.hide(); mediaExpanded = !mediaExpanded
                }, onSave = save, onRetryInput = onRetryInput, onRetryDraftLoad = onRetryDraftLoad,
                    mediaExpanded = mediaExpanded, onInputFocus = { mediaExpanded = false; onStatisticsExpanded(false) },
                    onRemoveAttachment = onRemoveAttachment, onOpenFile = onOpenFile,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                AnimatedVisibility(mediaExpanded, enter = expandVertically(tween(260)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(220)) + fadeOut(tween(150))) {
                    val selected = state.media.selectedSources.mapNotNull { (uri, id) ->
                        state.media.items.indexOfFirst { it.asset.id == id }.takeIf { it >= 0 }?.let { uri to it + 1 }
                    }.toMap()
                    DeviceMediaPanel(attachmentActions, state.inputEditable && !state.media.busy,
                        selected, onPickLocal, Modifier.fillMaxWidth().height(pickerHeight))
                }
            }
        }
    }
    if (state.detailId != null) key(state.detailId) {
        FlashnoteDetailSheet(state.detail, state.detailLoading, state.detailFailed,
            editor = state.editor, busy = state.detailId in state.pendingOperations, deleted = state.detailDeleted,
            failure = state.actionFailure?.takeIf { it.operation == null || it.operation.entry.id == state.detailId },
            onRetry = onRetryDetail, onDismiss = { onOpenDetail(null) },
            onToggleCompleted = onToggleCompleted, onEdit = onEdit, onDelete = onDelete, onRetryAction = onRetryAction,
            onEditTitle = onEditTitle, onEditContent = onEditContent, onSaveEdit = onSaveEdit,
            onCancelEdit = onCancelEdit, onRetryEditLoad = onRetryEditLoad, onRetryEditInput = onRetryEditInput,
            attachments = state.detailAttachments, onRemoveAttachment = onRemoveEditAttachment, onOpenFile = onOpenFile,
            titleState = state.titleStates[state.detailId], onGenerateTitle = onGenerateTitle)
    }
}

@Composable
private fun FlashnoteNavigationButton(icon: Int, label: Int, onClick: () -> Unit) {
    val colors = scheduleColors()
    IconButton(hapticAction(onClick), Modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surface, contentColor = colors.ink)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
    }
}

@Composable
private fun FlashnoteComposer(
    state: FlashnotePageState, onInputChanged: (String) -> Unit, onAdd: () -> Unit,
    onSave: () -> Unit, onRetryInput: () -> Unit, onRetryDraftLoad: () -> Unit, modifier: Modifier,
    mediaExpanded: Boolean, onInputFocus: () -> Unit, onRemoveAttachment: (String) -> Unit,
    onOpenFile: suspend (FlashnoteAttachment) -> Intent?,
) {
    val colors = scheduleColors()
    val label = stringResource(R.string.flashnote_input_label)
    Column(modifier.fillMaxWidth()) {
        when {
            state.inputSaveState == FlashnoteInputSaveState.CONFLICT -> FlashnoteInputNotice(
                R.string.flashnote_input_conflict, R.string.flashnote_keep_input_retry, onRetryInput)
            state.saveFailed -> FlashnoteInputNotice(
                if (state.saveConflict) R.string.flashnote_save_conflict else R.string.flashnote_save_failed,
                R.string.flashnote_retry_save, onSave)
            state.draftLoadFailed -> FlashnoteInputNotice(
                R.string.flashnote_draft_load_failed, R.string.flashnote_retry, onRetryDraftLoad)
            state.inputSaveState == FlashnoteInputSaveState.FAILED -> FlashnoteInputNotice(
                R.string.flashnote_input_save_failed, R.string.flashnote_retry, onRetryInput)
        }
        if (state.media.isImporting) Text(stringResource(R.string.schedule_attachment_importing, state.media.importingIndex, state.media.importingTotal),
            color = colors.secondary, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        state.media.failure?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) }
        BotComposerInput(
            input = state.input, editable = state.inputEditable, sending = state.isSaving, canSend = state.canSave,
            mediaExpanded = mediaExpanded, onAdd = onAdd, onInputChanged = onInputChanged,
            onInputFocus = onInputFocus, onSend = onSave, inputLabel = label, placeholder = label,
            hasPendingMedia = state.media.items.isNotEmpty(),
            pendingMedia = {
                FlashnoteMediaContent(state.media.items, onOpenFile, dense = true,
                    enabled = state.inputEditable && !state.media.busy,
                    onRemove = onRemoveAttachment, onAdd = onAdd)
            },
        )
    }
}

@Composable
private fun FlashnoteInputNotice(message: Int, action: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(message), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = onClick) { Text(stringResource(action), fontSize = 12.sp) }
    }
}

@Composable
private fun FlashnoteLoadStatus(loading: Boolean, onRetry: () -> Unit) {
    val colors = scheduleColors()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent)
        else TextButton(onClick = onRetry) { Text(stringResource(R.string.flashnote_load_failed), color = colors.accent) }
    }
}

@Composable
private fun FlashnoteEmptyState(status: FlashnoteStatus) {
    val colors = scheduleColors()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = colors.surface) {
            Icon(painterResource(R.drawable.ic_flashnote), null, Modifier.padding(20.dp).size(30.dp), tint = colors.accent)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(if (status == FlashnoteStatus.PENDING) R.string.flashnote_empty_pending else R.string.flashnote_empty_completed),
            color = colors.ink, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (status == FlashnoteStatus.PENDING) R.string.flashnote_empty_pending_body else R.string.flashnote_empty_completed_body),
            color = colors.secondary, fontSize = 13.sp, lineHeight = 21.sp)
    }
}

@Preview(name = "闪念", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "深色闪念", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FlashnoteScreenPreview() {
    MebotTheme { FlashnoteScreen(
        FlashnotePageState(pages = mapOf(FlashnoteStatus.PENDING to previewFlashnoteEntries()), loading = false,
            counts = mapOf(FlashnoteStatus.PENDING to 3), countsLoaded = true, draftLoaded = true),
        onOpenDrawer = {}, onReturnHome = {}) }
}
