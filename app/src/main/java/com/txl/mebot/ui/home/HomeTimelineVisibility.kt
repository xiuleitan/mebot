package com.txl.mebot.ui.home

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

internal data class HomeTimelineVisibility(val tomorrow: Boolean, val completed: Boolean, val changing: Boolean = false)

/** 先插入明天并滚动到顶部，停稳后再展开完成记录，避免两次布局变化与位移动画竞争。 */
@Composable
internal fun rememberHomeTimelineVisibility(today: LocalDate, expanded: Boolean,
    grid: LazyGridState, tomorrowOffsets: HomeTimelineTopOffsets): HomeTimelineVisibility {
    var visibility by remember(today) { mutableStateOf(HomeTimelineVisibility(expanded, expanded)) }
    var previouslyExpanded by remember(today) { mutableStateOf(expanded) }
    LaunchedEffect(today, expanded, tomorrowOffsets) {
        val wasExpanded = previouslyExpanded
        previouslyExpanded = expanded
        if (!expanded && !wasExpanded) return@LaunchedEffect
        visibility = HomeTimelineVisibility(expanded, expanded && wasExpanded && visibility.completed, changing = true)
        awaitTimelineLayout()
        try {
            grid.revealTimelineTop(if (expanded) tomorrowOffsets else HomeTimelineTopOffsets.Collapsed)
        } catch (_: CancellationException) {
            // 用户接管滚动时顺着手势停稳；眼睛再次切换或页面退出则取消整段流程。
            currentCoroutineContext().ensureActive()
        }
        snapshotFlow { grid.isScrollInProgress }.first { !it }
        visibility = visibility.copy(completed = expanded)
        awaitTimelineLayout()
        visibility = visibility.copy(changing = false)
    }
    return visibility
}

private suspend fun awaitTimelineLayout() {
    // 让状态引起的重组和测量先各经过一帧；不采用固定毫秒延迟猜测滚动时长。
    repeat(2) { withFrameNanos { } }
}
