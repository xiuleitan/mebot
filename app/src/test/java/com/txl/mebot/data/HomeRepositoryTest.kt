package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.DayimaChange
import com.txl.mebot.data.dayima.DayimaWriteResult
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.BuiltinCover
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.*
import com.txl.mebot.data.subscriptions.*
import java.time.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.*
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
class HomeRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var repo: HomeRepository
    private lateinit var schedules: ScheduleRepository
    private lateinit var notes: FlashnoteRepository
    private val today = LocalDate.parse("2026-10-30")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-30T04:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context); schedules = ScheduleRepository(db, clock) { zone }; notes = FlashnoteRepository(db, clock)
        repo = HomeRepository(db, schedules, notes, DayimaRepository(db, clock) { zone }, clock) { zone }
        repo.prepare(today.minusDays(3), today.plusDays(1), today)
    }
    @After fun teardown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }
    private suspend fun snapshot() = withTimeout(10_000) { repo.observe(today.minusDays(3), today.plusDays(1), today).first() }
    private fun HomeTimelineData.on(day: LocalDate) = days.firstOrNull { it.date == day }?.slots.orEmpty()
    private suspend fun schedule(title: String, date: LocalDate, time: LocalTime? = null, leads: List<Int> = emptyList(), recurrence: RecurrenceRule? = null): ScheduleOccurrenceEntity {
        val session = schedules.openSession()
        schedules.createTask(session.id, session.id, ScheduleDraft(title, date = date, time = time, reminderMinutes = leads, recurrence = recurrence))
        return schedules.observeDate(date).first().first { it.title == title }
    }
    @Test fun todayIncludesOrdinaryTimedAndRecurringButNeverMovesYesterdaysUnfinishedForward() = runBlocking {
        schedule("昨天未完成", today.minusDays(1)); schedule("今天普通", today)
        schedule("今天准点", today, LocalTime.of(16, 0), listOf(0, 10, 30))
        schedule("每天都有", today.minusDays(1), recurrence = RecurrenceRule(RecurrencePattern.DAILY))
        schedules.materializeRange(today.minusDays(1), today.plusDays(1))
        val result = snapshot()
        assertEquals(setOf("今天普通", "今天准点", "每天都有"), result.on(today).map { it.selected.title }.toSet())
        assertEquals(setOf("昨天未完成", "每天都有"), result.on(today.minusDays(1)).map { it.selected.title }.toSet())
        assertEquals(1, result.on(today).count { it.selected.title == "今天准点" })
    }
    @Test fun advanceRemindersCollapseToOneCardAndCompletionKeepsOriginalAlertDayHistory() = runBlocking {
        val next = schedule("下周扣费资料", today.plusDays(1), LocalTime.of(12, 0), listOf(24 * 60, 25 * 60, 0))
        val card = snapshot().on(today).single().selected
        assertEquals(next.id, card.sourceId)
        repo.setCompleted(card, true)
        val after = snapshot()
        assertTrue(after.on(today).single().selected.completed)
        assertTrue(after.on(today.plusDays(1)).single().selected.completed)
        assertNotNull(db.home().event("schedule:${next.id}:$today"))
        repo.setCompleted(after.on(today).single().selected, false)
        assertFalse(snapshot().on(today).single().selected.completed)
        assertFalse(snapshot().on(today.plusDays(1)).single().selected.completed)
    }
    @Test fun scheduleCardsShareTheBotsDisplayRecordIncludingLiveAttachmentsAndActualEventDate() = runBlocking {
        val media = LocalMediaStore(context, db, clock)
        val attachments = ScheduleAttachmentRepository(context, db, media, clock)
        val session = schedules.openSession()
        suspend fun attach(to: String, name: String) = media.importDocument(
            { ByteArrayInputStream("%PDF-1.4 $name".toByteArray()) }, name, "application/pdf",
            onImported = { attachments.attach(to, it, asPending = false) })
        val first = attach(session.id, "资料一.pdf")
        attach(session.id, "资料二.pdf")
        attach(schedules.openSession().id, "其他日程.pdf")
        val cover = media.builtin(BuiltinCover.DINNER)
        schedules.createTask(session.id, "create", ScheduleDraft("明天的重复日程", date = today.plusDays(1),
            time = LocalTime.of(22, 0), reminderMinutes = listOf(24 * 60, 0),
            recurrence = RecurrenceRule(RecurrencePattern.DAILY), coverAssetId = cover.id))

        suspend fun assertSameCard(expectedCount: Int): HomeCard {
            val home = snapshot().on(today).single().selected
            val bot = schedules.observeDisplayDate(today.plusDays(1)).first().single()
            assertEquals(bot, home.schedule)
            assertEquals(bot, db.home().schedule(home.sourceId))
            assertEquals(bot, repo.detail(home)!!.schedule)
            assertEquals(expectedCount, bot.attachmentCount)
            assertEquals(today, home.day)
            assertEquals(today.plusDays(1), bot.occurrence.date)
            assertEquals(LocalTime.of(22, 0), bot.occurrence.time)
            assertTrue(bot.isRecurring)
            assertEquals(MediaSource.BUILT_IN, bot.coverSource)
            assertEquals("dinner", bot.coverBuiltinKey)
            return home
        }
        val pending = assertSameCard(2)
        repo.setCompleted(pending, true)
        val completed = assertSameCard(2)
        assertNotNull(completed.schedule!!.occurrence.completedAt)
        repo.setCompleted(completed, false)
        assertNull(assertSameCard(2).schedule!!.occurrence.completedAt)
        attachments.remove(session.id, first.id)
        assertSameCard(1)
        Unit
    }
    @Test fun dailyFlashnoteSelectionPersistsSwipesAndCompletedHistorySurvivesReopen() = runBlocking {
        for (i in 1..4) db.flashnoteDao().insertNote(FlashnoteEntity("note$i", "闪念$i", "正文$i", FlashnoteStatus.PENDING, clock.millis(), clock.millis()))
        val slot = snapshot().on(today).single()
        assertEquals(4, slot.cards.size)
        val target = slot.cards.last()
        repo.selectNote(today, target.sourceId)
        assertEquals(target.sourceId, snapshot().on(today).single().selected.sourceId)
        repo.setCompleted(target, true)
        val changed = snapshot()
        assertEquals(1, changed.on(today).count { it.cards.size == 3 })
        assertEquals(target.sourceId, changed.on(today).single { it.selected.completed }.selected.sourceId)
        db.close(); db = MebotDatabase.create(context)
        notes = FlashnoteRepository(db, clock); schedules = ScheduleRepository(db, clock) { zone }
        repo = HomeRepository(db, schedules, notes, DayimaRepository(db, clock) { zone }, clock) { zone }
        assertEquals(target.sourceId, snapshot().on(today).single { it.selected.completed }.selected.sourceId)
        repo.setCompleted(snapshot().on(today).single { it.selected.completed }.selected, false)
        val restored = snapshot().on(today)
        assertEquals(4, restored.single().cards.size)
        assertFalse(restored.single().selected.completed)
    }
    @Test fun deletingSourceNoteHidesItsHistoryAndDetailUsesFullBodyOnlyOnDemand() = runBlocking {
        val text = "详细正文".repeat(500)
        db.flashnoteDao().insertNote(FlashnoteEntity("note", "详情标题", text, FlashnoteStatus.PENDING, clock.millis(), clock.millis()))
        val card = snapshot().on(today).single().selected
        assertTrue(card.body.length <= 160)
        assertEquals(text, repo.detail(card)!!.body)
        notes.delete("note", 1)
        assertTrue(snapshot().on(today).isEmpty())
        assertNull(repo.detail(card))
    }
    @Test fun subscriptionManualCompletionNeverCreatesPaymentAndCancelledRenewalHasNoPendingCard() = runBlocking {
        val sub = SubscriptionEntity("sub", "会员", 1800, "CNY", SubscriptionCycleUnit.MONTH, 1, today.plusDays(3), false,
            today.plusDays(3), null, true, SubscriptionStatus.ACTIVE, null, null, "", null, clock.millis(), clock.millis(), 1)
        db.subscriptions().insert(sub)
        val plan = SubscriptionReminderPlanEntity(sub.id, "cycle", SubscriptionReminderTarget.AUTO_CHARGE, today.plusDays(3), clock.millis(), zone.id, 1, 1, "generation", SubscriptionReminderPlanState.PENDING, updatedAt = clock.millis())
        HomeEventStore(db, clock) { zone }.sync(plan, sub)
        val card = snapshot().on(today).single().selected
        assertEquals(card.eventId, repo.reminder(HomeSource.SUBSCRIPTION, sub.id)?.eventId)
        assertNull(repo.reminder(HomeSource.SUBSCRIPTION, "other", card.eventId))
        repo.setCompleted(card, true)
        assertTrue(repo.reminder(HomeSource.SUBSCRIPTION, sub.id, card.eventId)!!.completed)
        assertTrue(snapshot().on(today).single().selected.completed)
        val storage = SubscriptionStorage(db, db.subscriptions(), db.subscriptionConversations(), db.mediaDao())
        assertTrue(SubscriptionRepository(storage).payments(sub.id).isEmpty())
        repo.setCompleted(snapshot().on(today).single().selected, false)
        db.subscriptions().update(sub.copy(autoRenew = false, status = SubscriptionStatus.CANCEL_AT_PERIOD_END, revision = 2))
        assertTrue(snapshot().on(today).isEmpty())
        assertNull(repo.reminder(HomeSource.SUBSCRIPTION, sub.id))
    }
    @Test fun missingReminderDetailsResolveToTheActualRecordedPeriodAndKeepTheirHistoryDate() = runBlocking {
        val dayima = DayimaRepository(db, clock) { zone }
        val origin = (dayima.execute(DayimaChange.RecordPeriod(today.minusDays(32))) as DayimaWriteResult.PeriodSaved).period
        val event = HomeEventEntity("missing", HomeEventKind.DAYIMA_MISSING, origin.id, "cycle", today.minusDays(1),
            "补记本次经期", "请补记实际日期", createdAt = clock.millis(), updatedAt = clock.millis())
        db.home().insertEvent(event)
        val pending = repo.reminder(HomeSource.DAYIMA, origin.id, event.id)!!
        assertFalse(pending.completed)
        assertNull(pending.resolvedById)
        val actual = (dayima.execute(DayimaChange.RecordPeriod(today)) as DayimaWriteResult.PeriodSaved).period
        val completed = repo.reminder(HomeSource.DAYIMA, actual.id, event.id)!!
        assertTrue(completed.completed)
        assertEquals(actual.id, completed.resolvedById)
        assertEquals(event.eventDate, completed.day)
        assertEquals(actual.id, repo.detail(pending)!!.resolvedById)
        assertNull(repo.reminder(HomeSource.DAYIMA, "other", event.id))
    }

}
