package com.txl.mebot.ui.trading

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.txl.mebot.data.trading.TradingContract
import java.text.BreakIterator
import kotlin.math.abs

private val WatchlistCategoryWidth = 29.dp
private val WatchlistCategoryGap = 4.dp

@Composable
internal fun TradingWatchlistList(
    contracts: List<TradingContract>,
    state: LazyListState,
    sorting: Boolean,
    onReorder: (List<TradingContract>) -> Unit,
    modifier: Modifier = Modifier,
    categories: Map<String, String> = emptyMap(),
    categoryColors: Map<String, WatchlistCategoryColor> = emptyMap(),
    card: @Composable (TradingContract, Modifier) -> Unit,
) {
    val drag = remember(state) { ContractDrag(state) }
    val currentContracts by rememberUpdatedState(contracts)
    val currentCategories by rememberUpdatedState(categories)
    val reorder by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 36.dp.toPx() }
    val scrollSpeed = with(LocalDensity.current) { 480.dp.toPx() }
    val gesture = if (sorting) Modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.singleOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    if (change.changedToUp()) {
                        val result = drag.order
                        if (result != null) {
                            change.consume()
                            if (result != currentContracts) reorder(result)
                        }
                        break
                    }
                    if (drag.symbol == null) {
                        if (change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                            if ((change.position - down.position).getDistance() >= viewConfiguration.touchSlop) break
                            continue
                        }
                        if (!drag.start(down.position.y, currentContracts, currentCategories)) break
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    // Claim the movement on the initial pass before the list starts scrolling.
                    drag.moveBy(change.positionChange().y)
                    change.consume()
                }
            } finally {
                drag.reset()
            }
        }
    } else Modifier
    LaunchedEffect(sorting, contracts, categories) {
        if (!sorting || drag.order?.toSet()?.let { it != contracts.toSet() } == true) drag.reset()
    }
    LaunchedEffect(drag.symbol, edge, scrollSpeed) {
        if (drag.symbol == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag.symbol != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val fraction = drag.scrollFraction(edge)
            if (fraction != 0f && state.scrollBy(fraction * scrollSpeed * seconds) != 0f) drag.updateOrder()
        }
    }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides (ContractCardMinHeight + 4.dp)) {
        Box(modifier) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().then(gesture),
                state = state,
                contentPadding = PaddingValues(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(drag.order ?: contracts, key = { it.symbol }) { contract ->
                    val position = if (drag.symbol == contract.symbol) Modifier.zIndex(1f).graphicsLayer {
                        translationY = drag.translation()
                    } else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                    if (contract.symbol in categories) {
                        card(contract, position.padding(start = WatchlistCategoryWidth + WatchlistCategoryGap))
                    } else {
                        card(contract, position)
                    }
                }
            }
            if (categories.isNotEmpty()) {
                WatchlistCategoryRails(state, categories, categoryColors, Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun WatchlistCategoryRails(
    state: LazyListState,
    categories: Map<String, String>,
    categoryColors: Map<String, WatchlistCategoryColor>,
    modifier: Modifier,
) {
    val names = remember(categories) { categories.values.distinct() }
    val colors = MaterialTheme.colorScheme
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            for (name in names) {
                val background = categoryColors[name]?.background ?: colors.surfaceVariant.copy(alpha = 0.7f)
                Box(
                    Modifier.background(background, RoundedCornerShape(5.dp))
                        .semantics(mergeDescendants = true) { contentDescription = name }
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        remember(name) { verticalCategoryName(name) },
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp,
                        ),
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        // The list is measured first. Each rail covers its visible cards and the gaps between them.
        val rowsByCategory = state.layoutInfo.visibleItemsInfo
            .mapNotNull { row -> categories[row.key as String]?.let { it to row } }
            .groupBy({ it.first }, { it.second })
        val width = WatchlistCategoryWidth.roundToPx()
        val height = constraints.maxHeight
        val rails = measurables.mapIndexedNotNull { index, measurable ->
            val rows = rowsByCategory[names[index]] ?: return@mapIndexedNotNull null
            val top = rows.minOf { it.offset }.coerceAtLeast(0)
            val bottom = rows.maxOf { it.offset + it.size }.coerceAtMost(height)
            if (top >= bottom) return@mapIndexedNotNull null
            measurable.measure(Constraints.fixed(width, bottom - top)) to top
        }
        layout(constraints.maxWidth, height) {
            for ((rail, top) in rails) rail.placeRelative(0, top)
        }
    }
}

private fun verticalCategoryName(name: String): String {
    val breaks = BreakIterator.getCharacterInstance().apply { setText(name) }
    var start = breaks.first()
    return buildString {
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            if (start > 0) append('\n')
            append(name, start, end)
            start = end
            end = breaks.next()
        }
    }
}

private class ContractDrag(private val state: LazyListState) {
    var order by mutableStateOf<List<TradingContract>?>(null)
        private set
    var symbol by mutableStateOf<String?>(null)
        private set
    private var center by mutableStateOf(0f)
    private var finger = 0f
    private var categories = emptyMap<String, String>()

    fun start(y: Float, contracts: List<TradingContract>, categories: Map<String, String>): Boolean {
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: return false
        val dragged = item.key as String
        val category = categories[dragged]
        if (contracts.count { categories[it.symbol] == category } < 2) return false
        this.categories = categories
        symbol = dragged
        order = contracts
        center = item.offset + item.size / 2f
        finger = y
        return true
    }

    fun moveBy(distance: Float) {
        center += distance
        finger += distance
        updateOrder()
    }

    fun updateOrder() {
        val items = order ?: return
        val dragged = symbol ?: return
        val category = categories[dragged]
        val slots = items.indices.filter { categories[items[it].symbol] == category }
        val peers = slots.map { items[it] }.toMutableList()
        val target = state.layoutInfo.visibleItemsInfo.asSequence()
            .filter { it.index in slots }
            .minByOrNull { abs(center - it.offset - it.size / 2f) }?.index ?: return
        val from = peers.indexOfFirst { it.symbol == dragged }
        val to = slots.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        val first = state.firstVisibleItemIndex
        if (slots[from] == first || slots[to] == first) state.requestScrollToItem(first, state.firstVisibleItemScrollOffset)
        peers.add(to, peers.removeAt(from))
        order = items.toMutableList().apply { slots.forEachIndexed { index, position -> this[position] = peers[index] } }
    }

    fun translation(): Float {
        val dragged = symbol ?: return 0f
        val visible = state.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.key == dragged } ?: return 0f
        val category = categories[dragged]
        val visualCenter = if (category == null) center else {
            val peers = visible.filter { categories[it.key as String] == category }
            center.coerceIn(peers.minOf { it.offset + it.size / 2f }, peers.maxOf { it.offset + it.size / 2f })
        }
        return visualCenter - item.offset - item.size / 2f
    }

    fun scrollFraction(edge: Float): Float {
        val layout = state.layoutInfo
        val above = finger - layout.viewportStartOffset - edge
        val below = finger - layout.viewportEndOffset + edge
        return when {
            above < 0 && state.canScrollBackward -> (above / edge).coerceAtLeast(-1f)
            below > 0 && state.canScrollForward -> (below / edge).coerceAtMost(1f)
            else -> 0f
        }
    }

    fun reset() {
        order = null
        symbol = null
    }
}
