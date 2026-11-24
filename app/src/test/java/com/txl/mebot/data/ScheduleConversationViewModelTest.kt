package com.txl.mebot.data

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.ui.schedule.ConversationSaveState
import com.txl.mebot.ui.schedule.ScheduleConversationViewModel
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** 仅在开发机创建无内容 Activity，验证真实 SavedState 工厂和异步保存顺序。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleConversationViewModelTest {
    private lateinit var application: MebotApplication
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: ScheduleConversationViewModel

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity.get())[ScheduleConversationViewModel::class.java]
        model.setConversationVisible(true)
        await { model.state.value.isLoaded }
    }

    @After fun tearDown() {
        activity.pause().stop().destroy()
        application.container.database.close()
        application.deleteDatabase("mebot.db")
    }

    @Test fun rapidTypingAndRepeatedNewScheduleEntryRestoreLatestInput() = runBlocking {
        val first = requireNotNull(model.state.value.sessionId)
        repeat(60) { model.changeInput("买土豆、猕猴桃，补充 $it") }
        model.setConversationVisible(false)
        model.openCreation() // 不等待防抖，再次进入仍是同一个草稿。
        await { model.state.value.isLoaded && !model.state.value.isLoading && model.state.value.saveState == ConversationSaveState.SAVED }
        assertEquals(first, model.state.value.sessionId)
        assertEquals("买土豆、猕猴桃，补充 59", model.state.value.input)
        assertEquals("买土豆、猕猴桃，补充 59", application.container.conversations.openCreation().inputText)
        assertTrue(application.container.conversations.observeMessages(first, 40).first().isEmpty())
    }

    @Test fun clearDiscardsPendingSaveAndReopenCannotRestoreDiscardedConversation() = runBlocking {
        val previous = requireNotNull(model.state.value.sessionId)
        model.changeInput("今天买菜")
        model.send()
        await { !model.state.value.isSending && model.state.value.input.isEmpty() }
        model.changeInput("再加辣椒，还没有发送")
        model.clearCreation() // 未到防抖时间，清空应直接丢弃这一段输入。
        await { !model.state.value.isLoading && model.state.value.sessionId != previous }
        val fresh = model.state.value.sessionId
        assertEquals("", model.state.value.input)
        assertTrue(model.state.value.messages.isEmpty())
        assertNull(application.container.database.scheduleDao().session(previous))
        model.setConversationVisible(false)
        model.openCreation()
        await { !model.state.value.isLoading }
        assertEquals(fresh, model.state.value.sessionId)
        assertEquals("", model.state.value.input)
        assertTrue(model.state.value.messages.isEmpty())
        model.setConversationVisible(true)
        model.changeInput("明天下午开会")
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        assertEquals("明天下午开会", application.container.conversations.openCreation().inputText)
    }

    @Test fun doubleSendIsSingleCommitAndFurtherTextStaysInSameConversation() = runBlocking {
        val sessionId = requireNotNull(model.state.value.sessionId)
        model.changeInput("今天买土豆和猕猴桃")
        model.send()
        model.send()
        await { !model.state.value.isSending && !model.state.value.isAnalyzing && model.state.value.input.isEmpty() }
        model.changeInput("再加茄子和辣椒")
        model.send()
        await { !model.state.value.isSending && model.state.value.input.isEmpty() }
        val messages = application.container.conversations.observeMessages(sessionId, 40).first()
        assertEquals(listOf("今天买土豆和猕猴桃", "再加茄子和辣椒"), messages.map { it.content })
        assertEquals(sessionId, model.state.value.sessionId)
        assertNull(application.container.schedules.taskForSession(sessionId))
    }

    @Test fun savedActivityStateRestoresTheOriginalConversationAndUnsentText() {
        val sessionId = model.state.value.sessionId
        model.changeInput("明天下午四点开会，还没写完")
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
        model = ViewModelProvider(activity.get())[ScheduleConversationViewModel::class.java]
        model.setConversationVisible(true)
        await { model.state.value.isLoaded && !model.state.value.isLoading }
        assertEquals(sessionId, model.state.value.sessionId)
        assertEquals("明天下午四点开会，还没写完", model.state.value.input)
    }

    @Test fun coldStartWithoutSavedActivityStateStillFindsLatestCreationDraft() {
        val sessionId = model.state.value.sessionId
        model.changeInput("重启后还要继续写的日程")
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        activity.pause().stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() // 不传任何 SavedState。
        model = ViewModelProvider(activity.get())[ScheduleConversationViewModel::class.java]
        model.openCreation()
        model.setConversationVisible(true)
        await { model.state.value.isLoaded && !model.state.value.isLoading }
        assertEquals(sessionId, model.state.value.sessionId)
        assertEquals("重启后还要继续写的日程", model.state.value.input)
    }

    @Test fun draftConflictPreservesBothInputsUntilUserChoosesCurrentText() = runBlocking {
        val id = requireNotNull(model.state.value.sessionId)
        model.changeInput("原始草稿")
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        val previous = application.container.conversations.openConversation(id)
        application.container.conversations.saveInput(id, "其他页面的内容", previous.revision)
        model.changeInput("当前页面继续编辑")
        await { model.state.value.saveState == ConversationSaveState.CONFLICT }
        assertEquals("当前页面继续编辑", model.state.value.input)
        assertEquals("其他页面的内容", application.container.conversations.openConversation(id).inputText)
        model.retry() // UI 在冲突状态明确显示“保留当前文字”。
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        assertEquals("当前页面继续编辑", application.container.conversations.openConversation(id).inputText)
    }

    @Test fun homeHandoffOpensTheOriginalConversationWithoutResendingOrReplacingOtherInput() = runBlocking {
        val oldSession = requireNotNull(model.state.value.sessionId)
        model.changeInput("日程页面还没发的草稿")
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        val container = application.container
        val before = container.homeComposer.read()
        val input = container.homeComposer.saveInput("明天买菜", before.revision)
        val ticket = container.homeDispatch.dispatch(com.txl.mebot.data.home.HomeBot.SCHEDULE,
            com.txl.mebot.data.home.HomeSendSnapshot(input.inputText, input.revision, emptyList()))
        var opening = activity.get().lifecycleScope.launch { model.acceptHomeMessage(ticket.targetId, ticket.messageId) }
        await { opening.isCompleted }
        assertEquals(ticket.targetId, model.state.value.sessionId)
        assertTrue(model.state.value.messages.any { it.id == ticket.messageId && it.content == "明天买菜" })
        assertEquals("日程页面还没发的草稿", container.database.conversationDao().draft(oldSession)!!.inputText)
        opening = activity.get().lifecycleScope.launch { model.acceptHomeMessage(ticket.targetId, ticket.messageId) }
        await { opening.isCompleted }
        assertEquals(1, container.database.conversationDao().contextMessages(ticket.targetId, Long.MAX_VALUE, 20)
            .count { it.role == com.txl.mebot.data.local.MessageRole.USER })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待会话状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5) // 给真实 SQLite 的后台执行器完成事务的时间。
        }
    }
}
