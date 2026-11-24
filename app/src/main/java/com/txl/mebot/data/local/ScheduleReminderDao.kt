package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

data class ReminderQueueStamp(val id: String, val triggerAt: Long, val occurrenceRevision: Long)

data class ReminderWallTime(val id: String, val date: LocalDate, val time: LocalTime, val minutesBefore: Int)
data class CalendarReminderRow(
    @Embedded val reminder: ScheduleReminderEntity,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val time: LocalTime,
    val sessionId: String,
)

data class CalendarReminderEntry(
    val occurrenceId: String,
    val title: String,
    val detail: String,
    val startAt: Long,
    val zoneId: String,
    val sessionId: String,
    val reminders: List<ScheduleReminderEntity>,
) {
    fun pendingReminders(now: Long) = reminders.filter {
        it.triggerAt >= now && (it.status == ReminderStatus.PENDING || it.status == ReminderStatus.SCHEDULED)
    }
}

@Dao
interface ScheduleReminderDao {
    @Query("SELECT * FROM schedule_notification_settings WHERE id = 1")
    suspend fun settings(): ScheduleNotificationSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: ScheduleNotificationSettingsEntity)

    // 旧 SCHEDULED 记录撤销应用闹钟后会转回 PENDING，队列内容不变。
    @Query("""SELECT id, triggerAt, occurrenceRevision FROM schedule_reminders
        WHERE status IN ('PENDING', 'SCHEDULED') ORDER BY id""")
    fun observeQueue(): Flow<List<ReminderQueueStamp>>

    @Query("SELECT id FROM schedule_reminders WHERE status = 'SCHEDULED'")
    suspend fun legacyScheduledIds(): List<String>

    @Query("""SELECT r.*, o.title, o.detail, o.date, o.time, t.sessionId FROM schedule_reminders r
        JOIN schedule_occurrences o ON o.id = r.occurrenceId JOIN schedule_tasks t ON t.id = o.taskId
        WHERE r.status != 'CANCELLED' AND r.occurrenceRevision = o.revision
        AND t.deletedAt IS NULL AND o.completedAt IS NULL AND o.suppressedByRule = 0 AND o.time IS NOT NULL
        AND o.date >= :from ORDER BY o.date, o.time, r.triggerAt, r.id""")
    suspend fun calendarReminders(from: LocalDate): List<CalendarReminderRow>

    @Query("""SELECT r.id, o.date, o.time, r.minutesBefore FROM schedule_reminders r
        JOIN schedule_occurrences o ON o.id = r.occurrenceId
        WHERE r.status IN ('PENDING', 'SCHEDULED') AND r.zoneId != :zoneId AND o.time IS NOT NULL""")
    suspend fun changedWallTimes(zoneId: String): List<ReminderWallTime>
    @Query("""UPDATE schedule_reminders SET triggerAt = :time, zoneId = :zoneId, status = 'PENDING'
        WHERE id = :id AND status IN ('PENDING', 'SCHEDULED')""")
    suspend fun updateWallTime(id: String, time: Long, zoneId: String)

    @Query("""UPDATE schedule_reminders SET status = 'MISSED'
        WHERE status IN ('PENDING', 'SCHEDULED') AND triggerAt < :cutoff""")
    suspend fun expire(cutoff: Long)
    @Query("UPDATE schedule_reminders SET status = 'PENDING' WHERE id = :id AND status = 'SCHEDULED'")
    suspend fun markPending(id: String)
}
