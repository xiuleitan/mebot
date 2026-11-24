package com.txl.mebot.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.data.home.*
import com.txl.mebot.ui.common.hapticChange
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HomeDayTitleStyle = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
private val HomeEmptyDayStyle = TextStyle(fontSize = 14.sp)

/** 两列与三列共用六格；余像素分散到两侧，避免集中舍入使左列比右列宽两像素。 */
private object HomeTimelineCells : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val available = (availableSize - spacing * 5).coerceAtLeast(0)
        val size = available / 6
        val remainder = available % 6
        val extras = when (remainder) {
            2 -> intArrayOf(0, 3)
            3 -> intArrayOf(0, 2, 4)
            4 -> intArrayOf(0, 1, 3, 4)
            else -> IntArray(remainder) { it }
        }
        return List(6) { size + if (it in extras) 1 else 0 }
    }
}

@Composable
internal fun HomeTimeline(
    today: LocalDate,
    bottomPadding: Dp, onOpenEntry: (HomePreviewEntry) -> Unit,
    modifier: Modifier = Modifier, data: HomeTimelineData? = null,
    onOpenCard: (HomeCard) -> Unit = {}, onSelectNote: (LocalDate, String) -> Unit = { _, _ -> },
    onMore: () -> Unit = {}, loading: Boolean = false,
    onPinNote: (String, Boolean) -> Unit = { _, _ -> },
    expanded: Boolean = false, onToggleExpansion: () -> Unit = {},
    reflectionCard: (@Composable () -> Unit)? = null,
    grid: LazyGridState = rememberLazyGridState(),
) {
    val colors = scheduleColors()
    val tomorrowCount = if (data == null) homePreviewEntries.count { it.dayOffset == 1L }
        else data.days.firstOrNull { it.date == today.plusDays(1) }?.slots?.size ?: 0
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = bottomPadding).clipToBounds()) {
        val offsets = homeTomorrowOffsets(maxWidth, tomorrowCount)
        val visibility = rememberHomeTimelineVisibility(today, expanded, grid, offsets)
        val days = remember(visibility.tomorrow, visibility.completed) {
            homePreviewDays(visibility.tomorrow, visibility.completed)
        }
        val placement = if (visibility.changing) null else spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)
        val fadeIn = if (visibility.changing && !visibility.completed) null else tween<Float>(180)
        val fadeOut = tween<Float>(120)
        // 六个基础格：今天／明天每张占三格，历史每张占两格，始终保持同一滚动容器。
        LazyVerticalGrid(HomeTimelineCells, Modifier.fillMaxSize().testTag("home_timeline"), state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (data == null) days.forEach { (offset, entries) ->
                item(key = "day:$offset", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                    HomeDayHeading(today, offset, Modifier.animateItem(fadeIn, placement, fadeOut), preview = true,
                        expanded = expanded, onToggleVisibility = onToggleExpansion,
                        historyBoundary = offset == days.keys.firstOrNull { it < 0 })
                }
                items(entries, key = { it.id }, span = { GridItemSpan(if (offset < 0) 2 else 3) }, contentType = { "card" }) { entry ->
                    HomePreviewCard(entry, { onOpenEntry(entry) }, Modifier.animateItem(fadeIn, placement, fadeOut), compact = offset < 0)
                }
            }
            if (data != null) {
                val hasToday = data.days.any { it.date == today }
                val tomorrow = today.plusDays(1)
                val visibleDays = buildList {
                    addAll(data.days.filter { it.date <= today || (visibility.tomorrow && it.date == tomorrow) })
                    // Keep today's header as the first scroll anchor while local data loads.
                    if (!hasToday) add(HomeDay(today, emptyList()))
                    if (visibility.tomorrow && data.days.none { it.date == tomorrow }) add(HomeDay(tomorrow, emptyList()))
                }
                    .sortedByDescending { it.date }
                    .map { day -> day.copy(slots = day.slots.filter { slot ->
                        day.date == tomorrow || visibility.completed || !slot.selected.completed
                    }) }
                    .filter { it.date >= today || it.slots.isNotEmpty() }
                val firstHistoryDate = visibleDays.firstOrNull { it.date < today }?.date
                visibleDays.forEach { day ->
                    val offset = ChronoUnit.DAYS.between(today, day.date)
                    val slots = day.slots
                    item(key = "day:$offset", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                        HomeDayHeading(today, offset, Modifier.animateItem(fadeIn, placement, fadeOut),
                            expanded = expanded, onToggleVisibility = onToggleExpansion,
                            historyBoundary = day.date == firstHistoryDate)
                    }
                    if (slots.isEmpty() && (offset != 0L || hasToday)) item(key = "empty:$offset", span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (visibility.completed || day.date == tomorrow) "这一天暂无事项" else "这一天暂无待办提醒",
                            Modifier.fillMaxWidth().padding(vertical = 20.dp), color = colors.secondary,
                            style = LocalTextStyle.current.merge(HomeEmptyDayStyle))
                    }
                    items(slots, key = { it.id }, span = { GridItemSpan(if (offset < 0) 2 else 3) }, contentType = { "real_card" }) { slot ->
                        HomeTaskSlot(slot, onOpenCard, { onSelectNote(day.date, it) }, Modifier.animateItem(fadeIn, placement, fadeOut), compact = offset < 0, pin = onPinNote)
                    }
                    if (offset == 0L && reflectionCard != null) item(key = "home_reflection", span = { GridItemSpan(maxLineSpan) }, contentType = "reflection") {
                        Box(Modifier.fillMaxWidth().animateItem(fadeIn, placement, fadeOut).layout { measurable, constraints ->
                            // 只让寄语跨过网格的两侧留白，其他卡片及展开滚动的测量保持不变。
                            val inset = 16.dp.roundToPx()
                            val fullWidth = constraints.maxWidth + inset * 2
                            val child = measurable.measure(constraints.copy(minWidth = fullWidth, maxWidth = fullWidth))
                            layout(constraints.maxWidth, child.height) { child.placeRelative(-inset, 0) }
                        }) {
                            reflectionCard()
                        }
                    }
                }
                item(key = "history_more", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onMore, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("查看更早的事项") }
                }
            }
        }
    }
}

/** 复用卡片尺寸与文字样式测量明天的区域，不用可见行的平均高度猜测目标距离。 */
@Composable
private fun homeTomorrowOffsets(width: Dp, count: Int): HomeTimelineTopOffsets {
    val density = LocalDensity.current
    val text = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current
    val spacing = with(density) { 12.dp.roundToPx() }
    val contentWidth = with(density) { (width.roundToPx() - 2 * 16.dp.roundToPx()).coerceAtLeast(1) }
    val cells = with(HomeTimelineCells) { with(density) { calculateCrossAxisCellSizes(contentWidth, spacing) } }
    val left = with(density) { (cells.take(3).sum() + spacing * 2).toDp() }
    val right = with(density) { (cells.drop(3).sum() + spacing * 2).toDp() }
    val leftHeight = with(density) { com.txl.mebot.ui.schedule.scheduleCardMetrics(left).height.roundToPx() }
    val rightHeight = with(density) { com.txl.mebot.ui.schedule.scheduleCardMetrics(right).height.roundToPx() }
    val heading = text.measure(stringResource(R.string.home_tomorrow), style = baseStyle.merge(HomeDayTitleStyle),
        constraints = Constraints(maxWidth = contentWidth)).size.height
    val headingHeight = with(density) { maxOf(48.dp.roundToPx(), heading + 4.dp.roundToPx()) }
    val emptyHeight = text.measure("这一天暂无事项", style = baseStyle.merge(HomeEmptyDayStyle),
        constraints = Constraints(maxWidth = contentWidth)).size.height + with(density) { 2 * 20.dp.roundToPx() }
    return remember(count, spacing, headingHeight, leftHeight, rightHeight, emptyHeight) {
        val items = mutableListOf(0)
        var y = headingHeight + spacing
        if (count == 0) {
            items += y
            y += emptyHeight + spacing
        } else {
            repeat((count + 1) / 2) { row ->
                items += y
                val paired = row * 2 + 1 < count
                if (paired) items += y
                y += (if (paired) maxOf(leftHeight, rightHeight) else leftHeight) + spacing
            }
        }
        items += y // 今天标题；展开前的锚点也有明确位置。
        HomeTimelineTopOffsets(items)
    }
}

@Composable
private fun HomeDayHeading(
    today: LocalDate, offset: Long, modifier: Modifier, preview: Boolean = false,
    expanded: Boolean = false, onToggleVisibility: () -> Unit = {}, historyBoundary: Boolean = false,
) {
    val colors = scheduleColors()
    val label = when (offset) {
        1L -> stringResource(R.string.home_tomorrow)
        0L -> stringResource(R.string.home_today)
        -1L -> stringResource(R.string.home_yesterday)
        -2L -> stringResource(R.string.home_before_yesterday)
        else -> today.plusDays(offset).let { date ->
            date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日"))
        }
    }
    Row(modifier.padding(top = if (historyBoundary) 32.dp else 0.dp).fillMaxWidth().heightIn(min = if (offset < 0) 40.dp else 48.dp).padding(top = 4.dp).testTag("home_day_$offset"),
        verticalAlignment = Alignment.CenterVertically) {
        if (offset < 0) {
            HomeDateDivider(Modifier.weight(1f).height(1.dp))
            Text(label, Modifier.padding(horizontal = 12.dp).semantics { heading() },
                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, color = colors.secondary)
            HomeDateDivider(Modifier.weight(1f).height(1.dp).testTag("home_day_divider_$offset"))
        } else {
            Text(label, Modifier.semantics { heading() }, style = LocalTextStyle.current.merge(HomeDayTitleStyle), color = colors.ink)
        }
        if (offset == 0L) {
            Spacer(Modifier.weight(1f))
            if (preview) {
                val previewLabel = stringResource(R.string.home_preview_label)
                Text(stringResource(R.string.home_preview_badge), color = colors.secondary, fontSize = 11.sp,
                    modifier = Modifier.semantics { stateDescription = previewLabel })
            }
            val visibilityState = stringResource(if (expanded) R.string.home_timeline_expanded else R.string.home_timeline_collapsed)
            IconToggleButton(checked = expanded, onCheckedChange = hapticChange<Boolean> { onToggleVisibility() },
                modifier = Modifier.size(48.dp).testTag("home_completed_visibility").semantics { stateDescription = visibilityState }) {
                Icon(painterResource(if (expanded) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    stringResource(if (expanded) R.string.home_collapse_timeline else R.string.home_expand_timeline),
                    Modifier.size(24.dp), tint = if (expanded) colors.ink else colors.secondary)
            }
        }
    }
}

@Composable
private fun HomeDateDivider(modifier: Modifier) {
    val colors = scheduleColors()
    Canvas(modifier) {
        drawLine(colors.divider, start = Offset(0f, size.height / 2), end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    }
}

@Composable
private fun HomePreviewCard(entry: HomePreviewEntry, onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    val source = when (entry.source) {
        R.string.flashnote_title -> HomeSource.FLASHNOTE
        R.string.dayima_title -> HomeSource.DAYIMA
        R.string.subscriptions_title -> HomeSource.SUBSCRIPTION
        else -> HomeSource.SCHEDULE
    }
    HomeTaskCard(HomeCard(entry.id, source, entry.id, LocalDate.MIN, stringResource(entry.title), "",
        entry.completed, 1), onClick, modifier, compact = compact, coverResource = entry.cover)
}
