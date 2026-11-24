package com.txl.mebot.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.CalendarReminderEntry
import com.txl.mebot.data.local.ReminderStatus
import com.txl.mebot.data.local.ScheduleReminderEntity
import com.txl.mebot.data.reminders.CalendarRinging
import com.txl.mebot.data.reminders.ScheduleCalendarReminders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScheduleCalendarRemindersTest {
    private lateinit var provider: CalendarProvider
    private val now = 1_700_000_000_000L
    private val startAt = now + 15 * 60_000L
    private val entry = CalendarReminderEntry(
        occurrenceId = "test",
        title = "洗衣服",
        detail = "带上洗衣液\n参照本任务图片",
        startAt = startAt,
        zoneId = "Asia/Shanghai",
        sessionId = "test-session",
        reminders = listOf(2, 0).map { minutes ->
            ScheduleReminderEntity("reminder-$minutes", "test", 1, minutes,
                startAt - minutes * 60_000L, "Asia/Shanghai", ReminderStatus.PENDING, createdAt = now)
        },
    )

    private fun calendar(exposesRinging: Boolean): ScheduleCalendarReminders {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        provider = Robolectric.buildContentProvider(CalendarProvider::class.java).create(CalendarContract.AUTHORITY).get()
        provider.database = SQLiteDatabase.create(null).apply {
            execSQL("""CREATE TABLE calendars (_id INTEGER PRIMARY KEY, account_name TEXT, account_type TEXT, name TEXT,
                ${Calendars.MAX_REMINDERS} INTEGER DEFAULT 5)""")
            execSQL("INSERT INTO calendars (_id, account_name, account_type, name) VALUES (1, ?, ?, ?)",
                arrayOf(app.packageName, CalendarContract.ACCOUNT_TYPE_LOCAL, "mebot_schedule_reminders"))
            val ringing = if (exposesRinging) ", force_reminder INTEGER DEFAULT 0" else ""
            execSQL("""CREATE TABLE events (_id INTEGER PRIMARY KEY, ${Events.CALENDAR_ID} INTEGER, title TEXT, description TEXT,
                dtstart INTEGER, dtend INTEGER, eventTimezone TEXT, availability INTEGER, ${Events.UID_2445} TEXT,
                allDay INTEGER DEFAULT 0, rrule TEXT, rdate TEXT, hasAlarm INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0$ringing)""")
            execSQL("CREATE TABLE reminders (_id INTEGER PRIMARY KEY, event_id INTEGER, minutes INTEGER, method INTEGER)")
            execSQL("""CREATE TRIGGER delete_event_reminders AFTER DELETE ON events
                BEGIN DELETE FROM reminders WHERE event_id = OLD._id; END""")
        }
        return ScheduleCalendarReminders(app)
    }

    @After fun closeProvider() { if (::provider.isInitialized) provider.database.close() }

    @Test fun verifiesRingingAndUpdatesOnlyTheOwnedEventInPlaceOnRetry() {
        val calendar = calendar(exposesRinging = true)
        provider.database.execSQL("INSERT INTO events (_id, calendar_id, dtstart, ${Events.UID_2445}, hasAlarm) VALUES (1, 1, ?, 'other', 1)",
            arrayOf(startAt))
        provider.ignoreRingingWrites = true
        assertEquals(CalendarRinging.UNCONFIRMED, calendar.sync(listOf(entry), now))
        val original = requireNotNull(calendar.registeredReminder(entry.occurrenceId))
        assertEquals(CalendarRinging.UNCONFIRMED, original.ringing)

        provider.ignoreRingingWrites = false
        assertEquals(CalendarRinging.ENABLED, calendar.sync(listOf(entry), now))
        assertEquals(original.copy(ringing = CalendarRinging.ENABLED), calendar.registeredReminder(entry.occurrenceId))
        provider.database.rawQuery("SELECT force_reminder FROM events WHERE _id = 1", null).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        assertEquals(listOf(0, 2), reminderMinutes(original.eventId))
    }

    @Test fun standardCalendarKeepsTheAlertWithoutTheRingingColumn() {
        val calendar = calendar(exposesRinging = false)
        assertEquals(CalendarRinging.UNAVAILABLE, calendar.sync(listOf(entry), now))
        assertEquals(CalendarRinging.UNAVAILABLE, calendar.registeredReminder(entry.occurrenceId)?.ringing)
    }

    @Test fun replacesTheOldShortEventWithOneTimedEventWithNotesAndBothOffsets() {
        val calendar = calendar(exposesRinging = true)
        provider.database.execSQL("""INSERT INTO events (_id, calendar_id, title, dtstart, dtend, eventTimezone,
            ${Events.UID_2445}, hasAlarm) VALUES (1, 1, ?, ?, ?, ?, 'mebot-schedule:reminder-0', 1)""",
            arrayOf<Any>(entry.title, startAt, startAt + 60_000L, entry.zoneId))
        provider.database.execSQL("INSERT INTO reminders (event_id, minutes, method) VALUES (1, 0, ?)",
            arrayOf(Reminders.METHOD_ALERT))

        assertEquals(CalendarRinging.ENABLED, calendar.sync(listOf(entry), now))
        val saved = requireNotNull(calendar.registeredReminder(entry.occurrenceId))
        provider.database.rawQuery("SELECT title, description, dtstart, dtend, allDay, rrule, rdate FROM events", null).use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(entry.title, it.getString(0))
            assertEquals(entry.detail, it.getString(1))
            assertEquals(startAt, it.getLong(2))
            assertEquals(startAt + 5 * 60_000L, it.getLong(3))
            assertEquals(0, it.getInt(4))
            assertEquals(null, it.getString(5))
            assertEquals(null, it.getString(6))
        }
        assertEquals(listOf(0, 2), reminderMinutes(saved.eventId))

        val afterAdvance = now + 14 * 60_000L
        val edited = entry.copy(detail = "更新的备注")
        assertEquals(CalendarRinging.ENABLED, calendar.sync(listOf(edited), afterAdvance))
        assertEquals(saved, calendar.registeredReminder(entry.occurrenceId))
        assertEquals(listOf(0, 2), reminderMinutes(saved.eventId))
        provider.database.rawQuery("SELECT description FROM events", null).use {
            it.moveToFirst()
            assertEquals(edited.detail, it.getString(0))
        }

        calendar.sync(emptyList(), afterAdvance)
        assertEquals(null, calendar.registeredReminder(entry.occurrenceId))
        assertEquals(emptyList<Int>(), reminderMinutes(saved.eventId))
    }

    @Test fun advanceOnlyUsesTheRequestedOffsetWithoutAddingAnOnTimeReminder() {
        val calendar = calendar(exposesRinging = true)
        val advanceOnly = entry.copy(reminders = entry.reminders.filter { it.minutesBefore == 2 })
        val afterAdvance = now + 14 * 60_000L
        assertEquals(null, calendar.sync(listOf(advanceOnly), afterAdvance))
        provider.database.rawQuery("SELECT _id FROM events", null).use { assertEquals(0, it.count) }

        assertEquals(CalendarRinging.ENABLED, calendar.sync(listOf(advanceOnly), now))
        assertEquals(null, calendar.sync(listOf(advanceOnly), afterAdvance))
        provider.database.rawQuery("SELECT _id, dtstart, force_reminder FROM events", null).use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(startAt, it.getLong(1))
            assertEquals(1, it.getInt(2))
            assertEquals(listOf(2), reminderMinutes(it.getLong(0)))
        }
    }

    private fun reminderMinutes(eventId: Long): List<Int> = provider.database.rawQuery(
        "SELECT minutes, method FROM reminders WHERE event_id = ? ORDER BY minutes", arrayOf(eventId.toString()),
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                assertEquals(Reminders.METHOD_ALERT, rows.getInt(1))
                add(rows.getInt(0))
            }
        }
    }

    class CalendarProvider : ContentProvider() {
        lateinit var database: SQLiteDatabase
        var ignoreRingingWrites = false
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            database.query(uri.pathSegments.first(), projection, selection, selectionArgs, null, null, sortOrder)

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val data = requireNotNull(values)
            val table = uri.pathSegments.first()
            val id = database.insertOrThrow(table, null, data)
            if (table == "reminders") {
                database.execSQL("UPDATE events SET hasAlarm = 1 WHERE _id = ?", arrayOf(data.getAsLong(Reminders.EVENT_ID)))
            }
            return ContentUris.withAppendedId(uri, id)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (ignoreRingingWrites && values?.containsKey("force_reminder") == true) return 1
            return database.update(uri.pathSegments.first(), values, selection, selectionArgs)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            database.delete(uri.pathSegments.first(), selection, selectionArgs)
    }
}
