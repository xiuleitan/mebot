package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 输入框草稿独立于已发送消息；revision 防止迟到的保存覆盖新内容。 */
@Entity(
    tableName = "schedule_conversation_drafts",
    foreignKeys = [ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"])],
)
data class ScheduleConversationDraftEntity(
    @PrimaryKey val sessionId: String,
    val inputText: String,
    val revision: Long,
    val updatedAt: Long,
)

/** 追问单独保留，普通回复不会自动把所有问题判为已解决。 */
@Entity(
    tableName = "schedule_pending_questions",
    foreignKeys = [
        ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"]),
        ForeignKey(ScheduleMessageEntity::class, ["id"], ["answerMessageId"]),
    ],
    indices = [Index(value = ["sessionId", "answeredAt"]), Index("answerMessageId")],
)
data class SchedulePendingQuestionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val question: String,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val answerMessageId: String? = null,
)

// 业务任务（包括旧版软删除任务）及有操作记录的会话不属于可丢弃的新建草稿。
private const val OtherCreationSessions = """
    SELECT s.id FROM schedule_sessions s WHERE s.id != :keepSessionId
    AND NOT EXISTS (SELECT 1 FROM schedule_tasks t WHERE t.sessionId = s.id)
    AND NOT EXISTS (SELECT 1 FROM schedule_operations o WHERE o.sessionId = s.id)
    AND NOT EXISTS (SELECT 1 FROM home_dispatches h WHERE h.botId='schedule' AND h.targetId=s.id AND h.handedOff=1)
"""

@Dao
interface ScheduleConversationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDraft(draft: ScheduleConversationDraftEntity)

    @Query("SELECT * FROM schedule_conversation_drafts WHERE sessionId = :sessionId")
    suspend fun draft(sessionId: String): ScheduleConversationDraftEntity?

    @Query("""
        UPDATE schedule_conversation_drafts SET inputText = :text, revision = revision + 1, updatedAt = :now
        WHERE sessionId = :sessionId AND revision = :expectedRevision
    """)
    suspend fun saveDraft(sessionId: String, text: String, expectedRevision: Long, now: Long): Int

    @Query("SELECT * FROM schedule_messages WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
    fun observeMessages(sessionId: String, limit: Int): Flow<List<ScheduleMessageEntity>>

    @Query("SELECT * FROM schedule_messages WHERE sessionId = :sessionId AND role = 'USER' ORDER BY sequence DESC LIMIT 1")
    suspend fun latestUserMessage(sessionId: String): ScheduleMessageEntity?
    @Query("SELECT * FROM schedule_messages WHERE sessionId = :sessionId AND role = 'USER' ORDER BY sequence LIMIT 1")
    suspend fun firstUserMessage(sessionId: String): ScheduleMessageEntity?
    @Query("SELECT * FROM schedule_messages WHERE sessionId = :sessionId AND sequence <= :through ORDER BY sequence DESC LIMIT :limit")
    suspend fun contextMessages(sessionId: String, through: Long, limit: Int): List<ScheduleMessageEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM schedule_messages WHERE sessionId = :sessionId AND role = 'ASSISTANT' AND sequence > :sequence)")
    suspend fun hasReplyAfter(sessionId: String, sequence: Long): Boolean
    @Query("""
        SELECT m.* FROM schedule_messages m WHERE m.sessionId = :sessionId AND m.role = 'USER'
        AND m.sequence = (SELECT MAX(sequence) FROM schedule_messages WHERE sessionId = :sessionId AND role = 'USER')
        AND NOT EXISTS (SELECT 1 FROM schedule_messages a WHERE a.sessionId = :sessionId
            AND a.role = 'ASSISTANT' AND a.sequence > m.sequence)
    """)
    fun observeAwaitingAnalysis(sessionId: String): Flow<ScheduleMessageEntity?>

    @Insert suspend fun insertQuestion(question: SchedulePendingQuestionEntity)
    @Query("SELECT * FROM schedule_pending_questions WHERE id = :id")
    suspend fun question(id: String): SchedulePendingQuestionEntity?
    @Query("SELECT * FROM schedule_pending_questions WHERE sessionId = :sessionId AND answeredAt IS NULL ORDER BY createdAt, id")
    fun observePendingQuestions(sessionId: String): Flow<List<SchedulePendingQuestionEntity>>
    @Query("SELECT * FROM schedule_pending_questions WHERE sessionId = :sessionId AND answeredAt IS NULL ORDER BY createdAt, id")
    suspend fun pendingQuestions(sessionId: String): List<SchedulePendingQuestionEntity>
    @Query("""
        UPDATE schedule_pending_questions SET answeredAt = :now, answerMessageId = :messageId
        WHERE id = :id AND answeredAt IS NULL
    """)
    suspend fun answerQuestion(id: String, messageId: String, now: Long)

    @Query("""
        SELECT s.id FROM schedule_sessions s
        LEFT JOIN schedule_conversation_drafts d ON d.sessionId = s.id
        WHERE NOT EXISTS (SELECT 1 FROM schedule_tasks t WHERE t.sessionId = s.id)
          AND NOT EXISTS (SELECT 1 FROM schedule_operations o WHERE o.sessionId = s.id)
          AND NOT EXISTS (SELECT 1 FROM home_dispatches h WHERE h.botId='schedule' AND h.targetId=s.id AND h.handedOff=1)
        ORDER BY (LENGTH(TRIM(COALESCE(d.inputText, ''))) > 0
          OR EXISTS (SELECT 1 FROM schedule_messages WHERE sessionId = s.id)
          OR EXISTS (SELECT 1 FROM schedule_pending_questions WHERE sessionId = s.id)
          OR EXISTS (SELECT 1 FROM schedule_local_attachments WHERE sessionId = s.id)) DESC,
          s.updatedAt DESC, s.createdAt DESC, s.id DESC LIMIT 1
    """)
    suspend fun latestCreationSessionId(): String?

    @Query("DELETE FROM schedule_pending_questions WHERE sessionId IN ($OtherCreationSessions)")
    suspend fun deleteOtherCreationQuestions(keepSessionId: String)
    @Query("DELETE FROM schedule_attachments WHERE sessionId IN ($OtherCreationSessions)")
    suspend fun deleteOtherCreationAttachmentReferences(keepSessionId: String)
    @Query("DELETE FROM schedule_local_attachments WHERE sessionId IN ($OtherCreationSessions)")
    suspend fun deleteOtherCreationLocalAttachments(keepSessionId: String)
    @Query("""SELECT * FROM media_assets WHERE id IN (
        SELECT assetId FROM schedule_local_attachments WHERE sessionId IN ($OtherCreationSessions)
        UNION SELECT assetId FROM schedule_attachments WHERE sessionId IN ($OtherCreationSessions))""")
    suspend fun otherCreationMedia(keepSessionId: String): List<MediaAssetEntity>
    @Query("DELETE FROM schedule_messages WHERE sessionId IN ($OtherCreationSessions)")
    suspend fun deleteOtherCreationMessages(keepSessionId: String)
    @Query("DELETE FROM schedule_conversation_drafts WHERE sessionId IN ($OtherCreationSessions)")
    suspend fun deleteOtherCreationDrafts(keepSessionId: String)
    @Query("DELETE FROM schedule_sessions WHERE id IN ($OtherCreationSessions)")
    suspend fun deleteOtherCreationSessions(keepSessionId: String)
}
