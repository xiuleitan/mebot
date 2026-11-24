package com.txl.mebot.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState

/** 明天各项及今天标题相对于列表顶部的实际像素位置；同一行的两张卡共用位置。 */
internal data class HomeTimelineTopOffsets(val items: List<Int>) {
    companion object { val Collapsed = HomeTimelineTopOffsets(listOf(0)) }
}

internal suspend fun LazyGridState.revealTimelineTop(offsets: HomeTimelineTopOffsets) {
    val start = offsets.items.getOrNull(firstVisibleItemIndex)
    if (start == null) {
        // 从远处历史回今天时仍使用列表寻址；眼睛展开使用下面的已知区域，不估算行高。
        animateScrollToItem(0)
        return
    }
    val distance = start + firstVisibleItemScrollOffset
    if (distance > 0) animateScrollBy(-distance.toFloat(), tween(360, easing = FastOutSlowInEasing))
    // 布局的整数像素舍入不再启动第二段弹簧动画。
    if (firstVisibleItemIndex == 0) {
        if (firstVisibleItemScrollOffset > 0) scrollBy(-firstVisibleItemScrollOffset.toFloat())
    } else {
        // 字体或窗口在移动中改变时保证最终位置有效。
        scrollToItem(0)
    }
}
