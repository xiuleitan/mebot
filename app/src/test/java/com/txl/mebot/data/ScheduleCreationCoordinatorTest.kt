package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ScheduleAction
import com.txl.mebot.data.media.BuiltinCover
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 使用真实 SQLite 和替代模型结果验证业务提交，测试不调用外部模型。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduleCreationCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: MebotDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var conversations: ScheduleConversationRepository
    private lateinit var coordinator: ScheduleCreationCoordinator
    private lateinit var session: String
    private var calls = 0
    private val day = LocalDate.of(2026, 10, 22)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(day.atTime(10, 0).atZone(zone).toInstant(), zone)
    private var behavior: suspend (ScheduleAnalysisContext) -> ScheduleInterpretation = {
        ScheduleInterpretation.Save(ScheduleDraft("采购食材", "土豆、猕猴桃"))
    }

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MebotDatabase::class.java).build()
        connect()
        session = conversations.openCreation().sessionId
    }
    @After fun tearDown() { database.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }

    private fun connect() {
        schedules = ScheduleRepository(database, clock) { zone }
        conversations = ScheduleConversationRepository(database, schedules, clock)
        coordinator = ScheduleCreationCoordinator(database, schedules, conversations, ScheduleTextInterpreter { calls++; behavior(it) }, clock) { zone }
    }

    @Test fun shoppingCreatesOneDateOnlyCardAndFurtherItemsUpdateThatSameTask() = runBlocking {
        val message = send("今天买土豆、猕猴桃")
        coordinator.process(session, message)
        val original = requireNotNull(schedules.taskForSession(session))
        val first = schedules.observeDate(day).first().single()
        assertEquals(day, first.date)
        assertNull(first.time)
        assertTrue(database.scheduleDao().reminders(first.id).isEmpty())
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), schedules.recentMessages(session).map { it.role })
        val cover = LocalMediaStore(context, database, clock).builtin(BuiltinCover.GROCERIES)
        schedules.updateTask(session, "set-cover", ScheduleDraft("采购食材", "土豆、猕猴桃", day, coverAssetId = cover.id), original.revision)
        behavior = {
            assertEquals("土豆、猕猴桃", it.current?.detail)
            ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("采购食材", "土豆、猕猴桃、茄子、辣椒", day), setOf("detail")))
        }
        coordinator.process(session, send("再加茄子、辣椒"))
        val updated = requireNotNull(schedules.taskForSession(session))
        assertEquals(original.id, updated.id)
        assertEquals(cover.id, updated.coverAssetId)
        assertEquals("土豆、猕猴桃、茄子、辣椒", updated.detail)
        assertEquals(first.id, schedules.observeDate(day).first().single().id)
        assertEquals(1, schedules.operationHistory(session).count { it.action == ScheduleAction.CREATE })
        val newDraft = conversations.openCreation()
        assertNotEquals(session, newDraft.sessionId)
        assertEquals(4, schedules.recentMessages(session).size) // 新建入口不丢弃已绑定任务的会话。
    }

    @Test fun mixedEnglishTitleSavesFullTitleDetailsAndReminderWithoutRetrying() = runBlocking {
        val title = "关注Predict上Alpha"
        val detail = "留意Predict上Alpha的开盘情况，如果低于预期可以关注机会。"
        behavior = { ScheduleInterpretation.Save(ScheduleDraft(title, detail, day.plusDays(1), LocalTime.of(16, 55))) }
        coordinator.process(session, send("明天下午4:55提醒我，Predict上Alpha开盘，保留开盘观察备注"))
        val task = requireNotNull(schedules.taskForSession(session))
        val card = schedules.observeDate(day.plusDays(1)).first().single()
        assertEquals(title, task.title)
        assertEquals(title, card.title)
        assertEquals(detail, card.detail)
        assertEquals(LocalTime.of(16, 55), card.time)
        val reminders = database.scheduleDao().reminders(card.id)
        assertEquals(listOf(10, 0), reminders.map { it.minutesBefore })
        assertEquals(listOf(LocalTime.of(16, 45), LocalTime.of(16, 55)), reminders.map {
            Instant.ofEpochMilli(it.triggerAt).atZone(zone).toLocalTime()
        })
        assertTrue(schedules.recentMessages(session).last().content.contains("提前10分钟、准点提醒"))
        assertEquals(1, calls)
        assertEquals(1, schedules.operationHistory(session).count { it.action == ScheduleAction.CREATE })
    }

    @Test fun changingTheAdvanceReplacesTheDefaultAndKeepsTheStartReminder() = runBlocking {
        val date = day.plusDays(1)
        schedules.createTask(session, "base", ScheduleDraft("开会", date = date, time = LocalTime.of(16, 0)))
        val occurrence = schedules.observeDate(date).first().single()
        behavior = { input ->
            val patch = JSONObject().put("reminder_minutes", JSONArray().put(8))
            parseScheduleInterpretation(ScheduleTextInterpreterTest.editJson(patch).toString(), input)
        }

        coordinator.process(session, send("改为提前8分钟提醒", occurrence.id))

        val pending = schedules.pendingReminders(0, Long.MAX_VALUE)
        assertEquals(listOf(8, 0), pending.map { it.minutesBefore })
        assertEquals(listOf(LocalTime.of(15, 52), LocalTime.of(16, 0)), pending.map {
            Instant.ofEpochMilli(it.triggerAt).atZone(zone).toLocalTime()
        })
        assertEquals(occurrence.id, pending.map { it.occurrenceId }.distinct().single())
        assertTrue(schedules.recentMessages(session).last().content.contains("提前8分钟、准点提醒"))
    }

    @Test fun clarificationSurvivesDatabaseReopenAndOnlyExplicitlyAnsweredQuestionsResolve() = runBlocking {
        database.close(); database = MebotDatabase.create(context); connect()
        session = conversations.openCreation().sessionId
        behavior = { ScheduleInterpretation.Ask("明天下午几点提醒你开会？") }
        coordinator.process(session, send("明天下午提醒我开会"))
        assertNull(schedules.taskForSession(session))
        val question = conversations.observePendingQuestions(session).first().single()
        database.close(); database = MebotDatabase.create(context); connect()
        behavior = { input ->
            assertEquals(question.id, input.questions.single().id)
            assertEquals(listOf("明天下午提醒我开会", "明天下午几点提醒你开会？", "下午4点，提前10分钟和准点"), input.messages.map { it.content })
            ScheduleInterpretation.Save(ScheduleDraft("团队会议", date = day.plusDays(1), time = LocalTime.of(16, 0), reminderMinutes = listOf(10, 0)), answeredQuestionIds = setOf(question.id))
        }
        coordinator.process(session, send("下午4点，提前10分钟和准点"))
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
        val occurrence = schedules.observeDate(day.plusDays(1)).first().single()
        assertEquals(listOf(10, 0), database.scheduleDao().reminders(occurrence.id).map { it.minutesBefore })
        assertEquals(4, schedules.recentMessages(session).size)
    }

    @Test fun readyRecurrenceStoresMultipleLeadsAndSharesTheConversationAcrossDates() = runBlocking {
        behavior = { ScheduleInterpretation.Save(ScheduleDraft(
            "每天喂猫", date = day, time = LocalTime.of(18, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY), reminderMinutes = listOf(30, 10, 0),
        )) }
        coordinator.process(session, send("每天下午6点喂猫，提前30分钟、10分钟和准点提醒"))
        schedules.materializeRange(day, day.plusDays(2))
        val today = schedules.observeDisplayDate(day).first().single()
        val tomorrow = schedules.observeDisplayDate(day.plusDays(1)).first().single()
        assertEquals(session, today.sessionId); assertEquals(session, tomorrow.sessionId)
        assertNotEquals(today.occurrence.id, tomorrow.occurrence.id)
        assertEquals(listOf(30, 10, 0), database.scheduleDao().reminders(tomorrow.occurrence.id).map { it.minutesBefore })
        assertEquals(1, schedules.observeDateCounts(day, day.plusDays(2)).first().first().count)
    }

    @Test fun weeklyCreationNavigatesToItsFirstActualOccurrenceRatherThanTheStartDay() = runBlocking {
        behavior = { ScheduleInterpretation.Save(ScheduleDraft("周五开会", date = day, time = LocalTime.of(16, 0),
            recurrence = RecurrenceRule(RecurrencePattern.WEEKLY, weekDays = setOf(java.time.DayOfWeek.FRIDAY)))) }
        val result = coordinator.process(session, send("每周五下午4点开会"))
        assertEquals(day.plusDays(1), result.savedDate)
        assertTrue(schedules.observeDate(day).first().isEmpty())
        assertEquals(1, schedules.observeDate(day.plusDays(1)).first().size)
    }

    @Test fun assistantWriteFailureRollsBackTaskAndOperationsAndRetryCannotDuplicate() = runBlocking {
        val id = send("今天买土豆、猕猴桃")
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_assistant BEFORE INSERT ON schedule_messages WHEN NEW.role = 'ASSISTANT'
                BEGIN SELECT RAISE(ABORT, 'assistant write failed'); END
            """.trimIndent())
        }
        assertEquals(ScheduleAnalysisFailure.LOCAL_SAVE, failure { coordinator.process(session, id) }.failure)
        assertNull(schedules.taskForSession(session))
        assertTrue(schedules.operationHistory(session).isEmpty())
        assertEquals(id, conversations.observeAwaitingAnalysis(session).first()?.id)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_assistant") }
        coordinator.process(session, id)
        val count = calls
        coordinator.process(session, id)
        assertEquals(count, calls) // 已有提交结果的消息不再次调用模型。
        assertEquals(1, schedules.observeDate(day).first().size)
        assertEquals(2, schedules.recentMessages(session).size)
    }

    @Test fun secondsAreQuestionedLocallyAndMinuteCorrectionCanThenCreate() = runBlocking {
        for (text in listOf("30s后提醒吃药", "三十秒后提醒喝水", "09:01:30开会", "明天10点30分15秒开会", "提前20秒提醒", "30s")) assertTrue(text, hasSecondPrecisionRequest(text))
        for (text in listOf("买30g盐", "5分钟后提醒喝水", "下午4点开会", "看30秒视频，今天找时间完成")) assertFalse(text, hasSecondPrecisionRequest(text))
        coordinator.process(session, send("30s后提醒吃药"))
        assertEquals(0, calls)
        assertNull(schedules.taskForSession(session))
        val question = conversations.observePendingQuestions(session).first().single()
        assertTrue(question.question.contains("分钟"))
        behavior = { ScheduleInterpretation.Save(ScheduleDraft("吃药", date = day, time = LocalTime.of(10, 5)), answeredQuestionIds = setOf(question.id)) }
        coordinator.process(session, send("改成5分钟后"))
        assertEquals(LocalTime.of(10, 5), schedules.observeDate(day).first().single().time)
    }

    @Test fun invalidModelOutputAndLocalValidationRetryTheSameMessageUntilOneAtomicCreation() = runBlocking {
        val attempts = mutableListOf<Int>()
        behavior = {
            assertNull(schedules.taskForSession(session))
            assertEquals(1, schedules.recentMessages(session).size)
            when (calls) {
                1, 2 -> throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
                3 -> ScheduleInterpretation.Save(ScheduleDraft("一".repeat(31)))
                else -> ScheduleInterpretation.Save(ScheduleDraft("采购食材", "土豆、猕猴桃"))
            }
        }
        val id = send("今天买土豆和猕猴桃")
        val result = coordinator.process(session, id) { attempts += it }
        assertEquals(listOf(1, 2, 3, 4), attempts)
        assertEquals(4, calls)
        val card = schedules.observeDate(day).first().single()
        assertEquals(card.id, result.createdOccurrenceId)
        assertEquals(day, result.savedDate)
        assertEquals("土豆、猕猴桃", card.detail)
        assertEquals(2, schedules.recentMessages(session).size)
        assertEquals(1, schedules.operationHistory(session).count { it.action == ScheduleAction.CREATE })
        assertNull(coordinator.process(session, id).createdOccurrenceId)
        assertEquals(4, calls)
    }

    @Test fun nonValidationFailuresNeverAutomaticallyRetry() = runBlocking {
        val failures = listOf(ScheduleAnalysisFailure.NETWORK, ScheduleAnalysisFailure.TIMEOUT,
            ScheduleAnalysisFailure.AUTHORIZATION, ScheduleAnalysisFailure.BILLING,
            ScheduleAnalysisFailure.RATE_LIMIT, ScheduleAnalysisFailure.SERVICE, ScheduleAnalysisFailure.LOCAL_SAVE)
        failures.forEachIndexed { index, reason ->
            behavior = { throw ScheduleAnalysisException(reason) }
            val id = send("今天买菜，尝试$index")
            assertEquals(reason, failure { coordinator.process(session, id) }.failure)
            assertEquals(index + 1, calls)
            assertNull(schedules.taskForSession(session))
        }
    }

    @Test fun aNewerUserMessageStopsTheValidationRetryBeforeAnotherModelCall() = runBlocking {
        val id = send("今天买菜")
        behavior = {
            send("先不要创建，我要改一下")
            throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
        }
        assertEquals(ScheduleAnalysisFailure.CHANGED, failure { coordinator.process(session, id) }.failure)
        assertEquals(1, calls)
        assertNull(schedules.taskForSession(session))
    }

    @Test fun aChangedTaskStopsTheValidationRetryWithoutRebasingTheOldRequest() = runBlocking {
        val original = schedules.createTask(session, "initial", ScheduleDraft("采购", "保留原内容", day))
        val id = send("补充一些东西")
        behavior = {
            schedules.updateTask(session, "external-edit", ScheduleDraft("采购", "后来保存的内容", day), original.revision)
            throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
        }
        assertEquals(ScheduleAnalysisFailure.CHANGED, failure { coordinator.process(session, id) }.failure)
        assertEquals(1, calls)
        assertEquals("后来保存的内容", schedules.taskForSession(session)?.detail)
    }

    @Test fun cancelingAnAutomaticRetryStopsAllLaterAttempts() = runBlocking {
        val enteredRetry = CompletableDeferred<Unit>()
        behavior = {
            if (calls == 1) throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
            enteredRetry.complete(Unit)
            awaitCancellation()
        }
        val id = send("今天买菜")
        val work = async(Dispatchers.IO) { coordinator.process(session, id) }
        enteredRetry.await()
        work.cancelAndJoin()
        assertEquals(2, calls)
        assertNull(schedules.taskForSession(session))
        assertEquals(1, schedules.recentMessages(session).size)
    }

    @Test fun invalidModelTitleOrPastReminderCannotCreatePartialRecords() = runBlocking {
        behavior = { ScheduleInterpretation.Save(ScheduleDraft("一".repeat(31))) }
        val id = send("帮我安排今天的事情")
        assertEquals(ScheduleAnalysisFailure.INVALID_RESULT, failure { coordinator.process(session, id) }.failure)
        assertEquals(ScheduleAnalysisMaxAttempts, calls)
        assertNull(schedules.taskForSession(session))
        assertEquals(1, schedules.recentMessages(session).size)
        behavior = { ScheduleInterpretation.Save(ScheduleDraft("开会", date = day, time = LocalTime.of(9, 0))) }
        coordinator.process(session, id)
        assertNull(schedules.taskForSession(session))
        assertTrue(conversations.observePendingQuestions(session).first().single().question.contains("已经过去"))
    }

    @Test fun clearingDraftWhileModelWaitsPreventsLateTaskCreation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        behavior = { started.complete(Unit); release.await(); ScheduleInterpretation.Save(ScheduleDraft("旧草稿任务")) }
        val id = send("今天做旧任务")
        val work = async(Dispatchers.Default) { runCatching { coordinator.process(session, id) } }
        started.await()
        val fresh = conversations.clearCreation()
        release.complete(Unit)
        assertEquals(ScheduleAnalysisFailure.CHANGED, (work.await().exceptionOrNull() as ScheduleAnalysisException).failure)
        assertNull(database.scheduleDao().session(session))
        assertNull(schedules.taskForSession(fresh.sessionId))
        assertTrue(schedules.observeDate(day).first().isEmpty())
    }

    @Test fun newerUserMessageAndTaskRevisionEachRejectAnOutdatedModelResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        behavior = { started.complete(Unit); release.await(); ScheduleInterpretation.Save(ScheduleDraft("旧解析结果")) }
        val oldMessage = send("买土豆")
        val oldWork = async(Dispatchers.Default) { runCatching { coordinator.process(session, oldMessage) } }
        started.await()
        val newer = send("再买茄子")
        release.complete(Unit)
        assertEquals(ScheduleAnalysisFailure.CHANGED, (oldWork.await().exceptionOrNull() as ScheduleAnalysisException).failure)
        behavior = { ScheduleInterpretation.Save(ScheduleDraft("采购食材", "土豆、茄子")) }
        coordinator.process(session, newer)
        val task = requireNotNull(schedules.taskForSession(session))
        val nextStarted = CompletableDeferred<Unit>(); val nextRelease = CompletableDeferred<Unit>()
        behavior = { nextStarted.complete(Unit); nextRelease.await(); ScheduleInterpretation.Save(ScheduleDraft("迟到的修改")) }
        val edit = send("改一下标题")
        val editing = async(Dispatchers.Default) { runCatching { coordinator.process(session, edit) } }
        nextStarted.await()
        schedules.updateTask(session, "external-change", ScheduleDraft("保留较新修改", date = day), task.revision)
        nextRelease.complete(Unit)
        assertEquals(ScheduleAnalysisFailure.CHANGED, (editing.await().exceptionOrNull() as ScheduleAnalysisException).failure)
        assertEquals("保留较新修改", schedules.taskForSession(session)?.title)
    }

    @Test fun periodicEditsRequireAnExplicitSupportedScope() = runBlocking {
        val rule = RecurrenceRule(RecurrencePattern.DAILY)
        behavior = { ScheduleInterpretation.Save(ScheduleDraft("喂猫", date = day, time = LocalTime.of(18, 0), recurrence = rule)) }
        coordinator.process(session, send("每天18点喂猫"))
        val first = requireNotNull(schedules.taskForSession(session))
        val changed = ScheduleDraft("喂猫", date = day, time = LocalTime.of(19, 0), recurrence = rule)
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(changed, setOf("time"))) }
        coordinator.process(session, send("改成19点"))
        assertEquals(first.revision, schedules.taskForSession(session)?.revision)
        assertTrue(conversations.observePendingQuestions(session).first().single().question.contains("范围"))
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(changed, setOf("time")), ScheduleEditScope.FUTURE) }
        coordinator.process(session, send("从今天起及之后都改"))
        assertEquals(LocalTime.of(19, 0), schedules.taskForSession(session)?.time)
    }

    @Test fun contextIsLimitedToThisConversationAndQueriesOrDeleteRequestsDoNotCreate() = runBlocking {
        val other = schedules.openSession()
        schedules.appendMessage(other.id, "private-other-message", MessageRole.USER, "别的任务的私密内容")
        behavior = { input ->
            assertTrue(input.messages.all { it.sessionId == session })
            assertFalse(input.messages.any { "私密" in it.content })
            ScheduleInterpretation.Reject(UnsupportedScheduleRequest.QUERY)
        }
        coordinator.process(session, send("当前是什么任务"))
        assertNull(schedules.taskForSession(session))
        behavior = { ScheduleInterpretation.Delete() }
        coordinator.process(session, send("删除这个任务"))
        assertNull(schedules.taskForSession(session))
        assertTrue(schedules.recentMessages(session).last().content.contains("还没有创建"))
    }

    @Test fun timeOnlyPatchPreservesTitleDetailsDateCoverAndEveryReminder() = runBlocking {
        val cover = LocalMediaStore(context, database, clock).builtin(BuiltinCover.MEETING)
        val original = ScheduleDraft("项目例会", "带电脑和会议资料", day.plusDays(1), LocalTime.of(16, 0), reminderMinutes = listOf(30, 10, 0), coverAssetId = cover.id)
        val task = schedules.createTask(session, "base", original)
        val card = schedules.observeDate(day.plusDays(1)).first().single()
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("不应被改名", "不应覆盖详情", day, LocalTime.of(17, 0)), setOf("time"))) }
        coordinator.process(session, send("改成五点", card.id))
        val updated = requireNotNull(schedules.taskForSession(session))
        assertEquals(task.id, updated.id); assertEquals(original.title, updated.title); assertEquals(original.detail, updated.detail)
        assertEquals(original.date, updated.date); assertEquals(original.coverAssetId, updated.coverAssetId)
        assertEquals(LocalTime.of(17, 0), updated.time)
        assertEquals(listOf(30, 10, 0), schedules.reminderMinutesForSession(session))
        assertEquals(card.id, schedules.observeDate(day.plusDays(1)).first().single().id)
    }

    @Test fun changingToDateOnlyCancelsAlarmsWithoutTouchingOtherFields() = runBlocking {
        schedules.createTask(session, "base", ScheduleDraft("明天开会", "地点不变", day.plusDays(1), LocalTime.of(16, 0), reminderMinutes = listOf(10, 0)))
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", time = null), setOf("time"))) }
        coordinator.process(session, send("取消具体时间，明天找空完成"))
        val task = requireNotNull(schedules.taskForSession(session))
        assertNull(task.time); assertEquals("地点不变", task.detail); assertEquals(day.plusDays(1), task.date)
        assertTrue(schedules.reminderMinutesForSession(session).isEmpty())
        assertTrue(schedules.pendingReminders(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun selectedRecurringCardCanChangeOnceAndKeepIndependentLeadsAfterRestore() = runBlocking {
        schedules.createTask(session, "base", ScheduleDraft("喂猫", "准备猫粮", day, LocalTime.of(18, 0), RecurrenceRule(RecurrencePattern.DAILY), listOf(30, 0)))
        schedules.materializeRange(day, day.plusDays(2))
        val selected = schedules.observeDate(day.plusDays(1)).first().single()
        behavior = {
            assertEquals(day.plusDays(1), it.selected?.date)
            ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", time = LocalTime.of(19, 0), reminderMinutes = listOf(15, 0)), setOf("time", "reminder_minutes")), ScheduleEditScope.ONCE)
        }
        coordinator.process(session, send("只改这次，19点，提前15分钟和准点提醒", selected.id))
        val changed = requireNotNull(database.scheduleDao().occurrence(selected.id))
        assertEquals(LocalTime.of(19, 0), changed.time)
        assertTrue(changed.isException)
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(day).first().single().time)
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(day.plusDays(2)).first().single().time)
        val completed = schedules.setCompleted(session, selected.id, true, "complete", changed.revision)
        schedules.setCompleted(session, selected.id, false, "restore", completed.revision)
        val row = requireNotNull(database.scheduleDao().occurrence(selected.id))
        assertEquals(listOf(15, 0), database.scheduleDao().reminders(row.id).filter { it.occurrenceRevision == row.revision }.map { it.minutesBefore })
        schedules.materializeRange(day, day.plusDays(2))
        assertEquals(LocalTime.of(19, 0), schedules.observeDate(day.plusDays(1)).first().single().time)
    }

    @Test fun futureTimePatchKeepsSingleOccurrenceNotesAndCompletedHistory() = runBlocking {
        val root = schedules.createTask(session, "base", ScheduleDraft("喂猫", "常规猫粮", day, LocalTime.of(18, 0), RecurrenceRule(RecurrencePattern.DAILY), listOf(30, 0)))
        schedules.materializeRange(day, day.plusDays(2))
        val today = schedules.observeDate(day).first().single()
        schedules.setCompleted(session, today.id, true, "complete", today.revision)
        val special = schedules.observeDate(day.plusDays(1)).first().single()
        schedules.editOccurrence(session, special.id, SchedulePatch(ScheduleDraft("忽略", "特殊猫粮", reminderMinutes = listOf(15, 0)), setOf("detail", "reminder_minutes")), "special", special.revision)
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", time = LocalTime.of(19, 0)), setOf("time")), ScheduleEditScope.FUTURE) }
        coordinator.process(session, send("从今天起及之后都改成19点"))
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(day).first().single().time)
        assertEquals(LocalTime.of(19, 0), schedules.observeDate(day.plusDays(2)).first().single().time)
        val changed = requireNotNull(database.scheduleDao().occurrence(special.id))
        assertEquals("特殊猫粮", changed.detail); assertEquals(LocalTime.of(19, 0), changed.time)
        assertEquals(listOf(15, 0), schedules.occurrenceDraft(changed).reminderMinutes)
        assertEquals(root.id, schedules.taskForSession(session)?.id)
    }

    @Test fun deletingOneOccurrencePhysicallyRemovesItAndExclusionSurvivesReopen() = runBlocking {
        database.close(); database = MebotDatabase.create(context); connect()
        session = conversations.openCreation().sessionId
        val task = schedules.createTask(session, "base", ScheduleDraft("喂猫", date = day, time = LocalTime.of(18, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        schedules.materializeRange(day, day.plusDays(2))
        val selected = schedules.observeDate(day.plusDays(1)).first().single()
        behavior = { ScheduleInterpretation.Delete(ScheduleEditScope.ONCE) }
        val result = coordinator.process(session, send("删除本次日程", selected.id))
        assertEquals(selected.id, result.deletedOccurrenceId)
        assertNull(database.scheduleDao().occurrence(selected.id))
        assertTrue(database.scheduleDao().reminders(selected.id).isEmpty())
        assertEquals(task.id, schedules.taskForSession(session)?.id)
        database.close(); database = MebotDatabase.create(context); connect()
        schedules.materializeRange(day, day.plusDays(2))
        assertTrue(schedules.observeDate(day.plusDays(1)).first().isEmpty())
        assertEquals(1, schedules.observeDate(day).first().size)
        assertEquals(1, schedules.observeDate(day.plusDays(2)).first().size)
    }

    @Test fun directDeleteRemovesEntireConversationAndFailureRollsEverythingBack() = runBlocking {
        val task = schedules.createTask(session, "base", ScheduleDraft("购物", date = day.plusDays(1), time = LocalTime.of(16, 0)))
        val id = send("删除这个日程")
        behavior = { ScheduleInterpretation.Delete() }
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_delete BEFORE DELETE ON schedule_tasks BEGIN SELECT RAISE(ABORT, 'fail deletion'); END") }
        assertEquals(ScheduleAnalysisFailure.LOCAL_SAVE, failure { coordinator.process(session, id) }.failure)
        assertEquals(task.id, schedules.taskForSession(session)?.id)
        assertEquals(id, conversations.observeAwaitingAnalysis(session).first()?.id)
        assertEquals(1, schedules.observeDate(day.plusDays(1)).first().size)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_delete") }
        val deleted = coordinator.process(session, id)
        assertEquals(session, deleted.deletedSessionId)
        assertNull(database.scheduleDao().session(session))
        assertTrue(schedules.recentMessages(session).isEmpty()); assertTrue(schedules.operationHistory(session).isEmpty())
        assertNull(database.conversationDao().draft(session))
        assertTrue(schedules.observeDate(day.plusDays(1)).first().isEmpty())
        schedules.deleteTask(session, "retry", task.revision)
        assertNull(database.scheduleDao().session(session))
    }

    @Test fun unclearPeriodicDeleteAsksScopeThenWholeCycleDeletionLeavesNoHistory() = runBlocking {
        val task = schedules.createTask(session, "base", ScheduleDraft("喂猫", date = day, recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        schedules.materializeRange(day, day.plusDays(2))
        behavior = { ScheduleInterpretation.Delete() }
        coordinator.process(session, send("删除这个日程"))
        assertEquals(task.id, schedules.taskForSession(session)?.id)
        assertTrue(conversations.observePendingQuestions(session).first().single().question.contains("范围"))
        behavior = { ScheduleInterpretation.Delete(ScheduleEditScope.ALL) }
        coordinator.process(session, send("整个周期"))
        assertNull(database.scheduleDao().session(session))
        assertTrue(schedules.history(day, day.plusDays(30)).isEmpty())
        schedules.materializeRange(day, day.plusDays(30))
        assertTrue(schedules.history(day, day.plusDays(30)).isEmpty())
    }

    @Test fun modelCannotDeleteOnNegationItemRemovalOrAQuery() = runBlocking {
        val task = schedules.createTask(session, "base", ScheduleDraft("采购食材", "土豆、茄子", day))
        behavior = { ScheduleInterpretation.Delete() }
        for (text in listOf("不要删除这个日程", "怎么删除这个日程", "删除采购清单里的土豆", "把土豆删掉")) {
            coordinator.process(session, send(text))
            assertEquals(task.id, schedules.taskForSession(session)?.id)
        }
    }

    @Test fun editingARecurringTaskWithAPastStartUsesItsNextFutureOccurrence() = runBlocking {
        schedules.createTask(session, "base", ScheduleDraft("喂猫", date = day.minusDays(3), time = LocalTime.of(9, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY), reminderMinutes = listOf(30, 0)))
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", time = LocalTime.of(8, 0)), setOf("time")), ScheduleEditScope.FUTURE) }
        val result = coordinator.process(session, send("从今天起都改成早上8点"))
        assertEquals(day.plusDays(1), result.savedDate)
        assertEquals(LocalTime.of(8, 0), schedules.observeDate(day.plusDays(1)).first().single().time)
        assertEquals(listOf(30, 0), schedules.reminderMinutesForSession(session))
    }

    @Test fun stopPreservesSessionHistoryAndCoverWhileDeleteStillRemovesEverything() = runBlocking {
        val cover = LocalMediaStore(context, database, clock).builtin(BuiltinCover.GROCERIES)
        val task = schedules.createTask(session, "base", ScheduleDraft("喂猫", date = day.minusDays(2), time = LocalTime.of(18, 0),
            recurrence = RecurrenceRule(RecurrencePattern.DAILY), coverAssetId = cover.id))
        schedules.materializeRange(day.minusDays(2), day.plusDays(3))
        behavior = { ScheduleInterpretation.Stop() }
        val message = send("停止整个周期")
        coordinator.process(session, message)
        assertTrue(schedules.recurrenceForSession(session)?.enabled == false)
        assertTrue(schedules.observeDate(day.plusDays(1)).first().isEmpty())
        assertEquals(cover.id, schedules.taskForSession(session)?.coverAssetId)
        assertEquals(2, schedules.recentMessages(session).size)
        val revision = schedules.taskForSession(session)?.revision
        coordinator.process(session, message)
        assertEquals(revision, schedules.taskForSession(session)?.revision)
        behavior = { ScheduleInterpretation.Delete(ScheduleEditScope.ALL) }
        coordinator.process(session, send("删除整个周期"))
        assertNull(database.scheduleDao().session(session))
        assertNull(database.scheduleDao().task(task.id))
        assertTrue(schedules.recentMessages(session).isEmpty())
    }

    @Test fun stopCannotBeExecutedOnNegationQueriesOrNotificationOnlyCommands() = runBlocking {
        val task = schedules.createTask(session, "base", ScheduleDraft("喂猫", date = day, time = LocalTime.of(18, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY)))
        behavior = { ScheduleInterpretation.Stop() }
        for (text in listOf("不要停止这个周期", "怎么停止整个周期", "停止这个任务的提醒", "暂停闹钟", "以后不需要提醒了", "只暂停本次", "把时间改成20点")) {
            coordinator.process(session, send(text))
            assertTrue(schedules.recurrenceForSession(session)?.enabled == true)
            assertEquals(task.revision, schedules.taskForSession(session)?.revision)
        }
    }

    @Test fun unavailableRecurrenceRangeAsksInsteadOfSavingAnInvisibleEdit() = runBlocking {
        val rule = RecurrenceRule(RecurrencePattern.DAILY, endDate = day.plusDays(2))
        val task = schedules.createTask(session, "base", ScheduleDraft("喂猫", date = day, time = LocalTime.of(18, 0), recurrence = rule))
        behavior = { ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", time = LocalTime.of(20, 0)), setOf("time")), ScheduleEditScope.FUTURE, day.plusDays(5)) }
        coordinator.process(session, send("从下周开始都改到20点"))
        assertEquals(task.revision, schedules.taskForSession(session)?.revision)
        assertTrue(conversations.observePendingQuestions(session).first().single().question.contains("没有后续日程"))
    }

    private suspend fun send(text: String, occurrenceId: String? = null): String {
        val draft = conversations.openConversation(session)
        val saved = conversations.saveInput(session, text, draft.revision)
        val id = UUID.randomUUID().toString()
        conversations.sendUserMessage(session, id, text, saved.revision, occurrenceId)
        return id
    }
    private suspend fun failure(block: suspend () -> Unit): ScheduleAnalysisException {
        val result = runCatching { block() }.exceptionOrNull()
        assertTrue(result is ScheduleAnalysisException)
        return result as ScheduleAnalysisException
    }
}
