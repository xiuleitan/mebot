package com.txl.mebot.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.home.HomeReflectionViewModel
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReflectionViewModelTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var model: HomeReflectionViewModel
    private lateinit var handle: SavedStateHandle
    private var activity: ActivityController<ComponentActivity>? = null
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app); create()
    }
    private fun create(saved: SavedStateHandle = SavedStateHandle()) {
        handle = saved; activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity!!.get(), object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = HomeReflectionViewModel(app, saved, container.homeReflection) as T
        })[HomeReflectionViewModel::class.java]
        await { model.state.value.loaded }
    }
    private fun destroy() { activity?.pause()?.stop()?.destroy(); activity = null; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
    @After fun close() { destroy(); container.stopMaintenance(); container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME); File(app.filesDir, "media").deleteRecursively() }
    private fun opened() { model.open(); await { !model.state.value.historyLoading } }
    private fun save() { val task = requireNotNull(model.saveText()); await { task.isCompleted } }
    private fun picture(): Uri {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val file = File(app.cacheDir, "reflection-vm.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return Uri.fromFile(file)
    }
    @Test fun addAndEditKeepDraftSeparateUntilSavedAndCancelLeavesCurrentContentUntouched() = runBlocking {
        container.homeReflection.addText("原来的文字"); await { model.state.value.record.content.text == "原来的文字" }; opened()
        model.editText(); repeat(60) { model.text("新内容 $it") }
        assertEquals("原来的文字", container.homeReflection.read().content.text)
        model.cancelText(); assertNull(model.state.value.textEditor)
        model.editText(); model.text("保存的新文字"); save()
        assertNull(model.state.value.error); assertNull(model.state.value.textEditor)
        assertEquals("保存的新文字", container.homeReflection.read().content.text)
        assertEquals(2, model.state.value.history.size)
        assertTrue(model.state.value.editing)
        model.dismiss(); destroy(); create(); opened()
        assertEquals("保存的新文字", model.state.value.record.content.text)
    }
    @Test fun failedWriteKeepsEditorAndSavedStateDraftUntilRetrySucceeds() = runBlocking {
        container.homeReflection.addText("原内容"); await { model.state.value.record.content.text == "原内容" }; opened()
        val original = model.state.value.history.single().content
        model.editText(original); model.text("编辑中的内容")
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_library BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        save(); assertNotNull(model.state.value.error); assertEquals("编辑中的内容", model.state.value.textEditor!!.text)
        assertEquals("原内容", container.homeReflection.read().content.text)
        val backup = handle.keys().associateWith { handle.get<Any>(it) }
        destroy(); create(SavedStateHandle(backup)); await { !model.state.value.historyLoading }
        assertEquals("编辑中的内容", model.state.value.textEditor!!.text)
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_library")
        save(); assertNull(model.state.value.error)
        assertEquals("编辑中的内容", container.homeReflection.read().content.text)
        assertEquals(1, model.state.value.history.size)
    }
    @Test fun addingPhotosKeepsLibraryOpenAndSelectingHistoryRequestsReturnHomeAfterCommit() = runBlocking {
        container.homeReflection.addText("原文字"); await { model.state.value.record.content.text == "原文字" }; opened()
        val request = requireNotNull(model.preparePhoto())
        val task = requireNotNull(model.pickedPhoto(request.token, picture())); await { task.isCompleted }
        assertEquals(HomeReflectionMode.IMAGE, model.state.value.record.content.mode)
        assertEquals(2, model.state.value.history.size)
        assertTrue(model.state.value.editing); assertFalse(model.state.value.closeRequested)
        val oldText = model.state.value.history.single { it.content.mode == HomeReflectionMode.TEXT }.content
        val use = requireNotNull(model.useHistory(oldText.id)); await { use.isCompleted }
        assertEquals(HomeReflectionMode.TEXT, model.state.value.record.content.mode)
        assertTrue(model.state.value.closeRequested); assertFalse(model.state.value.busy)
        assertNull(model.state.value.textEditor)
        assertEquals("原文字", container.homeReflection.read().content.text)
        model.dismiss(); opened()
        assertFalse(model.state.value.closeRequested)
    }
    @Test fun failedHistorySelectionStaysOpenAndRetryClosesOnlyAfterTheContentIsSaved() = runBlocking {
        container.homeReflection.addText("历史文字"); container.homeReflection.addText("正在展示")
        await { model.state.value.record.content.text == "正在展示" }; opened()
        val old = model.state.value.history.single { it.content.text == "历史文字" }.content
        container.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_selection BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        val failed = requireNotNull(model.useHistory(old.id)); await { failed.isCompleted }
        assertEquals("正在展示", container.homeReflection.read().content.text)
        assertTrue(model.state.value.editing); assertFalse(model.state.value.closeRequested)
        assertNotNull(model.state.value.error)
        container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_selection")
        val retry = requireNotNull(model.useHistory(old.id)); await { retry.isCompleted }
        assertTrue(model.state.value.closeRequested); assertNull(model.state.value.error)
        assertEquals("历史文字", container.homeReflection.read().content.text)
        assertEquals(2, container.homeReflection.historyPage(10).size)
        val backup = handle.keys().associateWith { handle.get<Any>(it) }
        destroy(); create(SavedStateHandle(backup))
        assertFalse(model.state.value.editing)
        assertEquals("历史文字", model.state.value.record.content.text)
    }
    @Test fun canceledAndLatePhotoResultsCannotChangeContentOrAnotherOpenPage() = runBlocking {
        opened(); val canceled = requireNotNull(model.preparePhoto()); model.pickedPhoto(canceled.token, null)
        assertNull(model.state.value.photoRequest); assertNull(model.state.value.record.content.imageAssetId)
        val stale = requireNotNull(model.preparePhoto()); model.dismiss(); opened()
        assertNull(model.pickedPhoto(stale.token, picture()))
        assertNull(container.homeReflection.read().content.imageAssetId)
    }
    @Test fun historyLoadsOnDemandAndEditingAnOlderEntryKeepsTheLoadedWindowAndCurrentDisplay() = runBlocking {
        repeat(85) { container.homeReflection.addText("感悟 $it") }
        await { model.state.value.record.content.text == "感悟 84" }; opened()
        assertEquals(40, model.state.value.history.size); assertTrue(model.state.value.historyHasMore)
        model.moreHistory(); await { !model.state.value.historyLoading }; assertEquals(80, model.state.value.history.size)
        model.moreHistory(); await { !model.state.value.historyLoading }; assertEquals(85, model.state.value.history.size)
        val old = model.state.value.history.single { it.content.text == "感悟 0" }.content
        model.editText(old); model.text("修改后的旧感悟"); save()
        assertEquals("感悟 84", model.state.value.record.content.text)
        assertEquals(85, model.state.value.history.size); assertFalse(model.state.value.historyHasMore)
        assertTrue(model.state.value.history.any { it.content.text == "修改后的旧感悟" })
    }
    @Test fun deletionRequiresAChosenRecordAndCancelDoesNotDeleteCurrentOrHistory() = runBlocking {
        container.homeReflection.addText("要保留的内容"); await { model.state.value.record.content.text == "要保留的内容" }; opened()
        assertNull(model.delete())
        val entry = model.state.value.history.single().content
        model.askDelete(entry); model.cancelDelete()
        assertEquals("要保留的内容", container.homeReflection.read().content.text)
        model.askDelete(entry); val task = requireNotNull(model.delete()); await { task.isCompleted }
        assertNull(model.state.value.deletion); assertNull(model.state.value.error)
        assertEquals("", model.state.value.record.content.text); assertTrue(model.state.value.history.isEmpty())
        model.dismiss(); opened(); assertTrue(model.state.value.history.isEmpty())
    }
    @Test fun staleEditorCannotRecreateADeletedRecordAndLegacyUnsavedTextIsRecovered() = runBlocking {
        container.homeReflection.addText("旧条目"); await { model.state.value.record.content.text == "旧条目" }; opened()
        val old = model.state.value.history.single().content
        model.editText(old); model.text("迟到的编辑")
        container.homeReflection.deleteEntry(old); save()
        assertNotNull(model.state.value.error); assertEquals("迟到的编辑", model.state.value.textEditor!!.text)
        assertTrue(container.homeReflection.historyPage(10).isEmpty())
        destroy(); create(SavedStateHandle(mapOf("home.reflection.open" to true,
            "home.reflection.input" to "{\"text\":\"升级前没保存的输入\",\"mode\":\"TEXT\"}")))
        await { !model.state.value.historyLoading }
        assertEquals("升级前没保存的输入", model.state.value.textEditor!!.text)
    }
    @Test fun photoResultDuringTheInitialHistoryReadWaitsAndIsNotDropped() = runBlocking {
        opened(); val request = requireNotNull(model.preparePhoto())
        val backup = handle.keys().associateWith { handle.get<Any>(it) }
        destroy(); create(SavedStateHandle(backup))
        val job = requireNotNull(model.pickedPhoto(request.token, picture()))
        await { job.isCompleted && !model.state.value.busy }
        assertEquals(HomeReflectionMode.IMAGE, model.state.value.record.content.mode)
        assertNotNull(model.state.value.record.content.imageAssetId)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "寄语状态等待超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
