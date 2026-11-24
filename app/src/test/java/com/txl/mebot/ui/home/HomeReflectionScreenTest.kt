package com.txl.mebot.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.io.File
import java.time.Duration
import java.time.LocalDate
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
class HomeReflectionScreenTest {
    @Before fun pause() { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
    @After fun resume() { ShadowChoreographer.setPaused(false) }
    @Test fun reflectionTextUsesItsContentHeightAndPrecedesHistoryInBothThemes() {
        for (theme in AppTheme.entries) for (scale in listOf(1f, 1.3f)) withScreen(theme, scale) { screen ->
            val task = screen.tag("home_card_today-task").boundsInWindow
            val reflection = screen.tag("home_reflection_card").boundsInWindow
            assertTrue(reflection.height < task.height)
            assertEquals(task.left, screen.tag("home_reflection_text").boundsInWindow.left, 1f)
            assertTrue(reflection.width > task.width * 2)
            assertTrue(reflection.top > task.bottom)
            val grid = screen.tag("home_timeline")
            grid.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 220f); screen.frames(500)
            assertTrue(screen.tag("home_reflection_card").boundsInWindow.bottom < screen.tag("home_day_-1").boundsInWindow.top)
            screen.snapshot("reflection-${theme.id}-$scale")
            val longText = (1..10).joinToString("\n") { "给自己的提醒 $it" }
            runBlocking { screen.container.homeReflection.write(longText, HomeReflectionMode.TEXT) }
            screen.frames(800)
            val text = screen.tag("home_reflection_text")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            assertEquals(10, layouts.single().lineCount)
            assertFalse(layouts.single().hasVisualOverflow)
            val lineHeight = layouts.single().getLineBaseline(1) - layouts.single().getLineBaseline(0)
            // Android 大字体使用非线性缩放；检验实际行间距而非假定固定倍率。
            assertTrue(lineHeight >= 30 * screen.density - 1)
            assertTrue(lineHeight <= 30 * screen.density * scale + 1)
            assertTrue(screen.tag("home_reflection_card").size.height > reflection.height)
            screen.snapshot("reflection-long-${theme.id}-$scale")
        }
    }
    @Test fun currentContentAndHistoryUseOneListWithCurrentFirstInBothThemesAndLargerFont() {
        for (theme in listOf(AppTheme.BasicDark, AppTheme.BasicLight)) withScreen(theme, 1.3f) { screen ->
            runBlocking { screen.container.homeReflection.addText("旧寄语"); screen.container.homeReflection.addText("当前寄语") }
            screen.frames(300); screen.openLibrary()
            val current = screen.model.state.value.history.single { it.content.text == "当前寄语" }.content
            val old = screen.model.state.value.history.single { it.content.text == "旧寄语" }.content
            assertTrue(screen.tag("home_reflection_history_${current.id}", screen.dialog()).boundsInWindow.top < screen.tag("home_reflection_history_${old.id}", screen.dialog()).boundsInWindow.top)
            assertEquals(1, screen.nodes(screen.dialog(), false).count { it.config.getOrNull(SemanticsProperties.TestTag) == "home_reflection_history_${current.id}" })
            assertNotNull(screen.text("添加文字", screen.dialog())); assertNotNull(screen.text("添加图片", screen.dialog()))
            assertFalse(screen.nodes(screen.dialog(), false).any { it.config.getOrNull(SemanticsProperties.TestTag) == "home_reflection_history_open" })
            assertFalse(screen.nodes(screen.dialog(), false).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "展示到首页" } == true })
            val remove = screen.tag("home_reflection_delete_${current.id}", screen.dialog()).boundsInWindow
            val edit = screen.tag("home_reflection_edit_${current.id}", screen.dialog()).boundsInWindow
            assertTrue(remove.left < edit.left); assertEquals(remove.top, edit.top, 1f)
            screen.snapshot("reflection-library-${theme.id}", screen.dialog())
            val card = screen.tag("home_reflection_history_${old.id}", screen.dialog()).boundsInWindow
            screen.tap(screen.dialog(), card.center.x, card.center.y)
            screen.await { !screen.model.state.value.editing }; screen.frames(400)
            assertEquals("旧寄语", runBlocking { screen.container.homeReflection.read().content.text })
            assertNotNull(screen.text("旧寄语"))
            // 当前条目也可点击返回首页，重开列表不会沿用上一次的关闭请求。
            screen.openLibrary()
            val currentCard = screen.tag("home_reflection_history_${old.id}", screen.dialog()).boundsInWindow
            screen.tap(screen.dialog(), currentCard.center.x, currentCard.center.y)
            screen.await { !screen.model.state.value.editing }
        }
    }
    @Test fun addingTextSavesOneNewRecordAndKeepsTheUnifiedPageOpen() = withScreen { screen ->
        screen.openLibrary(); screen.click(screen.tag("home_reflection_add_text", screen.dialog())); screen.frames(300)
        screen.tag("home_reflection_input", screen.dialog()).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("心有所向\n行有所止"))
        screen.click(screen.tag("home_reflection_save_text", screen.dialog()))
        screen.await { !screen.model.state.value.busy && screen.model.state.value.textEditor == null }; screen.frames(400)
        assertTrue(screen.model.state.value.editing)
        assertEquals("心有所向\n行有所止", runBlocking { screen.container.homeReflection.read().content.text })
        assertEquals(2, screen.model.state.value.history.size)
        screen.closeLibrary(); screen.await { !screen.model.state.value.editing }; screen.frames(400)
        assertNotNull(screen.text("心有所向\n行有所止"))
    }
    @Test fun historicalEditDoesNotSwitchHomeAndDeleteUsesConfirmation() = withScreen { screen ->
        runBlocking { screen.container.homeReflection.addText("历史寄语"); screen.container.homeReflection.addText("当前寄语") }
        screen.frames(300); screen.openLibrary()
        val old = screen.model.state.value.history.single { it.content.text == "历史寄语" }.content
        val editButton = screen.tag("home_reflection_edit_${old.id}", screen.dialog()).boundsInWindow
        screen.tap(screen.dialog(), editButton.center.x, editButton.center.y); screen.frames(300)
        screen.tag("home_reflection_input", screen.dialog()).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("改过的历史"))
        screen.click(screen.tag("home_reflection_save_text", screen.dialog()))
        screen.await { !screen.model.state.value.busy && screen.model.state.value.textEditor == null }; screen.frames(400)
        assertEquals("当前寄语", runBlocking { screen.container.homeReflection.read().content.text })
        assertTrue(screen.model.state.value.editing); assertFalse(screen.model.state.value.closeRequested)
        val changed = screen.model.state.value.history.single { it.content.text == "改过的历史" }.content
        val deleteButton = screen.tag("home_reflection_delete_${changed.id}", screen.dialog()).boundsInWindow
        screen.tap(screen.dialog(), deleteButton.center.x, deleteButton.center.y); screen.frames(300)
        assertTrue(runBlocking { screen.container.homeReflection.historyPage(10).any { it.content.id == changed.id } })
        screen.click(screen.text("取消", screen.dialog())); screen.frames(200)
        assertNull(screen.model.state.value.deletion)
        assertTrue(screen.model.state.value.editing); assertFalse(screen.model.state.value.closeRequested)
        screen.click(screen.tag("home_reflection_delete_${changed.id}", screen.dialog())); screen.frames(200)
        screen.click(screen.text("确认", screen.dialog()))
        screen.await { !screen.model.state.value.busy && screen.model.state.value.deletion == null }
        assertTrue(screen.model.state.value.history.none { it.content.id == changed.id })
        assertEquals("当前寄语", screen.model.state.value.record.content.text)
    }
    @Test fun failedSaveKeepsEditorAndInputUntilTheUserRetries() = withScreen { screen ->
        screen.openLibrary()
        val current = screen.model.state.value.history.single().content
        screen.click(screen.tag("home_reflection_edit_${current.id}", screen.dialog())); screen.frames(200)
        screen.container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_reflection_ui BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        screen.tag("home_reflection_input", screen.dialog()).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("输入不能丢失"))
        screen.click(screen.tag("home_reflection_save_text", screen.dialog()))
        screen.await { !screen.model.state.value.busy && screen.model.state.value.error != null }
        assertNotNull(screen.model.state.value.textEditor)
        assertEquals("输入不能丢失", screen.model.state.value.textEditor!!.text)
        assertEquals("守住初心，做好眼前的事", runBlocking { screen.container.homeReflection.read().content.text })
        screen.container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_reflection_ui")
        screen.frames(150); screen.click(screen.tag("home_reflection_save_text", screen.dialog()))
        screen.await { !screen.model.state.value.busy && screen.model.state.value.textEditor == null }
        assertEquals("输入不能丢失", runBlocking { screen.container.homeReflection.read().content.text })
    }
    @Test fun picturesCanBeAddedReplacedInHistoryAndDisplayedAgainOnTheSamePage() = withScreen { screen ->
        screen.openLibrary()
        fun picture(name: String, width: Int, height: Int, color: Int): Uri {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val file = File(app.cacheDir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            return Uri.fromFile(file)
        }
        fun picked(uri: Uri, original: HomeReflectionHistoryEntity? = null) {
            val request = requireNotNull(screen.model.preparePhoto(original)); val task = requireNotNull(screen.model.pickedPhoto(request.token, uri))
            screen.await { task.isCompleted }; screen.frames(500)
        }
        picked(picture("reflection-library-red.png", 300, 80, Color.RED))
        val red = screen.model.state.value.history.single { it.content.mode == HomeReflectionMode.IMAGE }.content
        picked(picture("reflection-library-blue.png", 300, 80, Color.BLUE))
        val currentImage = screen.model.state.value.record.content.imageAssetId
        picked(picture("reflection-library-green.png", 80, 600, Color.GREEN), red)
        assertEquals(currentImage, screen.model.state.value.record.content.imageAssetId)
        val edited = screen.model.state.value.history.single { it.content.mode == HomeReflectionMode.IMAGE && it.content.imageAssetId != currentImage }.content
        screen.tag("home_reflection_history_list", screen.dialog()).config[SemanticsActions.ScrollToIndex].action!!.invoke(1); screen.frames(500)
        val image = screen.tag("home_reflection_history_image_${edited.id}", screen.dialog())
        assertEquals(80f / 600f, image.size.width.toFloat() / image.size.height, .01f)
        assertTrue(image.boundsInWindow.bottom < screen.tag("home_reflection_edit_${edited.id}", screen.dialog()).boundsInWindow.top)
        screen.snapshot("reflection-library-images", screen.dialog())
        val card = screen.tag("home_reflection_history_${edited.id}", screen.dialog()).boundsInWindow
        screen.tap(screen.dialog(), card.center.x, card.center.y)
        screen.await { !screen.model.state.value.busy && screen.model.state.value.record.content.imageAssetId == edited.imageAssetId && !screen.model.state.value.editing }
        screen.frames(500)
        val actual = screen.tag("home_reflection_image").size
        assertEquals(screen.width.toFloat(), actual.width.toFloat(), 1f)
        assertTrue(actual.height <= screen.height / 2f + 1)
    }
    @Test fun homePictureFillsTheWidthAndPinchShowsTheWholeImageUntilReleased() = withScreen { screen ->
        val bitmap = Bitmap.createBitmap(120, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.GREEN)
        val paint = android.graphics.Paint()
        paint.color = Color.RED; canvas.drawRect(0f, 0f, 120f, 200f, paint)
        paint.color = Color.BLUE; canvas.drawRect(0f, 600f, 120f, 800f, paint)
        val file = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "reflection-pinch.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        runBlocking { screen.container.homeReflection.importImage(Uri.fromFile(file)) }
        screen.frames(700)
        screen.tag("home_timeline").config[SemanticsActions.ScrollToIndex].action!!.invoke(2); screen.frames(400)
        val image = screen.tag("home_reflection_image").boundsInWindow
        assertEquals(0f, image.left, 1f)
        assertEquals(screen.width.toFloat(), image.right, 1f)
        assertEquals(screen.height / 2f, image.height, 1f)
        val x = image.center.x; val y = image.top + 20 * screen.density
        assertEquals(Color.GREEN, screen.pixel(x, y))
        screen.snapshot("reflection-image-fill")
        screen.pinch(image) {
            assertFalse(screen.model.state.value.editing)
            assertEquals(Color.RED, screen.pixel(x, y))
            assertEquals(Color.BLUE, screen.pixel(x, image.bottom - 20 * screen.density))
            screen.snapshot("reflection-image-full-during-pinch")
        }
        assertFalse(screen.model.state.value.editing)
        assertEquals(Color.GREEN, screen.pixel(x, y))
        screen.snapshot("reflection-image-restored")
        // 双指结束后单指滚动仍正常，轻点图片才打开原内容页。
        val before = screen.tag("home_reflection_image").boundsInWindow.top
        screen.drag(screen.root, x, image.center.y, 50 * screen.density); screen.frames(300)
        assertTrue(screen.tag("home_reflection_image").boundsInWindow.top > before)
        assertFalse(screen.model.state.value.editing)
        val after = screen.tag("home_reflection_image").boundsInWindow
        screen.tap(screen.root, after.left + 8 * screen.density, after.center.y)
        screen.await { screen.model.state.value.editing }
    }
    @Test fun landscapePictureUsesNaturalHeightAndStillReachesBothEdges() = withScreen { screen ->
        val bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val file = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "reflection-wide.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        runBlocking { screen.container.homeReflection.importImage(Uri.fromFile(file)) }
        screen.frames(700)
        val image = screen.tag("home_reflection_image").boundsInWindow
        assertEquals(0f, image.left, 1f); assertEquals(screen.width.toFloat(), image.right, 1f)
        assertEquals(image.width / 4, image.height, 1f)
        screen.snapshot("reflection-landscape-fill")
    }
    @Test fun draggingTheListDownDoesNotDismissAndBackAndOutsideStillCloseIt() = withScreen { screen ->
        runBlocking { repeat(12) { screen.container.homeReflection.addText("历史内容 $it") } }
        screen.frames(300); screen.openLibrary()
        val list = screen.tag("home_reflection_history_list", screen.dialog())
        screen.drag(screen.dialog(), list.boundsInWindow.center.x, list.boundsInWindow.top + 40f, 260f)
        screen.frames(500); assertTrue(screen.model.state.value.editing)
        list.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 350f); screen.frames(300)
        assertTrue(screen.model.state.value.editing)
        screen.latestDialog().onBackPressed(); screen.await { !screen.model.state.value.editing }; screen.frames(400)
        screen.openLibrary(); screen.tap(screen.dialog(), 2f, 2f)
        screen.await { !screen.model.state.value.editing }
    }
    private fun withScreen(theme: AppTheme = AppTheme.BasicDark, scale: Float = 1f, block: (Screen) -> Unit) {
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance())) }) {
            val screen = Screen(theme, scale)
            try { block(screen) } finally { screen.destroy() }
        }
    }
    private class Screen(theme: AppTheme, scale: Float) {
        private val app = ApplicationProvider.getApplicationContext<Application>()
        val container: AppContainer
        val model: HomeReflectionViewModel
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val decor get() = controller.get().window.decorView
        val root get() = decor
        val width get() = decor.width
        val height get() = decor.height
        val density get() = controller.get().resources.displayMetrics.density
        init {
            app.deleteDatabase(MebotDatabase.FILE_NAME); container = AppContainer(app)
            runBlocking { container.homeReflection.write("守住初心，做好眼前的事", HomeReflectionMode.TEXT) }
            model = ViewModelProvider(controller.get(), object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeReflectionViewModel(app, SavedStateHandle(), container.homeReflection) as T
            })[HomeReflectionViewModel::class.java]
            val today = LocalDate.of(2026, 10, 31)
            fun card(id: String, day: LocalDate) = HomeCard(id, HomeSource.SCHEDULE, id, day, "当天事项", "", false, 1, builtinKey = "meeting")
            val timeline = HomeTimelineData(listOf(
                HomeDay(today, listOf(HomeSlot("today", listOf(card("today-task", today))))),
                HomeDay(today.minusDays(1), listOf(HomeSlot("past", listOf(card("past-task", today.minusDays(1)))))),
            ))
            val page = HomePageState(today = today, timeline = timeline, loading = false)
            val density = controller.get().resources.displayMetrics.density
            controller.get().setContent { MebotTheme(theme) {
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    val state by model.state.collectAsState()
                    HomeScreen(today = today, state = page, reflectionCard = { HomeReflectionCard(state, model::open) })
                    if (state.editing) HomeReflectionEditor(state, model)
                }
            } }
            frames(700); await { model.state.value.loaded }
        }
        fun latestDialog() = ShadowDialog.getShownDialogs().last { it.isShowing }
        fun dialog() = latestDialog().window!!.decorView
        fun openLibrary() {
            click(tag("home_reflection_card")); await { model.state.value.editing && !model.state.value.historyLoading }; frames(500)
        }
        fun closeLibrary() { click(nodes(dialog()).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("关闭") == true }) }
        fun tap(view: View, x: Float, y: Float) {
            val start = android.os.SystemClock.uptimeMillis()
            for ((t, action) in listOf(start to android.view.MotionEvent.ACTION_DOWN, start + 1 to android.view.MotionEvent.ACTION_UP)) {
                val e = android.view.MotionEvent.obtain(start, t, action, x, y, 0); view.dispatchTouchEvent(e); e.recycle()
            }
        }
        fun drag(view: View, x: Float, y: Float, dy: Float) {
            val start = android.os.SystemClock.uptimeMillis()
            for (i in 0..12) {
                val action = when (i) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val e = android.view.MotionEvent.obtain(start, start + i * 16L, action, x, y + dy * i / 12f, 0)
                view.dispatchTouchEvent(e); e.recycle(); frames(16)
            }
        }
        fun pinch(bounds: androidx.compose.ui.geometry.Rect, held: () -> Unit) {
            val start = android.os.SystemClock.uptimeMillis()
            var step = 0
            fun event(action: Int, spread: Float, count: Int = 2) {
                val properties = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                    x = bounds.center.x + (if (index == 0) -spread else spread)
                    y = bounds.center.y; pressure = 1f; size = 1f
                } }
                val event = MotionEvent.obtain(start, start + step++ * 16L, action, count, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                decor.dispatchTouchEvent(event); event.recycle(); frames(16)
            }
            val wide = bounds.width * .4f
            event(MotionEvent.ACTION_DOWN, wide, 1)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), wide)
            for (i in 1..8) event(MotionEvent.ACTION_MOVE, wide * (1f - .98f * i / 8))
            frames(80); held()
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), wide * .02f)
            event(MotionEvent.ACTION_UP, wide * .02f, 1)
            frames(400)
        }
        fun pixel(x: Float, y: Float): Int {
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap)); val color = bitmap.getPixel(x.toInt(), y.toInt()); bitmap.recycle(); return color
        }
        fun frames(ms: Long) { repeat(((ms + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); measure(decor)
            ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.window?.decorView?.let(::measure) }; Thread.sleep(3)
        } }
        fun await(condition: () -> Boolean) { val end = System.nanoTime() + Duration.ofSeconds(8).toNanos()
            while (!condition()) { check(System.nanoTime() < end) { "界面状态等待超时：${model.state.value}" }; frames(32) } }
        fun nodes(view: View, merged: Boolean = true) = views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.flatMap {
            val owner = it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner
            descendants(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }.toList()
        fun tag(value: String, view: View = decor) = nodes(view, false).first { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun text(value: String, view: View = decor) = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun click(node: SemanticsNode) { assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()) }
        fun snapshot(name: String, view: View = decor) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            File("build/reports/home-reflection/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        fun destroy() {
            ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.dismiss() }; controller.pause().stop().destroy(); frames(100)
            container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
            File(app.filesDir, "media").deleteRecursively()
        }
    }
    companion object {
        private fun views(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i))) }
        private fun measure(view: View) { views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) } }
        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
    }
}
