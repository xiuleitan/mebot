package com.txl.mebot.ui.schedule

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.theme.LocalAppTheme
import java.time.LocalDate
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val Weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 索引与小数分开，避免十亿级日期页码转换为 Float 后丢失跟手的小数位。 */
private data class StripPosition(val page: Int, val fraction: Float = 0f) {
    fun shifted(days: Float): StripPosition {
        val total = fraction + days
        val whole = floor(total + 0.5f).toInt()
        val next = (page.toLong() + whole).coerceIn(0, Int.MAX_VALUE.toLong() - 1).toInt()
        return StripPosition(next, if (next.toLong() == page.toLong() + whole) total - whole else 0f)
    }
}

@Composable
internal fun ScheduleDateStrip(
    today: LocalDate,
    selectedDate: LocalDate,
    datesWithEntries: Set<LocalDate>,
    entryCounts: Map<LocalDate, Int>,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onVisibleDatesChanged: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    dayPager: PagerState? = null,
    navigation: ScheduleDateNavigationState = remember { ScheduleDateNavigationState() },
    onDatePreload: (LocalDate?) -> Unit = {},
) {
    var browsing by remember { mutableStateOf(false) }
    var manualPosition by remember { mutableStateOf(StripPosition(selectedDate.schedulePageIndex())) }
    val visibleDatesChanged by rememberUpdatedState(onVisibleDatesChanged)
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun contentPosition() = StripPosition(dayPager?.currentPage ?: selectedDate.schedulePageIndex(), dayPager?.currentPageOffsetFraction ?: 0f)
    fun stripPosition(): StripPosition = when {
        navigation.targetDate != null -> manualPosition
        !browsing || dayPager?.isScrollInProgress == true -> contentPosition()
        else -> manualPosition
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val density = LocalDensity.current
        // 通常字体显示五日；大字体给日期更多宽度，通过横向浏览查看其他日期。
        val cellWidth = maxOf((maxWidth - 8.dp * 4) / 5, with(density) { 14.sp.toDp() * 2 + 12.dp }, 48.dp)
        val stepPixels = with(density) { (cellWidth + 8.dp).toPx() }
        val stripHeight = maxOf(96.dp, with(density) { 19.sp.toDp() + 33.sp.toDp() } + 38.dp)
        val scroll = rememberScrollableState { delta ->
            if (!browsing) {
                val position = stripPosition()
                if (navigation.targetDate != null) {
                    navigation.cancel()
                    onDatePreload(null)
                }
                manualPosition = position
            }
            browsing = true
            manualPosition = manualPosition.shifted(-delta / stepPixels)
            delta
        }
        val defaultFling = ScrollableDefaults.flingBehavior()
        val snappingFling = remember(defaultFling, stepPixels) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val remainder = with(defaultFling) { performFling(initialVelocity) }
                    val distance = manualPosition.fraction * stepPixels
                    var previous = 0f
                    if (distance.absoluteValue > 0.1f) {
                        animate(0f, distance, animationSpec = tween(180)) { value, _ ->
                            scrollBy(value - previous)
                            previous = value
                        }
                    }
                    return remainder
                }
            }
        }
        LaunchedEffect(dayPager, scroll) {
            if (dayPager != null) {
                var previousPage = dayPager.currentPage
                snapshotFlow { dayPager.currentPage to dayPager.isScrollInProgress }.distinctUntilChanged().collect { (page, moving) ->
                    if (moving || page != previousPage) {
                        scroll.stopScroll(MutatePriority.PreventUserInput)
                        browsing = false
                    }
                    previousPage = page
                }
            }
        }
        LaunchedEffect(selectedDate, dayPager) { if (dayPager == null) browsing = false }

        val centerPage = stripPosition().page
        val pages = (-3..3).mapNotNull { offset ->
            (centerPage.toLong() + offset).takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.toInt()
        }
        LaunchedEffect(centerPage) {
            visibleDatesChanged(scheduleDateAtPage(pages.first()), scheduleDateAtPage(pages.last()))
        }

        Layout(
            modifier = Modifier.fillMaxWidth().height(stripHeight + 10.dp).clipToBounds()
                .scrollable(scroll, Orientation.Horizontal, enabled = dayPager?.isScrollInProgress != true,
                    reverseDirection = rtl, flingBehavior = snappingFling),
            content = {
                pages.forEach { page ->
                    val date = scheduleDateAtPage(page)
                    key(page) {
                        ScheduleDateCell(
                            date = date, today = today, selectedDate = selectedDate,
                            hasEntries = date in datesWithEntries,
                            count = entryCounts[date] ?: 0,
                            cellWidth = cellWidth, stripHeight = stripHeight,
                            selectionProgress = {
                                val position = stripPosition()
                                (1f - ((position.page.toLong() - page).toFloat() + position.fraction).absoluteValue).coerceIn(0f, 1f)
                            },
                            onClick = {
                                val start = stripPosition()
                                val cardsAlreadyMoving = dayPager?.isScrollInProgress == true
                                manualPosition = start
                                val request = navigation.begin(date)
                                val job = scope.launch {
                                    try {
                                        scroll.stopScroll(MutatePriority.PreventUserInput)
                                        browsing = false
                                        onDatePreload(date)
                                        val target = date.schedulePageIndex()
                                        suspend fun moveDates() {
                                            val distance = (target.toLong() - start.page).toFloat() - start.fraction
                                            if (distance.absoluteValue > 0.001f) animate(0f, distance, animationSpec = tween(160)) { value, _ ->
                                                if (navigation.owns(request)) manualPosition = start.shifted(value)
                                            }
                                            if (navigation.owns(request)) manualPosition = StripPosition(target)
                                        }
                                        suspend fun moveCards() {
                                            if (dayPager == null) return
                                            if (dayPager.settledPage != target || dayPager.isScrollInProgress || dayPager.currentPageOffsetFraction != 0f) {
                                                dayPager.animateScrollToPage(target, animationSpec = tween(260))
                                            }
                                        }
                                        if (cardsAlreadyMoving) coroutineScope {
                                            // 连点时从当前进度转向，避免把卡片停在半页等待。
                                            launch { moveDates() }
                                            moveCards()
                                        } else {
                                            moveDates()
                                            moveCards()
                                        }
                                        if (navigation.owns(request)) onDateSelected(date)
                                    } finally {
                                        if (navigation.owns(request)) {
                                            // 手势打断时保留正在露出的目标页，待手势停稳提交后再释放。
                                            if (dayPager?.isScrollInProgress != true) onDatePreload(null)
                                            navigation.finish(request)
                                        }
                                    }
                                }
                                navigation.attach(request, job)
                            },
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val slotWidth = stepPixels.roundToInt()
            val placeables = measurables.map { it.measure(Constraints.fixed(slotWidth, height)) }
            layout(width, height) {
                // 在放置阶段直接读卡片的当前偏移，不通过协程排队请求另一个列表重排。
                val position = stripPosition()
                placeables.forEachIndexed { index, placeable ->
                    val distance = (pages[index].toLong() - position.page).toFloat() - position.fraction
                    placeable.placeRelative(((width - slotWidth) / 2f + distance * stepPixels).roundToInt(), 0)
                }
            }
        }
    }
}

@Composable
private fun ScheduleDateCell(
    date: LocalDate, today: LocalDate, selectedDate: LocalDate, hasEntries: Boolean, count: Int,
    cellWidth: androidx.compose.ui.unit.Dp, stripHeight: androidx.compose.ui.unit.Dp,
    selectionProgress: () -> Float, onClick: () -> Unit,
) {
    val colors = scheduleColors()
    val isDark = LocalAppTheme.current.isDark
    val isToday = date == today
    val isPast = date < today
    val isSelected = date == selectedDate
    val shape = RoundedCornerShape(13.dp)
    val selection = selectionProgress()
    val gray = if (isDark) Color(0xFFA4ABB2) else Color(0xFF78828C)
    val background = when {
        isToday -> colors.accent
        isPast -> lerp(colors.backgroundTop, colors.past, 0.45f)
        else -> lerp(colors.surface, colors.selected, selection)
    }
    val foreground = when { isToday -> colors.onAccent; isPast -> gray; else -> colors.ink }
    val widthFraction = when { isToday -> 1f; isPast -> 0.82f; else -> 0.93f }
    val heightFraction = when { isToday -> 0.96f; isPast -> 0.70f; else -> 0.85f }
    val weekday = Weekdays[date.dayOfWeek.value - 1]
    val todayLabel = stringResource(R.string.schedule_today)
    val label = stringResource(R.string.schedule_date_accessibility, date.scheduleAccessibilityLabel(),
        if (isToday) "$weekday，$todayLabel" else weekday, count)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.minimumInteractiveComponentSize().width(cellWidth * widthFraction).height(stripHeight * heightFraction)
                .dropShadow(shape) {
                    radius = 4.dp.toPx()
                    offset = Offset(0f, 2.dp.toPx())
                    color = Color.Black.copy(alpha = when {
                        isToday -> if (isDark) 0.42f else 0.20f
                        isPast -> if (isDark) 0.18f else 0.08f
                        else -> if (isDark) 0.32f else 0.14f
                    })
                }
                .clip(shape).background(background)
                .border(1.5.dp, if (isToday) Color.Transparent else colors.accent.copy(alpha = selection), shape)
                .selectable(isSelected, role = Role.Tab, onClick = hapticAction(onClick))
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(vertical = if (isPast) 6.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Text(weekday, color = foreground, fontSize = when { isToday -> 14.sp; isPast -> 11.sp; else -> 13.sp },
                lineHeight = when { isToday -> 19.sp; isPast -> 14.sp; else -> 17.sp })
            Text(date.dayOfMonth.toString(), color = foreground,
                fontSize = when { isToday -> 28.sp; isPast -> 19.sp; else -> 22.sp },
                lineHeight = when { isToday -> 33.sp; isPast -> 22.sp; else -> 26.sp },
                fontWeight = if (isToday) FontWeight.Bold else if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
            Box(Modifier.padding(top = 2.dp).size(if (isPast) 4.dp else 5.dp).background(
                if (!hasEntries) Color.Transparent else if (isToday) colors.onAccent else if (isPast) gray else colors.accent,
                CircleShape))
        }
    }
}
