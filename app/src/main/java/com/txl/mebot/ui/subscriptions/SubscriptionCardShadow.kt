package com.txl.mebot.ui.subscriptions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.txl.mebot.ui.theme.LocalAppTheme

/** 与首页卡片一致；放在尺寸动画外侧，避免阴影被动画的边界裁断。 */
@Composable
internal fun Modifier.subscriptionCardShadow(shape: Shape): Modifier {
    val dark = LocalAppTheme.current.isDark
    return dropShadow(shape) {
        radius = 5.dp.toPx()
        offset = Offset(0f, 2.dp.toPx())
        color = Color.Black.copy(alpha = if (dark) 0.40f else 0.18f)
    }
}
