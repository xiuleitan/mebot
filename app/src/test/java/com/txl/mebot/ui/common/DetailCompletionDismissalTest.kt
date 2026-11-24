package com.txl.mebot.ui.common

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.ui.flashnote.FlashnoteActionFailure
import com.txl.mebot.ui.flashnote.FlashnoteDetailSheet
import com.txl.mebot.ui.flashnote.FlashnoteEntry
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.ScheduleSheet
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.time.Duration
import java.time.LocalDate
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
class DetailCompletionDismissalTest {
    private enum class Kind { FLASHNOTE, SCHEDULE }

    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun resetFrames() { ShadowChoreographer.setPaused(false) }

    @Test fun completionAndRestorationCloseOnlyAfterTheWriteHasFinished() {
        for (kind in Kind.entries) for (initiallyCompleted in listOf(false, true)) withSheet(kind, initiallyCompleted) { sheet ->
            sheet.tapToggle(); frames(80)
            assertEquals(1, sheet.requests)
            assertEquals("Pending writes keep the detail visible", 0, sheet.dismissals)
            assertEquals("Pending writes do not confirm success", 0, sheet.confirmations)
            sheet.completed.value = !initiallyCompleted
            frames(80)
            assertEquals("A committed value must also wait for busy state to finish", 0, sheet.dismissals)
            assertEquals(0, sheet.confirmations)
            sheet.busy.value = false
            frames(1200)
            assertEquals("Successful changes close once", 1, sheet.dismissals)
            assertEquals("A successful completion or restoration confirms once", 1, sheet.confirmations)
            frames(300)
            assertEquals(1, sheet.dismissals)
            assertEquals(1, sheet.confirmations)
        }
    }

    @Test fun failedWritesKeepTheDetailAndSuccessfulRetryClosesIt() {
        for (kind in Kind.entries) withSheet(kind, false) { sheet ->
            sheet.tapToggle(); frames(80)
            sheet.failed.value = true; sheet.busy.value = false
            frames(400)
            assertEquals("Failure must keep the detail and retry controls", 0, sheet.dismissals)
            assertEquals("Failed writes cannot confirm success", 0, sheet.confirmations)
            sheet.tapToggle(); frames(80)
            assertEquals(2, sheet.requests)
            sheet.completed.value = true; sheet.busy.value = false
            frames(1200)
            assertEquals(1, sheet.dismissals)
            assertEquals(1, sheet.confirmations)
        }
    }

    @Test fun externalUpdatesAndChangingRecordCannotCloseAnotherDetail() {
        for (kind in Kind.entries) withSheet(kind, false) { sheet ->
            sheet.completed.value = true
            frames(400)
            assertEquals("External status changes do not dismiss", 0, sheet.dismissals)
            assertEquals("Merely observing an external update is silent", 0, sheet.confirmations)
            sheet.tapToggle(); frames(80)
            sheet.recordId.value = "another-record"
            sheet.completed.value = false; sheet.busy.value = false
            frames(1200)
            assertEquals("The previous record request cannot close a new selection", 0, sheet.dismissals)
            assertEquals(0, sheet.confirmations)
            sheet.tapToggle(); frames(80)
            sheet.completed.value = true; sheet.busy.value = false
            frames(1200)
            assertEquals(1, sheet.dismissals)
        }
    }

    private fun withSheet(kind: Kind, initiallyCompleted: Boolean, block: (Fixture) -> Unit) {
        val sheet = Fixture(kind, initiallyCompleted)
        try { block(sheet) } finally { sheet.destroy() }
    }

    private inner class Fixture(private val kind: Kind, initiallyCompleted: Boolean) {
        val completed = mutableStateOf(initiallyCompleted)
        val busy = mutableStateOf(false)
        val failed = mutableStateOf(false)
        val recordId = mutableStateOf("record")
        private val shown = mutableStateOf(true)
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var requests = 0
        var dismissals = 0
        var confirmations = 0
        private val haptics = object : AppHapticFeedback {
            override fun tap() = Unit
            override fun selection() = Unit
            override fun success() { confirmations++ }
        }

        init {
            controller.get().setContent {
                CompositionLocalProvider(LocalAppHapticOverride provides haptics) {
                MebotTheme(if (initiallyCompleted) AppTheme.BasicLight else AppTheme.BasicDark) {
                    if (shown.value) {
                        val dismiss = { dismissals++; shown.value = false }
                        val toggle = {
                            if (!busy.value) { requests++; busy.value = true; failed.value = false }
                        }
                        if (kind == Kind.FLASHNOTE) FlashnoteDetailSheet(
                            entry = FlashnoteEntry(recordId.value, "测试记录", "测试正文", 0, completed.value),
                            loading = false, failed = false, editor = null, busy = busy.value, deleted = false,
                            failure = if (failed.value) FlashnoteActionFailure(operation = null) else null,
                            onRetry = {}, onDismiss = dismiss, onToggleCompleted = { toggle() },
                            onEdit = {}, onDelete = {}, onRetryAction = {}, onEditTitle = {}, onEditContent = {},
                            onSaveEdit = {}, onCancelEdit = {}, onRetryEditLoad = {}, onRetryEditInput = {},
                            attachments = emptyList(), onRemoveAttachment = {}, onOpenFile = { null },
                        ) else ScheduleSheet(
                            entry = ScheduleEntry(recordId.value, "测试记录", LocalDate.of(2026, 10, 29),
                                detail = "测试正文", completed = completed.value),
                            width = 393.dp, height = 600.dp, onDismiss = dismiss, onToggleCompleted = { toggle() },
                            completionEnabled = !busy.value, completionFailed = failed.value,
                        )
                    }
                }
                }
            }
            frames(600)
        }

        fun tapToggle() {
            val label = controller.get().getString(when {
                !completed.value && kind == Kind.FLASHNOTE -> R.string.flashnote_complete
                !completed.value -> R.string.schedule_detail_complete
                kind == Kind.FLASHNOTE -> R.string.flashnote_restore
                else -> R.string.schedule_completed
            })
            val decor = requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
            val node = requireNotNull(nodes(decor).firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }) { "Missing action $label for $kind" }
            val bounds = node.boundsInWindow
            val start = SystemClock.uptimeMillis()
            for ((time, action) in listOf(start to MotionEvent.ACTION_DOWN, start + 1 to MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, time, action, bounds.center.x, bounds.center.y, 0)
                try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }

        fun destroy() {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
            frames(32)
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
