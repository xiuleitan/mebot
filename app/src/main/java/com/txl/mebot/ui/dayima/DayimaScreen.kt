package com.txl.mebot.ui.dayima

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.schedule.attachmentPickerActions
import com.txl.mebot.ui.theme.MebotTheme
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun DayimaRoute(model: DayimaViewModel?, onOpenDrawer: () -> Unit, onReturnHome: () -> Unit, chatModel: DayimaChatViewModel? = null,
    detailId: String? = null, reminderEventId: String? = null, onDetailClosed: () -> Unit = {}) {
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: DayimaPageState(loading = false)
    val confirmedNotice = state.notice?.takeIf { it.text in setOf(R.string.dayima_details_saved, R.string.dayima_record_saved,
        R.string.dayima_duplicate_note_saved, R.string.dayima_birth_removed, R.string.dayima_birth_saved,
        R.string.dayima_note_saved, R.string.dayima_span_saved, R.string.dayima_span_removed, R.string.dayima_deleted) }
    com.txl.mebot.ui.common.SuccessHaptics(confirmedNotice?.token)
    var detailRequested by remember(detailId) { mutableStateOf(false) }
    LaunchedEffect(detailId, state.busy) {
        if (detailId != null && !detailRequested && !state.busy) {
            detailRequested = true; model?.closeSheet(); model?.openDetail(detailId)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    DisposableEffect(model, chatModel, lifecycle, context) {
        val dates = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { model?.refresh() }
        }
        ContextCompat.registerReceiver(context, dates, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { model?.setVisible(true); chatModel?.setVisible(true) }
                Lifecycle.Event.ON_STOP -> { model?.setVisible(false); chatModel?.setVisible(false) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        model?.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        chatModel?.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(dates); model?.setVisible(false); chatModel?.setVisible(false) }
    }
    if (detailId == null) DayimaScreen(state, model, onOpenDrawer, onReturnHome, chatModel)
    else {
        DayimaSheetHost(state, model, reminderEventId, onClosed = onDetailClosed)
        val snackbar = remember { SnackbarHostState() }
        val message = state.notice?.let { stringResource(it.text) }
        LaunchedEffect(state.notice?.token) {
            state.notice?.let { snackbar.showSnackbar(requireNotNull(message)); model?.consumeNotice(it.token) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SnackbarHost(snackbar, Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun DayimaScreen(state: DayimaPageState, model: DayimaViewModel?, onOpenDrawer: () -> Unit, onReturnHome: () -> Unit, chatModel: DayimaChatViewModel? = null) {
    val colors = scheduleColors()
    val chat = chatModel?.state?.collectAsStateWithLifecycle()?.value ?: DayimaChatState()
    val historyScroll = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val mediaNotice = stringResource(R.string.dayima_images_files_unsupported)
    val picker = attachmentPickerActions(prepare = { chatModel?.prepareImagePicker() == true },
        picked = { uris, imagesOnly -> chatModel?.pickedImages(uris, imagesOnly) },
        prepareCamera = { chatModel?.prepareCamera() }, cameraResult = { chatModel?.cameraResult(it) },
        report = { chatModel?.reportMedia(it) })
    val mediaActions = picker.copy(files = {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(mediaNotice) }
    })
    val message = state.notice?.let { stringResource(it.text) }
    LaunchedEffect(state.notice?.token) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(requireNotNull(message))
        model?.consumeNotice(notice.token)
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        containerColor = colors.backgroundTop,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        DayimaComposerWorkspace(chat, chatModel, mediaActions,
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
                .padding(padding).windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max = 720.dp)) { bottomPadding ->
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DayimaNavigationButton(R.drawable.ic_menu, R.string.open_sidebar, onOpenDrawer)
                    Text(stringResource(R.string.dayima_title), Modifier.weight(1f).padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    if (model != null) DayimaReminderButton()
                    val birth = state.overview?.records?.profile?.birthDate
                    val age = birth?.takeIf { it <= state.today }?.let { Period.between(it, state.today).years }
                    val birthDescription = if (birth == null) stringResource(R.string.dayima_birth_add)
                        else stringResource(R.string.dayima_birth_button, birth.dayimaDate()) + "，" + stringResource(R.string.dayima_birth_edit)
                    TextButton(onClick = { model?.beginBirth() }, enabled = state.overview != null && !state.busy && model != null,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = birthDescription },
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(if (age == null) stringResource(R.string.dayima_birth_short) else stringResource(R.string.dayima_birth_age, age),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    Spacer(Modifier.width(4.dp))
                    DayimaNavigationButton(R.drawable.ic_home, R.string.return_home, onReturnHome)
                }
                val overview = state.overview
                val periods = overview?.records?.periods.orEmpty().asReversed()
                val previousCycles = remember(overview?.statistics) { overview?.statistics?.period?.cycles.orEmpty().associateBy { it.endPeriodId } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = historyScroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) item("loading") { DayimaLoading() }
                    if (state.loadFailed) item("load_error") { DayimaLoadError { model?.refresh() } }
                    if (overview != null) {
                        item("cycle_calendar") { DayimaCycleCalendarCard(overview) }
                        overview.statistics.let { stats ->
                            if (stats.period.excludedCycleCount > 0 || stats.period.pendingReviewMarkerCount > 0) item("markers") {
                                DayimaCard {
                                    if (stats.period.excludedCycleCount > 0) Text(stringResource(R.string.dayima_excluded_count, stats.period.excludedCycleCount),
                                        color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                                    if (stats.period.pendingReviewMarkerCount > 0) Text(stringResource(R.string.dayima_pending_count, stats.period.pendingReviewMarkerCount),
                                        color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { model?.reviewMarkers() }) { Text(stringResource(R.string.dayima_review)) }
                                }
                            }
                            if (stats.ignoredFutureRecordCount > 0) item("future") {
                                Text(stringResource(R.string.dayima_future_records, stats.ignoredFutureRecordCount), color = colors.secondary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item("chart_deck") { DayimaChartDeck(state.charts, model) }
                        if (periods.isEmpty() && !state.loading && !state.loadFailed) item("empty") {
                            Text(stringResource(R.string.dayima_history_empty), Modifier.padding(vertical = 24.dp), color = colors.secondary)
                        }
                        items(periods, key = { "period-${it.id}" }) { period ->
                            DayimaPeriodRow(period, previousCycles[period.id]?.days,
                                Modifier.animateItem(), onClick = { model?.openDetail(period.id) })
                        }
                    }
                }
            }
        }
    }
    DayimaSheetHost(state, model)
    DayimaChatHistory(chat, chatModel)
}

@Composable
private fun DayimaPeriodRow(period: DayimaPeriodEntity, previousIntervalDays: Long?, modifier: Modifier, onClick: () -> Unit) {
    val colors = scheduleColors()
    Surface(onClick = hapticAction(onClick), shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(period.startDate.dayimaDate(), Modifier.weight(1f), color = colors.ink,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(if (previousIntervalDays == null) stringResource(R.string.dayima_previous_interval_unknown)
                else stringResource(R.string.dayima_previous_interval, previousIntervalDays),
                color = colors.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DayimaCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = scheduleColors()
    Surface(shape = RoundedCornerShape(26.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun DayimaLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
    }
}

@Composable
internal fun DayimaLoadError(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.dayima_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onRetry) { Text(stringResource(R.string.dayima_retry)) }
    }
}

@Composable
private fun DayimaNavigationButton(icon: Int, label: Int, onClick: () -> Unit) {
    val colors = scheduleColors()
    IconButton(hapticAction(onClick), Modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surface, contentColor = colors.ink)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
    }
}

internal fun LocalDate.dayimaDate(): String = format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
internal fun Double.dayimaNumber(): String = if (this == toLong().toDouble()) toLong().toString() else String.format(Locale.CHINA, "%.1f", this)
internal fun DayimaCycleStatus.dayimaLabel(): Int = when (this) {
    DayimaCycleStatus.ELIGIBLE -> R.string.dayima_eligible_span
    DayimaCycleStatus.MISSING_RECORDS -> R.string.dayima_missing_span
    DayimaCycleStatus.SPECIAL_PHASE -> R.string.dayima_special_span
    DayimaCycleStatus.PENDING_REVIEW -> R.string.dayima_pending_span
}

@Preview(name = "大姨妈空状态", showBackground = true)
@Composable
private fun DayimaPreview() { MebotTheme { DayimaScreen(DayimaPageState(loading = false), null, {}, {}) } }
