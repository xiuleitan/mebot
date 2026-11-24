package com.txl.mebot.ui.common

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal interface AppHapticFeedback {
    fun tap()
    fun selection()
    fun success()
}

/** 相邻控件或组合控件同一瞬间只反馈一次，不拦截或消费触摸事件。 */
internal class HapticGate(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var last: Long? = null
    fun allow(): Boolean {
        val time = now()
        if (last?.let { time >= it && time - it < 50L } == true) return false
        last = time
        return true
    }
}

private data class HapticEnvironment(val enabled: Boolean = false, val gate: HapticGate = HapticGate())
private val LocalHapticEnvironment = staticCompositionLocalOf { HapticEnvironment() }
internal val LocalAppHapticOverride = staticCompositionLocalOf<AppHapticFeedback?> { null }

@Composable
internal fun AppHapticProvider(enabled: Boolean, content: @Composable () -> Unit) {
    val gate = remember { HapticGate() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = enabled && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val active by rememberUpdatedState(foreground)
    val original = LocalHapticFeedback.current
    // 系统文字选择、长按、Material 控件的内建反馈也遵守 App 总开关。
    val composeFeedback = remember(original, gate) { object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            if (active && gate.allow()) original.performHapticFeedback(hapticFeedbackType)
        }
    } }
    CompositionLocalProvider(LocalHapticEnvironment provides HapticEnvironment(foreground, gate),
        LocalHapticFeedback provides composeFeedback, content = content)
}

@Composable
internal fun rememberAppHaptics(): AppHapticFeedback {
    LocalAppHapticOverride.current?.let { return it }
    val view = LocalView.current
    val environment by rememberUpdatedState(LocalHapticEnvironment.current)
    return remember(view) { object : AppHapticFeedback {
        private fun perform(type: Int) {
            if (environment.enabled && view.isAttachedToWindow && view.isShown && environment.gate.allow()) {
                // 不使用忽略系统设置的标志，不申请 VIBRATE，也不播放自定义长振动。
                view.performHapticFeedback(type)
            }
        }
        override fun tap() = perform(HapticFeedbackConstants.CONTEXT_CLICK)
        override fun selection() = perform(HapticFeedbackConstants.CLOCK_TICK)
        override fun success() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    } }
}

/** UI 已收到事务成功事件后反馈一次；初始状态、重组和重开页面不重放历史事件。 */
@Composable
internal fun SuccessHaptics(event: Any?) {
    val haptics = rememberAppHaptics()
    var delivered by remember { mutableStateOf(event) }
    LaunchedEffect(event) {
        if (event != null && event != delivered) haptics.success()
        delivered = event
    }
}

@Composable
internal fun hapticAction(action: () -> Unit): () -> Unit {
    val haptics = rememberAppHaptics()
    val latest by rememberUpdatedState(action)
    return remember(haptics) { { haptics.tap(); latest() } }
}

@Composable
internal fun <T> hapticChange(action: (T) -> Unit): (T) -> Unit {
    val haptics = rememberAppHaptics()
    val latest by rememberUpdatedState(action)
    return remember(haptics) { { value -> haptics.tap(); latest(value) } }
}

/** 仅用户翻页且停稳后确实换了内容才反馈；初始化、程序翻页和取消手势均不反馈。 */
@Composable
internal fun PagerSelectionHaptics(pager: PagerState, keyAtPage: (Int) -> Any? = { it }) {
    val haptics = rememberAppHaptics()
    val key by rememberUpdatedState(keyAtPage)
    LaunchedEffect(pager, haptics) {
        var start: Pair<DragInteraction.Start, Any?>? = null
        var settling: Job? = null
        pager.interactionSource.interactions.collect { interaction -> when (interaction) {
            is DragInteraction.Start -> { settling?.cancel(); start = interaction to key(pager.currentPage) }
            is DragInteraction.Cancel -> if (start?.first == interaction.start) { start = null; settling?.cancel() }
            is DragInteraction.Stop -> {
                val previous = start?.takeIf { it.first == interaction.start }?.second
                val dragged = start?.first == interaction.start
                start = null
                if (dragged) settling = launch {
                    snapshotFlow { pager.isScrollInProgress }.first { !it }
                    if (key(pager.settledPage) != previous) haptics.selection()
                }
            }
        } }
    }
}
