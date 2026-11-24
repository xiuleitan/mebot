package com.txl.mebot.data

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.CalendarReminderEntry
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.ReminderStatus
import com.txl.mebot.data.reminders.CalendarReminderRegistration
import com.txl.mebot.data.reminders.CalendarRinging
import com.txl.mebot.data.reminders.LegacyScheduleAlarms
import com.txl.mebot.data.reminders.ScheduleCalendarDriver
import com.txl.mebot.data.reminders.ScheduleReminderEngine
import com.txl.mebot.data.schedule.RecurrencePattern
import com.txl.mebot.data.schedule.RecurrenceRule
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduleReminderEngineTest {
    private lateinit var app: Application
    private lateinit var database: MebotDatabase
    private lateinit var repository: ScheduleRepository
    private lateinit var engine: ScheduleReminderEngine
    private lateinit var calendar: RecordingScheduleCalendar
    private val clock = ReminderTestClock(Instant.parse("2026-10-24T08:00:00Z"))
    private var zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 24)

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(app, MebotDatabase::class.java).build()
        repository = ScheduleRepository(database, clock) { zone }
        calendar = RecordingScheduleCalendar()
        engine = ScheduleReminderEngine(database, repository, calendar, LegacyScheduleAlarms(app)::cancel, clock) { zone }
    }

    @After fun tearDown() {
        database.close()
    }

    private suspend fun create(
        time: LocalTime? = LocalTime.of(16, 15),
        rule: RecurrenceRule? = null,
        leads: List<Int>? = null,
    ): String {
        val session = repository.openSession()
        repository.createTask(session.id, "create:${session.id}", ScheduleDraft(
            "测试提醒", date = day, time = time, recurrence = rule, reminderMinutes = leads,
        ))
        return session.id
    }

    @Test fun aNewTimedScheduleIsSentToCalendarByTheBackgroundQueue() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            engine.start(scope)
            withTimeout(5_000) { engine.state.first { it.calendarSynced } }
            create(time = null)
            create(leads = emptyList())
            val sessionId = create()
            val state = withTimeout(5_000) { engine.state.first { it.nextCalendarTriggerAt != null } }
            assertEquals(Instant.parse("2026-10-24T08:05:00Z").toEpochMilli(), state.nextCalendarTriggerAt)
            val entry = calendar.entries.single()
            assertEquals(sessionId, entry.sessionId)
            assertEquals(Instant.parse("2026-10-24T08:15:00Z").toEpochMilli(), entry.startAt)
            assertEquals(listOf(10, 0), entry.pendingReminders(clock.millis()).map { it.minutesBefore })
        } finally {
            scope.cancel()
        }
    }

    @Test fun removesLegacyAlarmAndRegistersUpcomingReminderWhenPermissionIsGranted() = runBlocking {
        calendar.access = false
        create(time = LocalTime.of(16, 2))
        val occurrence = repository.observeDate(day).first().single()
        val old = database.scheduleDao().reminders(occurrence.id).single { it.minutesBefore == 0 }
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE schedule_reminders SET status = 'SCHEDULED' WHERE id = ?", arrayOf(old.id),
            )
        }
        val previousAlarm = PendingIntent.getBroadcast(app, 0,
            Intent("com.txl.mebot.SCHEDULE_ALARM")
                .setClassName(app.packageName, "com.txl.mebot.data.reminders.ScheduleAlarmReceiver")
                .setData(Uri.parse("mebot://schedule-reminder/${old.id}"))
                .putExtra("schedule_reminder_id", old.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        alarmManager.set(AlarmManager.RTC_WAKEUP, old.triggerAt, previousAlarm)
        assertEquals(previousAlarm, shadowOf(alarmManager).scheduledAlarms.single().operation)

        engine.reconcile()
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        assertTrue(calendar.entries.isEmpty())
        assertFalse(engine.state.value.calendarSynced)
        assertEquals(ReminderStatus.PENDING, database.scheduleDao().reminders(occurrence.id).single { it.id == old.id }.status)

        calendar.access = true
        engine.reconcile()
        assertEquals(listOf(0), calendar.entries.single().pendingReminders(clock.millis()).map { it.minutesBefore })
        assertEquals(old.triggerAt, engine.state.value.nextCalendarTriggerAt)
    }

    @Test fun sendsCustomOffsetsAndReconcilesEditsCompletionAndDeletion() = runBlocking {
        val first = create(leads = listOf(2, 0))
        val second = create(LocalTime.of(16, 30))
        val firstTask = requireNotNull(repository.taskForSession(first))
        val firstOccurrence = database.scheduleDao().occurrencesForTask(firstTask.id).single()
        engine.reconcile()
        assertEquals(listOf(2, 0), calendar.entries.first().pendingReminders(clock.millis()).map { it.minutesBefore })

        val revised = repository.updateTask(second, "move-calendar", repository.draftForDate(second, day)
            .copy(title = "改期测试", detail = "修改后的说明", time = LocalTime.of(17, 30)),
            requireNotNull(repository.taskForSession(second)).revision, setOf("title", "detail", "time"))
        val completed = repository.setCompleted(first, firstOccurrence.id, true, "complete-calendar", firstOccurrence.revision)
        engine.reconcile()
        val remaining = calendar.entries.single()
        assertEquals("改期测试", remaining.title)
        assertEquals("修改后的说明", remaining.detail)
        assertEquals(Instant.parse("2026-10-24T09:30:00Z").toEpochMilli(), remaining.startAt)

        repository.setCompleted(first, completed.id, false, "restore-calendar", completed.revision)
        repository.deleteTask(second, "delete-calendar", revised.revision)
        engine.reconcile()
        assertEquals(firstOccurrence.id, calendar.entries.single().occurrenceId)
    }

    @Test fun expandsRepeatingRemindersInTheBackgroundAndCancelsThemWhenStopped() = runBlocking {
        val session = create(rule = RecurrenceRule(RecurrencePattern.DAILY))
        create(time = null, rule = RecurrenceRule(RecurrencePattern.DAILY))
        engine.reconcile()
        assertEquals(36, calendar.entries.count { it.sessionId == session })
        assertEquals(1, repository.history(day, day.plusDays(60), 100).count { it.time == null })

        repository.stopRecurrence(session, "stop", requireNotNull(repository.taskForSession(session)).revision)
        engine.reconcile()
        assertTrue(calendar.entries.isEmpty())
        assertNull(engine.state.value.nextCalendarTriggerAt)
    }

    @Test fun rechecksWallTimeAndDoesNotReplayElapsedReminders() = runBlocking {
        create()
        engine.reconcile()
        zone = ZoneOffset.UTC
        engine.reconcile()
        assertEquals(Instant.parse("2026-10-24T16:05:00Z").toEpochMilli(), engine.state.value.nextCalendarTriggerAt)
        clock.current = Instant.parse("2026-10-24T16:06:00Z")
        engine.reconcile()
        assertEquals(listOf(0), calendar.entries.single().pendingReminders(clock.millis()).map { it.minutesBefore })
        clock.current = Instant.parse("2026-10-24T16:16:00Z")
        engine.reconcile()
        assertTrue(calendar.entries.isEmpty())
    }

    @Test fun testScheduleReportsItsOwnCalendarWriteAndCanBeRetried() = runBlocking {
        calendar.access = false
        assertTrue(runCatching { engine.createTestSchedule() }.isFailure)
        assertTrue(repository.observeDate(day).first().isEmpty())

        calendar.access = true
        calendar.fail = true
        val test = engine.createTestSchedule()
        assertEquals(LocalTime.of(16, 2), test.time)
        assertEquals("日历响铃测试", test.title)
        assertFalse(test.registered)
        assertTrue(engine.state.value.calendarRegistrationFailed)
        assertEquals(test.title, repository.observeDate(day).first().single().title)

        calendar.fail = false
        engine.reconcile()
        assertFalse(engine.state.value.calendarRegistrationFailed)
        assertEquals(test.title, calendar.entries.single().title)

        calendar.ringing = CalendarRinging.UNCONFIRMED
        clock.current = clock.current.plusSeconds(60)
        val ordinary = engine.createTestSchedule()
        val entry = calendar.entries.single { it.startAt > test.date.atTime(test.time).atZone(zone).toInstant().toEpochMilli() }
        assertEquals(entry.occurrenceId, calendar.lastReadOccurrenceId)
        assertEquals(CalendarRinging.UNCONFIRMED, ordinary.calendarRegistration?.ringing)
    }
}

private class RecordingScheduleCalendar : ScheduleCalendarDriver {
    var access = true
    var fail = false
    var ringing = CalendarRinging.ENABLED
    var lastReadOccurrenceId: String? = null
    var entries = emptyList<CalendarReminderEntry>()
    override fun hasAccess() = access

    override fun sync(entries: List<CalendarReminderEntry>, now: Long): CalendarRinging? {
        check(access)
        if (fail) throw IOException("calendar write failed")
        this.entries = entries
        return ringing.takeIf { entries.any { it.pendingReminders(now).isNotEmpty() } }
    }

    override fun registeredReminder(occurrenceId: String): CalendarReminderRegistration? {
        return entries.firstOrNull { it.occurrenceId == occurrenceId }?.let {
            lastReadOccurrenceId = it.occurrenceId
            CalendarReminderRegistration(7L, ringing)
        }
    }
}

private class ReminderTestClock(var current: Instant) : Clock() {
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
}
