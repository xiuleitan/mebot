package com.txl.mebot.ui.common

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.txl.mebot.ui.settings.HapticSettingsRow
import com.txl.mebot.ui.settings.HapticSettingsState
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.time.Duration
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class AppHapticsTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java)
    private val decor get() = controller.get().window.decorView
    private val feedback = RecordingFeedback()
    @Before fun setup() {
        ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller.setup().visible()
    }
    @After fun cleanup() { controller.pause().stop().destroy(); frames(48); ShadowChoreographer.setPaused(false) }

    @Test fun aClickFeedsBackOnceButDisabledButtonsCanceledTouchesAndVerticalScrollingDoNot() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        show(fake = true) {
            Column(Modifier.height(260.dp).verticalScroll(rememberScrollState())) {
                Button(hapticAction { clicks++ }, enabled = enabled.value,
                    modifier = Modifier.fillMaxWidth().height(100.dp).testTag("action")) { Text("打开详情") }
                Spacer(Modifier.height(1200.dp))
            }
        }
        touch(tag("action"), 0f, 0f)
        assertEquals(1, clicks); assertEquals(1, feedback.taps)
        touch(tag("action"), 0f, 0f, cancel = true)
        assertEquals(1, clicks); assertEquals(1, feedback.taps)
        enabled.value = false; frames(64)
        touch(tag("action"), 0f, 0f)
        assertEquals(1, clicks); assertEquals(1, feedback.taps)
        enabled.value = true; frames(64)
        touch(tag("action"), 0f, -140f)
        assertEquals(1, clicks); assertEquals(1, feedback.taps); assertEquals(0, feedback.selections)
    }
    @Test fun pagingFeedsBackOnlyAfterAUserSwipeSettlesOnDifferentContent() {
        lateinit var pager: PagerState
        lateinit var move: () -> Unit
        show(fake = true) {
            pager = rememberPagerState { 3 }
            val scope = rememberCoroutineScope()
            move = { scope.launch { pager.scrollToPage(1) }; Unit }
            PagerSelectionHaptics(pager) { "photo-$it" }
            HorizontalPager(pager, Modifier.fillMaxWidth().height(180.dp).testTag("pager")) { page ->
                Surface(Modifier.fillMaxSize()) { Text("图片 $page") }
            }
        }
        assertEquals(0, feedback.selections)
        move(); frames(500)
        assertEquals(1, pager.settledPage); assertEquals(0, feedback.selections)
        touch(tag("pager"), -tag("pager").boundsInWindow.width * .75f, 0f)
        frames(1000)
        assertEquals(2, pager.settledPage); assertEquals(1, feedback.selections)
        touch(tag("pager"), 35f, 0f, cancel = true); frames(800)
        assertEquals(1, feedback.selections)
    }
    @Test fun changingTheAppSettingAndLeavingForegroundDisableActualPlatformFeedback() {
        val enabled = mutableStateOf(true)
        lateinit var haptics: AppHapticFeedback
        lateinit var view: View
        show {
            AppHapticProvider(enabled.value) {
                view = LocalView.current
                haptics = rememberAppHaptics()
                Text("测试")
            }
        }
        haptics.tap(); assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, shadowOf(view).lastHapticFeedbackPerformed())
        enabled.value = false; frames(80); haptics.selection()
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, shadowOf(view).lastHapticFeedbackPerformed())
        enabled.value = true; frames(80); haptics.selection()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
        controller.pause().stop(); frames(80); haptics.success()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
        controller.start().resume().visible(); frames(100); haptics.success()
        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed())
    }
    @Test fun settingsSwitchHasOneAccessibleTargetAndShowsFailedSaveWithoutChangingItsValue() {
        val state = mutableStateOf(HapticSettingsState(enabled = true, loaded = true))
        val requests = mutableListOf<Boolean>()
        show(fake = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                HapticSettingsRow(state.value, { requests += it }, {})
            }
        }
        val row = tag("haptic_feedback_setting")
        val image = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(image))
        java.io.File("build/reports/haptics").apply { mkdirs() }.resolve("settings-large-font.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        assertEquals(Role.Switch, row.config[SemanticsProperties.Role])
        assertEquals(1, nodes().count { it.config.getOrNull(SemanticsProperties.ToggleableState) != null })
        touch(row, 0f, 0f); assertEquals(listOf(false), requests)
        state.value = state.value.copy(error = "保存失败"); frames(80)
        assertEquals(androidx.compose.ui.state.ToggleableState.On, tag("haptic_feedback_setting").config[SemanticsProperties.ToggleableState])
        assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "保存失败" } == true })
    }
    private fun show(fake: Boolean = false, content: @Composable () -> Unit) {
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance())) }) {
            controller.get().setContent { MebotTheme(AppTheme.BasicDark) {
                CompositionLocalProvider(LocalAppHapticOverride provides if (fake) feedback else null) { content() }
            } }
            frames(400)
        }
    }
    private fun touch(node: SemanticsNode, dx: Float, dy: Float, cancel: Boolean = false) {
        val b = node.boundsInWindow
        val x = if (dx < 0) b.right - 24f else b.center.x
        val y = b.center.y
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, xx: Float, yy: Float, time: Long) {
            val event = MotionEvent.obtain(started, time, action, xx, yy, 0)
            try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, x, y, started)
        if (dx != 0f || dy != 0f) repeat(20) { i ->
            val progress = (i + 1) / 20f
            send(MotionEvent.ACTION_MOVE, x + dx * progress, y + dy * progress, started + (i + 1) * 16L); frames(16)
        }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x + dx, y + dy, SystemClock.uptimeMillis() + 1)
        frames(100)
    }
    private fun frames(ms: Long) { repeat(((ms + 15) / 16).toInt()) {
        Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        views(decor).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            .forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) }
    } }
    private fun tag(value: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private fun nodes(): List<SemanticsNode> = views(decor).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
        .flatMap { descendants((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode) }.toList()
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); node.children.forEach { yieldAll(descendants(it)) } }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private class RecordingFeedback : AppHapticFeedback {
        var taps = 0; var selections = 0
        override fun tap() { taps++ }
        override fun selection() { selections++ }
        override fun success() = Unit
    }
}
