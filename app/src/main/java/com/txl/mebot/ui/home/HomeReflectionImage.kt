package com.txl.mebot.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** 使用完整图片的 Fit 图层缩放到铺满；缩小到 1 时仍能看到原图全貌。 */
@Composable
internal fun HomeReflectionImage(photo: ImageBitmap, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.clipToBounds().testTag("home_reflection_image")) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val fit = min(width / photo.width, height / photo.height)
        val fillScale = max(width / photo.width, height / photo.height) / fit
        val fittedWidth = photo.width * fit
        val fittedHeight = photo.height * fit
        var scale by remember(photo, width, height) { mutableFloatStateOf(fillScale) }
        var offset by remember(photo, width, height) { mutableStateOf(Offset.Zero) }
        var returnAnimation by remember(photo, width, height) { mutableStateOf<Job?>(null) }
        var inspecting by remember(photo, width, height) { mutableStateOf(false) }

        Image(photo, "写给自己的图片", Modifier.fillMaxSize()
            .semantics { stateDescription = if (inspecting) "双指查看图片" else "图片铺满显示" }
            .pointerInput(photo, width, height) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var transformed = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                if (!transformed) {
                                    returnAnimation?.cancel()
                                    inspecting = true
                                    transformed = true
                                }
                                val nextScale = (scale * event.calculateZoom()).coerceIn(1f, fillScale * 4f)
                                val zoom = nextScale / scale
                                val center = event.calculateCentroid(useCurrent = false) - Offset(width / 2, height / 2)
                                val nextOffset = (offset - center) * zoom + center + event.calculatePan()
                                val maxX = ((fittedWidth * nextScale - width) / 2).coerceAtLeast(0f)
                                val maxY = ((fittedHeight * nextScale - height) / 2).coerceAtLeast(0f)
                                scale = nextScale
                                offset = Offset(nextOffset.x.coerceIn(-maxX, maxX), nextOffset.y.coerceIn(-maxY, maxY))
                            }
                            // 单指点击／滚动交给首页；双指开始后吞掉余下手势，避免松手误开编辑页。
                            if (transformed) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        if (transformed) {
                            val startScale = scale
                            val startOffset = offset
                            returnAnimation = scope.launch {
                                animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { progress, _ ->
                                    scale = startScale + (fillScale - startScale) * progress
                                    offset = startOffset * (1f - progress)
                                }
                                inspecting = false
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }, contentScale = ContentScale.Fit)
    }
}
