package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.home.HomeRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.schedule.ScheduleRepository
import com.txl.mebot.data.local.*
import java.time.*
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
class DayimaReminderEngineTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var clock: ReminderClock
    private lateinit var repo: DayimaRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context); clock = ReminderClock(); repo = DayimaRepository(db, clock) { zone }
        repo.execute(DayimaChange.RecordPeriod(LocalDate.parse("2026-09-01")))
        repo.execute(DayimaChange.RecordPeriod(LocalDate.parse("2026-09-29")))
        Unit
    }
    @After fun teardown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME) }
    private fun engine() = DayimaReminderEngine(db, repo, clock) { zone }
    private suspend fun plan(kind: DayimaReminderKind) = db.dayimaReminders().plans().single { it.kind == kind }
    @Test fun predictionBoundaryDatesAppearOnHomeOnceAcrossRestarts() = runBlocking {
        coroutineScope { List(4) { async(Dispatchers.IO) { engine().reconcile() } }.awaitAll() }
        val prepare = plan(DayimaReminderKind.PREPARE)
        val missing = plan(DayimaReminderKind.MISSING_RECORD)
        assertEquals(LocalDate.parse("2026-10-24"), db.home().event(prepare.id)?.eventDate)
        assertEquals(LocalDate.parse("2026-10-30"), db.home().event(missing.id)?.eventDate)
        assertEquals(DayimaDeliveryState.PENDING, prepare.deliveryState)
        clock.set("2026-10-30T05:00:00Z")
        engine().reconcile()
        assertEquals(2, db.home().observeEvents(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")).first().size)
    }

    @Test fun togglesRespectTheSavedHomeCompletionAndExistingDeliveryHistory() = runBlocking {
        engine().reconcile()
        val prepare = plan(DayimaReminderKind.PREPARE)
        val event = requireNotNull(db.home().event(prepare.id))
        val completedAt = clock.millis()
        db.home().updateEvent(event.copy(completedAt = completedAt, revision = event.revision + 1))
        db.dayimaReminders().savePlan(prepare.copy(deliveryState = DayimaDeliveryState.DELIVERED, deliveredAt = completedAt))
        db.dayimaDao().insertReminderSettings(DayimaReminderSettingsEntity(prepareEnabled = false, missingRecordEnabled = false, updatedAt = completedAt))
        engine().reconcile()
        assertTrue(db.dayimaReminders().plans().none { it.active })
        val settings = requireNotNull(db.dayimaDao().reminderSettings())
        db.dayimaDao().updateReminderSettings(true, true, LocalTime.NOON, 1, clock.millis(), settings.revision)
        engine().reconcile()
        assertEquals(completedAt, db.home().event(event.id)?.completedAt)
        assertEquals(completedAt, plan(DayimaReminderKind.PREPARE).deliveredAt)
    }

    @Test fun periodSavedTransactionCompletesMissingHistoryBeforeTheNextRefresh() = runBlocking {
        clock.set("2026-10-30T05:00:00Z")
        engine().reconcile()
        val original = plan(DayimaReminderKind.MISSING_RECORD)
        val event = requireNotNull(db.home().event(original.id))
        val saved = repo.execute(DayimaChange.RecordPeriod(LocalDate.parse("2026-10-29"))) as DayimaWriteResult.PeriodSaved
        val completed = requireNotNull(db.home().event(original.id))
        assertNotNull(completed.completedAt)
        assertEquals(saved.period.id, completed.resolvedById)
        assertEquals(event.eventDate, completed.eventDate)
        engine().reconcile()
        assertFalse(requireNotNull(db.dayimaReminders().plan(original.id)).active)
        assertEquals(completed, db.home().event(original.id))
    }

    @Test fun deletingSourceWithNoRemainingPredictionCancelsItsOpenCards() = runBlocking {
        engine().reconcile()
        val last = requireNotNull(repo.latestPeriod())
        repo.execute(DayimaChange.DeletePeriod(last.id, last.revision))
        engine().reconcile()
        assertTrue(db.dayimaReminders().plans().none { it.active })
        assertTrue(db.home().openDayimaEvents().all { it.cancelled })
    }

    @Test fun recalculationKeepsTheLocalReminderDateWhenTimeZoneChanges() = runBlocking {
        clock.set("2026-10-23T05:00:00Z")
        engine().reconcile()
        val before = plan(DayimaReminderKind.PREPARE)
        DayimaReminderEngine(db, repo, clock) { ZoneId.of("Asia/Tokyo") }.reconcile()
        assertEquals(before.triggerAt - 3_600_000, plan(DayimaReminderKind.PREPARE).triggerAt)
        assertEquals(before.reminderDate, db.home().event(before.id)?.eventDate)
    }

    @Test fun homeMissingRecordCannotBeCompletedByAnOldCycleDate() = runBlocking {
        clock.set("2026-10-30T05:00:00Z"); engine().reconcile()
        val plan = plan(DayimaReminderKind.MISSING_RECORD)
        val event = db.home().event(plan.id)!!
        val home = HomeRepository(db, ScheduleRepository(db, clock) { zone }, FlashnoteRepository(db, clock), repo, clock) { zone }
        val card = com.txl.mebot.data.home.HomeCard(event.id, com.txl.mebot.data.home.HomeSource.DAYIMA, event.sourceId,
            event.eventDate, event.title, event.body, false, event.revision, eventId = event.id, eventKind = event.kind)
        assertTrue(runCatching { home.recordPeriod(card, LocalDate.parse("2026-09-29")) }.isFailure)
        assertNull(db.home().event(event.id)!!.completedAt)
        assertEquals(2, repo.periods().size)
        home.recordPeriod(card, LocalDate.parse("2026-10-29"))
        assertNotNull(db.home().event(event.id)!!.completedAt)
    }
    private class ReminderClock(var at: Instant = Instant.parse("2026-10-24T05:00:00Z")) : Clock() {
        fun set(value: String) { at = Instant.parse(value) }
        override fun instant() = at
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(at, zone)
    }
}
