package com.txl.mebot.data.reminders

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import com.txl.mebot.data.local.CalendarReminderEntry
import java.time.ZoneId

enum class CalendarRinging { ENABLED, UNAVAILABLE, UNCONFIRMED }

data class CalendarReminderRegistration(val eventId: Long, val ringing: CalendarRinging)

interface ScheduleCalendarDriver {
    fun hasAccess(): Boolean
    fun sync(entries: List<CalendarReminderEntry>, now: Long): CalendarRinging?
    fun registeredReminder(occurrenceId: String): CalendarReminderRegistration?
}

/** 同一次发生写入一条本机日历事件，业务数据仍以 Room 为准。 */
class ScheduleCalendarReminders(context: Context) : ScheduleCalendarDriver {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val calendarAccount = app.packageName

    override fun hasAccess() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun sync(entries: List<CalendarReminderEntry>, now: Long): CalendarRinging? {
        val calendarId = localCalendar(entries.maxOfOrNull { it.reminders.size } ?: 0)
        val existing = upcomingEvents(calendarId, now).groupBy { it.key }.toMutableMap()
        val reminders = savedReminders(existing.values.flatten().map { it.id })
        val operations = ArrayList<ContentProviderOperation>()
        val ringingKeys = mutableSetOf<String>()
        fun flush() {
            if (operations.isEmpty()) return
            resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            operations.clear()
        }
        fun reserve(count: Int) {
            if (operations.size + count > BATCH_LIMIT) flush()
        }
        for (entry in entries) {
            val key = eventKey(entry.occurrenceId)
            val legacyKey = entry.reminders.firstOrNull { it.minutesBefore == 0 }?.let { "$KEY_PREFIX${it.id}" }
            val candidates = existing.remove(key).orEmpty() + legacyKey?.let { existing.remove(it) }.orEmpty()
            val pending = entry.pendingReminders(now).map { it.minutesBefore }.toSet()
            if (pending.isNotEmpty()) ringingKeys += key
            val method = if (entry.sessionId.startsWith(LEGACY_DEFAULT_TEST_PREFIX)) Reminders.METHOD_DEFAULT else Reminders.METHOD_ALERT
            val reusable = candidates.firstOrNull { it.canReuse(entry, reminders[it.id].orEmpty(), pending, method, now) }
            val keepUntilWrite = reusable ?: candidates.firstOrNull().takeIf { pending.isNotEmpty() }
            for (event in candidates) if (event != keepUntilWrite) {
                reserve(1)
                operations += deleteEvent(calendarId, event)
            }
            if (reusable != null) {
                val changes = reusable.metadataChanges(entry, key)
                if (changes.size() > 0) {
                    reserve(1)
                    operations += ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                        .withSelection(OWNED_EVENT, ownedArgs(calendarId, reusable))
                        .withValues(changes).withExpectedCount(1).build()
                }
                continue
            }
            if (pending.isEmpty()) continue

            // 日程变动时，旧事件与新事件和提醒在同一批次替换。
            reserve(2 + pending.size)
            keepUntilWrite?.let { operations += deleteEvent(calendarId, it) }
            val index = operations.size
            operations += ContentProviderOperation.newInsert(Events.CONTENT_URI)
                .withValue(Events.CALENDAR_ID, calendarId)
                .withValue(Events.TITLE, entry.title)
                .withValue(Events.DESCRIPTION, entry.detail)
                .withValue(Events.DTSTART, entry.startAt)
                .withValue(Events.DTEND, entry.startAt + EVENT_DURATION)
                .withValue(Events.EVENT_TIMEZONE, entry.zoneId)
                .withValue(Events.ALL_DAY, 0)
                .withValue(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
                .withValue(Events.UID_2445, key).build()
            for (minutes in pending) {
                operations += ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                    .withValueBackReference(Reminders.EVENT_ID, index)
                    .withValue(Reminders.MINUTES, minutes)
                    .withValue(Reminders.METHOD, method).build()
            }
        }
        for (event in existing.values.flatten()) {
            reserve(1)
            operations += deleteEvent(calendarId, event)
        }
        flush()
        return enableRinging(calendarId, now, ringingKeys)
    }

    override fun registeredReminder(occurrenceId: String): CalendarReminderRegistration? {
        val calendarId = findCalendar()?.id ?: return null
        val event = resolver.query(Events.CONTENT_URI, null,
            "${Events.CALENDAR_ID} = ? AND ${Events.UID_2445} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString(), eventKey(occurrenceId)), null,
        )?.use { cursor ->
            if (cursor.count != 1 || !cursor.moveToFirst()) return null
            val ringingColumn = cursor.getColumnIndex(FORCE_REMINDER)
            val ringing = when {
                ringingColumn < 0 -> CalendarRinging.UNAVAILABLE
                cursor.getInt(ringingColumn) == 1 -> CalendarRinging.ENABLED
                else -> CalendarRinging.UNCONFIRMED
            }
            CalendarReminderRegistration(cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID)), ringing)
        } ?: return null
        val registered = resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.METHOD),
            "${Reminders.EVENT_ID} = ? AND ${Reminders.MINUTES} = 0", arrayOf(event.eventId.toString()), null,
        )?.use { it.count == 1 && it.moveToFirst() && !it.isNull(0) && it.getInt(0) == Reminders.METHOD_ALERT } == true
        return event.takeIf { registered }
    }

    private fun enableRinging(calendarId: Long, now: Long, keys: Set<String>): CalendarRinging? {
        if (keys.isEmpty()) return null
        return try {
            val before = ringingStates(calendarId, now) ?: return CalendarRinging.UNAVAILABLE
            val disabledKeys = keys.filter { before[it] == false }
            if (disabledKeys.isEmpty() && keys.all { before[it] == true }) return CalendarRinging.ENABLED

            // 先提交标准日程和提醒，再单独写厂商字段，保留普通提醒作为回退。
            val values = ContentValues().apply { put(FORCE_REMINDER, 1) }
            for (batch in disabledKeys.chunked(BATCH_LIMIT)) {
                val placeholders = batch.joinToString(",") { "?" }
                resolver.update(Events.CONTENT_URI, values,
                    "$UPCOMING_EVENTS AND ${Events.HAS_ALARM} = 1 AND ${Events.UID_2445} IN ($placeholders)",
                    upcomingArgs(calendarId, now) + batch,
                )
            }
            val after = ringingStates(calendarId, now)
            if (keys.all { after?.get(it) == true }) CalendarRinging.ENABLED else CalendarRinging.UNCONFIRMED
        } catch (_: RuntimeException) {
            CalendarRinging.UNCONFIRMED
        }
    }

    private fun ringingStates(calendarId: Long, now: Long): Map<String, Boolean>? = upcomingRows(calendarId, now, null).use { cursor ->
        val ringingColumn = cursor.getColumnIndex(FORCE_REMINDER)
        if (ringingColumn < 0) return null
        val keyColumn = cursor.getColumnIndexOrThrow(Events.UID_2445)
        buildMap {
            while (cursor.moveToNext()) put(cursor.getString(keyColumn), cursor.getInt(ringingColumn) == 1)
        }
    }

    private fun localCalendar(reminderCount: Int): Long {
        check(hasAccess()) { "请允许访问日历" }
        val existing = findCalendar()
        if (existing != null && existing.maxReminders >= reminderCount) return existing.id
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, calendarAccount)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        if (existing != null) {
            val values = ContentValues().apply { put(Calendars.MAX_REMINDERS, reminderCount) }
            check(resolver.update(ContentUris.withAppendedId(uri, existing.id), values, null, null) == 1) {
                "无法更新本机日历的提醒数量上限"
            }
            return existing.id
        }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, calendarAccount)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, "mebot 到点提醒")
            put(Calendars.OWNER_ACCOUNT, calendarAccount)
            put(Calendars.CALENDAR_COLOR, 0xFF67508B.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
            put(Calendars.ALLOWED_REMINDERS, ALLOWED_REMINDERS)
            put(Calendars.MAX_REMINDERS, maxOf(5, reminderCount))
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(uri, values)) { "无法创建本机日历" })
    }

    private fun findCalendar(): LocalCalendar? = checkNotNull(resolver.query(
        Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.MAX_REMINDERS),
        "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.NAME} = ?",
        arrayOf(calendarAccount, CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_NAME), null,
    )) { "无法读取本机日历" }.use { if (it.moveToFirst()) LocalCalendar(it.getLong(0), it.getInt(1)) else null }

    private fun upcomingEvents(calendarId: Long, now: Long): List<CalendarEvent> = upcomingRows(calendarId, now,
        arrayOf(Events._ID, Events.UID_2445, Events.TITLE, Events.DESCRIPTION, Events.DTSTART, Events.DTEND,
            Events.EVENT_TIMEZONE, Events.HAS_ALARM, Events.ALL_DAY, Events.RRULE, Events.RDATE),
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(CalendarEvent(
                id = rows.getLong(0), key = rows.getString(1), title = rows.getString(2), detail = rows.getString(3),
                start = rows.getLong(4), end = rows.getLong(5), zoneId = rows.getString(6), hasAlarm = rows.getInt(7) == 1,
                timedOnce = rows.getInt(8) == 0 && rows.getString(9).isNullOrEmpty() && rows.getString(10).isNullOrEmpty(),
            ))
        }
    }

    private fun savedReminders(eventIds: List<Long>): Map<Long, List<SavedReminder>> {
        val result = mutableMapOf<Long, MutableList<SavedReminder>>()
        for (ids in eventIds.chunked(BATCH_LIMIT)) {
            val placeholders = ids.joinToString(",") { "?" }
            checkNotNull(resolver.query(Reminders.CONTENT_URI,
                arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN ($placeholders)", ids.map(Long::toString).toTypedArray(), null,
            )) { "无法读取日历提醒分钟数" }.use { rows ->
                while (rows.moveToNext()) {
                    result.getOrPut(rows.getLong(0)) { mutableListOf() }.add(SavedReminder(rows.getInt(1), rows.getInt(2)))
                }
            }
        }
        return result
    }

    private fun upcomingRows(calendarId: Long, now: Long, projection: Array<String>?): Cursor = checkNotNull(
        resolver.query(Events.CONTENT_URI, projection, UPCOMING_EVENTS, upcomingArgs(calendarId, now), null),
    ) { "无法读取日历提醒" }

    private fun upcomingArgs(calendarId: Long, now: Long) = arrayOf(calendarId.toString(), "$KEY_PREFIX%", now.toString())

    private fun deleteEvent(calendarId: Long, event: CalendarEvent) = ContentProviderOperation
        .newDelete(Events.CONTENT_URI).withSelection(OWNED_EVENT, ownedArgs(calendarId, event)).build()

    private fun ownedArgs(calendarId: Long, event: CalendarEvent) = arrayOf(event.id.toString(), calendarId.toString(), event.key)

    private data class LocalCalendar(val id: Long, val maxReminders: Int)
    private data class SavedReminder(val minutes: Int, val method: Int)

    private data class CalendarEvent(
        val id: Long,
        val key: String,
        val title: String?,
        val detail: String?,
        val start: Long,
        val end: Long,
        val zoneId: String?,
        val hasAlarm: Boolean,
        val timedOnce: Boolean,
    ) {
        fun canReuse(entry: CalendarReminderEntry, saved: List<SavedReminder>, pending: Set<Int>, method: Int, now: Long): Boolean {
            if (!timedOnce || start != entry.startAt || zoneId != entry.zoneId || hasAlarm != saved.isNotEmpty()) return false
            val minutes = saved.map { it.minutes }.toSet()
            val elapsed = entry.reminders.filter { it.triggerAt < now }.map { it.minutesBefore }
            return minutes.size == saved.size && saved.all { it.method == method } &&
                minutes.containsAll(pending) && (pending + elapsed).containsAll(minutes)
        }

        fun metadataChanges(entry: CalendarReminderEntry, wantedKey: String) = ContentValues().apply {
            if (key != wantedKey) put(Events.UID_2445, wantedKey)
            if (title != entry.title) put(Events.TITLE, entry.title)
            if (detail.orEmpty() != entry.detail) put(Events.DESCRIPTION, entry.detail)
            if (end != entry.startAt + EVENT_DURATION) put(Events.DTEND, entry.startAt + EVENT_DURATION)
        }
    }

    private fun eventKey(occurrenceId: String) = "${KEY_PREFIX}occurrence:$occurrenceId"

    private companion object {
        const val CALENDAR_NAME = "mebot_schedule_reminders"
        const val ALLOWED_REMINDERS = "${Reminders.METHOD_DEFAULT},${Reminders.METHOD_ALERT}"
        // OPPO 日历将独立响铃开关保存在这个扩展列中。
        const val FORCE_REMINDER = "force_reminder"
        const val LEGACY_DEFAULT_TEST_PREFIX = "calendar-test:METHOD_DEFAULT:"
        const val KEY_PREFIX = "mebot-schedule:"
        const val OWNED_EVENT = "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.UID_2445} = ? AND ${Events.DELETED} = 0"
        const val UPCOMING_EVENTS = "${Events.CALENDAR_ID} = ? AND ${Events.UID_2445} LIKE ? AND ${Events.DTSTART} >= ? AND ${Events.DELETED} = 0"
        const val EVENT_DURATION = 5 * 60_000L
        const val BATCH_LIMIT = 60
    }
}
