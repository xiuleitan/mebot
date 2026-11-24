package com.txl.mebot.data

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.ui.schedule.ScheduleEditorViewModel
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleEditorViewModelTest {
    private lateinit var app: MebotApplication
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: ScheduleEditorViewModel
    private lateinit var id: String
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        id = read {
            val session = app.container.schedules.openSession().id
            val task = app.container.schedules.createTask(session, UUID.randomUUID().toString(), ScheduleDraft("原始标题", "正文", LocalDate.now()))
            app.container.database.scheduleDao().occurrencesForTask(task.id).single().id
        }
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        // 与 MainActivity 一样，验证 Android 默认 SavedState 工厂可创建表单模型。
        model = ViewModelProvider(activity.get())[ScheduleEditorViewModel::class.java]
        model.open(id); await { model.state.value?.loading == false }
    }
    @After fun cleanup() {
        activity.pause().stop().destroy()
        app.container.database.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
    }
    @Test(timeout = 30_000) fun saveFlushesLastKeystrokeWithoutWaitingForAutosaveAndClearsOnlyAfterExit() {
        model.change { it.copy(title = "立即保存的标题", detail = "最后一个字符") }
        model.save()
        await { model.state.value?.saved == true }
        assertEquals("立即保存的标题", read { app.container.database.scheduleDao().occurrence(id)!!.title })
        assertEquals("最后一个字符", read { app.container.database.scheduleDao().occurrence(id)!!.detail })
        assertTrue(dismiss(model))
        assertNotNull(model.state.value) // 隐藏动画结束前仍保留表单。
        model.closed(); assertNull(model.state.value)
    }
    @Test(timeout = 30_000) fun invalidTitleRetainsFormAndCanBeCorrectedWithoutChangingOriginalEarly() {
        model.change { it.copy(title = " ") }; model.save()
        await { model.state.value?.error != null && model.state.value?.busy == false }
        assertEquals(" ", model.state.value?.draft?.title)
        assertEquals("原始标题", read { app.container.database.scheduleDao().occurrence(id)!!.title })
        model.change { it.copy(title = "更正后标题") }; model.save()
        await { model.state.value?.saved == true }
        assertEquals("更正后标题", read { app.container.database.scheduleDao().occurrence(id)!!.title })
    }
    @Test(timeout = 30_000) fun restoredModelReadsRoomDraftAndCancelKeepsOriginalUnchanged() {
        model.change { it.copy(detail = "尚未提交的正文") }
        await { read { app.container.database.scheduleEditor().draft(id)?.detail == "尚未提交的正文" } }
        val restored = ScheduleEditorViewModel(app, SavedStateHandle(mapOf("schedule_manual_editor_id" to id)), app.container.scheduleEditor)
        await { restored.state.value?.loading == false }
        assertEquals("尚未提交的正文", restored.state.value?.draft?.detail)
        assertTrue(dismiss(restored)); restored.closed()
        assertEquals("正文", read { app.container.database.scheduleDao().occurrence(id)!!.detail })
        assertNull(read { app.container.database.scheduleEditor().draft(id) })
    }
    @Test(timeout = 30_000) fun pickerResultFromDiscardedDraftDoesNotAttachToReloadedForm() {
        val previous = requireNotNull(model.state.value?.draft).requestId
        model.reload()
        await { model.state.value?.draft?.requestId != previous && model.state.value?.busy == false }
        model.importImages(previous, listOf(android.net.Uri.parse("content://stale/picture")))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue(model.state.value!!.attachments.isEmpty())
        assertNull(model.state.value?.error)
    }
    private fun <T> read(action: suspend () -> T): T = runBlocking(Dispatchers.IO) { action() }
    private fun dismiss(editor: ScheduleEditorViewModel): Boolean {
        var result: Boolean? = null
        CoroutineScope(Dispatchers.Main.immediate).launch { result = editor.beforeDismiss() }
        await { result != null }
        return result == true
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待编辑状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(5)
        }
    }
}
