package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.*
import com.txl.mebot.data.schedule.ScheduleRepository
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 直接读取主库验证跨日、旧版展示记录与原 Bot 的完成／恢复操作。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeFlashnoteHistoryTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var home: HomeRepository
    private lateinit var notes: FlashnoteRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 31)
    private val clock = MutableClock(zone)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        clock.at(today); openDatabase()
    }
    private fun openDatabase() {
        db = MebotDatabase.create(context)
        notes = FlashnoteRepository(db, clock, { zone })
        home = HomeRepository(db, ScheduleRepository(db, clock) { zone }, notes, DayimaRepository(db, clock) { zone }, clock) { zone }
    }
    @After fun close() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private suspend fun create(id: String) = db.flashnoteDao().insertNote(FlashnoteEntity(id, "标题$id", "详细内容$id",
        createdAt = clock.millis(), updatedAt = clock.millis()))
    private suspend fun legacyVisit(id: String, day: LocalDate) {
        db.home().insertFlashnoteDay(HomeFlashnoteDayEntity(day, 42, id, clock.millis()))
        db.home().insertEvent(HomeEventEntity("flashnote:$id:$day", HomeEventKind.FLASHNOTE, id, null, day,
            "旧展示标题", "", createdAt = clock.millis(), updatedAt = clock.millis()))
    }
    private suspend fun snapshot(day: LocalDate = today): HomeTimelineData = withTimeout(10_000) {
        home.prepare(day.minusDays(10), day.plusDays(1), day)
        home.observe(day.minusDays(10), day.plusDays(1), day).first()
    }
    private fun HomeTimelineData.on(day: LocalDate) = days.firstOrNull { it.date == day }?.slots.orEmpty()
    private fun HomeTimelineData.cards() = days.flatMap { it.slots }.flatMap { it.cards }.filter { it.source == HomeSource.FLASHNOTE }

    @Test fun openTimelineRotatesAtOneHourAndKeepsManualSelectionUntilItsOwnDeadline() = runBlocking {
        create("a"); create("b"); create("c")
        val initial = snapshot().on(today).single()
        clock.at(today, LocalTime.of(12, 59, 59))
        val updates = withTimeout(10_000) {
            home.observe(today, today.plusDays(1), today).onEach { data ->
                if (data.on(today).single().selectedId == initial.selectedId) clock.at(today, LocalTime.of(13, 0))
            }.take(2).toList()
        }
        assertEquals(initial.selectedId, updates.first().on(today).single().selectedId)
        val rotated = updates.last().on(today).single().selectedId
        assertEquals(initial.cards[1].sourceId, rotated)

        clock.at(today, LocalTime.of(13, 20))
        home.selectNote(today, initial.selectedId)
        clock.at(today, LocalTime.of(14, 19, 59))
        db.close(); openDatabase()
        assertEquals(initial.selectedId, snapshot().on(today).single().selectedId)
        clock.at(today, LocalTime.of(14, 20))
        assertEquals(rotated, snapshot().on(today).single().selectedId)
    }

    @Test fun pinSurvivesReopenAndDaysAwayThenUnpinStartsANewHour() = runBlocking {
        create("a"); create("b"); create("c")
        val first = snapshot().on(today).single()
        val selected = first.selectedId
        home.setNotePinned(selected, true)
        // Pager 写回相同 ID 不应误解为手动切换。
        home.selectNote(today, selected)
        clock.at(today, LocalTime.of(15, 0))
        db.close(); openDatabase()
        assertEquals(selected, snapshot().on(today).single().selectedId)
        assertTrue(snapshot().on(today).single().pinned)
        val later = today.plusDays(3)
        clock.at(later)
        val afterDays = snapshot(later)
        assertEquals(selected, afterDays.on(later).single().selectedId)
        assertTrue(afterDays.on(later).single().pinned)
        assertTrue(afterDays.days.filter { it.date < later }.all { it.slots.isEmpty() })

        clock.at(later, LocalTime.of(16, 0))
        home.setNotePinned(selected, false)
        assertNull(db.home().flashnotePin())
        clock.at(later, LocalTime.of(16, 59, 59))
        assertEquals(selected, snapshot(later).on(later).single().selectedId)
        clock.at(later, LocalTime.of(17, 0))
        assertNotEquals(selected, snapshot(later).on(later).single().selectedId)
    }

    @Test fun manualSwitchUnpinsAndResumesRotationWhileCompletionAndDeletionClearThePin() = runBlocking {
        create("a"); create("b"); create("c")
        val initial = snapshot().on(today).single()
        val first = initial.selectedId
        val second = initial.cards[1].sourceId
        home.setNotePinned(first, true)
        home.selectNote(today, second)
        assertNull(db.home().flashnotePin())
        assertFalse(snapshot().on(today).single().pinned)
        clock.at(today, LocalTime.of(13, 0))
        assertEquals(initial.cards[2].sourceId, snapshot().on(today).single().selectedId)

        home.setNotePinned(second, true)
        notes.complete(second, notes.note(second)!!.revision)
        assertNull(db.home().flashnotePin())
        notes.restore(second, notes.note(second)!!.revision)
        assertNull(db.home().flashnotePin())
        home.setNotePinned(second, true)
        notes.delete(second, notes.note(second)!!.revision)
        assertNull(db.home().flashnotePin())
        val afterDelete = snapshot().on(today).single()
        assertNotEquals(second, afterDelete.selectedId)
        assertFalse(afterDelete.pinned)
    }

    @Test fun singlePendingNoteCanBePinnedAndNewNotesDoNotDisplaceIt() = runBlocking {
        create("a"); snapshot()
        home.setNotePinned("a", true)
        clock.at(today, LocalTime.of(14, 0)); create("b")
        assertEquals("a", snapshot().on(today).single().selectedId)
        assertTrue(snapshot().on(today).single().pinned)
        assertTrue(runCatching { home.setNotePinned("missing", true) }.isFailure)
        notes.complete("a", notes.note("a")!!.revision)
        assertTrue(runCatching { home.setNotePinned("a", true) }.isFailure)
        assertNull(db.home().flashnotePin())
        assertEquals("b", snapshot().on(today).first { !it.selected.completed }.selectedId)
    }

    @Test fun unfinishedDailySelectionsNeverBecomeHistoryEvenWithOldVisitsAndDaysAway() = runBlocking {
        create("a"); create("b")
        for (day in listOf(today.minusDays(2), today.minusDays(1))) {
            legacyVisit("a", day); legacyVisit("b", day)
        }
        for (offset in listOf(0L, 1L, 4L)) {
            val day = today.plusDays(offset); clock.at(day)
            val data = snapshot(day)
            val slot = data.on(day).single()
            assertEquals(setOf("a", "b"), slot.cards.map { it.sourceId }.toSet())
            assertTrue(slot.cards.none { it.completed })
            assertTrue(data.days.filter { it.date < day }.all { it.slots.isEmpty() })
            home.selectNote(day, "b")
            assertEquals("b", snapshot(day).on(day).single().selectedId)
        }
        // 每日选择持久化，不再补写展示事件；旧版记录保留但不当作完成事实。
        assertEquals(2, db.home().sourceEvents(HomeEventKind.FLASHNOTE, "a").size)
        assertEquals(2, db.home().sourceEvents(HomeEventKind.FLASHNOTE, "b").size)
        db.close(); openDatabase()
        val reopened = snapshot(today.plusDays(4))
        assertEquals("b", reopened.on(today.plusDays(4)).single().selectedId)
        assertEquals(2, reopened.cards().size)
        assertNotNull(db.home().event("flashnote:a:${today.minusDays(1)}"))
    }

    @Test fun onlyActualCompletionDaysRemainAndCompletingSeveralNotesKeepsOneCardPerNote() = runBlocking {
        clock.at(today.minusDays(5))
        for (id in listOf("a", "b", "c")) create(id)
        for (day in listOf(today.minusDays(4), today.minusDays(3))) {
            legacyVisit("a", day); legacyVisit("b", day)
        }
        clock.at(today.minusDays(2))
        val a = snapshot(today.minusDays(2)).on(today.minusDays(2)).single().cards.first { it.sourceId == "a" }
        home.setCompleted(a, true)
        clock.at(today.minusDays(1))
        notes.complete("b", 1) // 原闪念 Bot 的完成入口也必须出现在相同首页历史。
        clock.at(today)
        val before = snapshot()
        assertEquals(listOf("a"), before.on(today.minusDays(2)).map { it.selected.sourceId })
        assertEquals(listOf("b"), before.on(today.minusDays(1)).map { it.selected.sourceId })
        assertTrue(before.on(today.minusDays(3)).isEmpty())
        assertTrue(before.on(today.minusDays(4)).isEmpty())
        assertEquals("c", before.on(today).single().selected.sourceId)
        home.setCompleted(before.on(today).single().selected, true)
        val completed = snapshot()
        assertEquals(3, completed.cards().size)
        assertTrue(completed.cards().all { it.completed })
        clock.at(today.plusDays(1))
        val tomorrow = snapshot(today.plusDays(1))
        assertTrue(tomorrow.on(today.plusDays(1)).isEmpty())
        assertEquals("c", tomorrow.on(today).single().selected.sourceId)
        assertEquals(3, tomorrow.cards().size)
        assertTrue(tomorrow.cards().all { it.completed })
    }

    @Test fun restoreReturnsOnlyToTodayAndRecompletionUsesLocalCompletionDateAfterReopen() = runBlocking {
        clock.at(today.minusDays(3)); create("a"); legacyVisit("a", today.minusDays(2))
        clock.at(today.minusDays(1), LocalTime.of(23, 59)); notes.complete("a", 1)
        clock.at(today, LocalTime.of(0, 1))
        val yesterday = snapshot().on(today.minusDays(1)).single().selected
        assertTrue(yesterday.completed)
        home.setCompleted(yesterday, false)
        val restored = snapshot()
        assertEquals(1, restored.cards().size)
        assertEquals(today, restored.cards().single().day)
        assertFalse(restored.cards().single().completed)
        assertTrue(restored.on(today.minusDays(1)).isEmpty())
        home.setCompleted(restored.on(today).single().selected, true)
        val actualTime = notes.note("a")!!.completedAt
        db.close(); openDatabase()
        val completed = snapshot().cards().single()
        assertEquals(today, completed.day); assertTrue(completed.completed)
        home.setCompleted(completed, true)
        assertEquals(actualTime, notes.note("a")!!.completedAt)
        clock.at(today.plusDays(1))
        val next = snapshot(today.plusDays(1))
        assertEquals(1, next.on(today).size)
        assertTrue(next.on(today).single().selected.completed)
        assertTrue(next.on(today.minusDays(1)).isEmpty())
    }

    private class MutableClock(private val dayZone: ZoneId) : Clock() {
        @Volatile private var now = Instant.EPOCH
        fun at(day: LocalDate, time: LocalTime = LocalTime.NOON) { now = day.atTime(time).atZone(dayZone).toInstant() }
        override fun instant() = now
        override fun getZone(): ZoneId = dayZone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
