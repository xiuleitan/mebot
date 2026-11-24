package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Insert suspend fun insertSession(session: ScheduleSessionEntity)
    @Query("SELECT * FROM schedule_sessions WHERE id = :id")
    suspend fun session(id: String): ScheduleSessionEntity?
    @Query("UPDATE schedule_sessions SET updatedAt = :now WHERE id = :id")
    suspend fun touchSession(id: String, now: Long)

    @Insert suspend fun insertMessage(message: ScheduleMessageEntity)
    @Query("SELECT * FROM schedule_messages WHERE id = :id")
    suspend fun message(id: String): ScheduleMessageEntity?
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM schedule_messages WHERE sessionId = :sessionId")
    suspend fun nextMessageSequence(sessionId: String): Long
    @Query("SELECT * FROM schedule_messages WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
    suspend fun recentMessages(sessionId: String, limit: Int): List<ScheduleMessageEntity>

    @Insert suspend fun insertTask(task: ScheduleTaskEntity)
    @Update suspend fun updateTask(task: ScheduleTaskEntity)
    @Query("SELECT * FROM schedule_tasks WHERE sessionId = :sessionId")
    suspend fun taskForSession(sessionId: String): ScheduleTaskEntity?
    @Query("SELECT * FROM schedule_tasks WHERE sessionId = :sessionId")
    fun observeTaskForSession(sessionId: String): Flow<ScheduleTaskEntity?>
    @Query("SELECT * FROM schedule_tasks WHERE id = :id")
    suspend fun task(id: String): ScheduleTaskEntity?
    @Query("SELECT id, revision FROM schedule_tasks ORDER BY id")
    fun observeTaskRevisions(): Flow<List<ScheduleTaskRevision>>
    @Insert suspend fun insertVersion(version: ScheduleTaskVersionEntity)
    @Query("SELECT * FROM schedule_task_versions WHERE taskId = :taskId AND revision = :revision")
    suspend fun version(taskId: String, revision: Long): ScheduleTaskVersionEntity?
    @Query("""
        SELECT v.* FROM schedule_task_versions v JOIN schedule_tasks t ON t.id = v.taskId
        WHERE t.deletedAt IS NULL AND v.effectiveFrom <= :date AND v.date <= :date
        AND NOT EXISTS (SELECT 1 FROM schedule_task_versions newer
            WHERE newer.taskId = v.taskId AND newer.revision > v.revision AND newer.effectiveFrom <= :date)
    """)
    suspend fun versionsForDate(date: LocalDate): List<ScheduleTaskVersionEntity>

    @Query("SELECT * FROM schedule_task_versions WHERE taskId = :taskId AND effectiveFrom <= :date ORDER BY revision DESC LIMIT 1")
    suspend fun versionForDate(taskId: String, date: LocalDate): ScheduleTaskVersionEntity?

    @Insert suspend fun insertRecurrence(rule: ScheduleRecurrenceEntity)
    @Query("SELECT * FROM schedule_recurrences WHERE taskId = :taskId AND taskRevision = :revision")
    suspend fun recurrence(taskId: String, revision: Long): ScheduleRecurrenceEntity?
    @Insert suspend fun insertLeads(leads: List<ScheduleReminderLeadEntity>)
    @Query("SELECT minutesBefore FROM schedule_reminder_leads WHERE taskId = :taskId AND taskRevision = :revision ORDER BY minutesBefore DESC")
    suspend fun reminderLeads(taskId: String, revision: Long): List<Int>

    @Insert suspend fun insertOccurrence(occurrence: ScheduleOccurrenceEntity)
    @Update suspend fun updateOccurrence(occurrence: ScheduleOccurrenceEntity)
    @Query("SELECT * FROM schedule_occurrences WHERE id = :id")
    suspend fun occurrence(id: String): ScheduleOccurrenceEntity?
    @Query("SELECT * FROM schedule_occurrences WHERE taskId = :taskId AND occurrenceKey = :key")
    suspend fun occurrenceForKey(taskId: String, key: String): ScheduleOccurrenceEntity?
    @Query("SELECT * FROM schedule_occurrences WHERE taskId = :taskId ORDER BY date, id")
    suspend fun occurrencesForTask(taskId: String): List<ScheduleOccurrenceEntity>

    @Query("""
        SELECT o.* FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId
        WHERE t.deletedAt IS NULL AND o.suppressedByRule = 0 AND o.date = :date
        ORDER BY o.completedAt IS NOT NULL, o.completedAt DESC, o.time IS NULL, o.time, o.createdAt, o.id
    """)
    fun observeDate(date: LocalDate): Flow<List<ScheduleOccurrenceEntity>>

    @Query("""
        SELECT o.*, t.sessionId, a.source AS coverSource, a.builtinKey AS coverBuiltinKey,
          a.relativePath AS coverRelativePath,
          (SELECT COUNT(*) FROM schedule_local_attachments attachments WHERE attachments.sessionId = t.sessionId) AS attachmentCount,
          r.taskId AS recurrence_taskId, r.taskRevision AS recurrence_taskRevision,
          r.pattern AS recurrence_pattern, r.intervalDays AS recurrence_intervalDays,
          r.weekDayMask AS recurrence_weekDayMask, r.monthDay AS recurrence_monthDay,
          r.endDate AS recurrence_endDate, r.enabled AS recurrence_enabled,
          COALESCE((SELECT enabled FROM schedule_recurrences currentRule WHERE currentRule.taskId = t.id AND currentRule.taskRevision = t.revision), 0) = 0 AS recurrenceStopped,
          EXISTS(SELECT 1 FROM schedule_recurrences r WHERE r.taskId = t.id AND (r.taskRevision = t.revision OR r.taskRevision = o.taskRevision)) AS isRecurring
        FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId
        LEFT JOIN media_assets a ON a.id = o.coverAssetId
        LEFT JOIN schedule_recurrences r ON r.taskId = o.taskId AND r.taskRevision = o.taskRevision
        WHERE t.deletedAt IS NULL AND o.suppressedByRule = 0 AND o.date = :date
        ORDER BY o.completedAt IS NOT NULL, o.completedAt DESC, o.time IS NULL, o.time, o.createdAt, o.id
    """)
    fun observeDisplayDate(date: LocalDate): Flow<List<ScheduleDisplayRecord>>

    @Query("""
        SELECT o.*, t.sessionId, a.source AS coverSource, a.builtinKey AS coverBuiltinKey,
          a.relativePath AS coverRelativePath,
          (SELECT COUNT(*) FROM schedule_local_attachments attachments WHERE attachments.sessionId = t.sessionId) AS attachmentCount,
          r.taskId AS recurrence_taskId, r.taskRevision AS recurrence_taskRevision,
          r.pattern AS recurrence_pattern, r.intervalDays AS recurrence_intervalDays,
          r.weekDayMask AS recurrence_weekDayMask, r.monthDay AS recurrence_monthDay,
          r.endDate AS recurrence_endDate, r.enabled AS recurrence_enabled,
          COALESCE((SELECT enabled FROM schedule_recurrences currentRule WHERE currentRule.taskId = t.id AND currentRule.taskRevision = t.revision), 0) = 0 AS recurrenceStopped,
          EXISTS(SELECT 1 FROM schedule_recurrences r WHERE r.taskId = t.id AND (r.taskRevision = t.revision OR r.taskRevision = o.taskRevision)) AS isRecurring
        FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId
        LEFT JOIN media_assets a ON a.id = o.coverAssetId
        LEFT JOIN schedule_recurrences r ON r.taskId = o.taskId AND r.taskRevision = o.taskRevision
        WHERE o.id = :id AND t.deletedAt IS NULL AND o.suppressedByRule = 0
    """)
    fun observeDisplayRecord(id: String): Flow<ScheduleDisplayRecord?>

    @Query("""
        SELECT o.*, t.sessionId, a.source AS coverSource, a.builtinKey AS coverBuiltinKey,
          a.relativePath AS coverRelativePath,
          (SELECT COUNT(*) FROM schedule_local_attachments attachments WHERE attachments.sessionId = t.sessionId) AS attachmentCount,
          r.taskId AS recurrence_taskId, r.taskRevision AS recurrence_taskRevision,
          r.pattern AS recurrence_pattern, r.intervalDays AS recurrence_intervalDays,
          r.weekDayMask AS recurrence_weekDayMask, r.monthDay AS recurrence_monthDay,
          r.endDate AS recurrence_endDate, r.enabled AS recurrence_enabled,
          COALESCE((SELECT enabled FROM schedule_recurrences currentRule WHERE currentRule.taskId = t.id AND currentRule.taskRevision = t.revision), 0) = 0 AS recurrenceStopped,
          EXISTS(SELECT 1 FROM schedule_recurrences r WHERE r.taskId = t.id AND (r.taskRevision = t.revision OR r.taskRevision = o.taskRevision)) AS isRecurring
        FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId
        LEFT JOIN media_assets a ON a.id = o.coverAssetId
        LEFT JOIN schedule_recurrences r ON r.taskId = o.taskId AND r.taskRevision = o.taskRevision
        WHERE t.sessionId = :sessionId AND t.deletedAt IS NULL AND o.suppressedByRule = 0
        ORDER BY CASE WHEN o.id = :selectedId THEN 0 ELSE 1 END,
          CASE WHEN o.date >= :today THEN 0 ELSE 1 END,
          CASE WHEN o.date >= :today THEN o.date END, o.date DESC, o.id LIMIT 1
    """)
    fun observeConversationRecord(sessionId: String, today: LocalDate, selectedId: String? = null): Flow<ScheduleDisplayRecord?>

    @Query("""
        SELECT o.date, COUNT(*) AS count FROM schedule_occurrences o
        JOIN schedule_tasks t ON t.id = o.taskId
        WHERE t.deletedAt IS NULL AND o.suppressedByRule = 0 AND o.date BETWEEN :from AND :through
        GROUP BY o.date ORDER BY o.date
    """)
    fun observeDateCounts(from: LocalDate, through: LocalDate): Flow<List<ScheduleDateCount>>

    @Query("""
        SELECT o.* FROM schedule_occurrences o JOIN schedule_tasks t ON t.id = o.taskId
        WHERE t.deletedAt IS NULL AND o.suppressedByRule = 0 AND o.date BETWEEN :from AND :through
        ORDER BY o.date DESC, o.time IS NULL, o.time, o.id LIMIT :limit OFFSET :offset
    """)
    suspend fun history(from: LocalDate, through: LocalDate, limit: Int, offset: Int): List<ScheduleOccurrenceEntity>

    @Insert suspend fun insertReminders(reminders: List<ScheduleReminderEntity>)
    @Query("SELECT * FROM schedule_reminders WHERE occurrenceId = :id ORDER BY triggerAt, id")
    suspend fun reminders(id: String): List<ScheduleReminderEntity>
    @Query("""
        UPDATE schedule_reminders SET status = 'CANCELLED'
        WHERE occurrenceId = :id AND status IN ('PENDING', 'SCHEDULED')
    """)
    suspend fun cancelReminders(id: String)
    @Query("""
        SELECT r.* FROM schedule_reminders r
        JOIN schedule_occurrences o ON o.id = r.occurrenceId
        JOIN schedule_tasks t ON t.id = o.taskId
        WHERE r.status IN ('PENDING', 'SCHEDULED') AND r.occurrenceRevision = o.revision
        AND t.deletedAt IS NULL AND o.completedAt IS NULL AND o.suppressedByRule = 0
        AND r.triggerAt BETWEEN :from AND :through ORDER BY r.triggerAt, r.id LIMIT :limit
    """)
    suspend fun pendingReminders(from: Long, through: Long, limit: Int): List<ScheduleReminderEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttachment(attachment: ScheduleAttachmentEntity): Long
    @Query("SELECT * FROM schedule_attachments WHERE sessionId = :sessionId ORDER BY addedAt, assetId")
    suspend fun attachments(sessionId: String): List<ScheduleAttachmentEntity>
    @Query("SELECT * FROM schedule_attachments WHERE messageId = :messageId")
    suspend fun attachmentsForMessage(messageId: String): List<ScheduleAttachmentEntity>

    @Insert suspend fun insertOperation(operation: ScheduleOperationEntity)
    @Query("SELECT * FROM schedule_operations WHERE requestId = :id")
    suspend fun operation(id: String): ScheduleOperationEntity?
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM schedule_operations WHERE sessionId = :sessionId")
    suspend fun nextOperationSequence(sessionId: String): Long
    @Query("SELECT * FROM schedule_operations WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
    suspend fun operations(sessionId: String, limit: Int): List<ScheduleOperationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertExclusion(item: ScheduleExclusionEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM schedule_exclusions WHERE taskId = :taskId AND occurrenceKey = :key)")
    suspend fun isExcluded(taskId: String, key: String): Boolean
    @Insert suspend fun insertOccurrenceLeads(items: List<ScheduleOccurrenceLeadEntity>)
    @Query("SELECT minutesBefore FROM schedule_occurrence_leads WHERE occurrenceId = :id ORDER BY minutesBefore DESC")
    suspend fun occurrenceLeads(id: String): List<Int>
    @Query("DELETE FROM schedule_occurrence_leads WHERE occurrenceId = :id") suspend fun deleteOccurrenceLeads(id: String)
    @Query("DELETE FROM schedule_reminders WHERE occurrenceId = :id") suspend fun deleteOccurrenceReminders(id: String)
    @Query("DELETE FROM schedule_occurrences WHERE id = :id") suspend fun deleteOccurrence(id: String)
    @Query("UPDATE schedule_messages SET occurrenceContextId = NULL WHERE occurrenceContextId = :id")
    suspend fun clearOccurrenceContext(id: String)
    @Query("DELETE FROM schedule_operations WHERE taskId = :taskId AND (INSTR(beforeSnapshot, :id) > 0 OR INSTR(afterSnapshot, :id) > 0)")
    suspend fun deleteOccurrenceOperations(taskId: String, id: String)

    @Query("SELECT * FROM media_assets WHERE id IN (SELECT coverAssetId FROM schedule_tasks WHERE id = :taskId UNION SELECT coverAssetId FROM schedule_task_versions WHERE taskId = :taskId UNION SELECT coverAssetId FROM schedule_occurrences WHERE taskId = :taskId UNION SELECT assetId FROM schedule_attachments WHERE sessionId = :sessionId UNION SELECT assetId FROM schedule_local_attachments WHERE sessionId = :sessionId UNION SELECT e.assetId FROM schedule_editor_attachments e JOIN schedule_occurrences o ON o.id = e.occurrenceId WHERE o.taskId = :taskId)")
    suspend fun taskMedia(taskId: String, sessionId: String): List<MediaAssetEntity>
    @Query("DELETE FROM schedule_pending_questions WHERE sessionId = :id") suspend fun deleteQuestions(id: String)
    @Query("DELETE FROM schedule_attachments WHERE sessionId = :id") suspend fun deleteAttachments(id: String)
    @Query("DELETE FROM schedule_messages WHERE sessionId = :id") suspend fun deleteMessages(id: String)
    @Query("DELETE FROM schedule_conversation_drafts WHERE sessionId = :id") suspend fun deleteDraft(id: String)
    @Query("DELETE FROM schedule_operations WHERE sessionId = :id") suspend fun deleteOperations(id: String)
    @Query("DELETE FROM schedule_recurrences WHERE taskId = :id") suspend fun deleteRecurrences(id: String)
    @Query("DELETE FROM schedule_reminder_leads WHERE taskId = :id") suspend fun deleteTaskLeads(id: String)
    @Query("DELETE FROM schedule_exclusions WHERE taskId = :id") suspend fun deleteExclusions(id: String)
    @Query("DELETE FROM schedule_task_versions WHERE taskId = :id") suspend fun deleteVersions(id: String)
    @Query("DELETE FROM schedule_tasks WHERE id = :id") suspend fun deleteTaskRow(id: String)
    @Query("DELETE FROM schedule_sessions WHERE id = :id") suspend fun deleteSession(id: String)
}

/** 一次查询取得卡片快照、原会话和封面引用，避免逐卡查询任务及图片。 */
data class ScheduleDisplayRecord(
    @Embedded val occurrence: ScheduleOccurrenceEntity,
    val sessionId: String,
    val coverSource: MediaSource?,
    val coverBuiltinKey: String?,
    val coverRelativePath: String?,
    val isRecurring: Boolean,
    @Embedded(prefix = "recurrence_") val recurrence: ScheduleRecurrenceEntity?,
    val recurrenceStopped: Boolean,
    val attachmentCount: Int,
)

/** 仅观察模板版本；展开日期和完成卡片不会再次触发模板展开。 */
data class ScheduleTaskRevision(val id: String, val revision: Long)

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(asset: MediaAssetEntity): Long
    @Query("SELECT * FROM media_assets WHERE id = :id") suspend fun asset(id: String): MediaAssetEntity?
    @Query("SELECT * FROM media_assets WHERE sha256 = :hash") suspend fun assetByHash(hash: String): MediaAssetEntity?
    @Query("""DELETE FROM media_assets WHERE id = :id
        AND NOT EXISTS(SELECT 1 FROM schedule_tasks WHERE coverAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM schedule_task_versions WHERE coverAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM schedule_occurrences WHERE coverAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM schedule_attachments WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM schedule_local_attachments WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM schedule_editor_attachments WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM flashnote_attachments WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM flashnote_draft_attachments WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM subscriptions WHERE coverAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM subscription_images WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM home_reflection WHERE imageAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM home_reflection_history WHERE imageAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM home_composer_attachments WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM subscription_changes WHERE beforeCoverAssetId = :id OR afterCoverAssetId = :id)
        AND NOT EXISTS(SELECT 1 FROM subscription_conversation_media WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM dayima_draft_media WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM dayima_message_media WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM dayima_period_media WHERE assetId = :id)
        AND NOT EXISTS(SELECT 1 FROM trading_watchlist WHERE iconAssetId = :id)""")
    suspend fun deleteIfUnused(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun queueCleanup(item: MediaCleanupEntity)
    @Query("SELECT relativePath FROM media_cleanup ORDER BY relativePath LIMIT :limit") suspend fun cleanupPaths(limit: Int): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM media_assets WHERE relativePath = :path)") suspend fun pathIsRegistered(path: String): Boolean
    @Query("DELETE FROM media_cleanup WHERE relativePath = :path") suspend fun finishCleanup(path: String)
}
