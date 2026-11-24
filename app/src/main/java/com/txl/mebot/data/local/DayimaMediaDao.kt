package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.txl.mebot.data.dayima.DayimaImage
import com.txl.mebot.data.dayima.DayimaMessageImage
import kotlinx.coroutines.flow.Flow

@Dao
interface DayimaMediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDraft(item: DayimaDraftMediaEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMessage(item: DayimaMessageMediaEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPeriod(item: DayimaPeriodMediaEntity): Long

    @Query("SELECT * FROM dayima_draft_media WHERE sessionId = :id ORDER BY position")
    suspend fun draftLinks(id: String): List<DayimaDraftMediaEntity>
    @Query("SELECT a.*, m.displayName FROM dayima_draft_media m JOIN media_assets a ON a.id = m.assetId WHERE m.sessionId = :id ORDER BY m.position")
    suspend fun draftImages(id: String): List<DayimaImage>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM dayima_draft_media WHERE sessionId = :id")
    suspend fun nextDraftPosition(id: String): Int
    @Query("DELETE FROM dayima_draft_media WHERE sessionId = :id AND assetId = :assetId")
    suspend fun removeDraft(id: String, assetId: String): Int
    @Query("DELETE FROM dayima_draft_media WHERE sessionId = :id") suspend fun clearDraft(id: String)

    @Query("SELECT * FROM dayima_message_media WHERE messageId = :id ORDER BY position")
    suspend fun messageLinks(id: String): List<DayimaMessageMediaEntity>
    @Query("""SELECT a.*, m.displayName, m.messageId FROM dayima_message_media m
        JOIN media_assets a ON a.id = m.assetId JOIN dayima_chat_messages msg ON msg.id = m.messageId
        WHERE msg.sessionId = :sessionId AND msg.id IN (:messageIds) ORDER BY msg.sequence, m.position""")
    suspend fun messageImages(sessionId: String, messageIds: List<String>): List<DayimaMessageImage>
    @Query("""SELECT assetId FROM dayima_draft_media WHERE sessionId = :id UNION SELECT m.assetId
        FROM dayima_message_media m JOIN dayima_chat_messages msg ON msg.id = m.messageId WHERE msg.sessionId = :id""")
    suspend fun sessionAssetIds(id: String): List<String>

    @Query("SELECT a.*, m.displayName FROM dayima_period_media m JOIN media_assets a ON a.id = m.assetId WHERE m.periodId = :id ORDER BY m.position")
    suspend fun periodImages(id: String): List<DayimaImage>
    @Query("SELECT a.*, m.displayName FROM dayima_period_media m JOIN media_assets a ON a.id = m.assetId WHERE m.periodId = :id ORDER BY m.position")
    fun observePeriodImages(id: String): Flow<List<DayimaImage>>
    @Query("SELECT assetId FROM dayima_period_media WHERE periodId = :id") suspend fun periodAssetIds(id: String): List<String>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM dayima_period_media WHERE periodId = :id")
    suspend fun nextPeriodPosition(id: String): Int
    @Query("DELETE FROM dayima_period_media WHERE periodId = :id AND assetId = :assetId")
    suspend fun removePeriod(id: String, assetId: String): Int

    @Query("""UPDATE dayima_chat_drafts SET revision = revision + 1, updatedAt = :now
        WHERE sessionId = :id AND revision = :expected""")
    suspend fun bumpDraft(id: String, expected: Long, now: Long): Int
}
