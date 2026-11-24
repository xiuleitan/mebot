package com.txl.mebot.ui.common

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaCyclePhase
import com.txl.mebot.data.dayima.DayimaPhaseEstimate
import com.txl.mebot.data.dayima.DayimaPhaseEstimateStatus
import com.txl.mebot.data.dayima.DayimaPhaseRange
import com.txl.mebot.ui.archive.ArchiveSheet
import com.txl.mebot.ui.dayima.DayimaPhaseDetailsSheet
import com.txl.mebot.ui.dayima.label
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.ScheduleSheet
import com.txl.mebot.ui.subscriptions.SubscriptionSheet
import com.txl.mebot.ui.subscriptions.SubscriptionSheetTitle
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class BotSheetDismissalTest {
    private enum class Kind { SCHEDULE, SUBSCRIPTIONS, ARCHIVE, PHASE }

    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun resetFrames() { ShadowChoreographer.setPaused(false) }

    @Test fun draggingHeadersAndContentEdgesCannotMoveOrDismissSheetsButContentStillScrolls() {
        for (kind in Kind.entries) withSheet(kind) { sheet ->
            val top = sheet.title().boundsInWindow.top
            val header = sheet.title().boundsInWindow
            sheet.swipe(header.center.x, header.center.y, 120f * sheet.density) {
                assertEquals("$kind header cannot move during a drag", top, sheet.title().boundsInWindow.top, 1f)
            }
            frames(600)
            assertEquals("$kind header drag cannot dismiss", 0, sheet.dismissals)
            assertEquals("$kind header must stay fixed", top, sheet.title().boundsInWindow.top, 1f)
            val scroll = sheet.scrollNode()
            val body = scroll.boundsInWindow
            sheet.swipe(body.center.x, body.center.y, 120f * sheet.density) {
                assertEquals("$kind top-edge drag cannot move the sheet", top, sheet.title().boundsInWindow.top, 1f)
            }
            frames(600)
            assertEquals("$kind top-edge overscroll cannot dismiss", 0, sheet.dismissals)
            assertEquals("$kind content drag cannot move the sheet", top, sheet.title().boundsInWindow.top, 1f)
            if (kind != Kind.PHASE) {
                sheet.swipe(body.center.x, body.center.y, -140f * sheet.density)
                frames(400)
                val range = requireNotNull(sheet.scrollNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                assertTrue("$kind body scrolling remains enabled", range.value() > 0f)
            }
        }
    }

    @Test fun backScrimAndTopRightCloseStillDismissEachSheet() {
        for (kind in Kind.entries) for (exit in listOf("back", "scrim", "close")) withSheet(kind) { sheet ->
            when (exit) {
                "back" -> (sheet.dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                "scrim" -> {
                    val top = sheet.title().boundsInWindow.top
                    sheet.tap(sheet.decor.width / 2f, (top - 32f * sheet.density).coerceAtLeast(1f))
                }
                else -> {
                    val close = requireNotNull(nodes(sheet.decor).firstOrNull {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(sheet.closeLabel) == true &&
                            it.config.getOrNull(SemanticsActions.OnClick) != null
                    }) { "$kind needs a close action" }.boundsInWindow
                    sheet.tap(close.center.x, close.center.y)
                }
            }
            frames(1200)
            assertEquals("$kind must support $exit", 1, sheet.dismissals)
            assertFalse(sheet.dialog.isShowing)
        }
    }

    private fun withSheet(kind: Kind, block: (Fixture) -> Unit) {
        // Robolectric 会重建 Choreographer；为本组窗口使用当前时钟，避免缓存的 UI 调度器串扰其他测试。
        WindowRecomposerPolicy.withFactory({ view ->
            view.createLifecycleAwareWindowRecomposer(
                Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance()),
            )
        }) {
            val sheet = Fixture(kind)
            try { block(sheet) } finally { sheet.destroy() }
        }
    }

    private inner class Fixture(kind: Kind) {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val shown = mutableStateOf(true)
        val density get() = decor.resources.displayMetrics.density
        val titleLabel = if (kind == Kind.PHASE) controller.get().getString(DayimaCyclePhase.MENSTRUAL.label()) else "手势测试"
        val closeLabel = if (kind == Kind.SUBSCRIPTIONS || kind == Kind.PHASE) "关闭" else controller.get().getString(R.string.close_panel)
        var dismissals = 0
        val dialog: android.app.Dialog
        val decor get() = requireNotNull(dialog.window).decorView

        init {
            controller.get().setContent {
                MebotTheme(AppTheme.BasicDark) {
                    val dismiss = { dismissals++; shown.value = false }
                    when (kind) {
                        Kind.SCHEDULE -> if (shown.value) ScheduleSheet(
                            ScheduleEntry("drag-test", titleLabel, LocalDate.of(2026, 10, 29), detail = "正文内容\n".repeat(100)),
                            width = 393.dp, height = 600.dp, onDismiss = dismiss, onToggleCompleted = {},
                        )
                        Kind.SUBSCRIPTIONS -> if (shown.value) SubscriptionSheet(dismiss) { close ->
                            SubscriptionSheetTitle(titleLabel, { close(null) })
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                repeat(100) { Text("正文内容 $it") }
                            }
                        }
                        Kind.ARCHIVE -> if (shown.value) ArchiveSheet(titleLabel, dismiss) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                repeat(100) { Text("正文内容 $it") }
                            }
                        }
                        Kind.PHASE -> DayimaPhaseDetailsSheet(
                            DayimaCyclePhase.MENSTRUAL.takeIf { shown.value },
                            DayimaPhaseEstimate(LocalDate.of(2026, 10, 29), null, 1,
                                DayimaPhaseEstimateStatus.START_ONLY, DayimaCyclePhase.MENSTRUAL,
                                DayimaCyclePhase.entries.map { DayimaPhaseRange(it, null) }), dismiss,
                        )
                    }
                }
            }
            frames(700)
            dialog = requireNotNull(ShadowDialog.getLatestDialog())
        }

        fun title(): SemanticsNode = requireNotNull(nodes(decor).firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == titleLabel } == true
        }) { "Missing $titleLabel" }
        fun scrollNode(): SemanticsNode = requireNotNull(nodes(decor).firstOrNull {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        })
        fun tap(x: Float, y: Float) {
            val start = SystemClock.uptimeMillis()
            for ((time, action) in listOf(start to MotionEvent.ACTION_DOWN, start + 1 to MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, time, action, x, y, 0)
                try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        fun swipe(x: Float, y: Float, distance: Float, onFrame: () -> Unit = {}) {
            val start = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, start + step * 16L, action, x, y + distance * step / 12f, 0)
                try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
                frames(16)
                if (step in 1..11) onFrame()
            }
        }
        fun destroy() {
            // 先让弹层动画及异步配图的 Composition 完整退出，避免影响下一组窗口测试。
            shown.value = false
            frames(1200)
            dialog.dismiss(); controller.pause().stop().destroy(); frames(120)
        }
    }

    private fun frames(milliseconds: Long) {
        repeat(((milliseconds + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { decor ->
                views(decor).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
                    .forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) }
            }
        }
    }
    private fun nodes(decor: View): List<SemanticsNode> = views(decor)
        .filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
        .flatMap {
            it.javaClass.getMethod("measureAndLayoutForTest").invoke(it)
            descendants((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)
        }.toList()
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }
}
