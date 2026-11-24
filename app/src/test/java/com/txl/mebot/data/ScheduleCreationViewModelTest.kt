package com.txl.mebot.data

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.models.*
import com.txl.mebot.data.schedule.*
import com.txl.mebot.ui.schedule.ConversationSaveState
import com.txl.mebot.ui.schedule.ScheduleConversationViewModel
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** 在开发机验证 UI 状态 → 已存配置 → HTTP 协议 → 校验 → SQLite → 卡片的完整链路。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleCreationViewModelTest {
    private lateinit var application: MebotApplication
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var coordinator: ScheduleCreationCoordinator
    private lateinit var model: ScheduleConversationViewModel
    private val tomorrow = LocalDate.now().plusDays(1)
    private var calls = 0
    private var response: suspend () -> ModelHttpResponse = { success() }
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            ScheduleConversationViewModel(application, extras.createSavedStateHandle(), coordinator) as T
    }

    @Before fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        val container = application.container
        val settings = ModelSettingsRepository(container.database, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 8 }, "AES") })
        settings.save(ModelSettings(), ApiKeyChange.Replace("sk-local-fake-viewmodel"), 0)
        val interpreter = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, _, _ -> calls++; response() })
        coordinator = ScheduleCreationCoordinator(container.database, container.schedules, container.conversations, interpreter)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        attach()
        await { model.state.value.isLoaded && !model.state.value.isLoading }
    }

    @After fun tearDown() {
        activity.pause().stop().destroy()
        application.container.database.close()
        application.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun sendingCreatesOneCardAndNextNewScheduleStartsAFreshConversation() = runBlocking {
        val session = requireNotNull(model.state.value.sessionId)
        model.changeInput("明天下午4点开会，提前10分钟和准点提醒")
        model.send(); model.send()
        awaitSaved()
        val card = requireNotNull(model.state.value.taskEntry)
        assertEquals(session, card.sessionId)
        assertEquals(tomorrow, card.date)
        assertEquals(LocalTime.of(16, 0), card.time)
        assertEquals(tomorrow, model.state.value.savedEvent?.date)
        assertEquals(card.id, model.state.value.savedEvent?.createdOccurrenceId)
        assertEquals(1, calls)
        assertEquals(2, model.state.value.messages.size)
        model.consumeSavedEvent(requireNotNull(model.state.value.savedEvent).messageId)
        assertNull(model.state.value.savedEvent)
        model.openCreation()
        await { !model.state.value.isLoading && model.state.value.sessionId != session }
        assertFalse(model.state.value.hasTask)
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(2, application.container.schedules.recentMessages(session).size)
    }

    @Test fun malformedModelResponsesAutomaticallyRecoverAndPublishTheCreatedCardTarget() {
        val enteredThirdAttempt = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        response = {
            if (calls < 3) ScheduleTextInterpreterTest.response(JSONObject().put("decision", "invalid"))
            else { enteredThirdAttempt.complete(Unit); release.await(); success() }
        }
        model.changeInput("明天下午4点开会")
        model.send()
        await { enteredThirdAttempt.isCompleted && model.state.value.analysisAttempt == 3 }
        assertTrue(model.state.value.isAnalyzing)
        assertNull(model.state.value.analysisFailure)
        assertNull(model.state.value.taskEntry)
        assertEquals(1, model.state.value.messages.size)
        release.complete(Unit)
        awaitSaved()
        assertEquals(3, calls)
        assertEquals(2, model.state.value.messages.size)
        assertEquals(model.state.value.taskEntry?.id, model.state.value.savedEvent?.createdOccurrenceId)
    }

    @Test fun malformedOutputStopsAfterTenAttemptsAndRetainsTheOriginalUserMessage() {
        response = { ScheduleTextInterpreterTest.response(JSONObject().put("decision", "invalid")) }
        model.changeInput("明天下午4点开会")
        model.send()
        await { !model.state.value.isAnalyzing && model.state.value.analysisFailure == ScheduleAnalysisFailure.INVALID_RESULT }
        assertEquals(10, calls)
        assertEquals(10, model.state.value.analysisAttempt)
        assertEquals(1, model.state.value.messages.size)
        assertNull(model.state.value.savedEvent)
        assertNull(model.state.value.taskEntry)
    }

    @Test fun closingDuringAnalysisStillSavesTheTaskAndKeepsNewlyTypedDraftText() {
        val release = CompletableDeferred<Unit>()
        response = { release.await(); success() }
        val session = requireNotNull(model.state.value.sessionId)
        model.changeInput("明天下午4点开会")
        model.send()
        await { model.state.value.isAnalyzing && !model.state.value.isSending && calls == 1 }
        model.changeInput("等会儿继续补充的内容")
        model.savePendingInput() // 浮窗收起时，底部输入栏仍在当前页面观察会话。
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        release.complete(Unit)
        await { !model.state.value.isAnalyzing && model.state.value.hasTask }
        model.openConversation(session)
        model.setConversationVisible(true)
        awaitSaved()
        assertEquals("等会儿继续补充的内容", model.state.value.input)
        assertEquals(1, calls)
    }

    @Test fun clearAfterCreationStartsANewDraftAndPreservesTheSavedTaskAndChat() = runBlocking {
        model.changeInput("明天下午4点开会")
        model.send()
        awaitSaved()
        val originalSession = requireNotNull(model.state.value.sessionId)
        val card = requireNotNull(model.state.value.taskEntry)
        val originalTask = requireNotNull(application.container.schedules.taskForSession(originalSession))
        model.clearCreation()
        await { !model.state.value.isLoading && model.state.value.sessionId != originalSession }
        assertFalse(model.state.value.hasTask)
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(originalTask.id, application.container.schedules.taskForSession(originalSession)?.id)
        assertEquals(2, application.container.schedules.recentMessages(originalSession).size)
        model.openForOccurrence(card.id)
        awaitSaved()
        assertEquals(originalSession, model.state.value.sessionId)
        assertEquals(card.id, model.state.value.taskEntry?.id)
    }

    @Test fun networkFailureAndColdStartExposeRetryWithoutDuplicatingTheUserMessage() {
        response = { throw IOException("simulated offline") }
        val session = model.state.value.sessionId
        model.changeInput("明天下午4点开会")
        model.send()
        await { !model.state.value.isAnalyzing && model.state.value.analysisFailure == ScheduleAnalysisFailure.NETWORK }
        assertEquals(1, model.state.value.messages.size)
        assertNull(model.state.value.taskEntry)
        activity.pause().stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        attach()
        await { model.state.value.isLoaded && model.state.value.analysisFailure == ScheduleAnalysisFailure.INTERRUPTED }
        assertEquals(session, model.state.value.sessionId)
        assertEquals(1, calls)
        response = { success() }
        model.retry(); model.retry()
        awaitSaved()
        assertEquals(2, calls)
        assertEquals(2, model.state.value.messages.size)
    }

    @Test fun clearCancelsTheRequestAndNewConversationCanImmediatelyCreate() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        response = { try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val discarded = requireNotNull(model.state.value.sessionId)
        model.changeInput("这份草稿将被清空")
        model.send()
        await { calls == 1 && model.state.value.isAnalyzing && !model.state.value.isSending }
        model.clearCreation()
        await { !model.state.value.isLoading && model.state.value.sessionId != discarded && cancelled.isCompleted }
        assertTrue(model.state.value.messages.isEmpty())
        assertNull(application.container.database.scheduleDao().session(discarded))
        response = { success() }
        model.changeInput("明天下午4点开会")
        model.send()
        awaitSaved()
        assertEquals(1, application.container.schedules.observeDate(tomorrow).first().size)
    }

    @Test fun savedStateReopensTheCreatedTasksConversationAndUnsentText() {
        model.changeInput("明天下午4点开会")
        model.send()
        awaitSaved()
        val session = model.state.value.sessionId
        model.changeInput("还没发送的会前准备")
        await { model.state.value.saveState == ConversationSaveState.SAVED }
        val bundle = Bundle()
        activity.pause().saveInstanceState(bundle).stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create(bundle).start().resume().visible()
        attach()
        awaitSaved()
        assertEquals(session, model.state.value.sessionId)
        assertEquals("还没发送的会前准备", model.state.value.input)
        assertEquals(1, calls)
    }

    @Test fun editingThenDeletingRemovesTaskAndChatWithoutCreatingAReplacementDraft() = runBlocking {
        model.changeInput("明天下午4点开会")
        model.send(); awaitSaved()
        val session = requireNotNull(model.state.value.sessionId)
        val firstCard = requireNotNull(model.state.value.taskEntry)
        response = { ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.editJson(JSONObject().put("time", "17:00"))) }
        model.changeInput("改成五点"); model.send()
        await { !model.state.value.isAnalyzing && model.state.value.taskEntry?.time == LocalTime.of(17, 0) }
        assertEquals(firstCard.id, model.state.value.taskEntry?.id)
        assertEquals(firstCard.title, model.state.value.taskEntry?.title)
        assertEquals(firstCard.detail, model.state.value.taskEntry?.detail)
        assertNull(model.state.value.savedEvent?.createdOccurrenceId)
        response = { ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.deleteJson()) }
        model.changeInput("删除这个日程"); model.send()
        await { model.state.value.isDeleted && model.state.value.deletedEvent != null }
        assertNull(model.state.value.sessionId)
        assertTrue(model.state.value.messages.isEmpty())
        assertNull(application.container.database.scheduleDao().session(session))
        assertTrue(application.container.schedules.observeDate(tomorrow).first().isEmpty())
        model.openCreation()
        await { !model.state.value.isLoading && model.state.value.sessionId != null }
        assertNotEquals(session, model.state.value.sessionId)
        assertTrue(model.state.value.messages.isEmpty())
    }

    @Test fun recurringCardsOpenTheSameChatButKeepTheSelectedOccurrenceAcrossActivityRecreation() = runBlocking {
        val schedules = application.container.schedules
        response = { ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.saveJson(ScheduleDraft(
            "喂猫", "常规猫粮", tomorrow, LocalTime.of(18, 0), RecurrenceRule(RecurrencePattern.DAILY), listOf(30, 0),
        ))) }
        model.changeInput("从明天起每天18点喂猫，提前30分钟和准点提醒")
        model.send(); awaitSaved()
        val session = requireNotNull(model.state.value.sessionId)
        schedules.materializeRange(tomorrow, tomorrow.plusDays(2))
        val selected = schedules.observeDate(tomorrow.plusDays(2)).first().single()
        model.openForOccurrence(selected.id)
        await { !model.state.value.isLoading && model.state.value.taskEntry?.id == selected.id }
        assertEquals(session, model.state.value.sessionId)
        assertEquals(2, model.state.value.messages.size)
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
        attach()
        await { model.state.value.isLoaded && !model.state.value.isLoading && model.state.value.taskEntry?.id == selected.id }
        response = { ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.editJson(JSONObject().put("time", "19:00"), "once")) }
        model.changeInput("只把本次改成19点")
        model.send()
        await { !model.state.value.isAnalyzing && model.state.value.messages.size == 4 && model.state.value.taskEntry?.time == LocalTime.of(19, 0) }
        assertEquals(selected.id, model.state.value.taskEntry?.id)
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(tomorrow).first().single().time)
        model.openForOccurrence(schedules.observeDate(tomorrow).first().single().id)
        await { !model.state.value.isLoading && model.state.value.taskEntry?.date == tomorrow }
        assertEquals(session, model.state.value.sessionId)
        assertEquals(4, model.state.value.messages.size)
    }

    @Test fun staleSavedStateCannotRestoreADeletedConversation() {
        model.changeInput("明天下午4点开会")
        model.send(); awaitSaved()
        val session = model.state.value.sessionId
        val stale = Bundle()
        activity.pause().saveInstanceState(stale).resume()
        response = { ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.deleteJson()) }
        model.changeInput("删除这个日程"); model.send()
        await { model.state.value.isDeleted }
        activity.pause().stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create(stale).start().resume().visible()
        attach()
        await { model.state.value.isLoaded && !model.state.value.isLoading }
        assertNotEquals(session, model.state.value.sessionId)
        assertFalse(model.state.value.hasTask)
        assertTrue(model.state.value.messages.isEmpty())
        assertFalse(model.state.value.loadFailed)
    }

    private fun attach() {
        model = ViewModelProvider(activity.get(), factory)[ScheduleConversationViewModel::class.java]
        model.setConversationVisible(true)
    }
    private fun awaitSaved() = await {
        !model.state.value.isSending && !model.state.value.isAnalyzing && model.state.value.taskEntry != null &&
            model.state.value.messages.size >= 2 && model.state.value.analysisFailure == null
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待创建状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }
    private fun success() = ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.saveJson(ScheduleDraft(
        "团队会议", "确认项目进度", tomorrow, LocalTime.of(16, 0), reminderMinutes = listOf(10, 0),
    )))
}
