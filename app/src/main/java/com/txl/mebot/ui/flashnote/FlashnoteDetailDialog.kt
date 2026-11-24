package com.txl.mebot.ui.flashnote

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.txl.mebot.R
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.theme.LocalAppTheme

/** 无拖拽详情始终按当前窗口贴底布局，异步加载不再依赖旧内容高度的展开锚点。 */
@Composable
internal fun FlashnoteDetailDialog(dismissAfterAction: Boolean, onDismiss: () -> Unit, content: @Composable (() -> Unit) -> Unit) {
    val colors = scheduleColors()
    val shape = RoundedCornerShape(26.dp)
    val entrance = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val close = { closing = true }
    Dialog(onDismissRequest = close, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = true, dismissOnClickOutside = true,
    )) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        val dark = LocalAppTheme.current.isDark
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        LaunchedEffect(dismissAfterAction) { if (dismissAfterAction) close() }
        LaunchedEffect(closing) {
            if (closing) {
                focus.clearFocus(); keyboard?.hide()
                entrance.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                dismiss()
            } else entrance.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
        val closeLabel = stringResource(R.string.flashnote_close_detail)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().background(botSheetScrim(entrance.value))
                .semantics { contentDescription = closeLabel }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = closeLabel, onClick = close))
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(top = 24.dp, bottom = 12.dp)) {
                Surface(modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 688.dp).fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        alpha = entrance.value
                        translationY = (1f - entrance.value) * 28.dp.toPx()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }.dropShadow(shape) {
                        radius = 24.dp.toPx(); spread = 2.dp.toPx()
                        color = Color.Black.copy(alpha = 0.28f); offset = Offset(0f, 6.dp.toPx())
                    }, shape = shape, color = colors.surface, contentColor = colors.ink) {
                    // 底边对齐的单一高度动画保留操作栏位置；外层圆角和阴影跟随实际尺寸。
                    Box(Modifier.animateContentSize(tween(220), alignment = Alignment.BottomCenter)) {
                        content(close)
                    }
                }
            }
        }
    }
}
