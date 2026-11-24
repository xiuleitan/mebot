package com.txl.mebot.ui.schedule

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 浮窗文字相对原版缩小三分之一，继续使用 sp 遵循设备字体设置。 */
internal fun scheduleChatTextSize(originalSp: Int): TextUnit = (originalSp * (2f / 3f)).sp

/** 日程气泡缩为上轮字号与行高的 3/4：正文 14 sp、行高 21 sp。 */
internal fun scheduleChatBubbleTextSize(originalSp: Int): TextUnit = scheduleChatTextSize(originalSp) * 1.5f
