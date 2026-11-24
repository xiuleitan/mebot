package com.txl.mebot.ui.schedule

import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job

private const val DateAnchorPage = Int.MAX_VALUE / 2
private val DateAnchor = LocalDate.of(2000, 1, 1)

internal fun LocalDate.schedulePageIndex(): Int =
    (DateAnchorPage.toLong() + toEpochDay() - DateAnchor.toEpochDay()).coerceIn(0, Int.MAX_VALUE.toLong() - 1).toInt()
internal fun scheduleDateAtPage(page: Int): LocalDate = DateAnchor.plusDays(page.toLong() - DateAnchorPage)

/** 连点、反向点击及手势中断共用一次请求；旧协程不能结束新请求的保护状态。 */
@Stable
internal class ScheduleDateNavigationState {
    var isInProgress by mutableStateOf(false)
        private set
    var targetDate by mutableStateOf<LocalDate?>(null)
        private set
    private var requestId = 0L
    private var job: Job? = null

    fun begin(date: LocalDate? = null): Long {
        requestId++
        isInProgress = true
        targetDate = date
        job?.cancel()
        job = null
        return requestId
    }

    fun owns(id: Long) = requestId == id && isInProgress

    fun attach(id: Long, request: Job) {
        if (owns(id)) job = request else request.cancel()
    }

    fun finish(id: Long) {
        if (!owns(id)) return
        isInProgress = false
        targetDate = null
        job = null
    }

    fun cancel() {
        requestId++
        job?.cancel()
        job = null
        isInProgress = false
        targetDate = null
    }
}

/** 与日期栏共用分页进度；视觉跟手更新，停稳后才提交业务选中日期。 */
@Composable
internal fun ScheduleDayPager(
    pager: PagerState,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    navigation: ScheduleDateNavigationState = remember { ScheduleDateNavigationState() },
    content: @Composable (LocalDate) -> Unit,
) {
    val selectDate by rememberUpdatedState(onDateSelected)
    var synchronizing by remember { mutableStateOf(true) }

    LaunchedEffect(selectedDate) {
        synchronizing = true
        navigation.cancel()
        try {
            val target = selectedDate.schedulePageIndex()
            // 手势停稳回传的日期不再发起动画，尤其不能打断紧接着的下一次横滑。
            if (pager.settledPage != target || (!pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f)) {
                pager.animateScrollToPage(target, animationSpec = tween(260))
            }
        } finally { synchronizing = false }
    }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            // 日期栏先行时，用户仍可立即接管内容滑动，不等第一段动画结束。
            if (interaction is DragInteraction.Start) navigation.cancel()
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow {
            pager.settledPage.takeUnless { pager.isScrollInProgress || synchronizing || navigation.isInProgress }
        }.distinctUntilChanged().collect { page ->
                if (page != null) {
                    val date = scheduleDateAtPage(page)
                    // 手势取消回原页也要释放临时预读；同日期提交在 ViewModel 中保持幂等。
                    selectDate(date)
                }
            }
    }
    com.txl.mebot.ui.common.PagerSelectionHaptics(pager)
    HorizontalPager(
        state = pager,
        modifier = modifier,
        beyondViewportPageCount = 1,
        userScrollEnabled = enabled,
        key = { it },
    ) { page ->
        // Pager 已提供跟手位移；整页再叠透明度会使多张图片、文字及阴影一起参与额外合成。
        content(scheduleDateAtPage(page))
    }
}
