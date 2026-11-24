package com.txl.mebot.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.launch

internal typealias BotEditorSheetCloser = ((() -> Unit)?) -> Unit

/** 沿用日程与闪念的底部弹层过渡，表单和详情共享相同的开合行为。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotEditorSheet(onDismiss: () -> Unit, dismissible: Boolean = true,
    beforeDismiss: suspend () -> Boolean = { true },
    content: @Composable ColumnScope.(BotEditorSheetCloser) -> Unit) {
    val colors = scheduleColors()
    val shape = RoundedCornerShape(26.dp)
    val density = LocalDensity.current
    val state = remember(density) { SheetState(true, initialValue = SheetValue.Expanded,
        positionalThreshold = { with(density) { 56.dp.toPx() } }, velocityThreshold = { with(density) { 125.dp.toPx() } }) }
    val entrance = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var closing by remember { mutableStateOf(false) }
    fun close(after: (() -> Unit)?) {
        if (closing || !dismissible) return
        closing = true
        scope.launch {
            try {
                if (!beforeDismiss()) {
                    if (!state.isVisible) state.show()
                    return@launch
                }
                focus.clearFocus(); keyboard?.hide(); state.hide()
                if (!state.isVisible) { onDismiss(); after?.invoke() }
            } finally { closing = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val available = (maxHeight - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() - 24.dp).coerceAtLeast(160.dp)
        ModalBottomSheet(onDismissRequest = { if (dismissible) close(null) }, sheetState = state, sheetMaxWidth = 688.dp,
            sheetGesturesEnabled = false,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissible, shouldDismissOnClickOutside = dismissible),
            modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp).graphicsLayer {
                alpha = entrance.value; translationY = (1f - entrance.value) * 28.dp.toPx(); compositingStrategy = CompositingStrategy.ModulateAlpha
            }.dropShadow(shape) {
                radius = 24.dp.toPx(); spread = 2.dp.toPx(); color = Color.Black.copy(alpha = 0.28f)
                val positioned = state.hasExpandedState
                alpha = if (positioned) 1f else 0f
                offset = Offset(0f, (if (positioned) state.requireOffset() else 0f) + 6.dp.toPx())
            }, shape = shape, containerColor = colors.surface, contentColor = colors.ink,
            scrimColor = botSheetScrim(entrance.value), contentWindowInsets = { WindowInsets(0, 0, 0, 0) }, dragHandle = null) {
            LaunchedEffect(entrance) { entrance.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
            val resize = if (entrance.value == 1f) Modifier.animateContentSize(tween(220)) else Modifier
            Column(Modifier.fillMaxWidth().heightIn(max = available).then(resize)) { content(this, ::close) }
        }
    }
}

@Composable
internal fun BotEditorSheetTitle(title: String, onClose: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
        IconButton(hapticAction(onClose), enabled = enabled) { Icon(painterResource(R.drawable.ic_close), "关闭", Modifier.size(24.dp)) }
    }
}
