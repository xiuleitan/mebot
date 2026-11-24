package com.txl.mebot.ui.subscriptions

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
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
class SubscriptionImagePreviewTest {
    @Before fun pauseFrames() { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
    @After fun resumeFrames() { ShadowChoreographer.setPaused(false) }

    @Test fun horizontalPagingTargetsCurrentImageAndActionsStayAtOppositeBottomCorners() {
        for (theme in listOf(AppTheme.BasicDark, AppTheme.BasicLight)) withGallery(theme) { gallery ->
            val left = gallery.tag("subscription_image_delete"); val right = gallery.tag("subscription_image_set_cover")
            assertTrue(left.boundsInWindow.right < right.boundsInWindow.left)
            assertTrue(left.boundsInWindow.top > gallery.canvas.height * 0.75f)
            assertEquals(left.boundsInWindow.top, right.boundsInWindow.top, 1f)
            assertTrue(right.config.contains(SemanticsProperties.Disabled))
            gallery.swipe(-gallery.canvas.width * 0.8f)
            assertEquals("2 / 3", gallery.position())
            gallery.click(gallery.tag("subscription_image_set_cover")); gallery.frames(200)
            assertEquals(listOf(gallery.original[1]), gallery.covers)
            assertTrue(gallery.tag("subscription_image_set_cover").config.contains(SemanticsProperties.Disabled))
            assertEquals("2 / 3", gallery.position())
            gallery.snapshot("subscription-gallery-${theme.id}")
            gallery.swipe(gallery.canvas.width * 0.8f)
            assertEquals("1 / 3", gallery.position())
        }
    }

    @Test fun deleteRequiresConfirmationKeepsAdjacentPageAndLastRemovalClosesPreview() = withGallery { gallery ->
        gallery.swipe(-gallery.canvas.width * 0.8f); assertEquals("2 / 3", gallery.position())
        gallery.click(gallery.tag("subscription_image_delete")); gallery.frames(300)
        assertTrue(gallery.deletions.isEmpty())
        gallery.click(gallery.text("取消", ShadowDialog.getLatestDialog().window!!.decorView)); gallery.frames(300)
        assertTrue(gallery.deletions.isEmpty()); assertEquals("2 / 3", gallery.position())
        repeat(3) {
            val before = gallery.images.value
            gallery.click(gallery.tag("subscription_image_delete")); gallery.frames(300)
            gallery.click(gallery.text("确认", ShadowDialog.getLatestDialog().window!!.decorView)); gallery.frames(400)
            assertEquals(before.size - 1, gallery.images.value.size)
        }
        assertEquals(gallery.original.toSet(), gallery.deletions.toSet())
        assertEquals(1, gallery.dismissals)
    }

    @Test fun savingDisablesActionsAndFailureKeepsCurrentPhotoAvailableForRetry() = withGallery { gallery ->
        gallery.swipe(-gallery.canvas.width * 0.8f)
        gallery.busy.value = true; gallery.frames(200)
        for (tag in listOf("subscription_image_delete", "subscription_image_set_cover")) assertTrue(gallery.tag(tag).config.contains(SemanticsProperties.Disabled))
        assertEquals(0, gallery.dismissals)
        gallery.busy.value = false; gallery.error.value = "图片修改失败，请重试"; gallery.frames(200)
        assertEquals("2 / 3", gallery.position()); assertEquals(gallery.original, gallery.images.value)
        assertNotNull(gallery.text("图片修改失败，请重试"))
        assertFalse(gallery.tag("subscription_image_delete").config.contains(SemanticsProperties.Disabled))
    }

    @Test fun detailGalleryShowsPartOfNextPhotoAndSwipesThroughImagesInBothThemes() {
        for (theme in listOf(AppTheme.BasicDark, AppTheme.BasicLight)) withGallery(theme, inline = true) { gallery ->
            assertEquals("1 / 3", gallery.detailPosition())
            val viewport = gallery.tag("subscription_detail_gallery").boundsInWindow
            val first = gallery.tag("subscription_detail_image_${gallery.original.first()}").boundsInWindow
            val next = gallery.tag("subscription_detail_image_${gallery.original[1]}").boundsInWindow
            val peek = minOf(viewport.right, next.right) - maxOf(viewport.left, next.left)
            assertTrue("Next picture must visibly peek into the viewport", peek > 24f)
            assertTrue("Next picture must only be partly visible", peek < first.width / 3)
            gallery.snapshot("subscription-detail-gallery-${theme.id}")
            gallery.swipe(-viewport.width * 0.8f)
            assertEquals("2 / 3", gallery.detailPosition())
            gallery.swipe(viewport.width * 0.8f)
            assertEquals("1 / 3", gallery.detailPosition())
        }
    }

    @Test fun tappingSwipedDetailPhotoOpensThatExactPhotoInFullscreen() = withGallery(inline = true) { gallery ->
        gallery.swipe(-gallery.tag("subscription_detail_gallery").boundsInWindow.width * 0.8f)
        assertEquals("2 / 3", gallery.detailPosition())
        gallery.click(gallery.tag("subscription_detail_image_${gallery.original[1]}")); gallery.frames(400)
        val fullscreen = ShadowDialog.getLatestDialog().window!!.decorView
        assertEquals("2 / 3", gallery.tag("subscription_image_position", fullscreen).config[SemanticsProperties.Text].single().text)
        gallery.click(gallery.tag("subscription_image_set_cover", fullscreen)); gallery.frames(400)
        assertEquals(listOf(gallery.original[1]), gallery.covers)
    }

    @Test fun singleImageUsesFullWidthAndGallerySurvivesImageRemoval() = withGallery(inline = true) { gallery ->
        gallery.swipe(-gallery.tag("subscription_detail_gallery").boundsInWindow.width * 0.8f)
        gallery.images.value = listOf(gallery.original.first()); gallery.frames(400)
        assertFalse(gallery.hasTag("subscription_detail_image_position"))
        gallery.click(gallery.tag("subscription_detail_gallery")); gallery.frames(400)
        val fullscreen = ShadowDialog.getLatestDialog().window!!.decorView
        assertEquals("1 / 1", gallery.tag("subscription_image_position", fullscreen).config[SemanticsProperties.Text].single().text)
        gallery.click(gallery.tag("subscription_image_delete", fullscreen)); gallery.frames(300)
        gallery.click(gallery.text("确认", ShadowDialog.getLatestDialog().window!!.decorView)); gallery.frames(400)
        assertEquals(1, gallery.dismissals); assertTrue(gallery.images.value.isEmpty())
        assertFalse(gallery.hasTag("subscription_detail_gallery"))
    }

    private fun withGallery(theme: AppTheme = AppTheme.BasicDark, inline: Boolean = false, block: (Gallery) -> Unit) {
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance())) }) {
            val gallery = Gallery(theme, inline)
            try { block(gallery) } finally { gallery.destroy() }
        }
    }

    private class Gallery(theme: AppTheme, private val inline: Boolean) {
        private val app = ApplicationProvider.getApplicationContext<Application>()
        private val container: AppContainer
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val original: List<String>
        val images = mutableStateOf(emptyList<String>())
        private val cover = mutableStateOf<String?>(null)
        val busy = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        private val showing = mutableStateOf(true)
        private val openedImage = mutableStateOf<String?>(null)
        val covers = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        var dismissals = 0
        val canvas: View
        init {
            app.deleteDatabase(MebotDatabase.FILE_NAME); container = AppContainer(app)
            original = runBlocking { listOf(Color.rgb(40, 90, 130), Color.rgb(130, 70, 70), Color.rgb(45, 115, 90)).map { color ->
                val bytes = ByteArrayOutputStream().use { output -> Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
                }; output.toByteArray() }
                container.media.importImage({ ByteArrayInputStream(bytes) }).id
            } }
            images.value = original; cover.value = original.first()
            val model = ViewModelProvider(controller.get(), object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SubscriptionsViewModel(app, SavedStateHandle(), container) as T
            })[SubscriptionsViewModel::class.java]
            controller.get().setContent { MebotTheme(theme) {
                if (inline) Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 40.dp)) {
                        SubscriptionDetailGallery(images.value, cover.value, model, !busy.value, { openedImage.value = it })
                    }
                }
                if (if (inline) openedImage.value != null else showing.value) SubscriptionImagePreview(openedImage.value ?: original.first(), images.value, model,
                    coverAssetId = cover.value, busy = busy.value, enabled = !busy.value, error = error.value,
                    onSetCover = { covers += it; cover.value = it }, onRemove = { deletions += it; images.value -= it },
                    onDismiss = { showing.value = false; openedImage.value = null; dismissals++ })
            } }
            frames(700)
            canvas = if (inline) controller.get().window.decorView else ShadowDialog.getLatestDialog().window!!.decorView
        }
        fun frames(ms: Long) { repeat(((ms + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            measure(controller.get().window.decorView)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::measure)
            Thread.sleep(2)
        } }
        fun tag(value: String, view: View = canvas) = nodes(view).first { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun hasTag(value: String) = nodes(canvas).any { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun text(value: String, view: View = canvas) = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun position() = tag("subscription_image_position").config[SemanticsProperties.Text].single().text
        fun detailPosition() = tag("subscription_detail_image_position").config[SemanticsProperties.Text].single().text
        fun click(node: SemanticsNode) { assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()) }
        fun swipe(distance: Float) {
            val start = SystemClock.uptimeMillis()
            val bounds = if (inline) tag("subscription_detail_gallery").boundsInWindow else tag("subscription_image_gallery").boundsInWindow
            val x = bounds.left + bounds.width * if (distance < 0) 0.9f else 0.1f
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(start, start + step * 16L, action, x + distance * step / 12f, bounds.center.y, 0)
                try { canvas.dispatchTouchEvent(event) } finally { event.recycle() }; frames(16)
            }; frames(500)
        }
        fun snapshot(name: String) {
            val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888); canvas.draw(Canvas(bitmap))
            val file = File("build/reports/subscription-preview/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        fun destroy() {
            showing.value = false; openedImage.value = null; frames(100); controller.pause().stop().destroy(); frames(100)
            container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
            File(app.filesDir, "media").deleteRecursively()
        }
    }
    companion object {
        private fun views(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i))) }
        private fun measure(view: View) { views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }
            .forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) } }
        private fun nodes(view: View): List<SemanticsNode> = views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.flatMap {
            it.javaClass.getMethod("measureAndLayoutForTest").invoke(it)
            descendants((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode)
        }.toList()
        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
    }
}
