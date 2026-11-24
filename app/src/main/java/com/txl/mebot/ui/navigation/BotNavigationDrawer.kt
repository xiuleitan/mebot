package com.txl.mebot.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DrawerWidthFraction = 0.775f
private val DrawerAnimation = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)

@Composable
fun BotNavigationDrawer(
    destination: BotDestination,
    onDestinationSelected: (BotDestination) -> Unit,
    closeRequest: Long = 0L,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawerState = rememberSaveable(saver = AnchoredDraggableState.Saver<DrawerValue>()) {
        AnchoredDraggableState(initialValue = DrawerValue.Closed)
    }
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = {
        if (!drawerState.isAnimationRunning || drawerState.targetValue != DrawerValue.Open) {
            scope.launch { drawerState.animateTo(DrawerValue.Open, DrawerAnimation) }
        }
    }
    val closeDrawer: () -> Unit = {
        if (!drawerState.isAnimationRunning || drawerState.targetValue != DrawerValue.Closed) {
            scope.launch { drawerState.animateTo(DrawerValue.Closed, DrawerAnimation) }
        }
    }
    LaunchedEffect(closeRequest) { if (closeRequest > 0L) closeDrawer() }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = drawerState,
        animationSpec = DrawerAnimation,
    )
    val closeLabel = stringResource(R.string.close_sidebar)
    val paneLabel = stringResource(R.string.sidebar)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerWidth = minOf(maxWidth * DrawerWidthFraction, 360.dp)
        val drawerWidthPx = with(LocalDensity.current) { drawerWidth.roundToPx() }
        val isVisible by remember(drawerState) {
            derivedStateOf { drawerState.openFraction() > 0f }
        }

        PredictiveBackHandler(enabled = isVisible) { events ->
            try {
                val startOffset = drawerState.requireOffset()
                drawerState.anchoredDrag(targetValue = DrawerValue.Closed) { anchors, _ ->
                    val closedOffset = anchors.positionOf(DrawerValue.Closed)
                    events.collect { event ->
                        dragTo(startOffset + (closedOffset - startOffset) * event.progress)
                    }
                }
                drawerState.animateTo(DrawerValue.Closed, DrawerAnimation)
            } catch (cancelled: CancellationException) {
                // 返回手势取消时，平滑回到展开位置。
                openDrawer()
                throw cancelled
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .anchoredDraggable(
                    state = drawerState,
                    orientation = Orientation.Horizontal,
                    flingBehavior = flingBehavior,
                    enabled = isVisible || destination == BotDestination.Home,
                ),
        ) {
            Box(
                modifier = if (isVisible) Modifier.clearAndSetSemantics { } else Modifier,
            ) {
                content(openDrawer)
            }

            if (destination != BotDestination.Home) {
                // 内容页仅保留左侧边缘拉出手势，不影响页面内的滚动。
                Box(
                    Modifier
                        .width(16.dp)
                        .fillMaxHeight()
                        .anchoredDraggable(
                            state = drawerState,
                            orientation = Orientation.Horizontal,
                            flingBehavior = flingBehavior,
                        ),
                )
            }

            val scrimModifier = if (isVisible) {
                Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = closeLabel,
                        onClick = closeDrawer,
                    )
                    .semantics { contentDescription = closeLabel }
            } else {
                Modifier
            }
            Canvas(modifier = Modifier.fillMaxSize().then(scrimModifier)) {
                // 位移和透明度共用进度，关闭到位前持续拦截遮罩点击。
                drawRect(Color.Black, alpha = drawerState.openFraction() * 0.4f)
            }

            BotDrawer(
                destination = destination,
                onArchiveClick = {
                    onDestinationSelected(BotDestination.Archive)
                    closeDrawer()
                },
                onSubscriptionsClick = {
                    onDestinationSelected(BotDestination.Subscriptions)
                    closeDrawer()
                },
                onScheduleClick = {
                    onDestinationSelected(BotDestination.Schedule)
                    closeDrawer()
                },
                onFlashnoteClick = {
                    onDestinationSelected(BotDestination.Flashnote)
                    closeDrawer()
                },
                onDayimaClick = {
                    onDestinationSelected(BotDestination.Dayima)
                    closeDrawer()
                },
                onTradingClick = {
                    onDestinationSelected(BotDestination.Trading)
                    closeDrawer()
                },
                onSettingsClick = {
                    onDestinationSelected(BotDestination.Settings)
                    closeDrawer()
                },
                modifier = Modifier
                    .width(drawerWidth)
                    .offset {
                        val offset = drawerState.offset
                        IntOffset(
                            x = if (offset.isNaN()) {
                                if (drawerState.currentValue == DrawerValue.Open) 0
                                else -drawerWidthPx
                            } else {
                                offset.roundToInt()
                            },
                            y = 0,
                        )
                    }
                    .onSizeChanged { size ->
                        // 同一帧更新实际宽度和锚点，适应横屏、窗口变化及状态恢复。
                        drawerState.updateAnchors(
                            newAnchors = DraggableAnchors {
                                DrawerValue.Closed at -size.width.toFloat()
                                DrawerValue.Open at 0f
                            },
                            newTarget = drawerState.targetValue,
                        )
                    }
                    .then(
                        if (isVisible) {
                            Modifier.semantics {
                                paneTitle = paneLabel
                                dismiss {
                                    closeDrawer()
                                    true
                                }
                            }
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ),
            )
        }
    }
}

private fun AnchoredDraggableState<DrawerValue>.openFraction(): Float {
    val closedOffset = anchors.positionOf(DrawerValue.Closed)
    val currentOffset = offset
    if (closedOffset.isNaN() || currentOffset.isNaN() || closedOffset >= 0f) {
        return if (currentValue == DrawerValue.Open) 1f else 0f
    }
    // 使用测量后的锚点，避免 dp 换算取整后关闭时残留透明遮罩。
    return ((currentOffset - closedOffset) / -closedOffset).coerceIn(0f, 1f)
}
