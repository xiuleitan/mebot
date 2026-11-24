package com.txl.mebot.ui.common

import androidx.compose.ui.graphics.Color

/** 各 Bot 的底部弹层与任务浮窗共用同一遮罩，进度由原有入退场动画控制。 */
internal fun botSheetScrim(progress: Float = 1f): Color =
    Color.Black.copy(alpha = 0.56f * progress.coerceIn(0f, 1f))
