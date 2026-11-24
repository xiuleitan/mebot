package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.*
import com.txl.mebot.data.schedule.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeScheduleRescheduleTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var schedules: ScheduleRepository
    private lateinit var home: HomeRepository
    private val today = LocalDate.of(2026, 10, 31)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), ZoneOffset.UTC)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        openDatabase()
    }
    private fun openDatabase() {
        db = MebotDatabase.create(context)
        schedules = ScheduleRepository(db, clock) { zone }
        home = HomeRepository(db, schedules, FlashnoteRepository(db, clock), DayimaRepository(db, clock) { zone }, clock) { zone }
    }
    @After fun close() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private suspend fun create(draft: ScheduleDraft): ScheduleTaskEntity {
        val session = schedules.openSession()
        return schedules.createTask(session.id, "create:${session.id}", draft)
    }
    private suspend fun snapshot(): HomeTimelineData = withTimeout(10_000) {
        home.prepare(today.minusDays(3), today.plusDays(1), today)
        home.observe(today.minusDays(3), today.plusDays(1), today).first()
    }
    private fun HomeTimelineData.cards() = days.flatMap { it.slots }.flatMap { it.cards }
    private fun HomeTimelineData.on(day: LocalDate) = cards().filter { it.day == day }
    private fun HomeTimelineData.datesFor(id: String) = cards().filter { it.sourceId == id }.map { it.day }
    private suspend fun update(task: ScheduleTaskEntity, draft: ScheduleDraft) =
        schedules.updateTask(task.sessionId, "update:${task.id}:${task.revision}", draft, task.revision)

    @Test fun ordinaryRescheduleRemovesOldDateAfterReopenAndCanMoveBackWithoutDuplicates() = runBlocking {
        val draft = ScheduleDraft("取快递", date = today, time = LocalTime.of(19, 0), reminderMinutes = listOf(0))
        val task = create(draft)
        val before = snapshot().on(today).single()
        val oldHistory = db.home().event("schedule:${before.sourceId}:$today")
        assertNotNull(oldHistory)
        update(task, draft.copy(date = today.plusDays(1)))
        val after = snapshot()
        assertTrue(after.on(today).isEmpty())
        assertEquals(listOf(today.plusDays(1)), after.datesFor(before.sourceId))
        assertEquals(schedules.observeDisplayDate(today.plusDays(1)).first().single(), after.on(today.plusDays(1)).single().schedule)
        // 手机已经保存的旧记录也必须正确处理；不靠清空历史或重新创建数据库修复。
        assertEquals(oldHistory, db.home().event(oldHistory!!.id))
        db.close(); openDatabase()
        assertEquals(listOf(today.plusDays(1)), snapshot().datesFor(before.sourceId))
        val current = db.scheduleDao().task(task.id)!!
        update(current, draft)
        val restored = snapshot()
        assertEquals(listOf(today), restored.datesFor(before.sourceId))
        assertTrue(restored.on(today.plusDays(1)).isEmpty())
    }

    @Test fun movingOneRecurringOccurrenceKeepsTheOtherRealOccurrenceOfTheSameSeries() = runBlocking {
        val draft = ScheduleDraft("每天取快递", date = today, time = LocalTime.of(19, 0), reminderMinutes = listOf(0),
            recurrence = RecurrenceRule(RecurrencePattern.DAILY))
        val task = create(draft)
        val original = snapshot().on(today).single()
        val tomorrow = schedules.observeDate(today.plusDays(1)).first().single()
        val moved = schedules.editOccurrence(task.sessionId, original.sourceId,
            SchedulePatch(draft.copy(date = today.plusDays(1)), setOf("date")), "edit-once", original.revision)
        val after = snapshot()
        assertTrue(after.on(today).isEmpty())
        assertEquals(setOf(moved.id, tomorrow.id), after.on(today.plusDays(1)).map { it.sourceId }.toSet())
        assertEquals(2, after.on(today.plusDays(1)).size)
        assertTrue(after.on(today.plusDays(1)).all { it.schedule!!.occurrence.date == it.day })
    }

    @Test fun changingTimeAcrossMidnightAndRemovingAdvanceRemindersInvalidateOldHomeDates() = runBlocking {
        val draft = ScheduleDraft("凌晨取快递", date = today.plusDays(1), time = LocalTime.of(0, 30), reminderMinutes = listOf(60, 0))
        val task = create(draft)
        val original = snapshot().on(today).single()
        val later = update(task, draft.copy(time = LocalTime.of(2, 0)))
        assertEquals(listOf(today.plusDays(1)), snapshot().datesFor(original.sourceId))
        val earlier = update(later, draft)
        assertEquals(setOf(today, today.plusDays(1)), snapshot().datesFor(original.sourceId).toSet())
        val noAdvance = update(earlier, draft.copy(reminderMinutes = listOf(0)))
        assertEquals(listOf(today.plusDays(1)), snapshot().datesFor(original.sourceId))
        update(noAdvance, draft.copy(time = null, reminderMinutes = emptyList()))
        assertEquals(listOf(today.plusDays(1)), snapshot().datesFor(original.sourceId))
    }

    @Test fun validAdvanceRemindersStillCollapseAndKeepHistoryThroughContentEditsCompletionAndRestore() = runBlocking {
        val draft = ScheduleDraft("明天的提醒", date = today.plusDays(1), time = LocalTime.of(12, 0),
            reminderMinutes = listOf(24 * 60, 25 * 60, 0))
        val task = create(draft)
        val original = snapshot().on(today).single()
        val changedDraft = draft.copy(title = "新的标题", detail = "更新内容，不改提醒日期")
        val changedTask = update(task, changedDraft)
        var data = snapshot()
        assertEquals(setOf(today, today.plusDays(1)), data.datesFor(original.sourceId).toSet())
        assertEquals(2, data.cards().size)
        assertTrue(data.cards().all { it.title == changedDraft.title })
        home.setCompleted(data.on(today).single(), true)
        // 完成会取消系统提醒，但不能丢掉确实出现过的提前提醒历史。
        update(changedTask, changedDraft.copy(detail = "完成后仍可修改说明"))
        data = snapshot()
        assertEquals(2, data.cards().size)
        assertTrue(data.cards().all { it.completed })
        home.setCompleted(data.on(today).single(), false)
        data = snapshot()
        assertEquals(2, data.cards().size)
        assertTrue(data.cards().none { it.completed })
    }

    @Test fun completedRescheduleAndMovesBeyondTheLoadedRangeDoNotReviveLegacyHomeDates() = runBlocking {
        val draft = ScheduleDraft("原来的今天", date = today, time = LocalTime.of(19, 0), reminderMinutes = listOf(0))
        val task = create(draft)
        val original = snapshot().on(today).single()
        home.setCompleted(original, true)
        val moved = update(task, draft.copy(date = today.plusDays(1)))
        val completed = snapshot()
        assertEquals(listOf(today.plusDays(1)), completed.datesFor(original.sourceId))
        assertTrue(completed.on(today.plusDays(1)).single().completed)
        home.setCompleted(completed.on(today.plusDays(1)).single(), false)
        update(moved, draft.copy(date = today.plusDays(40)))
        assertTrue(snapshot().cards().isEmpty())
        assertNotNull(db.home().event("schedule:${original.sourceId}:$today"))
    }

    @Test fun anAlreadyVisibleHomeStreamUpdatesItsGroupingAfterReschedule() = runBlocking {
        val draft = ScheduleDraft("取快递", date = today, time = LocalTime.of(19, 0), reminderMinutes = listOf(0))
        val task = create(draft)
        home.prepare(today.minusDays(3), today.plusDays(1), today)
        val emissions = Channel<HomeTimelineData>(Channel.UNLIMITED)
        val collection = launch(Dispatchers.Default) {
            home.observe(today.minusDays(3), today.plusDays(1), today).collect { emissions.send(it) }
        }
        try {
            val before = withTimeout(10_000) { emissions.receive() }.on(today).single()
            update(task, draft.copy(date = today.plusDays(1)))
            val after = withTimeout(10_000) {
                var value = emissions.receive()
                while (value.cards().none { it.sourceId == before.sourceId && it.revision > before.revision }) value = emissions.receive()
                value
            }
            assertEquals(listOf(today.plusDays(1)), after.datesFor(before.sourceId))
            assertTrue(after.on(today).isEmpty())
        } finally { collection.cancelAndJoin(); emissions.close() }
    }
}
