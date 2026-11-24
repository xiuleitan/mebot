package com.txl.mebot.ui.dayima

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleColors
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** 两个真实图表始终各保留一份组合，翻卡不重建柱图滚动位置，也不复制业务状态。 */
@Composable
internal fun DayimaChartDeck(state: DayimaChartsState, model: DayimaViewModel?) {
    val colors = scheduleColors()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = com.txl.mebot.ui.common.rememberAppHaptics()
    var front by rememberSaveable { mutableIntStateOf(0) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val animation = remember { Animatable(0f) }
    var heights by remember { mutableStateOf(listOf(0, 0)) }
    val frontOnTop by remember { derivedStateOf { abs(fraction) <= 0.5f } }
    val names = listOf(stringResource(R.string.dayima_chart_distribution), stringResource(R.string.dayima_chart_trend))
    val nextLabel = stringResource(R.string.dayima_chart_deck_next)
    val previousLabel = stringResource(R.string.dayima_chart_deck_previous)
    val deckLabel = stringResource(R.string.dayima_chart_deck_label)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val peek = 18.dp
        val frontWidth = (maxWidth - peek).coerceAtLeast(1.dp)
        val widthPx = with(density) { frontWidth.toPx() }
        val peekPx = with(density) { peek.toPx() }
        val depthPx = with(density) { 12.dp.toPx() }
        val threshold = with(density) { 850.dp.toPx() }
        val minHeight = with(density) { heights.max().toDp() }
        val histogramAvailable = !state.loading && !state.failed &&
            (state.data?.statistics?.period?.eligibleCycleCount ?: 0) > 0
        fun settle(target: Float, velocity: Float = 0f) {
            if (target == 0f && fraction == 0f && !settling) return
            val previous = job
            job = scope.launch {
                previous?.cancelAndJoin()
                settling = true
                try {
                    animation.snapTo(fraction)
                    animation.animateTo(target, spring(dampingRatio = 1f, stiffness = 450f),
                        initialVelocity = velocity / widthPx) { fraction = value.coerceIn(-1f, 1f) }
                    if (target != 0f) { front = 1 - front; haptics.selection() }
                    fraction = 0f
                } finally { settling = false }
            }
        }
        val drag = rememberDraggableState { delta -> fraction = (fraction + delta / widthPx).coerceIn(-1f, 1f) }
        val nestedScroll = remember(widthPx, threshold) {
            DayimaDeckNestedScroll(
                onStart = { job?.cancel(); settling = false },
                onDrag = { delta -> fraction = (fraction + delta / widthPx).coerceIn(-1f, 1f) },
                onStop = { velocity -> settle(dayimaDeckTarget(fraction, velocity, widthPx, threshold), velocity) },
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().clipToBounds().animateContentSize(tween(220)).padding(bottom = 12.dp)
                .nestedScroll(nestedScroll)
                .semantics {
                    contentDescription = deckLabel
                    stateDescription = names[front]
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { settle(-1f); true },
                        CustomAccessibilityAction(previousLabel) { settle(1f); true },
                    )
                }
                .draggable(drag, Orientation.Horizontal, startDragImmediately = settling,
                    onDragStarted = { job?.cancelAndJoin(); settling = false },
                    onDragStopped = { velocity -> settle(dayimaDeckTarget(fraction, velocity, widthPx, threshold), velocity) })) {
                repeat(2) { index ->
                    val active = index == front
                    val extraHeightPx = if (index == 0 && histogramAvailable) (heights.max() - heights[0]).coerceAtLeast(0) else 0
                    val extraHeight = with(density) { extraHeightPx.toDp() }
                    Surface(shape = RoundedCornerShape(26.dp), color = colors.surface,
                        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.5f)),
                        modifier = Modifier.width(frontWidth).zIndex(if (active) { if (frontOnTop) 2f else 0f } else 1f)
                            .graphicsLayer {
                                val pose = dayimaDeckPose(active, fraction, widthPx, peekPx)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = pose.x; translationY = pose.depth * depthPx
                                scaleX = pose.scale; scaleY = pose.scale; alpha = pose.alpha
                            }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })) {
                        // Box 不把最小高度传给内层：可测得自然高度，字号／空状态变化后也能缩回。
                        Box(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
                            Column(Modifier.fillMaxWidth().onSizeChanged { size ->
                                // 仍记录未拉伸的自然高度，避免填满后反复缩回、重新拉伸。
                                val naturalHeight = (size.height - extraHeightPx).coerceAtLeast(0)
                                if (heights[index] != naturalHeight) heights = heights.toMutableList().also { it[index] = naturalHeight }
                            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (index == 0) DayimaDistributionContent(state, model, extraHeight) else DayimaAgeTrendContent(state, model)
                            }
                        }
                    }
                }
            }
        }
    }
}
