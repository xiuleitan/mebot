package com.txl.mebot.data.local

import androidx.room.*
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** 同时校验发生记录版本和提醒的原定时间；完成后被取消的提醒仅用于核对已出现的历史。 */
data class HomeScheduleLead(val occurrenceId: String, val occurrenceRevision: Long,
    val reminderRevision: Long, val triggerAt: Long, val minutesBefore: Int, val zoneId: String,
    val status: ReminderStatus)
data class HomeNoteSummary(val id: String, val title: String, val excerpt: String,
    val status: FlashnoteStatus, val createdAt: Long, val completedAt: Long?, val revision: Long, val coverRelativePath: String?)

data class HomeSubscriptionRecord(@Embedded val record: SubscriptionEntity, val coverRelativePath: String?, val builtinKey: String?)

@Dao
interface HomeDao {
    @Query("SELECT * FROM home_events WHERE eventDate BETWEEN :from AND :through ORDER BY eventDate DESC, createdAt, id")
    fun observeEvents(from: LocalDate, through: LocalDate): Flow<List<HomeEventEntity>>
    @Query("SELECT s.*, m.relativePath AS coverRelativePath, m.builtinKey AS builtinKey FROM subscriptions s LEFT JOIN media_assets m ON m.id = s.coverAssetId WHERE s.status != 'DELETED'")
    fun observeSubscriptions(): Flow<List<HomeSubscriptionRecord>>
    @Query("""SELECT o.*, t.sessionId, m.source AS coverSource, m.relativePath AS coverRelativePath, m.builtinKey AS coverBuiltinKey,
        (SELECT COUNT(*) FROM schedule_local_attachments a WHERE a.sessionId = t.sessionId) AS attachmentCount,
        r.taskId AS recurrence_taskId, r.taskRevision AS recurrence_taskRevision,
        r.pattern AS recurrence_pattern, r.intervalDays AS recurrence_intervalDays,
        r.weekDayMask AS recurrence_weekDayMask, r.monthDay AS recurrence_monthDay,
        r.endDate AS recurrence_endDate, r.enabled AS recurrence_enabled,
        COALESCE((SELECT enabled FROM schedule_recurrences currentRule WHERE currentRule.taskId = t.id AND currentRule.taskRevision = t.revision), 0) = 0 AS recurrenceStopped,
        EXISTS(SELECT 1 FROM schedule_recurrences rule WHERE rule.taskId = t.id AND (rule.taskRevision = t.revision OR rule.taskRevision = o.taskRevision)) AS isRecurring
        FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId LEFT JOIN media_assets m ON m.id = o.coverAssetId
        LEFT JOIN schedule_recurrences r ON r.taskId = o.taskId AND r.taskRevision = o.taskRevision
        WHERE o.id = :id AND t.deletedAt IS NULL AND o.suppressedByRule = 0""")
    suspend fun schedule(id: String): ScheduleDisplayRecord?
    @Query("SELECT * FROM home_events WHERE id = :id") suspend fun event(id: String): HomeEventEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEvent(value: HomeEventEntity)
    @Update suspend fun updateEvent(value: HomeEventEntity): Int
    @Query("SELECT * FROM home_events WHERE kind = :kind AND sourceId = :id")
    suspend fun sourceEvents(kind: HomeEventKind, id: String): List<HomeEventEntity>
    @Query("SELECT * FROM home_events WHERE resolvedById = :id")
    suspend fun resolvedEvents(id: String): List<HomeEventEntity>
    @Query("SELECT * FROM home_events WHERE kind IN ('DAYIMA_PREPARE', 'DAYIMA_MISSING')")
    suspend fun dayimaEvents(): List<HomeEventEntity>
    @Query("SELECT * FROM home_events WHERE kind IN ('DAYIMA_PREPARE', 'DAYIMA_MISSING') AND completedAt IS NULL")
    suspend fun openDayimaEvents(): List<HomeEventEntity>
    @Query("SELECT * FROM home_flashnote_days WHERE day BETWEEN :from AND :through")
    fun observeFlashnoteDays(from: LocalDate, through: LocalDate): Flow<List<HomeFlashnoteDayEntity>>
    @Query("SELECT * FROM home_flashnote_days WHERE day = :day") suspend fun flashnoteDay(day: LocalDate): HomeFlashnoteDayEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFlashnoteDay(value: HomeFlashnoteDayEntity): Long
    @Query("UPDATE home_flashnote_days SET selectedNoteId = :id, updatedAt = :now WHERE day = :day")
    suspend fun selectFlashnote(day: LocalDate, id: String?, now: Long)

    @Query("SELECT * FROM home_flashnote_pin WHERE id = 1") fun observeFlashnotePin(): Flow<HomeFlashnotePinEntity?>
    @Query("SELECT * FROM home_flashnote_pin WHERE id = 1") suspend fun flashnotePin(): HomeFlashnotePinEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFlashnotePin(value: HomeFlashnotePinEntity)
    @Query("DELETE FROM home_flashnote_pin WHERE noteId = :id") suspend fun clearFlashnotePin(id: String)

    @Query("""SELECT o.*, t.sessionId, m.source AS coverSource, m.relativePath AS coverRelativePath, m.builtinKey AS coverBuiltinKey,
        (SELECT COUNT(*) FROM schedule_local_attachments a WHERE a.sessionId = t.sessionId) AS attachmentCount,
        r.taskId AS recurrence_taskId, r.taskRevision AS recurrence_taskRevision,
        r.pattern AS recurrence_pattern, r.intervalDays AS recurrence_intervalDays,
        r.weekDayMask AS recurrence_weekDayMask, r.monthDay AS recurrence_monthDay,
        r.endDate AS recurrence_endDate, r.enabled AS recurrence_enabled,
        COALESCE((SELECT enabled FROM schedule_recurrences currentRule WHERE currentRule.taskId = t.id AND currentRule.taskRevision = t.revision), 0) = 0 AS recurrenceStopped,
        EXISTS(SELECT 1 FROM schedule_recurrences rule WHERE rule.taskId = t.id AND (rule.taskRevision = t.revision OR rule.taskRevision = o.taskRevision)) AS isRecurring
        FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId
        LEFT JOIN media_assets m ON m.id = o.coverAssetId
        LEFT JOIN schedule_recurrences r ON r.taskId = o.taskId AND r.taskRevision = o.taskRevision
        WHERE t.deletedAt IS NULL AND o.suppressedByRule = 0 AND (
          o.date BETWEEN :from AND :through OR EXISTS(SELECT 1 FROM schedule_reminders r
            WHERE r.occurrenceId = o.id AND r.occurrenceRevision = o.revision AND r.status != 'CANCELLED'
              AND r.triggerAt >= :fromMillis AND r.triggerAt < :untilMillis) OR EXISTS(SELECT 1 FROM home_events h WHERE h.kind = 'SCHEDULE' AND h.sourceId = o.id AND h.cancelled = 0 AND h.eventDate BETWEEN :from AND :through)) ORDER BY o.date, o.time, o.id""")
    fun observeSchedules(from: LocalDate, through: LocalDate, fromMillis: Long, untilMillis: Long): Flow<List<ScheduleDisplayRecord>>
    @Query("""SELECT r.occurrenceId, o.revision AS occurrenceRevision, r.occurrenceRevision AS reminderRevision,
        r.triggerAt, r.minutesBefore, r.zoneId, r.status FROM schedule_reminders r
        JOIN schedule_occurrences o ON o.id = r.occurrenceId JOIN schedule_tasks t ON t.id = o.taskId
        WHERE t.deletedAt IS NULL AND o.suppressedByRule = 0
          AND r.triggerAt >= :from AND r.triggerAt < :until AND (
            (o.isException = 0 AND EXISTS(SELECT 1 FROM schedule_reminder_leads l
              WHERE l.taskId = o.taskId AND l.taskRevision = o.taskRevision AND l.minutesBefore = r.minutesBefore)) OR
            (o.isException = 1 AND EXISTS(SELECT 1 FROM schedule_occurrence_leads l
              WHERE l.occurrenceId = o.id AND l.minutesBefore = r.minutesBefore)))""")
    fun observeScheduleLeads(from: Long, until: Long): Flow<List<HomeScheduleLead>>
    @Query("""SELECT n.id, n.title, substr(n.content, 1, 160) AS excerpt, n.status, n.createdAt, n.completedAt, n.revision,
        (SELECT m.relativePath FROM flashnote_attachments a JOIN media_assets m ON m.id = a.assetId
          WHERE a.noteId = n.id AND m.mimeType LIKE 'image/%' AND m.width > 0 AND m.height > 0 ORDER BY a.position LIMIT 1) AS coverRelativePath
        FROM flashnotes n WHERE n.status = 'PENDING' OR (n.completedAt >= :from AND n.completedAt < :until)
        ORDER BY n.createdAt, n.id""")
    fun observeNotes(from: Long, until: Long): Flow<List<HomeNoteSummary>>
}

@Dao
interface DayimaReminderDao {
    @Query("SELECT * FROM dayima_reminder_plans ORDER BY triggerAt, id") suspend fun plans(): List<DayimaReminderPlanEntity>
    @Query("SELECT * FROM dayima_reminder_plans ORDER BY triggerAt, id") fun observePlans(): Flow<List<DayimaReminderPlanEntity>>
    @Query("SELECT * FROM dayima_reminder_plans WHERE id = :id") suspend fun plan(id: String): DayimaReminderPlanEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlan(value: DayimaReminderPlanEntity)
    @Query("""UPDATE dayima_reminder_plans SET deliveryState = 'POSTING', attemptId = :attempt,
        attemptStartedAt = :now, updatedAt = :now WHERE id = :id AND generation = :generation AND active = 1
        AND deliveryState IN ('PENDING','SCHEDULED','BLOCKED','FAILED')""")
    suspend fun claim(id: String, generation: String, attempt: String, now: Long): Int
    @Query("""UPDATE dayima_reminder_plans SET deliveryState = :state, deliveredAt = :deliveredAt,
        failure = :failure, updatedAt = :now WHERE id = :id AND attemptId = :attempt AND deliveryState = 'POSTING'""")
    suspend fun finish(id: String, attempt: String, state: DayimaDeliveryState, deliveredAt: Long?, failure: String?, now: Long): Int
    @Query("""UPDATE dayima_reminder_plans SET deliveryState = :state, failure = :failure, updatedAt = :now
        WHERE id = :id AND generation = :generation AND active = 1 AND deliveryState NOT IN ('POSTING','DELIVERED','UNCERTAIN')""")
    suspend fun state(id: String, generation: String, state: DayimaDeliveryState, failure: String?, now: Long): Int
}
