package com.txl.mebot.ui.trading

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.local.TradingClosedPosition
import com.txl.mebot.data.trading.DrawingTool
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.TradingPositionDetails
import com.txl.mebot.data.trading.TradingPositionKey
import com.txl.mebot.data.trading.TradingPositionPlan
import com.txl.mebot.data.trading.TradingTrend
import com.txl.mebot.data.trading.fillsForCurrentPositions
import com.txl.mebot.data.trading.parsePositionPlanPrice
import com.txl.mebot.ui.common.BotNavigationButton
import com.txl.mebot.ui.common.hapticAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChartControlsMinHeight = 27.dp

@Composable
internal fun rememberTradingViewModel(): TradingViewModel {
    val context = LocalContext.current
    val owner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
    }
    return remember(owner) { ViewModelProvider(owner)[TradingViewModel::class.java] }
}

@Composable
internal fun TradingRoute(onReturnHome: () -> Unit) {
    val model = rememberTradingViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.setVisible(true)
                Lifecycle.Event.ON_STOP -> model.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.setVisible(false)
        }
    }
    MaterialTheme(colorScheme = TradingColors, typography = TradingTypography) {
        TradingDrawer(model, state.contract, state.quote, state.openPositionSymbols) { openContracts, openNews ->
            TradingPage(state, model, onReturnHome, openContracts, openNews)
        }
    }
}

@Composable
private fun TradingPage(
    state: TradingState,
    model: TradingViewModel,
    onReturnHome: () -> Unit,
    onOpenContracts: () -> Unit,
    onOpenNews: () -> Unit,
) {
    val contract = state.contract
    val context = LocalContext.current
    val window = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()?.window
    }
    val overviewCapture = remember { TradingOverviewCapture() }
    var tool by remember(contract) { mutableStateOf<DrawingTool?>(null) }
    var showLines by remember(contract) { mutableStateOf(false) }
    var editingPosition by remember(contract) { mutableStateOf<TradingPositionKey?>(null) }
    var reviewingPosition by remember(contract) { mutableStateOf<TradingClosedPosition?>(null) }
    var showTrend by rememberSaveable(contract) { mutableStateOf(false) }
    var showNote by rememberSaveable { mutableStateOf(false) }
    var showPriceAlerts by rememberSaveable(contract.symbol) { mutableStateOf(false) }
    val onlyCurrentPosition = state.onlyCurrentPosition
    val chartFills = remember(onlyCurrentPosition, state.fills, state.positions) {
        if (onlyCurrentPosition) fillsForCurrentPositions(state.fills, state.positions.orEmpty()) else state.fills
    }
    val chartStops = remember(contract.symbol, state.positions, state.positionPlans) { state.chartStopLosses() }
    val chartFontScale = LocalDensity.current.fontScale
    val trendTopPadding = 6.dp + 14.dp * chartFontScale
    var selectedTrades by remember(contract) { mutableStateOf(emptyList<Long>()) }
    val listState = rememberLazyListState()
    LaunchedEffect(contract) { listState.scrollToItem(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            model.dismissNotice()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val chartHeight = (maxHeight * 0.63f).coerceIn(310.dp, 620.dp) * 0.8f
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().onGloballyPositioned { overviewCapture.header = it }) {
                    key(contract.symbol) {
                        val iconPath by remember(model) { model.observeCustomContractIcon(contract.symbol) }
                            .collectAsStateWithLifecycle(initialValue = null)
                        TradingHeader(state, onReturnHome, onOpenContracts, customIconPath = iconPath)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .onGloballyPositioned { overviewCapture.viewport = it }
                        .drawWithContent {
                            val bottom = overviewCapture.viewportClipBottom()
                            if (bottom == null) drawContent()
                            else clipRect(bottom = bottom) { this@drawWithContent.drawContent() }
                        },
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    item(key = "market-overview") {
                        Column(Modifier.fillMaxWidth().onGloballyPositioned { overviewCapture.overview = it }) {
                            val latestNews by model.latestNews.collectAsStateWithLifecycle(initialValue = emptyList())
                            val hasUnreadNews by model.hasUnreadNews.collectAsStateWithLifecycle(initialValue = false)
                            val pushStatus by model.pushStatus.collectAsStateWithLifecycle()
                            TradingNoteAndNews(
                                state.note, latestNews, pushStatus, onOpenNews = onOpenNews, onEdit = { showNote = true },
                                hasUnreadNews = hasUnreadNews,
                            )
                            state.marketProblem?.let {
                                Text(
                                    it.message,
                                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            ChartControls(
                                interval = state.interval,
                                tool = tool,
                                canDraw = state.bars.isNotEmpty() || state.tick != null,
                                hasLines = state.lines.isNotEmpty(),
                                onInterval = { tool = null; model.selectInterval(it) },
                                onTool = { tool = it },
                                onShowLines = { showLines = true },
                            )
                            Surface(color = TradingChartBackground) {
                                TradingChart(
                                    contract = contract,
                                    interval = state.interval,
                                    bars = state.bars,
                                    tick = state.tick,
                                    lines = state.lines,
                                    tool = tool,
                                    fills = chartFills,
                                    onFill = { selectedTrades = it },
                                    onLine = { kind, points ->
                                        if (tool == kind) {
                                            tool = null
                                            model.addLine(contract, kind, points)
                                        }
                                    },
                                    onMoveLine = model::moveLine,
                                    onOlder = { symbol, interval, before -> model.older(symbol, interval, before, mainChart = true) },
                                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                                    referencePrice = state.displayPrice,
                                    stopLosses = chartStops,
                                    initialBarSpace = model.chartBarSpace,
                                    onBarSpaceChanged = model::saveChartBarSpace,
                                ) {
                                    if (state.bars.isEmpty() && state.tick == null) {
                                        Text(
                                            if (state.loading) "正在获取 K 线…" else "暂无 K 线",
                                            Modifier.align(Alignment.Center),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    key(contract.symbol) {
                                        val hasActiveAlerts by remember(model) {
                                            model.priceAlerts.hasActiveAlerts(contract.symbol)
                                        }.collectAsStateWithLifecycle(initialValue = false)
                                        TradingChartActions(
                                            trend = state.trend,
                                            onlyCurrentPosition = onlyCurrentPosition,
                                            hasActiveAlerts = hasActiveAlerts,
                                            onShowTrend = { showTrend = true },
                                            onToggleFillScope = model::toggleFillScope,
                                            onShowPriceAlerts = { showPriceAlerts = true },
                                            modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = trendTopPadding),
                                        )
                                    }
                                    TradingBinanceTradeButton(
                                        contract,
                                        Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp + 20.dp * chartFontScale),
                                    )
                                }
                            }
                            TradingFearGreedRow(state.fearGreed)
                        }
                    }
                    item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PositionStatus(state)
                    }
                    items(state.positions.orEmpty(), key = { it.position.side }) { position ->
                        TradingPositionCard(contract, position, state.planFor(position), state.markPrice) {
                            editingPosition = position.key
                        }
                    }
                    item(key = "position-history-title") {
                        TradingPositionHistoryHeading(state.historySummary)
                    }
                    if (state.closedPositions.isEmpty()) item(key = "position-history-empty") {
                        Text(
                            stringResource(R.string.trading_history_empty),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(
                        state.closedPositions,
                        key = { "history:${it.scope}:${it.positionSide}:${it.closingTradeId}" },
                    ) { position ->
                        TradingClosedPositionCard(position) { reviewingPosition = position }
                    }
                    if (state.hasMoreClosedPositions) {
                        item(key = "position-history-more") {
                            TextButton(
                                onClick = hapticAction(model::showMoreClosedPositions),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.trading_history_more)) }
                        }
                    }
                }
            }
        }
    }

    reviewingPosition?.let { position ->
        TradingPositionReviewDialog(contract, position, state.interval, model, onDismiss = { reviewingPosition = null })
    }
    if (selectedTrades.isNotEmpty()) {
        val fills = remember(selectedTrades, state.fills) {
            val ids = selectedTrades.toSet()
            state.fills.filter { it.tradeId in ids }
        }
        if (fills.isNotEmpty()) {
            TradingFillDialog(contract, fills, onDismiss = { selectedTrades = emptyList() }, model::saveReason)
        }
    }
    editingPosition?.let { selected ->
        state.positions.orEmpty().find { it.key == selected || it.snapshotKey == selected }?.let { position ->
            key(selected) {
                val positionAlerts by remember(model, position.key, position.openingFill?.time) {
                    model.priceAlerts.positionRules(position)
                }.collectAsStateWithLifecycle(initialValue = null)
                TradingPositionPlanEditor(
                    position = position.position,
                    plan = state.planFor(position),
                    alerts = positionAlerts,
                    onDismiss = { editingPosition = null },
                    onSave = { stop, target -> model.savePositionPlan(position.key, stop, target) },
                    onSetAlert = { choice, stop -> model.setPositionAlert(position.key, choice, stop) },
                    onCancelAlert = model::cancelPositionAlert,
                )
            }
        }
    }
    if (showTrend && state.trend?.isSuccess == true) {
        TradingTrendEditor(
            state.trend.getOrNull(), onDismiss = { showTrend = false },
            onSave = model::saveTrend,
        )
    }
    if (showNote && state.note != null) {
        TradingNoteEditor(
            contract = contract,
            interval = state.interval,
            note = state.note,
            onDismiss = { showNote = false },
            onSave = model::saveNote,
            onCapture = {
                if (state.bars.isEmpty() && state.tick == null) null
                else window?.let { overviewCapture.png(it, listState) }
            },
        )
    }
    if (showPriceAlerts) {
        TradingPriceAlertsDialog(contract, model.priceAlerts) { showPriceAlerts = false }
    }
    if (showLines) {
        TradingLinesDialog(contract, state.lines, onDismiss = { showLines = false }) { id -> model.deleteLine(contract, id) }
    }
}

@Composable
internal fun TradingChartActions(
    trend: Result<TradingTrend?>?,
    onlyCurrentPosition: Boolean,
    hasActiveAlerts: Boolean,
    onShowTrend: () -> Unit,
    onToggleFillScope: () -> Unit,
    onShowPriceAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TradingTrendButton(trend, onClick = onShowTrend, modifier = Modifier.fillMaxHeight())
        TradingFillScopeButton(onlyCurrentPosition, onClick = onToggleFillScope, modifier = Modifier.fillMaxHeight())
        TradingPriceAlertButton(hasActiveAlerts, modifier = Modifier.fillMaxHeight(), onClick = onShowPriceAlerts)
    }
}

@Composable
private fun TradingFillScopeButton(onlyCurrentPosition: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = hapticAction(onClick),
            modifier = modifier,
            shape = RoundedCornerShape(6.dp),
            color = if (onlyCurrentPosition) colors.primaryContainer else colors.surfaceVariant,
            contentColor = if (onlyCurrentPosition) colors.onPrimaryContainer else colors.onSurface,
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                Text(
                    stringResource(if (onlyCurrentPosition) R.string.trading_fill_scope_current else R.string.trading_fill_scope_all),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val TradingState.displayPrice: Double?
    get() = tick?.close ?: quote?.lastPrice ?: bars.lastOrNull()?.close

@Composable
internal fun TradingHeader(
    state: TradingState,
    onReturnHome: () -> Unit,
    onOpenContracts: () -> Unit,
    customIconPath: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val candle = state.tick ?: state.bars.lastOrNull()
    val price = state.displayPrice
    val priceColor = when {
        candle == null || candle.close == candle.open -> colors.onSurface
        candle.close > candle.open -> TradingUp
        else -> TradingDown
    }
    val chooseContract = stringResource(R.string.trading_choose_contract)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f)
                .clickable(
                    role = Role.Button, onClickLabel = chooseContract, onClick = hapticAction(onOpenContracts),
                )
                .padding(top = 4.dp, end = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.defaultMinSize(minHeight = 40.dp).padding(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContractIcon(state.contract, customIconPath)
                Text(
                    state.contract.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Text(
                price?.let { formatPrice(it, state.contract.priceDecimals) } ?: "—",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = priceColor,
                maxLines = 1,
            )
        }
        BotNavigationButton(
            R.drawable.ic_home, R.string.return_home, colors.surfaceContainer, colors.onSurface,
            onClick = onReturnHome,
        )
    }
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
private fun ChartControls(
    interval: TradingInterval,
    tool: DrawingTool?,
    canDraw: Boolean,
    hasLines: Boolean,
    onInterval: (TradingInterval) -> Unit,
    onTool: (DrawingTool?) -> Unit,
    onShowLines: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup()) {
            TradingInterval.entries.forEach { item ->
                TradingTab(item.label, interval == item) { onInterval(item) }
            }
        }
        Box {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                TextButton(
                    onClick = { if (tool == null) expanded = true else onTool(null) },
                    modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = ChartControlsMinHeight),
                    enabled = tool != null || canDraw || hasLines,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text(if (tool == null) "画线" else "取消") }
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DrawingTool.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) }, enabled = canDraw,
                        onClick = { expanded = false; onTool(item) },
                    )
                }
                if (hasLines) {
                    DropdownMenuItem(text = { Text("已保存的线") }, onClick = { expanded = false; onShowLines() })
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun TradingTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.defaultMinSize(minWidth = 30.dp, minHeight = ChartControlsMinHeight)
            .selectable(selected, role = Role.Tab, onClick = hapticAction(onClick))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier.padding(bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        )
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).size(16.dp, 2.dp).background(colors.primary, CircleShape))
        }
    }
}

@Composable
private fun PositionStatus(state: TradingState) {
    val problem = state.positionsProblem
    val label = when {
        problem != null -> problem.message
        !state.connected -> stringResource(R.string.trading_binance_disconnected)
        state.positions == null -> "正在读取持仓…"
        state.positions.isEmpty() -> "暂无持仓"
        else -> return
    }
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (problem == null) colors.onSurfaceVariant else colors.error,
    )
}

private fun TradingState.planFor(position: TradingPositionDetails): TradingPositionPlan =
    positionPlans[position.key] ?: positionPlans[position.snapshotKey] ?: TradingPositionPlan()

internal fun TradingState.chartStopLosses(): List<TradingChartStopLoss> =
    positions.orEmpty().mapNotNull { position ->
        if (position.key.symbol != contract.symbol) return@mapNotNull null
        val stop = planFor(position).stopLoss?.let(::parsePositionPlanPrice) ?: return@mapNotNull null
        TradingChartStopLoss(stop.stripTrailingZeros().toPlainString(), position.position.side)
    }.distinctBy { it.price }

internal fun formatPrice(value: Double, decimals: Int = 2): String =
    String.format(Locale.getDefault(), "%,.${decimals}f", value)

internal fun formatPrice(value: String, decimals: Int = 2): String =
    value.toDoubleOrNull()?.let { formatPrice(it, decimals) } ?: value
internal fun String.trimDecimal(): String = toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: this

internal fun formatTradeTime(time: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(time))
