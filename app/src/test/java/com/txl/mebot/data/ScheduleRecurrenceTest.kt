package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ReminderStatus
import com.txl.mebot.data.media.BuiltinCover
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.*
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 在真实 SQLite 上验证周期范围、懒展开和停止后的持久化，不使用手机或付费模型。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduleRecurrenceTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var conversations: ScheduleConversationRepository
    private lateinit var session: String
    private val day = LocalDate.of(2026, 10, 22)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = TestClock(day.atTime(10, 0).atZone(zone).toInstant(), zone)

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        connect()
        session = conversations.openCreation().sessionId
    }

    private fun connect() {
        schedules = ScheduleRepository(db, clock) { zone }
        conversations = ScheduleConversationRepository(db, schedules, clock)
    }

    @After fun tearDown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }

    private fun draft(rule: RecurrenceRule = RecurrenceRule(RecurrencePattern.DAILY)) =
        ScheduleDraft("喂猫", "常规猫粮", day, LocalTime.of(18, 0), rule, listOf(30, 0))

    @Test fun futureBoundaryKeepsEarlierDatesAndLazyHistoryAndSharesCoverAndChat() = runBlocking {
        val cover = LocalMediaStore(context, db, clock).builtin(BuiltinCover.GROCERIES)
        val base = draft().copy(coverAssetId = cover.id)
        val task = schedules.createTask(session, "create", base)
        schedules.appendMessage(session, "initial", MessageRole.USER, "从今天起每天喂猫")
        // 故意不展开第 1 天，后续读取时仍须恢复原版本。
        schedules.materializeRange(day.plusDays(2), day.plusDays(4))
        val existing = schedules.observeDate(day.plusDays(3)).first().single()
        schedules.setCompleted(session, existing.id, true, "complete", existing.revision)
        schedules.updateTask(session, "future", base.copy(time = LocalTime.of(20, 0)), task.revision, setOf("time"), day.plusDays(2))
        schedules.materializeRange(day, day.plusDays(6))
        for (offset in 0L..6L) {
            val card = schedules.observeDisplayDate(day.plusDays(offset)).first().single()
            assertEquals(if (offset < 2 || offset == 3L) LocalTime.of(18, 0) else LocalTime.of(20, 0), card.occurrence.time)
            assertEquals(session, card.sessionId)
            assertEquals(cover.id, card.occurrence.coverAssetId)
            assertEquals(RecurrencePattern.DAILY, card.recurrence?.pattern)
        }
        assertEquals(1, schedules.recentMessages(session).size)
        assertEquals(1L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); it.getLong(0) })
        db.close(); db = MebotDatabase.create(context); connect()
        schedules.materializeRange(day, day.plusDays(8))
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(day.plusDays(1)).first().single().time)
        assertEquals(LocalTime.of(20, 0), schedules.observeDate(day.plusDays(8)).first().single().time)
    }

    @Test fun scheduledChangesUseTheTemplateEffectiveAtEachBoundary() = runBlocking {
        val first = schedules.createTask(session, "create", draft())
        val second = schedules.updateTask(session, "later", draft().copy(time = LocalTime.of(21, 0)), first.revision, setOf("time"), day.plusDays(5))
        assertEquals(LocalTime.of(18, 0), schedules.draftForDate(session, day.plusDays(2)).time)
        val earlier = schedules.draftForDate(session, day.plusDays(2)).copy(detail = "换新猫粮")
        schedules.updateTask(session, "earlier", earlier, second.revision, setOf("detail"), day.plusDays(2))
        schedules.materializeRange(day, day.plusDays(8))
        assertEquals("常规猫粮", schedules.observeDate(day.plusDays(1)).first().single().detail)
        assertEquals("换新猫粮", schedules.observeDate(day.plusDays(2)).first().single().detail)
        // 最新一次“该次及以后”的要求覆盖更早安排的后续版本，未改字段以范围起点为准。
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(day.plusDays(7)).first().single().time)
    }

    @Test fun intervalPhaseIsPreservedWhenOnlyTimeChangesFromAMiddleDate() = runBlocking {
        val base = draft(RecurrenceRule(RecurrencePattern.INTERVAL, intervalDays = 3))
        val task = schedules.createTask(session, "create", base)
        schedules.updateTask(session, "future", base.copy(time = LocalTime.of(20, 0)), task.revision, setOf("time"), day.plusDays(4))
        schedules.materializeRange(day, day.plusDays(10))
        val cards = schedules.history(day, day.plusDays(10)).sortedBy { it.date }
        assertEquals(listOf(0L, 3L, 6L, 9L).map(day::plusDays), cards.map { it.date })
        assertEquals(listOf(18, 18, 20, 20), cards.map { it.time?.hour })
    }

    @Test fun singleMovedExceptionRetainsItsDateAndNotesAfterFutureTimeEdit() = runBlocking {
        val task = schedules.createTask(session, "create", draft())
        schedules.materializeRange(day, day.plusDays(5))
        val original = schedules.observeDate(day.plusDays(2)).first().single()
        schedules.editOccurrence(session, original.id, SchedulePatch(ScheduleDraft("忽略", "单次换粮", day.plusDays(4), reminderMinutes = listOf(15, 0)), setOf("date", "detail", "reminder_minutes")), "move-once", original.revision)
        schedules.updateTask(session, "future", draft().copy(time = LocalTime.of(20, 0)), task.revision, setOf("time"), day.plusDays(2))
        schedules.materializeRange(day, day.plusDays(6))
        assertTrue(schedules.observeDate(day.plusDays(2)).first().isEmpty())
        val moved = requireNotNull(db.scheduleDao().occurrence(original.id))
        assertEquals(day.plusDays(4), moved.date)
        assertEquals("单次换粮", moved.detail)
        assertEquals(LocalTime.of(20, 0), moved.time)
        assertEquals(listOf(15, 0), schedules.occurrenceDraft(moved).reminderMinutes)
        assertEquals(2, schedules.observeDate(day.plusDays(4)).first().size)
        assertEquals(7, schedules.history(day, day.plusDays(6)).size)
    }

    @Test fun weekdayChangeSuppressesInvalidFutureDatesAndKeepsCompletedRecords() = runBlocking {
        val task = schedules.createTask(session, "create", draft())
        schedules.materializeRange(day, day.plusDays(7))
        val completed = schedules.observeDate(day.plusDays(3)).first().single()
        schedules.setCompleted(session, completed.id, true, "done", completed.revision)
        val rule = RecurrenceRule(RecurrencePattern.WEEKLY, weekDays = setOf(DayOfWeek.MONDAY))
        schedules.updateTask(session, "weekly", draft(rule), task.revision, setOf("recurrence"), day.plusDays(2))
        schedules.materializeRange(day, day.plusDays(12))
        assertEquals(setOf(day, day.plusDays(1), day.plusDays(3), day.plusDays(4), day.plusDays(11)), schedules.history(day, day.plusDays(12)).map { it.date }.toSet())
        assertTrue(schedules.observeDate(day.plusDays(2)).first().isEmpty())
        assertNotNull(db.scheduleDao().occurrence(completed.id)?.completedAt)
    }

    @Test fun stoppingCancelsUnfinishedFutureIncludingMovedCardsAndKeepsHistoryAfterReopen() = runBlocking {
        val task = schedules.createTask(session, "create", draft())
        schedules.appendMessage(session, "initial", MessageRole.USER, "每天18点喂猫")
        schedules.materializeRange(day, day.plusDays(6))
        val moved = schedules.observeDate(day).first().single()
        schedules.editOccurrence(session, moved.id, SchedulePatch(ScheduleDraft("忽略", date = day.plusDays(5)), setOf("date")), "move", moved.revision)
        val completed = schedules.observeDate(day.plusDays(4)).first().single()
        schedules.setCompleted(session, completed.id, true, "done", completed.revision)
        clock.current = day.plusDays(2).atTime(10, 0).atZone(zone).toInstant()
        schedules.stopRecurrence(session, "stop", task.revision)
        assertTrue(schedules.pendingReminders(clock.millis(), Long.MAX_VALUE).isEmpty())
        assertTrue(schedules.observeDate(day.plusDays(5)).first().isEmpty())
        assertEquals(listOf(day.plusDays(4), day.plusDays(1)), schedules.history(day, day.plusDays(6)).map { it.date })
        assertTrue(schedules.observeDisplayRecord(completed.id).first()?.recurrenceStopped == true)
        assertNotNull(conversations.openForOccurrence(completed.id))
        db.close(); db = MebotDatabase.create(context); connect()
        schedules.materializeRange(day, day.plusDays(30))
        assertEquals(listOf(day.plusDays(4), day.plusDays(1)), schedules.history(day, day.plusDays(30)).map { it.date })
        assertEquals(1, schedules.recentMessages(session).size)
        assertTrue(schedules.recurrenceForSession(session)?.enabled == false)
        assertFalse(schedules.pendingReminders(0, Long.MAX_VALUE).any { it.status == ReminderStatus.PENDING })
    }

    @Test fun stoppingKeepsPreviouslyUnexpandedPastDatesButNeverGeneratesFutureAgain() = runBlocking {
        val task = schedules.createTask(session, "create", draft())
        clock.current = day.plusDays(4).atTime(10, 0).atZone(zone).toInstant()
        schedules.stopRecurrence(session, "stop", task.revision)
        schedules.materializeRange(day, day.plusDays(20))
        assertEquals((0L..3L).map(day::plusDays), schedules.history(day, day.plusDays(20)).map { it.date }.sorted())
        assertNotNull(db.scheduleDao().session(session))
    }

    @Test fun stopFailureRollsBackRuleCardsAndPendingRemindersAndRetryIsIdempotent() = runBlocking {
        val task = schedules.createTask(session, "create", draft())
        schedules.materializeRange(day, day.plusDays(2))
        val cards = schedules.history(day, day.plusDays(2))
        val reminders = schedules.pendingReminders(0, Long.MAX_VALUE)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_stop BEFORE UPDATE ON schedule_occurrences BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { schedules.stopRecurrence(session, "stop", task.revision) }.isFailure)
        assertTrue(schedules.recurrenceForSession(session)?.enabled == true)
        assertEquals(cards, schedules.history(day, day.plusDays(2)))
        assertEquals(reminders, schedules.pendingReminders(0, Long.MAX_VALUE))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stop")
        val stopped = schedules.stopRecurrence(session, "stop", task.revision)
        clock.current = clock.current.plusSeconds(86_400)
        assertEquals(stopped.revision, schedules.stopRecurrence(session, "stop", task.revision).revision)
        assertTrue(schedules.pendingReminders(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun conversationPrioritizesTheClickedOccurrenceEvenAfterItIsRescheduled() = runBlocking {
        schedules.createTask(session, "create", draft())
        schedules.materializeRange(day, day.plusDays(3))
        val selected = schedules.observeDate(day.plusDays(2)).first().single()
        assertEquals(selected.id, schedules.observeConversationRecord(session, selected.id).first()?.occurrence?.id)
        val changed = schedules.editOccurrence(session, selected.id, SchedulePatch(ScheduleDraft("忽略", date = day.plusDays(5)), setOf("date")), "move", selected.revision)
        assertEquals(changed.date, schedules.observeConversationRecord(session, selected.id).first()?.occurrence?.date)
        schedules.deleteOccurrence(session, selected.id, changed.revision)
        assertNotNull(schedules.observeConversationRecord(session, selected.id).first())
    }

    @Test fun firstModifiedCardSkipsCompletedAndDeletedOccurrencesWithoutChangingTheirHistory() = runBlocking {
        val task = schedules.createTask(session, "create", draft())
        schedules.materializeRange(day, day.plusDays(1))
        val completed = schedules.observeDate(day).first().single()
        schedules.setCompleted(session, completed.id, true, "done", completed.revision)
        val deleted = schedules.observeDate(day.plusDays(1)).first().single()
        schedules.deleteOccurrence(session, deleted.id, deleted.revision)
        schedules.updateTask(session, "future", draft().copy(time = LocalTime.of(20, 0)), task.revision, setOf("time"), day)
        assertNotNull(db.scheduleDao().occurrence(completed.id)?.completedAt)
        assertTrue(schedules.observeDate(day.plusDays(1)).first().isEmpty())
        assertEquals(LocalTime.of(20, 0), schedules.observeDate(day.plusDays(2)).first().single().time)
    }

    @Test fun coordinatorUsesClickedOccurrenceAsFutureBoundaryAndExplicitDateOverridesIt() = runBlocking {
        schedules.createTask(session, "create", draft())
        schedules.materializeRange(day, day.plusDays(4))
        val selected = schedules.observeDate(day.plusDays(2)).first().single()
        suspend fun send(text: String): String {
            val id = UUID.randomUUID().toString()
            val input = conversations.openConversation(session)
            val saved = conversations.saveInput(session, text, input.revision)
            conversations.sendUserMessage(session, id, text, saved.revision, selected.id)
            return id
        }
        var target: LocalDate? = null
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
            assertEquals(selected.date, it.selected?.date)
            ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", time = LocalTime.of(20, 0)), setOf("time")), ScheduleEditScope.FUTURE, target)
        }, clock) { zone }
        coordinator.process(session, send("本次和之后都改成20点"))
        assertEquals(LocalTime.of(18, 0), schedules.observeDate(day.plusDays(1)).first().single().time)
        assertEquals(LocalTime.of(20, 0), schedules.observeDate(day.plusDays(2)).first().single().time)
        assertTrue(schedules.recentMessages(session).last().content.contains("10月24日"))
        target = day
        coordinator.process(session, send("从今天起全部改成20点"))
        assertEquals(LocalTime.of(20, 0), schedules.observeDate(day).first().single().time)
    }

    private class TestClock(var current: Instant, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = TestClock(current, zone)
    }
}
