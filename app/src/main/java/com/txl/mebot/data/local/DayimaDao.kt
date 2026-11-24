package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

data class DayimaPeriodNoteCount(val periodId: String, val count: Int)

/** 主库读写原语；日期业务校验和跨表事务由 DayimaRepository 协调。 */
@Dao
interface DayimaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: DayimaProfileEntity)
    @Query("SELECT * FROM dayima_profile WHERE id = 1")
    suspend fun profile(): DayimaProfileEntity?
    @Query("SELECT * FROM dayima_profile WHERE id = 1")
    fun observeProfile(): Flow<DayimaProfileEntity?>
    @Query("""UPDATE dayima_profile SET birthDate = :birthDate, updatedAt = :updatedAt, revision = revision + 1
        WHERE id = 1 AND revision = :expectedRevision""")
    suspend fun updateBirthDate(birthDate: LocalDate?, updatedAt: Long, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPeriod(period: DayimaPeriodEntity)
    @Query("SELECT * FROM dayima_periods WHERE id = :id")
    suspend fun period(id: String): DayimaPeriodEntity?
    @Query("SELECT * FROM dayima_periods WHERE startDate = :date")
    suspend fun periodOn(date: LocalDate): DayimaPeriodEntity?
    @Query("SELECT * FROM dayima_periods ORDER BY startDate ASC")
    suspend fun periods(): List<DayimaPeriodEntity>
    @Query("SELECT * FROM dayima_periods WHERE startDate BETWEEN :from AND :through ORDER BY startDate ASC")
    suspend fun periodsBetween(from: LocalDate, through: LocalDate): List<DayimaPeriodEntity>
    @Query("SELECT * FROM dayima_periods WHERE startDate < :before ORDER BY startDate DESC LIMIT :limit")
    suspend fun periodsBefore(before: LocalDate, limit: Int): List<DayimaPeriodEntity>
    @Query("SELECT * FROM dayima_periods ORDER BY startDate DESC LIMIT 1")
    suspend fun latestPeriod(): DayimaPeriodEntity?
    @Query("SELECT * FROM dayima_periods WHERE startDate > :after AND startDate <= :through ORDER BY startDate ASC LIMIT 1")
    suspend fun nextPeriod(after: LocalDate, through: LocalDate): DayimaPeriodEntity?
    @Query("SELECT * FROM dayima_periods ORDER BY startDate ASC")
    fun observePeriods(): Flow<List<DayimaPeriodEntity>>
    @Query("""UPDATE dayima_periods SET startDate = :startDate, updatedAt = :updatedAt, revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updatePeriodDate(id: String, startDate: LocalDate, updatedAt: Long, expectedRevision: Long): Int
    @Query("DELETE FROM dayima_periods WHERE id = :id AND revision = :expectedRevision")
    suspend fun deletePeriod(id: String, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: DayimaNoteEntity)
    @Query("SELECT * FROM dayima_notes WHERE id = :id")
    suspend fun note(id: String): DayimaNoteEntity?
    @Query("SELECT * FROM dayima_notes WHERE periodId = :periodId ORDER BY occurredDate ASC, id ASC")
    suspend fun notesForPeriod(periodId: String): List<DayimaNoteEntity>
    @Query("SELECT periodId, COUNT(*) AS count FROM dayima_notes WHERE periodId IS NOT NULL GROUP BY periodId")
    suspend fun periodNoteCounts(): List<DayimaPeriodNoteCount>
    @Query("SELECT COUNT(*) FROM dayima_notes WHERE periodId IS NULL")
    suspend fun independentNoteCount(): Int
    @Query("SELECT * FROM dayima_notes WHERE periodId IS NULL ORDER BY occurredDate DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun independentNotes(limit: Int, offset: Int): List<DayimaNoteEntity>
    @Query("SELECT * FROM dayima_notes WHERE occurredDate BETWEEN :from AND :through ORDER BY occurredDate ASC, id ASC")
    suspend fun notesBetween(from: LocalDate, through: LocalDate): List<DayimaNoteEntity>
    @Query("SELECT * FROM dayima_notes WHERE occurredDate BETWEEN :from AND :through ORDER BY occurredDate DESC, id DESC LIMIT 21")
    suspend fun chatNotes(from: LocalDate, through: LocalDate): List<DayimaNoteEntity>
    @Query("""UPDATE dayima_notes SET occurredDate = :occurredDate, content = :content, periodId = :periodId,
        updatedAt = :updatedAt, revision = revision + 1 WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updateNote(id: String, occurredDate: LocalDate, content: String, periodId: String?, updatedAt: Long, expectedRevision: Long): Int
    @Query("DELETE FROM dayima_notes WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteNote(id: String, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSpanMarker(marker: DayimaSpanMarkerEntity)
    @Query("SELECT * FROM dayima_span_markers WHERE id = :id")
    suspend fun spanMarker(id: String): DayimaSpanMarkerEntity?
    @Query("SELECT * FROM dayima_span_markers ORDER BY startDate ASC, id ASC")
    suspend fun spanMarkers(): List<DayimaSpanMarkerEntity>
    @Query("SELECT * FROM dayima_span_markers WHERE startPeriodId = :periodId OR endPeriodId = :periodId")
    suspend fun spanMarkersForPeriod(periodId: String): List<DayimaSpanMarkerEntity>
    @Query("SELECT * FROM dayima_span_markers WHERE startPeriodId = :startId AND endPeriodId = :endId")
    suspend fun spanMarkerForPair(startId: String, endId: String): DayimaSpanMarkerEntity?
    @Query("SELECT * FROM dayima_span_markers WHERE startDate < :date AND endDate > :date AND needsReview = 0")
    suspend fun spanMarkersCovering(date: LocalDate): List<DayimaSpanMarkerEntity>
    @Query("""UPDATE dayima_span_markers SET needsReview = 1, updatedAt = :updatedAt,
        revision = revision + 1 WHERE id = :id AND revision = :expectedRevision AND needsReview = 0""")
    suspend fun markSpanForReview(id: String, updatedAt: Long, expectedRevision: Long): Int
    @Query("""UPDATE dayima_span_markers SET startPeriodId = :startId, endPeriodId = :endId,
        startDate = :startDate, endDate = :endDate, startPeriodRevision = :startRevision,
        endPeriodRevision = :endRevision, reason = :reason, detail = :detail, updatedAt = :updatedAt, needsReview = 0,
        revision = revision + 1 WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updateSpanMarker(id: String, startId: String, endId: String, startDate: LocalDate, endDate: LocalDate,
        startRevision: Long, endRevision: Long, reason: DayimaSpanReason, detail: String, updatedAt: Long, expectedRevision: Long): Int
    @Query("""UPDATE dayima_span_markers SET
        startPeriodId = CASE WHEN startPeriodId = :periodId THEN NULL ELSE startPeriodId END,
        endPeriodId = CASE WHEN endPeriodId = :periodId THEN NULL ELSE endPeriodId END,
        updatedAt = :updatedAt, revision = revision + 1, needsReview = 1
        WHERE id = :id AND revision = :expectedRevision AND (startPeriodId = :periodId OR endPeriodId = :periodId)""")
    suspend fun detachSpanPeriod(id: String, periodId: String, updatedAt: Long, expectedRevision: Long): Int
    @Query("DELETE FROM dayima_span_markers WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteSpanMarker(id: String, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: DayimaChatSessionEntity)
    @Query("SELECT * FROM dayima_chat_sessions WHERE id = :id")
    suspend fun session(id: String): DayimaChatSessionEntity?
    @Query("SELECT * FROM dayima_chat_sessions WHERE id = :id")
    fun observeSession(id: String): Flow<DayimaChatSessionEntity?>
    @Query("SELECT * FROM dayima_chat_sessions ORDER BY updatedAt DESC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun sessions(limit: Int, offset: Int): List<DayimaChatSessionEntity>
    @Query("SELECT * FROM dayima_chat_sessions ORDER BY updatedAt DESC, id ASC LIMIT :limit")
    fun observeSessions(limit: Int): Flow<List<DayimaChatSessionEntity>>
    @Query("SELECT * FROM dayima_chat_sessions WHERE status = 'ACTIVE'")
    suspend fun activeSessions(): List<DayimaChatSessionEntity>
    @Query("SELECT * FROM dayima_chat_sessions WHERE status = 'ACTIVE' ORDER BY updatedAt DESC, id ASC LIMIT 1")
    suspend fun latestActiveSession(): DayimaChatSessionEntity?
    @Query("""UPDATE dayima_chat_sessions SET title = :title, status = :status, updatedAt = :updatedAt,
        revision = revision + 1 WHERE id = :id AND revision = :expectedRevision""")
    suspend fun updateSession(id: String, title: String, status: DayimaChatStatus, updatedAt: Long, expectedRevision: Long): Int
    @Query("DELETE FROM dayima_chat_sessions WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteSession(id: String, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: DayimaChatMessageEntity)
    @Query("SELECT * FROM dayima_chat_messages WHERE id = :id")
    suspend fun chatMessage(id: String): DayimaChatMessageEntity?
    @Query("SELECT * FROM dayima_chat_messages WHERE sessionId = :sessionId AND role = 'USER' ORDER BY sequence DESC LIMIT 1")
    suspend fun latestUserMessage(sessionId: String): DayimaChatMessageEntity?
    @Query("SELECT * FROM dayima_chat_messages WHERE id = :id")
    suspend fun message(id: String): DayimaChatMessageEntity?
    @Query("""SELECT * FROM (SELECT * FROM dayima_chat_messages WHERE sessionId = :sessionId
        AND sequence < :beforeSequence ORDER BY sequence DESC LIMIT :limit) ORDER BY sequence ASC""")
    suspend fun messages(sessionId: String, limit: Int, beforeSequence: Long = Long.MAX_VALUE): List<DayimaChatMessageEntity>
    @Query("""SELECT * FROM (SELECT * FROM dayima_chat_messages WHERE sessionId = :sessionId
        AND sequence < :beforeSequence ORDER BY sequence DESC LIMIT :limit) ORDER BY sequence ASC""")
    fun observeMessages(sessionId: String, limit: Int, beforeSequence: Long = Long.MAX_VALUE): Flow<List<DayimaChatMessageEntity>>
    @Query("SELECT COALESCE(MAX(sequence), 0) FROM dayima_chat_messages WHERE sessionId = :sessionId")
    suspend fun lastMessageSequence(sessionId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraft(draft: DayimaChatDraftEntity)
    @Query("SELECT * FROM dayima_chat_drafts WHERE sessionId = :sessionId")
    suspend fun draft(sessionId: String): DayimaChatDraftEntity?
    @Query("SELECT * FROM dayima_chat_drafts WHERE sessionId = :sessionId")
    fun observeDraft(sessionId: String): Flow<DayimaChatDraftEntity?>
    @Query("""UPDATE dayima_chat_drafts SET inputText = :inputText, updatedAt = :updatedAt,
        revision = revision + 1 WHERE sessionId = :sessionId AND revision = :expectedRevision""")
    suspend fun updateDraft(sessionId: String, inputText: String, updatedAt: Long, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: DayimaChatOperationEntity)
    @Query("SELECT * FROM dayima_chat_operations WHERE requestMessageId = :requestMessageId")
    suspend fun operation(requestMessageId: String): DayimaChatOperationEntity?
    @Query("SELECT * FROM dayima_chat_operations WHERE sessionId = :sessionId ORDER BY createdAt DESC, requestMessageId ASC LIMIT :limit")
    suspend fun operations(sessionId: String, limit: Int): List<DayimaChatOperationEntity>
    @Query("""SELECT o.* FROM dayima_chat_operations o JOIN dayima_chat_messages m ON m.id = o.requestMessageId
        WHERE o.sessionId = :sessionId ORDER BY m.sequence DESC LIMIT :limit""")
    suspend fun recentChatOperations(sessionId: String, limit: Int): List<DayimaChatOperationEntity>
    @Query("""SELECT o.* FROM dayima_chat_operations o JOIN dayima_chat_messages m ON m.id = o.requestMessageId
        WHERE o.sessionId = :sessionId ORDER BY m.sequence DESC LIMIT 1""")
    suspend fun latestChatOperation(sessionId: String): DayimaChatOperationEntity?
    @Query("""UPDATE dayima_chat_operations SET state = 'FAILED', failure = 'INTERRUPTED', attemptId = NULL,
        updatedAt = :now, revision = revision + 1 WHERE sessionId = :sessionId AND state = 'PENDING'""")
    suspend fun interruptChatOperations(sessionId: String, now: Long)
    @Query("SELECT * FROM dayima_chat_operations WHERE sessionId = :sessionId AND state = 'WAITING_FOR_INPUT' ORDER BY createdAt ASC, requestMessageId ASC")
    suspend fun pendingQuestions(sessionId: String): List<DayimaChatOperationEntity>
    @Query("""UPDATE dayima_chat_operations SET state = :state, action = :action, question = :question,
        resultJson = :resultJson, failure = :failure, attemptId = :attemptId, updatedAt = :updatedAt,
        revision = revision + 1 WHERE requestMessageId = :requestMessageId AND revision = :expectedRevision""")
    suspend fun updateOperation(requestMessageId: String, state: DayimaOperationState, action: DayimaOperationAction?,
        question: String?, resultJson: String?, failure: String?, attemptId: String?, updatedAt: Long, expectedRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReminderSettings(settings: DayimaReminderSettingsEntity)
    @Query("SELECT * FROM dayima_reminder_settings WHERE id = 1")
    suspend fun reminderSettings(): DayimaReminderSettingsEntity?
    @Query("SELECT * FROM dayima_reminder_settings WHERE id = 1")
    fun observeReminderSettings(): Flow<DayimaReminderSettingsEntity?>
    @Query("""UPDATE dayima_reminder_settings SET prepareEnabled = :prepareEnabled, missingRecordEnabled = :missingRecordEnabled,
        reminderTime = :reminderTime, prepareDaysBefore = :prepareDaysBefore, updatedAt = :updatedAt,
        revision = revision + 1 WHERE id = 1 AND revision = :expectedRevision""")
    suspend fun updateReminderSettings(prepareEnabled: Boolean, missingRecordEnabled: Boolean, reminderTime: LocalTime,
        prepareDaysBefore: Int, updatedAt: Long, expectedRevision: Long): Int
}
