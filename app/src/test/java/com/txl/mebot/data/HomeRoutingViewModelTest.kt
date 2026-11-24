package com.txl.mebot.data

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.home.HomeComposerViewModel
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
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeRoutingViewModelTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: HomeComposerViewModel
    private var handle = SavedStateHandle()
    private val requests = mutableListOf<String>()
    private var choose: suspend (String) -> HomeBot = { HomeBot.SCHEDULE }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity.get(), object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeComposerViewModel(app,
                handle, container.homeComposer, HomeTextRouter { text -> requests.add(text); choose(text) }, container.homeDispatch) as T
        })[HomeComposerViewModel::class.java]
        await { model.state.value.loaded }
    }
    @After fun close() {
        activity.pause().stop().destroy(); container.stopMaintenance(); container.database.close()
        app.deleteDatabase(MebotDatabase.FILE_NAME); File(app.filesDir, "media").deleteRecursively()
    }
    @Test fun attachmentOnlyAndWhitespaceNeverEnableSendOrCallDecisionModel() = runBlocking {
        val asset = MediaAssetEntity("fixture", MediaSource.IMPORTED, mimeType = "image/png", byteCount = 1, width = 1, height = 1, createdAt = 1)
        container.database.mediaDao().insert(asset); container.homeComposer.attach(asset, "附件")
        await { model.state.value.items.size == 1 }; model.chooseBot(HomeBot.FLASHNOTE)
        assertFalse(model.state.value.canSend); model.send(); idle(2_500)
        assertTrue(requests.isEmpty()); assertTrue(container.database.flashnoteDao().notes(FlashnoteStatus.PENDING).isEmpty())
        model.changeInput("  \n"); idle(2_500); assertFalse(model.state.value.canSend); assertTrue(requests.isEmpty())
    }
    @Test fun continuousEditingWaitsForOneAndAHalfIdleSecondsAndUnchangedTextDoesNotRepeatRequests() {
        repeat(6) {
            model.changeInput("明天买菜 $it")
            idle(400)
            assertTrue(requests.isEmpty())
        }
        model.changeInput("明天买菜")
        idle(600)
        model.changeInput("明天买菜") // 相同文字的重复回调不重新计时。
        idle(899); assertTrue(requests.isEmpty())
        idle(1); assertEquals(listOf("明天买菜"), requests)
        idle(3_000); assertEquals(1, requests.size)
        model.changeInput("后天买菜")
        idle(1_499); assertEquals(1, requests.size)
        idle(1); assertEquals(listOf("明天买菜", "后天买菜"), requests)
        model.changeInput("还没有输完的内容")
        idle(1_400); model.changeInput("")
        idle(2_500); assertEquals(2, requests.size)
        assertNull(model.state.value.selectedBot); assertFalse(model.state.value.deciding)
    }
    @Test fun manualChoiceSurvivesLateAnswerButBodyEditingStartsANewDecisionWithoutTheMention() {
        val late = CompletableDeferred<HomeBot>()
        choose = { withContext(NonCancellable) { late.await() } }
        repeat(20) { model.changeInput("明天买菜 $it") }
        idle(1_550); assertEquals(listOf("明天买菜 19"), requests)
        model.chooseBot(HomeBot.ARCHIVE)
        late.complete(HomeBot.SCHEDULE); idle(100)
        assertEquals(HomeBot.ARCHIVE, model.state.value.selectedBot); assertTrue(model.state.value.manualBot)
        assertEquals("@档案 明天买菜 19", model.state.value.input)
        choose = { HomeBot.FLASHNOTE }
        model.changeInput("@档案 做一个工具"); idle(1_499)
        assertFalse(model.state.value.canSend); assertFalse(model.state.value.manualBot)
        assertEquals(1, requests.size)
        idle(1)
        assertEquals("@闪念 做一个工具", model.state.value.input)
        assertEquals(listOf("明天买菜 19", "做一个工具"), requests)
    }
    @Test fun deletingOrMisspellingMentionDisablesSendWithoutAutomaticallyPuttingItBack() {
        model.changeInput("明天买菜"); idle(1_550)
        assertEquals("@日程 明天买菜", model.state.value.input); assertTrue(model.state.value.canSend)
        model.changeInput("明天买菜"); idle(2_500)
        assertNull(model.state.value.selectedBot); assertFalse(model.state.value.canSend)
        assertEquals(1, requests.size); model.send(); idle(100); assertNull(model.state.value.activeTask)
        model.changeInput("@日 明天买菜"); idle(2_500)
        assertFalse(model.state.value.canSend); assertEquals(1, requests.size)
        model.chooseBot(HomeBot.ARCHIVE); assertEquals("@档案 明天买菜", model.state.value.input)
        assertTrue(model.state.value.canSend)
        model.changeInput("@档案 后天买菜"); idle(1_550)
        assertEquals("@日程 后天买菜", model.state.value.input)
        assertEquals(listOf("明天买菜", "后天买菜"), requests)
    }
    @Test fun deletedMentionRemainsDeletedWhenTheActivityIsRestored() {
        model.changeInput("明天买菜"); idle(1_550)
        var saving = model.savePendingInput(); await { saving.isCompleted }
        model.changeInput("明天买菜")
        handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        saving = model.savePendingInput(); await { saving.isCompleted }
        activity.pause().stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity.get(), object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeComposerViewModel(app,
                handle, container.homeComposer, HomeTextRouter { text -> requests.add(text); choose(text) }, container.homeDispatch) as T
        })[HomeComposerViewModel::class.java]
        await { model.state.value.loaded }; idle(2_500)
        assertEquals("明天买菜", model.state.value.input)
        assertNull(model.state.value.selectedBot); assertFalse(model.state.value.canSend); assertEquals(1, requests.size)
    }
    @Test fun olderAutomaticAnswerCannotReplaceTheBotForNewerText() {
        val first = CompletableDeferred<HomeBot>(); val second = CompletableDeferred<HomeBot>()
        choose = { text -> withContext(NonCancellable) { if (text == "旧输入") first.await() else second.await() } }
        model.changeInput("旧输入"); idle(1_550)
        model.changeInput("新输入"); idle(1_550)
        second.complete(HomeBot.SUBSCRIPTIONS); await { model.state.value.selectedBot == HomeBot.SUBSCRIPTIONS }
        first.complete(HomeBot.SCHEDULE); idle(100)
        assertEquals(HomeBot.SUBSCRIPTIONS, model.state.value.selectedBot)
    }
    @Test fun routeFailureKeepsInputAndManualChoiceCanStillSaveAFlashnoteWithoutChat() = runBlocking {
        choose = { throw HomeRouteFailure("暂时断网") }
        model.changeInput("做一个小工具"); idle(1_550); await { model.state.value.routeError != null }
        assertEquals("做一个小工具", model.state.value.input); assertFalse(model.state.value.canSend)
        model.chooseBot(HomeBot.FLASHNOTE); assertTrue(model.state.value.canSend)
        model.send(); model.send()
        await { !model.state.value.dispatching && model.state.value.input.isEmpty() }
        assertNull(model.state.value.activeTask)
        assertEquals("做一个小工具", container.database.flashnoteDao().notes(FlashnoteStatus.PENDING).single().content)
        assertEquals(1, requests.size)
    }
    @Test fun decisionStopsAfterHandoffAndTheTaskHasItsOwnPersistedSession() {
        model.changeInput("明天买菜"); idle(1_550); await { model.state.value.selectedBot == HomeBot.SCHEDULE }
        model.send(); await { model.state.value.activeTask != null }
        val task = model.state.value.activeTask!!
        assertEquals("schedule", task.botId); assertFalse(model.state.value.editable)
        model.decide(); idle(2_500); assertEquals(1, requests.size)
        model.closeTask(); await { model.state.value.activeTask == null }
        assertTrue(model.state.value.editable); assertNull(model.state.value.selectedBot)
    }
    private fun idle(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun await(condition: () -> Boolean) {
        repeat(400) { idle(20); if (condition()) return; Thread.sleep(5) }
        assertTrue("Expected state: ${model.state.value}", condition())
    }
}
