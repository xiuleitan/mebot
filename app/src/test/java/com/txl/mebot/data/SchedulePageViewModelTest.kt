package com.txl.mebot.data

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.MebotApplication
import com.txl.mebot.R
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.ScheduleAction
import com.txl.mebot.data.media.BuiltinCover
import com.txl.mebot.data.schedule.RecurrencePattern
import com.txl.mebot.data.schedule.RecurrenceRule
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.ScheduleViewModel
import com.txl.mebot.ui.schedule.asEntry
import com.txl.mebot.ui.schedule.detailForDisplay
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** 开发机上的真实 Room 与默认 SavedState 工厂，不向手机植入测试记录。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MebotApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SchedulePageViewModelTest {
    private lateinit var application: MebotApplication
    private lateinit var database: MebotDatabase
    private lateinit var repository: ScheduleRepository
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var model: ScheduleViewModel
    private val day = LocalDate.now()

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = application.container.database
        val zone = ZoneId.systemDefault()
        repository = ScheduleRepository(database, Clock.fixed(day.atTime(8, 0).atZone(zone).toInstant(), zone)) { zone }
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        model = ViewModelProvider(activity.get())[ScheduleViewModel::class.java]
        model.selectDate(day)
        model.setVisible(true)
        await { !model.state.value.loading }
    }

    @After fun tearDown() {
        activity.pause().stop().destroy()
        database.close()
        application.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test fun emptyDatabaseStaysEmptyWithoutSamplesOrImplicitSessions() = runBlocking {
        assertTrue(model.state.value.entries.isEmpty())
        assertTrue(model.state.value.dateCounts.isEmpty())
        model.selectDate(day.minusYears(1))
        await { !model.state.value.loading }
        assertTrue(model.state.value.entries.isEmpty())
        withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM schedule_sessions").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun dateSwitchesNeverShowAnotherDaysCardsAndDetailKeepsItsSession() = runBlocking {
        val cover = application.container.media.builtin(BuiltinCover.MEDICAL)
        val first = create("医院复查", cover = cover.id, time = LocalTime.of(11, 30))
        val next = create("明天买菜", date = day.plusDays(1))
        await { model.state.value.entries.size == 1 && model.state.value.dateCounts[day.plusDays(1)] == 1 }
        assertEquals(R.drawable.schedule_medical, model.state.value.entries.single().image)
        model.openDetail(first.id)
        await { !model.state.value.detailLoading }
        assertEquals(first.sessionId, model.state.value.detail?.sessionId)
        repeat(15) { model.selectDate(day.plusDays(if (it % 2 == 0) 1 else 0)) }
        model.selectDate(day.plusDays(1))
        assertTrue(model.state.value.entries.all { it.date == day.plusDays(1) })
        await { !model.state.value.loading }
        assertEquals(listOf(next.id), model.state.value.entries.map { it.id })
        assertNull(model.state.value.entries.single().image) // 无封面不冒用示例照片。
        assertEquals(first.id, model.state.value.detail?.id)
        model.openDetail(next.id)
        await { !model.state.value.detailLoading }
        assertEquals(next.sessionId, model.state.value.detail?.sessionId)
    }

    @Test fun completionCommitsOnceSynchronizesDetailAndRestoresItsSortedPosition() = runBlocking {
        val late = create("晚间会议", time = LocalTime.of(20, 0))
        val untimed = create("整理笔记")
        val early = create("上午会议", time = LocalTime.of(9, 0))
        await { model.state.value.entries.size == 3 }
        assertEquals(listOf(early.id, late.id, untimed.id), model.state.value.entries.map { it.id })
        model.openDetail(early.id)
        await { !model.state.value.detailLoading }
        model.toggleCompleted(early.id)
        model.toggleCompleted(early.id) // 连续点击只提交一次，不把完成立即抵消。
        await { model.state.value.updatingIds.isEmpty() && model.state.value.detail?.completed == true }
        assertEquals(listOf(late.id, untimed.id, early.id), model.state.value.entries.map { it.id })
        assertNotNull(database.scheduleDao().occurrence(early.id)?.completedAt)
        assertEquals(1, repository.operationHistory(requireNotNull(early.sessionId)).count { it.action == ScheduleAction.COMPLETE })
        assertEquals(3, model.state.value.dateCounts[day]) // 完成仍属于当天，不移除圆点。
        model.toggleCompleted(early.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.detail?.completed == false }
        assertEquals(listOf(early.id, late.id, untimed.id), model.state.value.entries.map { it.id })
        assertNull(database.scheduleDao().occurrence(early.id)?.completedAt)
    }

    @Test fun adjacentDaysAreReadyBeforeSelectionAndOnlyThreeDaysStayInThePageCache() = runBlocking {
        val previous = create("昨天的任务", date = day.minusDays(1))
        val current = create("今天的任务")
        val next = create("明天的任务", date = day.plusDays(1))
        val later = create("后天的任务", date = day.plusDays(2))
        await { model.state.value.dayEntries[day.minusDays(1)]?.singleOrNull()?.id == previous.id &&
            model.state.value.dayEntries[day.plusDays(1)]?.singleOrNull()?.id == next.id }
        assertEquals(listOf(current.id), model.state.value.entries.map { it.id })
        val beforePreload = model.state.value
        model.preloadDate(day.plusDays(1))
        assertSame(beforePreload, model.state.value) // 邻日已就绪时，不在点击入场前重启数据或重建页面状态。
        model.selectDate(day.plusDays(1))
        assertFalse(model.state.value.loading)
        assertEquals(listOf(next.id), model.state.value.entries.map { it.id })
        await { model.state.value.dayEntries[day.plusDays(2)]?.singleOrNull()?.id == later.id }
        assertEquals(setOf(day, day.plusDays(1), day.plusDays(2)), model.state.value.dayEntries.keys)
        model.selectDate(day.plusDays(2))
        assertEquals(listOf(later.id), model.state.value.entries.map { it.id })
        val distant = day.plusDays(60)
        model.selectDate(distant)
        assertTrue(model.state.value.entries.isEmpty())
        await { !model.state.value.loading }
        assertEquals(setOf(distant.minusDays(1), distant, distant.plusDays(1)), model.state.value.dayEntries.keys)
        assertTrue(model.state.value.dayEntries.values.all { it.isEmpty() })
    }

    @Test fun newlyCompletedCardLeadsItsGroupAndCompletionTimeUpdatesAcrossThePageAndDetail() = runBlocking {
        val old = create("原来已完成", time = LocalTime.of(9, 0))
        val timed = create("待办会议", time = LocalTime.of(20, 0))
        val newest = create("刚完成的事")
        val earlier = ScheduleRepository(database,
            Clock.fixed(java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 60_000), ZoneId.systemDefault()))
        val oldCommit = earlier.setCompleted(requireNotNull(old.sessionId), old.id, true, UUID.randomUUID().toString(), old.revision)
        await { model.state.value.entries.size == 3 && model.state.value.entries.single { it.id == old.id }.completed }
        assertEquals(oldCommit.completedAt, model.state.value.entries.last().completedAt)
        model.openDetail(newest.id)
        await { !model.state.value.detailLoading }
        model.toggleCompleted(newest.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.detail?.completed == true }
        val committed = requireNotNull(database.scheduleDao().occurrence(newest.id))
        assertEquals(listOf(timed.id, newest.id, old.id), model.state.value.entries.map { it.id })
        assertEquals(committed.completedAt, model.state.value.detail?.completedAt)
        assertEquals(committed.completedAt, model.state.value.dayEntries.getValue(day).single { it.id == newest.id }.completedAt)
        model.toggleCompleted(newest.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.detail?.completed == false }
        assertNull(model.state.value.detail?.completedAt)
        assertNull(model.state.value.entries.single { it.id == newest.id }.completedAt)
        assertEquals(listOf(timed.id, newest.id, old.id), model.state.value.entries.map { it.id })
        model.selectDate(day.plusDays(1))
        model.selectDate(day)
        await { !model.state.value.loading && model.state.value.entries.size == 3 }
        assertEquals(listOf(timed.id, newest.id, old.id), model.state.value.entries.map { it.id })
    }

    @Test fun dateTapPreloadsOneDistantDayWithoutDiscardingTheOutgoingPageOrExpandingTheGap() = runBlocking {
        val today = create("每日任务", recurrence = RecurrenceRule(RecurrencePattern.DAILY, endDate = day.plusDays(200)))
        await { model.state.value.entries.singleOrNull()?.id == today.id && model.state.value.dayEntries.size == 3 }
        val target = day.plusDays(100)
        val outgoing = model.state.value.entries
        model.preloadDate(target)
        assertEquals(day, model.state.value.selectedDate)
        assertSame(outgoing, model.state.value.entries)
        assertFalse(model.state.value.loading)
        await { model.state.value.dayEntries[target]?.singleOrNull()?.sessionId == today.sessionId }
        assertEquals(setOf(day.minusDays(1), day, day.plusDays(1), target), model.state.value.dayEntries.keys)
        assertEquals(listOf(today.id), model.state.value.entries.map { it.id })
        val incoming = model.state.value.dayEntries.getValue(target).single()
        model.selectDate(target)
        assertFalse(model.state.value.loading)
        assertEquals(listOf(incoming.id), model.state.value.entries.map { it.id })
        await { model.state.value.dayEntries.keys == setOf(target.minusDays(1), target, target.plusDays(1)) }
        val task = requireNotNull(repository.taskForSession(requireNotNull(today.sessionId)))
        assertTrue(database.scheduleDao().occurrencesForTask(task.id).none { it.date == day.plusDays(50) })
    }

    @Test fun replacingOrCancelingADatePreloadKeepsTheCurrentCardsAndDropsStaleTargets() = runBlocking {
        val current = create("当前任务")
        val first = create("首个目标", date = day.plusDays(30))
        val latest = create("最新目标", date = day.plusDays(60))
        await { model.state.value.entries.singleOrNull()?.id == current.id && model.state.value.dayEntries.size == 3 }
        model.preloadDate(first.date)
        model.preloadDate(latest.date)
        await { model.state.value.dayEntries[latest.date]?.singleOrNull()?.id == latest.id }
        assertEquals(day, model.state.value.selectedDate)
        assertEquals(listOf(current.id), model.state.value.entries.map { it.id })
        assertFalse(first.date in model.state.value.dayEntries)
        assertEquals(4, model.state.value.dayEntries.size)
        model.preloadDate(null)
        assertEquals(setOf(day.minusDays(1), day, day.plusDays(1)), model.state.value.dayEntries.keys)
        assertEquals(listOf(current.id), model.state.value.entries.map { it.id })
        model.selectDate(latest.date)
        await { !model.state.value.loading && model.state.value.entries.singleOrNull()?.id == latest.id }
        assertEquals(latest.date, model.state.value.selectedDate)
        assertFalse(first.date in model.state.value.dayEntries)
    }

    @Test fun detailOpensImmediatelyFromAPreloadedNeighborAndDatabaseDeletionCannotReviveTheSnapshot() = runBlocking {
        val next = create("明天的任务", date = day.plusDays(1))
        await { model.state.value.dayEntries[day.plusDays(1)]?.singleOrNull()?.id == next.id }
        assertTrue(model.state.value.entries.isEmpty())
        // 打开瞬间不等待数据库，直接使用相邻页已显示的卡片。
        assertEquals(next, model.state.value.detailForDisplay(next.id))
        model.openDetail(next.id)
        assertEquals(next, model.state.value.detail)
        await { !model.state.value.detailLoading }
        val sessionId = requireNotNull(next.sessionId)
        val task = requireNotNull(repository.taskForSession(sessionId))
        repository.deleteTask(sessionId, "delete-open-neighbor", task.revision)
        await { !model.state.value.detailLoading && model.state.value.detail == null }
        assertNull(model.state.value.detailForDisplay(next.id, next))
    }

    @Test fun openingChatCardUsesItsSnapshotUntilTheDatabaseRepliesAndFailureDoesNotShowStaleDetails() = runBlocking {
        val entry = create("聊天中的任务")
        await { model.state.value.entries.singleOrNull()?.id == entry.id }
        val loading = model.state.value.copy(entries = emptyList(), dayEntries = emptyMap(),
            detailId = entry.id, detail = null, detailLoading = true)
        assertEquals(entry, loading.detailForDisplay(entry.id, entry))
        assertNull(loading.detailForDisplay("another-task", entry))
        assertNull(loading.copy(detailFailed = true).detailForDisplay(entry.id, entry))
        assertNull(loading.copy(detailLoading = false).detailForDisplay(entry.id, entry))
    }

    @Test fun completionDuringDateSwitchStaysOnItsOriginalDayAndIsVisibleWhenReturning() = runBlocking {
        val original = create("先完成这个")
        val next = create("明天的任务", date = day.plusDays(1))
        await { model.state.value.entries.size == 1 && model.state.value.dayEntries[day.plusDays(1)]?.size == 1 }
        model.toggleCompleted(original.id)
        model.selectDate(day.plusDays(1))
        await { model.state.value.updatingIds.isEmpty() && model.state.value.dayEntries[day]?.singleOrNull()?.completed == true }
        assertEquals(listOf(next.id), model.state.value.entries.map { it.id })
        assertFalse(model.state.value.entries.single().completed)
        model.selectDate(day)
        assertEquals(original.id, model.state.value.entries.single().id)
        assertTrue(model.state.value.entries.single().completed)
        assertTrue(model.state.value.entries.all { it.date == day })
    }

    @Test fun adjacentPageContentTracksReschedulingAndDeletionBeforeItIsSelected() = runBlocking {
        val next = create("原在明天", date = day.plusDays(1))
        await { model.state.value.dayEntries[day.plusDays(1)]?.singleOrNull()?.id == next.id }
        val session = requireNotNull(next.sessionId)
        val task = requireNotNull(repository.taskForSession(session))
        val moved = repository.updateTask(session, "move-neighbor", ScheduleDraft("改到昨天", date = day.minusDays(1)), task.revision)
        await { model.state.value.dayEntries[day.plusDays(1)]?.isEmpty() == true &&
            model.state.value.dayEntries[day.minusDays(1)]?.singleOrNull()?.title == "改到昨天" }
        assertTrue(model.state.value.entries.isEmpty())
        repository.deleteTask(session, "delete-neighbor", moved.revision)
        await { model.state.value.dayEntries.values.all { it.isEmpty() } }
        model.selectDate(day.minusDays(1))
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test fun failedCompletionLeavesDatabaseAndScreenUnchangedAndCanRetry() = runBlocking {
        val entry = create("完成失败回滚")
        await { model.state.value.entries.size == 1 }
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_completion BEFORE UPDATE OF completedAt ON schedule_occurrences
                BEGIN SELECT RAISE(ABORT, 'test completion failure'); END
            """.trimIndent())
        }
        model.toggleCompleted(entry.id)
        await { model.state.value.writeFailure != null && model.state.value.updatingIds.isEmpty() }
        assertFalse(model.state.value.entries.single().completed)
        assertNull(database.scheduleDao().occurrence(entry.id)?.completedAt)
        assertEquals(entry.revision, database.scheduleDao().occurrence(entry.id)?.revision)
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_completion") }
        model.toggleCompleted(entry.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.entries.single().completed }
        assertNull(model.state.value.writeFailure)
    }

    @Test fun openPageTracksRescheduleAndDeletionInCardsDotsAndDetail() = runBlocking {
        val entry = create("原来标题")
        await { model.state.value.entries.size == 1 && model.state.value.dateCounts[day] == 1 }
        model.openDetail(entry.id)
        await { !model.state.value.detailLoading }
        val id = requireNotNull(entry.sessionId)
        val task = requireNotNull(repository.taskForSession(id))
        val changed = repository.updateTask(id, "reschedule", ScheduleDraft(
            "修改后标题", detail = "已保存的新详情", date = day.plusDays(2), time = LocalTime.of(16, 0),
        ), task.revision)
        await {
            model.state.value.entries.isEmpty() && model.state.value.dateCounts[day] == null &&
                model.state.value.dateCounts[day.plusDays(2)] == 1 && model.state.value.detail?.title == "修改后标题"
        }
        assertEquals("已保存的新详情", model.state.value.detail?.detail)
        assertEquals(day.plusDays(2), model.state.value.detail?.date)
        model.selectDate(day.plusDays(2))
        await { !model.state.value.loading && model.state.value.entries.size == 1 }
        repository.deleteTask(id, "delete", changed.revision)
        await { model.state.value.entries.isEmpty() && model.state.value.detail == null && model.state.value.dateCounts.isEmpty() }
        assertNull(repository.taskForSession(id, includeDeleted = true))
        assertNull(database.scheduleDao().occurrence(entry.id))
    }

    @Test fun recurrenceAddedWhileOpenExpandsOnlyRequestedWindowsAndSharesTheConversation() = runBlocking {
        val entry = create("每天喂猫", recurrence = RecurrenceRule(RecurrencePattern.DAILY, endDate = day.plusDays(30)))
        await { model.state.value.dateCounts[day.plusDays(7)] == 1 && model.state.value.entries.size == 1 }
        assertNull(model.state.value.dateCounts[day.minusDays(1)])
        model.showVisibleDates(day.plusDays(20), day.plusDays(24))
        await { model.state.value.dateCounts[day.plusDays(30)] == 1 && day !in model.state.value.dateCounts }
        assertNull(model.state.value.dateCounts[day.plusDays(31)])
        model.selectDate(day.plusDays(21))
        await { !model.state.value.loading && model.state.value.entries.size == 1 }
        val future = model.state.value.entries.single()
        assertEquals(entry.sessionId, future.sessionId)
        assertNotEquals(entry.id, future.id)
        model.toggleCompleted(future.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.entries.single().completed }
        assertNull(database.scheduleDao().occurrence(entry.id)?.completedAt)
        val before = repository.history(day, day.plusDays(60)).map { it.id }.toSet()
        assertEquals(26, before.size) // 0…7 与 13…30；不展开中间未浏览的日期。
        model.setVisible(false); model.setVisible(true)
        await { !model.state.value.loading }
        assertEquals(before, repository.history(day, day.plusDays(60)).map { it.id }.toSet())
    }

    @Test fun localStopUpdatesDateCountsAndKeepsCompletedCardAndConversation() = runBlocking {
        val entry = create("每天喂猫", recurrence = RecurrenceRule(RecurrencePattern.DAILY), time = LocalTime.of(18, 0))
        await { model.state.value.entries.size == 1 && model.state.value.dateCounts[day.plusDays(2)] == 1 }
        model.toggleCompleted(entry.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.entries.single().completed }
        model.openDetail(entry.id)
        await { model.state.value.detail?.id == entry.id }
        model.stopRecurrence(entry.id)
        await { model.state.value.stoppedEvent != null && model.state.value.updatingIds.isEmpty() && model.state.value.detail?.recurrenceStopped == true && day.plusDays(2) !in model.state.value.dateCounts }
        assertTrue(model.state.value.entries.single().completed)
        assertNotNull(repository.taskForSession(requireNotNull(entry.sessionId)))
        assertTrue(repository.pendingReminders(0, Long.MAX_VALUE).isEmpty())
        model.toggleCompleted(entry.id)
        await { model.state.value.updatingIds.isEmpty() && !model.state.value.entries.single().completed }
        assertTrue(repository.pendingReminders(0, Long.MAX_VALUE).isEmpty())
        model.selectDate(day.plusDays(2))
        await { !model.state.value.loading && model.state.value.entries.isEmpty() }
    }

    @Test fun savedActivityDateAndColdStartBothReloadCommittedRecords() = runBlocking {
        val entry = create("重启保存的日程", date = day.plusDays(2))
        model.selectDate(day.plusDays(2))
        await { !model.state.value.loading && model.state.value.entries.size == 1 }
        model.toggleCompleted(entry.id)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.entries.single().completed }
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
        model = ViewModelProvider(activity.get())[ScheduleViewModel::class.java]
        assertEquals(day.plusDays(2), model.state.value.selectedDate)
        model.setVisible(true)
        await { !model.state.value.loading }
        assertEquals(entry.id, model.state.value.entries.single().id)
        assertTrue(model.state.value.entries.single().completed)
        activity.pause().stop().destroy()
        database.close() // 真正关闭并重开数据库，排除只保留在进程内状态的情况。
        database = MebotDatabase.create(application)
        val reopened = ScheduleRepository(database)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val store = activity.get().viewModelStore
        model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ScheduleViewModel(application, androidx.lifecycle.SavedStateHandle(), reopened) as T
        })[ScheduleViewModel::class.java]
        assertEquals(day, model.state.value.selectedDate)
        model.selectDate(day.plusDays(2)); model.setVisible(true)
        await { !model.state.value.loading }
        assertEquals(entry.id, model.state.value.entries.single().id)
        assertTrue(model.state.value.entries.single().completed)
        assertEquals(entry.sessionId, model.state.value.entries.single().sessionId)
    }

    @Test fun detailDeleteWorksLocallyAndPublishesACommittedDeletionEvent() = runBlocking {
        val entry = create("直接删除的任务", date = day)
        await { model.state.value.entries.size == 1 }
        model.openDetail(entry.id)
        await { !model.state.value.detailLoading }
        model.deleteEntry(entry.id, com.txl.mebot.data.schedule.ScheduleEditScope.ALL)
        model.deleteEntry(entry.id, com.txl.mebot.data.schedule.ScheduleEditScope.ALL)
        await { model.state.value.updatingIds.isEmpty() && model.state.value.deletionEvent != null }
        assertTrue(requireNotNull(model.state.value.deletionEvent).wholeTask)
        assertTrue(model.state.value.entries.isEmpty())
        assertNull(model.state.value.detail)
        assertNull(database.scheduleDao().session(requireNotNull(entry.sessionId)))
        assertTrue(repository.recentMessages(entry.sessionId).isEmpty())
    }

    private suspend fun create(
        title: String, date: LocalDate = day, time: LocalTime? = null,
        cover: String? = null, recurrence: RecurrenceRule? = null,
    ): ScheduleEntry {
        val session = repository.openSession()
        val task = repository.createTask(session.id, UUID.randomUUID().toString(), ScheduleDraft(
            title, date = date, time = time, coverAssetId = cover, recurrence = recurrence,
        ))
        return repository.observeDisplayDate(date).first().first { it.occurrence.taskId == task.id }.asEntry()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "等待日程状态超时：${model.state.value}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
    }
}
