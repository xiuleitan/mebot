package com.txl.mebot.ui.trading

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.trading.MarketQuote
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.ui.schedule.localPhoto
import kotlin.math.abs
import kotlinx.coroutines.launch

internal val ContractCardMinHeight = 34.dp
private val WatchlistHorizontalPadding = 10.dp
private val OpenPositionAccent = Color(0xFFFFC45C)
private enum class WatchlistMode { BROWSE, EDIT, SORT }
private enum class TradingDrawerPanel { CONTRACTS, NEWS }

@Composable
internal fun TradingDrawer(
    model: TradingViewModel,
    contract: TradingContract,
    quote: MarketQuote?,
    openPositionSymbols: Set<String>,
    content: @Composable (openContracts: () -> Unit, openNews: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf(TradingDrawerPanel.CONTRACTS) }
    var openedUnread by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val openingOrOpen = drawer.targetValue == DrawerValue.Open
    val showContent = openingOrOpen || drawer.isOpen
    val newsVisible = openingOrOpen && panel == TradingDrawerPanel.NEWS
    val watchlistVisible = openingOrOpen && panel == TradingDrawerPanel.CONTRACTS
    DisposableEffect(model, watchlistVisible) {
        model.setWatchlistVisible(watchlistVisible)
        onDispose { if (watchlistVisible) model.setWatchlistVisible(false) }
    }
    LaunchedEffect(model, newsVisible) {
        if (newsVisible) {
            val unread = model.markNewsOpened()
            if (unread.isNotEmpty()) openedUnread = ArrayList((openedUnread + unread).distinct())
        }
    }
    val close = { scope.launch { drawer.close() }; Unit }
    fun open(destination: TradingDrawerPanel) {
        if (destination == TradingDrawerPanel.NEWS && !newsVisible) openedUnread = arrayListOf()
        panel = destination
        scope.launch { drawer.open() }
    }
    val title = stringResource(R.string.trading_contracts)
    val titleStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val titleWidth = remember(title, titleStyle, textMeasurer) {
        textMeasurer.measure(title, titleStyle, maxLines = 1).size.width
    }
    val density = LocalDensity.current
    val extra = with(density) { (MaterialTheme.typography.titleSmall.fontSize * 2).toDp() }
    val headerWidth = with(density) { titleWidth.toDp() } + 48.dp * 4 + WatchlistHorizontalPadding * 2 + 4.dp

    BoxWithConstraints {
        val preferredWidth = (maxWidth * 0.75f).coerceAtMost(320.dp) + extra
        val width = maxOf(preferredWidth, headerWidth).coerceAtMost(maxWidth * 0.90f)
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = showContent,
            scrimColor = Color.Black.copy(alpha = 0.42f),
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawer,
                    modifier = Modifier.width(width),
                    drawerShape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
                    drawerContainerColor = TradingBackground,
                ) {
                    if (showContent) {
                        when (panel) {
                            TradingDrawerPanel.CONTRACTS -> TradingWatchlist(
                                model, contract, quote, openPositionSymbols, animatePositions = watchlistVisible,
                            ) { selected ->
                                close()
                                model.selectContract(selected)
                            }
                            TradingDrawerPanel.NEWS -> {
                                val stories by model.newsHistory.collectAsStateWithLifecycle(initialValue = emptyList())
                                val markers = remember(openedUnread) { openedUnread.toSet() }
                                TradingNewsDrawerContent(
                                    stories, openedUnread = markers, onClose = close,
                                    onOpenContract = { symbol ->
                                        close()
                                        scope.launch { model.selectAlertContract(symbol) }
                                    },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            content(
                { open(TradingDrawerPanel.CONTRACTS) },
                { open(TradingDrawerPanel.NEWS) },
            )
        }
    }
}

@Composable
private fun TradingWatchlist(
    model: TradingViewModel,
    selected: TradingContract,
    quote: MarketQuote?,
    openPositionSymbols: Set<String>,
    animatePositions: Boolean,
    onSelect: (TradingContract) -> Unit,
) {
    val entries by model.watchlist.collectAsStateWithLifecycle()
    val contracts = remember(entries) { entries.map { it.contract } }
    val categories = remember(entries) {
        buildMap { entries.forEach { entry -> entry.category?.let { put(entry.contract.symbol, it) } } }
    }
    val categoryChoices = remember(categories) { categories.values.distinct() }
    val colorOverrides by model.watchlistColorOverrides.collectAsStateWithLifecycle()
    val categoryColors = remember(categoryChoices, colorOverrides) {
        watchlistCategoryColors(categoryChoices, colorOverrides)
    }
    val quotes by model.contractQuotes.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val visiblePosition by remember(listState, openPositionSymbols) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { (it.key as? String) in openPositionSymbols }
        }
    }
    val positionProgress = if (animatePositions && visiblePosition) {
        rememberInfiniteTransition(label = "watchlist position indicator").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2_200, easing = LinearEasing)),
            label = "watchlist candle offset",
        )
    } else null
    var adding by rememberSaveable { mutableStateOf(false) }
    var showingPerformance by rememberSaveable { mutableStateOf(false) }
    var addedSymbol by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(WatchlistMode.BROWSE) }
    var editingSymbol by rememberSaveable { mutableStateOf<String?>(null) }
    var previewOrder by remember { mutableStateOf<List<TradingContract>?>(null) }
    var savingOrder by remember { mutableStateOf(false) }
    var orderError by remember { mutableStateOf(false) }
    val order = previewOrder ?: contracts

    LaunchedEffect(contracts, previewOrder) {
        if (previewOrder?.let { it == contracts || it.toSet() != contracts.toSet() } == true) previewOrder = null
    }

    LaunchedEffect(contracts, addedSymbol) {
        val index = contracts.indexOfFirst { it.symbol == addedSymbol }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            addedSymbol = null
        }
    }
    Column(Modifier.fillMaxHeight().padding(horizontal = WatchlistHorizontalPadding)) {
        val colors = MaterialTheme.colorScheme
        val modeColors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = colors.primaryContainer,
            checkedContentColor = colors.primary,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.trading_contracts),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = hapticAction { showingPerformance = true }) {
                Icon(
                    painterResource(R.drawable.ic_trading_performance),
                    stringResource(R.string.trading_performance_open),
                    Modifier.size(20.dp),
                )
            }
            IconToggleButton(
                checked = mode == WatchlistMode.EDIT,
                onCheckedChange = { mode = if (it) WatchlistMode.EDIT else WatchlistMode.BROWSE },
                colors = modeColors,
                enabled = contracts.isNotEmpty() && !savingOrder,
            ) {
                Icon(painterResource(R.drawable.ic_trading_edit), stringResource(R.string.trading_watchlist_edit), Modifier.size(20.dp))
            }
            IconToggleButton(
                checked = mode == WatchlistMode.SORT,
                onCheckedChange = {
                    mode = if (it) WatchlistMode.SORT else WatchlistMode.BROWSE
                    orderError = false
                },
                colors = modeColors,
                enabled = contracts.size > 1 && !savingOrder,
            ) {
                Icon(painterResource(R.drawable.ic_trading_sort), stringResource(R.string.trading_watchlist_sort), Modifier.size(20.dp))
            }
            IconButton(enabled = !savingOrder, onClick = hapticAction {
                mode = WatchlistMode.BROWSE
                adding = true
            }) {
                Icon(
                    painterResource(R.drawable.ic_plus),
                    stringResource(R.string.trading_add_contract),
                    Modifier.size(20.dp),
                )
            }
        }
        val hint = when {
            contracts.isEmpty() -> R.string.trading_watchlist_empty
            mode == WatchlistMode.EDIT -> R.string.trading_watchlist_edit_hint
            mode == WatchlistMode.SORT && orderError -> R.string.trading_watchlist_sort_failed
            mode == WatchlistMode.SORT -> R.string.trading_watchlist_sort_hint
            else -> null
        }
        if (hint != null) {
            Text(
                stringResource(hint), Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (mode == WatchlistMode.SORT && orderError) colors.error else colors.onSurfaceVariant,
            )
        }
        TradingWatchlistList(
            contracts = order,
            categories = categories,
            categoryColors = categoryColors,
            state = listState,
            sorting = mode == WatchlistMode.SORT && !savingOrder,
            onReorder = { reordered ->
                previewOrder = reordered
                savingOrder = true
                orderError = false
                model.saveContractOrder(reordered.map { it.symbol }) { saved ->
                    savingOrder = false
                    orderError = !saved
                    if (!saved) previewOrder = null
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { item, position ->
            val current = item.symbol == selected.symbol
            val customIcon by remember(model, item.symbol) { model.observeCustomContractIcon(item.symbol) }
                .collectAsStateWithLifecycle(initialValue = null)
            TradingContractCard(
                item, if (current) quote ?: quotes[item] else quotes[item], current,
                modifier = position, customIconPath = customIcon, selectable = mode != WatchlistMode.SORT,
                hasPosition = item.symbol in openPositionSymbols,
                categoryBackground = categories[item.symbol]?.let(categoryColors::get)?.background,
                positionProgress = positionProgress,
            ) {
                if (mode == WatchlistMode.EDIT) editingSymbol = item.symbol else onSelect(item)
            }
        }
    }

    contracts.firstOrNull { it.symbol == editingSymbol }?.let { contract ->
        TradingWatchlistEditor(
            contract, model, category = categories[contract.symbol], categoryColors = categoryColors,
            onDismiss = { editingSymbol = null },
            onRemoved = {
                editingSymbol = null
                if (contracts.size <= 1) mode = WatchlistMode.BROWSE
            },
        )
    }
    if (adding) {
        TradingAddContractDialog(
            onDismiss = { adding = false },
            onAdd = model::addContract,
            onAdded = { contract ->
                addedSymbol = contract.symbol
                adding = false
            },
        )
    }
    if (showingPerformance) {
        TradingPerformanceDialog(observe = model::performance, onDismiss = { showingPerformance = false })
    }
}

@Composable
internal fun TradingContractCard(
    contract: TradingContract,
    quote: MarketQuote?,
    active: Boolean,
    modifier: Modifier = Modifier,
    customIconPath: String? = null,
    selectable: Boolean = true,
    hasPosition: Boolean = false,
    categoryBackground: Color? = null,
    positionProgress: State<Float>? = null,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val change = quote?.changePercent24h
    val changeText = formatTradingChange(change)
    val description = stringResource(R.string.trading_change_24h, changeText)
    val positionDescription = if (hasPosition) stringResource(R.string.trading_watchlist_has_position) else null
    val borderColor = if (hasPosition) OpenPositionAccent else colors.outlineVariant
    Surface(
        onClick = hapticAction(onSelect),
        enabled = selectable,
        modifier = modifier.fillMaxWidth().semantics {
            selected = active
            if (positionDescription != null) stateDescription = positionDescription
        },
        shape = RoundedCornerShape(7.dp),
        color = categoryBackground ?: if (active) colors.primaryContainer else colors.surfaceContainer,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = ContractCardMinHeight).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContractIcon(contract, customIconPath)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    contract.asset,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasPosition) WatchlistPositionIndicator(positionProgress)
            }
            Text(
                quote?.let { formatPrice(it.lastPrice, contract.priceDecimals) } ?: "—",
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            Text(
                changeText,
                modifier = Modifier.widthIn(min = 47.dp).clearAndSetSemantics { contentDescription = description },
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.End,
                color = tradingChangeColor(change),
                maxLines = 1,
            )
        }
    }
}

private data class IndicatorCandle(val top: Float, val bottom: Float, val open: Float, val close: Float)

private val PositionIndicatorCandles = listOf(
    IndicatorCandle(.22f, .88f, .68f, .42f),
    IndicatorCandle(.08f, .63f, .47f, .24f),
    IndicatorCandle(.28f, .86f, .44f, .70f),
    IndicatorCandle(.14f, .70f, .55f, .37f),
)

@Composable
private fun WatchlistPositionIndicator(progress: State<Float>?) {
    Canvas(Modifier.padding(start = 4.dp).size(17.dp, 12.dp).testTag("watchlist_position_indicator")) {
        // Read the shared animation state here so only the small canvas is redrawn each frame.
        val offset = size.width * (progress?.value ?: 0f)
        val count = PositionIndicatorCandles.size
        val step = size.width / count
        val bodyWidth = step * .52f
        clipRect {
            for (index in -count until count) {
                val candle = PositionIndicatorCandles[(index + count) % count]
                val x = (index + .5f) * step + offset
                drawLine(
                    color = OpenPositionAccent,
                    start = Offset(x, candle.top * size.height),
                    end = Offset(x, candle.bottom * size.height),
                    strokeWidth = .75.dp.toPx(),
                )
                drawRect(
                    color = OpenPositionAccent,
                    topLeft = Offset(x - bodyWidth / 2f, minOf(candle.open, candle.close) * size.height),
                    size = Size(bodyWidth, abs(candle.open - candle.close) * size.height),
                )
            }
        }
    }
}

@Composable
internal fun ContractIcon(contract: TradingContract, customIconPath: String?, iconSize: Dp = 23.dp) {
    val customIcon = if (customIconPath != null) localPhoto(customIconPath, maxDimension = 128) else null
    val background = when (contract.symbol) {
        TradingContract.BTC.symbol -> Color(0xFFB56820)
        TradingContract.ETH.symbol -> Color(0xFF4E5B9E)
        TradingContract.HYPE.symbol -> Color(0xFF176052)
        "QQQUSDT" -> Color(0xFF534683)
        "TQQQUSDT" -> Color(0xFF235D61)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        Modifier.size(iconSize).background(background, CircleShape).clip(CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (customIcon != null) {
            Image(
                customIcon, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            )
        } else {
            val initial = contract.asset.firstOrNull(Char::isLetter)?.uppercaseChar()?.toString() ?: contract.asset.take(1)
            Text(
                initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

internal fun formatTradingChange(change: Double?): String {
    val value = change?.let { if (abs(it) < 0.005) 0.0 else it } ?: return "—"
    return String.format(java.util.Locale.getDefault(), if (value > 0) "%+.2f%%" else "%.2f%%", value)
}

@Composable
internal fun tradingChangeColor(change: Double?): Color = when {
    change == null || abs(change) < 0.005 -> MaterialTheme.colorScheme.onSurfaceVariant
    change > 0 -> TradingUp
    else -> TradingDown
}
