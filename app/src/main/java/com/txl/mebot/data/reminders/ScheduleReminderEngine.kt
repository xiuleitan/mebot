package com.txl.mebot.data.reminders

import androidx.room.withTransaction
import com.txl.mebot.data.local.CalendarReminderEntry
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.schedule.ScheduleDraft
import com.txl.mebot.data.schedule.ScheduleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReminderEngineState(
    val calendarSynced: Boolean = false,
    val nextCalendarTriggerAt: Long? = null,
    val calendarRegistrationFailed: Boolean = false,
    val calendarRinging: CalendarRinging? = null,
)
data class ReminderTestSchedule(
    val date: LocalDate,
    val time: LocalTime,
    val title: String,
    val calendarRegistration: CalendarReminderRegistration? = null,
) {
    val registered get() = calendarRegistration != null
}

/** Room 保存日程和提醒配置，系统日历负责触发提醒。 */
class ScheduleReminderEngine(
    private val database: MebotDatabase,
    private val schedules: ScheduleRepository,
    private val calendar: ScheduleCalendarDriver,
    private val cancelLegacyAlarm: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val dao = database.reminderDao()
    private val mutex = Mutex()
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(ReminderEngineState())
    val state = mutableState.asStateFlow()
    private var started = false

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            try { dao.observeQueue().distinctUntilChanged().collect { requestRefresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = ReminderEngineState(calendarRegistrationFailed = true) }
        }
        scope.launch {
            try { schedules.observeTaskRevisions().collect { requestRefresh() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = ReminderEngineState(calendarRegistrationFailed = true) }
        }
        scope.launch {
            for (ignored in refreshes) {
                reconcile()
            }
        }
        requestRefresh()
    }

    fun requestRefresh() { refreshes.trySend(Unit) }

    suspend fun reconcile() = mutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked() {
        try {
            for (id in dao.legacyScheduledIds()) {
                cancelLegacyAlarm(id)
                dao.markPending(id)
            }
            if (!calendar.hasAccess()) {
                mutableState.value = ReminderEngineState()
                return
            }
            prepareQueue()
            val now = clock.millis()
            val upcoming = calendarEntries(now)
            val ringing = calendar.sync(upcoming, now)
            val next = upcoming.flatMap { it.pendingReminders(now) }.minOfOrNull { it.triggerAt }
            mutableState.value = ReminderEngineState(
                calendarSynced = true, nextCalendarTriggerAt = next, calendarRinging = ringing,
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = ReminderEngineState(calendarRegistrationFailed = true) }
    }

    private suspend fun calendarEntries(now: Long): List<CalendarReminderEntry> {
        val currentZone = zone()
        val today = Instant.ofEpochMilli(now).atZone(currentZone).toLocalDate()
        return dao.calendarReminders(today).groupBy { it.reminder.occurrenceId }.mapNotNull { (id, rows) ->
            val first = rows.first()
            val startAt = first.date.atTime(first.time).atZone(currentZone).toInstant().toEpochMilli()
            if (startAt < now) return@mapNotNull null
            CalendarReminderEntry(
                occurrenceId = id,
                title = first.title,
                detail = first.detail,
                startAt = startAt,
                zoneId = currentZone.id,
                sessionId = first.sessionId,
                reminders = rows.map { it.reminder },
            )
        }
    }

    private suspend fun prepareQueue() {
        val currentZone = zone()
        val current = clock.instant().atZone(currentZone).toLocalDate()
        // 独立于用户是否浏览过这些日期，滚动展开下一段周期提醒。
        schedules.materializeReminderRange(current, current.plusDays(35))
        database.withTransaction {
            for (item in dao.changedWallTimes(currentZone.id)) {
                val trigger = item.date.atTime(item.time).atZone(currentZone).toInstant().toEpochMilli() - item.minutesBefore * 60_000L
                dao.updateWallTime(item.id, trigger, currentZone.id)
            }
            dao.expire(clock.millis() - LATE_GRACE_MILLIS)
        }
    }

    suspend fun createTestSchedule(): ReminderTestSchedule = mutex.withLock {
        check(calendar.hasAccess())
        val title = "日历响铃测试"
        val whenToRing = clock.instant().atZone(zone()).withSecond(0).withNano(0).plusMinutes(2)
        val sessionId = database.withTransaction {
            val session = schedules.openSession()
            val userMessage = "${whenToRing.toLocalDate()} ${whenToRing.toLocalTime()} 创建${title}日程"
            schedules.appendMessage(session.id, UUID.randomUUID().toString(), MessageRole.USER, userMessage)
            schedules.createTask(session.id, UUID.randomUUID().toString(), ScheduleDraft(
                title, "由通知提醒中的测试入口创建，可在日程中查看、修改或删除。",
                whenToRing.toLocalDate(), whenToRing.toLocalTime(), reminderMinutes = listOf(0),
            ))
            schedules.appendMessage(session.id, UUID.randomUUID().toString(), MessageRole.ASSISTANT,
                "已创建「$title」，计划在 ${whenToRing.toLocalTime()} 提醒。可在通知提醒中查看登记状态。")
            session.id
        }
        reconcileLocked()
        val calendarRegistration = if (mutableState.value.calendarSynced) {
            try {
                calendarEntries(clock.millis()).firstOrNull { it.sessionId == sessionId }
                    ?.let { calendar.registeredReminder(it.occurrenceId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        ReminderTestSchedule(whenToRing.toLocalDate(), whenToRing.toLocalTime(), title, calendarRegistration)
    }

    private companion object { const val LATE_GRACE_MILLIS = 60_000L }
}
