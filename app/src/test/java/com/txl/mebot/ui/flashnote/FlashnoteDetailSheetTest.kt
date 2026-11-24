package com.txl.mebot.ui.flashnote

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.R
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.time.Duration
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlashnoteDetailSheetTest {
    @Before fun pauseVsync() {
        // 手动推进帧，避免加载指示器的无限动画让测试时钟自行快进。
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }
    @After fun resetVsync() { ShadowChoreographer.setPaused(false) }

    @Test fun lateAttachmentLoadingKeepsAllActionsInsideTheDialogWindow() {
        for (delay in listOf(16L, 64L, 208L, 224L, 400L)) checkLateContent(delay, cachedEntry = true)
    }

    @Test fun initialDatabaseLoadingKeepsAllActionsInsideTheDialogWindow() {
        for (delay in listOf(16L, 64L, 208L, 224L, 400L)) checkLateContent(delay, cachedEntry = false)
    }

    @Test fun editingAndAllExitMethodsPreserveModalBehavior() {
        for (exit in listOf("close", "scrim", "back", "deleted")) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val activity = controller.get()
            val shown = mutableStateOf(true)
            val deleted = mutableStateOf(false)
            val editor = mutableStateOf<FlashnoteEditorState?>(null)
            val record = FlashnoteEntry("note", "记得做一下", "原始内容", 0)
            var dismissed = 0
            try {
                activity.setContent {
                    MebotTheme(AppTheme.BasicLight) {
                        if (shown.value) FlashnoteDetailSheet(
                            entry = record, loading = false, failed = false, editor = editor.value,
                            busy = false, deleted = deleted.value, failure = null, onRetry = {},
                            onDismiss = { dismissed++; shown.value = false }, onToggleCompleted = {},
                            onEdit = { editor.value = FlashnoteEditorState(record.id, record.title,
                                "长编辑草稿\n".repeat(80), loading = false) }, onDelete = { deleted.value = true },
                            onRetryAction = {}, onEditTitle = {}, onEditContent = {}, onSaveEdit = {},
                            onCancelEdit = { editor.value = null }, onRetryEditLoad = {}, onRetryEditInput = {},
                            attachments = emptyList(), onRemoveAttachment = {}, onOpenFile = { null },
                        )
                    }
                }
                frames(500)
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                val decor = requireNotNull(dialog.window).decorView
                val body = textNode(decor, record.detail).boundsInWindow
                tap(decor, body.center.x, body.center.y)
                swipeDown(decor, body.center.x, body.center.y)
                frames(300)
                assertEquals("Body taps and scrolling must not dismiss", 0, dismissed)
                tapText(decor, activity.getString(R.string.flashnote_edit))
                frames(600)
                assertActionsVisible(decor, "editor", listOf(activity.getString(R.string.flashnote_cancel),
                    activity.getString(R.string.flashnote_save_edit)))
                tapText(decor, activity.getString(R.string.flashnote_cancel))
                frames(600)
                assertActionsVisible(decor, "after cancel")
                when (exit) {
                    "back" -> (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                    "deleted" -> {
                        tapText(decor, activity.getString(R.string.flashnote_delete))
                        frames(300)
                        val confirmation = requireNotNull(ShadowDialog.getLatestDialog())
                        assertNotSame(dialog, confirmation)
                        tapText(requireNotNull(confirmation.window).decorView, activity.getString(R.string.detail_delete_confirm))
                    }
                    "scrim" -> {
                        val title = textNode(decor, record.title).boundsInWindow
                        tap(decor, decor.width / 2f, title.top - 24 * decor.resources.displayMetrics.density)
                    }
                    else -> {
                        val button = nodes(decor).first { node ->
                            node.config.getOrNull(SemanticsProperties.ContentDescription)
                                ?.contains(activity.getString(R.string.flashnote_close_detail)) == true &&
                                node.config.getOrNull(SemanticsActions.OnClick) != null && node.boundsInWindow.width < decor.width / 2
                        }.boundsInWindow
                        tap(decor, button.center.x, button.center.y)
                        tap(decor, button.center.x, button.center.y)
                    }
                }
                frames(500)
                assertEquals("$exit must dismiss exactly once", 1, dismissed)
                assertFalse(dialog.isShowing)
            } finally {
                ShadowDialog.getLatestDialog()?.dismiss()
                controller.pause().stop().destroy()
                frames(32)
            }
        }
    }

    @Test fun deleteRequiresConfirmationAndCancelOrBusyStateCannotDelete() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val shown = mutableStateOf(true)
        val deleted = mutableStateOf(false)
        val busy = mutableStateOf(false)
        val record = FlashnoteEntry("confirmation-note", "只删除确认的记录", "保留正文", 0)
        var deleteCalls = 0
        try {
            activity.setContent {
                MebotTheme(AppTheme.BasicDark) {
                    if (shown.value) FlashnoteDetailSheet(
                        entry = record, loading = false, failed = false, editor = null,
                        busy = busy.value, deleted = deleted.value, failure = null, onRetry = {},
                        onDismiss = { shown.value = false }, onToggleCompleted = {}, onEdit = {},
                        onDelete = { assertEquals(record.id, it.id); deleteCalls++; busy.value = true },
                        onRetryAction = {}, onEditTitle = {}, onEditContent = {}, onSaveEdit = {},
                        onCancelEdit = {}, onRetryEditLoad = {}, onRetryEditInput = {},
                        attachments = emptyList(), onRemoveAttachment = {}, onOpenFile = { null },
                    )
                }
            }
            frames(500)
            val detail = requireNotNull(ShadowDialog.getLatestDialog())
            val decor = requireNotNull(detail.window).decorView
            assertActionsVisible(decor, "before confirmation")
            for (exit in listOf("cancel", "back")) {
                tapText(decor, activity.getString(R.string.flashnote_delete))
                frames(300)
                val confirmation = requireNotNull(ShadowDialog.getLatestDialog())
                assertNotSame(detail, confirmation)
                assertEquals("Opening confirmation must not delete", 0, deleteCalls)
                val confirmationDecor = requireNotNull(confirmation.window).decorView
                assertActionsVisible(confirmationDecor, "confirmation", listOf(
                    activity.getString(R.string.detail_delete_cancel), activity.getString(R.string.detail_delete_confirm)))
                if (exit == "cancel") tapText(confirmationDecor, activity.getString(R.string.detail_delete_cancel))
                else (confirmation as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                frames(300)
                assertEquals("$exit must keep the record", 0, deleteCalls)
                assertTrue("Cancel must keep the detail open", detail.isShowing)
                assertFalse(confirmation.isShowing)
            }
            tapText(decor, activity.getString(R.string.flashnote_delete))
            frames(300)
            val confirmation = requireNotNull(ShadowDialog.getLatestDialog())
            val confirmationDecor = requireNotNull(confirmation.window).decorView
            busy.value = true
            frames(64)
            tapText(confirmationDecor, activity.getString(R.string.detail_delete_confirm))
            frames(64)
            assertEquals("Busy confirmation must not execute", 0, deleteCalls)
            busy.value = false
            frames(64)
            tapText(confirmationDecor, activity.getString(R.string.detail_delete_confirm))
            tapText(confirmationDecor, activity.getString(R.string.detail_delete_confirm))
            frames(300)
            assertEquals("Confirm must execute once", 1, deleteCalls)
            deleted.value = true
            frames(500)
            assertFalse("Successful deletion must close the detail", detail.isShowing)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
            frames(32)
        }
    }

    private fun checkLateContent(delay: Long, cachedEntry: Boolean) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val record = FlashnoteEntry("note", "记得做一下", "记得做一下", 0)
        val entry = mutableStateOf(record.takeIf { cachedEntry })
        val attachments = mutableStateOf<List<FlashnoteAttachment>>(emptyList())
        try {
            controller.get().setContent {
                MebotTheme(AppTheme.BasicDark) {
                    FlashnoteDetailSheet(
                        entry = entry.value,
                        loading = entry.value == null, failed = false, editor = null, busy = false, deleted = false, failure = null,
                        onRetry = {}, onDismiss = {}, onToggleCompleted = {}, onEdit = {}, onDelete = {},
                        onRetryAction = {}, onEditTitle = {}, onEditContent = {}, onSaveEdit = {}, onCancelEdit = {},
                        onRetryEditLoad = {}, onRetryEditInput = {}, attachments = attachments.value,
                        onRemoveAttachment = {}, onOpenFile = { null },
                    )
                }
            }
            var dialog: android.app.Dialog? = null
            for (frame in 0 until 60) {
                frames(16)
                dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                if (dialog != null) break
            }
            val shownDialog = requireNotNull(dialog) { "Dialog was not composed" }
            frames(delay)
            val decor = requireNotNull(shownDialog.window).decorView
            entry.value = record
            attachments.value = listOf(FlashnoteAttachment(
                MediaAssetEntity("photo", MediaSource.IMPORTED, relativePath = "media/missing-test-photo.png",
                    originalName = "photo.png", mimeType = "image/png", byteCount = 1, width = 1080, height = 2400, createdAt = 0),
                "photo.png", 0))
            frames(800)
            assertActionsVisible(decor, "delay=$delay, cached=$cachedEntry")
        } finally {
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

    private fun nodes(decor: View): List<SemanticsNode> =
        views(decor).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            .flatMap { view ->
                // Robolectric 不一定随窗口重绘完成 Compose 的待测量节点，查询前同步布局。
                view.javaClass.getMethod("measureAndLayoutForTest").invoke(view)
                val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
                descendants(owner.rootSemanticsNode)
            }.toList()

    private fun textNode(decor: View, label: String): SemanticsNode =
        requireNotNull(nodes(decor).firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }) { "Missing $label" }

    private fun assertActionsVisible(decor: View, scenario: String, labels: List<String> = listOf("删除", "编辑", "完成")) {
        val nodes = nodes(decor)
        var previousLeft = Float.NEGATIVE_INFINITY
        for (label in labels) {
            val action = requireNotNull(nodes.firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null
            }) { "$scenario: missing $label; window=${decor.width}x${decor.height}; " +
                "texts=${nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }}" }
            val bounds = action.boundsInWindow
            assertTrue("$scenario: $label must follow the previous action", bounds.left > previousLeft)
            previousLeft = bounds.left
            assertTrue("$scenario: $label is clipped: $bounds in ${decor.width}x${decor.height}; " +
                "nodes=${nodes.map { it.config.getOrNull(SemanticsProperties.Text).toString() + "@" + it.boundsInWindow }}",
                bounds.width > 0 && bounds.height >= 48 * decor.resources.displayMetrics.density - 1 &&
                    bounds.top >= 0 && bounds.bottom <= decor.height)
        }
    }

    private fun tapText(decor: View, label: String) {
        val bounds = textNode(decor, label).boundsInWindow
        tap(decor, bounds.center.x, bounds.center.y)
    }

    private fun tap(decor: View, x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        for ((time, action) in listOf(start to MotionEvent.ACTION_DOWN, start + 1 to MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, time, action, x, y, 0)
            try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun swipeDown(decor: View, x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(start, start + index * 100L, action, x, y + index * 45f, 0)
            try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }
}
