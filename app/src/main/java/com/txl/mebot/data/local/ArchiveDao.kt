package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 原始档案和可重建索引始终在同一个应用主库内，写入事务由 ArchiveStorage 管理。 */
@Dao
interface ArchiveDao {
    @Insert suspend fun insertRecord(value: ArchiveRecordEntity)
    @Update suspend fun updateRecord(value: ArchiveRecordEntity)
    @Query("SELECT * FROM archive_records WHERE id = :id") suspend fun record(id: String): ArchiveRecordEntity?
    @Query("SELECT * FROM archive_records WHERE deletedAt IS NULL ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun records(limit: Int, offset: Int): List<ArchiveRecordEntity>
    @Query("SELECT * FROM archive_records WHERE deletedAt IS NULL ORDER BY id") suspend fun activeRecords(): List<ArchiveRecordEntity>
    @Query("SELECT COUNT(*) FROM archive_records WHERE deletedAt IS NULL") suspend fun recordCount(): Int

    @Insert suspend fun insertSession(value: ArchiveSessionEntity)
    @Update suspend fun updateSession(value: ArchiveSessionEntity)
    @Query("SELECT * FROM archive_sessions WHERE id = :id") suspend fun session(id: String): ArchiveSessionEntity?
    @Query("SELECT * FROM archive_sessions ORDER BY updatedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun sessions(limit: Int, offset: Int): List<ArchiveSessionEntity>
    @Query("SELECT * FROM archive_sessions WHERE recordId = :recordId") suspend fun recordSessions(recordId: String): List<ArchiveSessionEntity>
    @Query("""SELECT * FROM archive_sessions s WHERE s.recordId IS NULL AND s.state = 'ACTIVE'
        AND NOT EXISTS(SELECT 1 FROM archive_messages m WHERE m.sessionId = s.id)
        ORDER BY s.updatedAt DESC, s.id LIMIT 50""") suspend fun unstartedSessions(): List<ArchiveSessionEntity>
    @Query("UPDATE archive_sessions SET recordId = :recordId, updatedAt = :now WHERE id = :id AND recordId IS NULL AND state = 'ACTIVE'")
    suspend fun bindSession(id: String, recordId: String, now: Long): Int
    @Insert suspend fun insertDraft(value: ArchiveDraftEntity)
    @Update suspend fun updateDraft(value: ArchiveDraftEntity)
    @Query("SELECT * FROM archive_drafts WHERE sessionId = :sessionId") suspend fun draft(sessionId: String): ArchiveDraftEntity?

    @Insert suspend fun insertMessage(value: ArchiveMessageEntity)
    @Query("SELECT * FROM archive_messages WHERE id = :id") suspend fun message(id: String): ArchiveMessageEntity?
    @Query("SELECT * FROM archive_messages WHERE sessionId = :sessionId AND role = 'USER' ORDER BY sequence DESC LIMIT 1")
    suspend fun latestUserMessage(sessionId: String): ArchiveMessageEntity?
    @Query("SELECT * FROM archive_messages WHERE sessionId = :sessionId AND sequence < :before ORDER BY sequence DESC LIMIT :limit")
    suspend fun messages(sessionId: String, before: Long, limit: Int): List<ArchiveMessageEntity>
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM archive_messages WHERE sessionId = :sessionId") suspend fun nextSequence(sessionId: String): Long
    @Insert suspend fun insertOperation(value: ArchiveOperationEntity)
    @Query("SELECT * FROM archive_operations WHERE id = :id") suspend fun operation(id: String): ArchiveOperationEntity?

    @Insert suspend fun insertAsset(value: ArchiveAssetEntity)
    @Query("SELECT * FROM archive_assets WHERE id = :id") suspend fun asset(id: String): ArchiveAssetEntity?
    @Query("SELECT relativePath FROM archive_assets") suspend fun assetPaths(): List<String>
    @Query("SELECT COUNT(*) FROM archive_assets WHERE relativePath = :path") suspend fun pathReferences(path: String): Int
    @Query("DELETE FROM archive_assets WHERE id = :id") suspend fun deleteAsset(id: String)
    @Insert suspend fun insertSessionAsset(value: ArchiveSessionAssetEntity)
    @Update suspend fun updateSessionAsset(value: ArchiveSessionAssetEntity)
    @Query("SELECT * FROM archive_session_assets WHERE sessionId = :sessionId ORDER BY position, assetId") suspend fun sessionAssets(sessionId: String): List<ArchiveSessionAssetEntity>
    @Query("DELETE FROM archive_session_assets WHERE sessionId = :sessionId AND assetId = :assetId") suspend fun removeSessionAsset(sessionId: String, assetId: String)
    @Query("SELECT COUNT(*) FROM archive_session_assets WHERE assetId = :assetId") suspend fun sessionReferences(assetId: String): Int
    @Insert suspend fun insertMessageAssets(values: List<ArchiveMessageAssetEntity>)
    @Query("SELECT * FROM archive_message_assets WHERE messageId = :messageId ORDER BY position") suspend fun messageAssets(messageId: String): List<ArchiveMessageAssetEntity>
    @Query("SELECT COUNT(*) FROM archive_message_assets WHERE sessionId = :sessionId AND assetId = :assetId") suspend fun messageReferences(sessionId: String, assetId: String): Int
    @Insert suspend fun insertRecordAssets(values: List<ArchiveRecordAssetEntity>)
    @Query("SELECT * FROM archive_record_assets WHERE recordId = :recordId ORDER BY position") suspend fun recordAssets(recordId: String): List<ArchiveRecordAssetEntity>
    @Query("DELETE FROM archive_record_assets WHERE recordId = :recordId") suspend fun clearRecordAssets(recordId: String)
    @Query("SELECT COUNT(*) FROM archive_record_assets WHERE assetId = :assetId") suspend fun recordReferences(assetId: String): Int

    @Insert suspend fun insertEmbeddings(values: List<ArchiveEmbeddingEntity>)
    @Query("DELETE FROM archive_embeddings WHERE recordId = :recordId") suspend fun clearEmbeddings(recordId: String)
    @Query("DELETE FROM archive_embeddings") suspend fun clearAllEmbeddings()
    @Query("""SELECT e.* FROM archive_embeddings e
        JOIN archive_records r ON r.id = e.recordId AND r.deletedAt IS NULL AND r.revision = e.recordRevision
        JOIN archive_index_settings s ON s.id = 1 AND s.fingerprint = e.fingerprint AND s.dimensions = e.dimensions
        JOIN archive_index_jobs j ON j.recordId = r.id AND j.recordRevision = r.revision
            AND j.fingerprint = s.fingerprint AND j.state = 'READY'
        ORDER BY e.recordId, e.part""")
    suspend fun effectiveEmbeddings(): List<ArchiveEmbeddingEntity>
    @Query("""SELECT e.* FROM archive_embeddings e
        JOIN archive_records r ON r.id=e.recordId AND r.deletedAt IS NULL AND r.revision=e.recordRevision
        JOIN archive_index_settings s ON s.id=1 AND s.fingerprint=e.fingerprint AND s.dimensions=e.dimensions
        JOIN archive_index_jobs j ON j.recordId=r.id AND j.recordRevision=r.revision AND j.fingerprint=s.fingerprint AND j.state='READY'
        WHERE e.fingerprint=:fingerprint AND (e.recordId>:afterId OR (e.recordId=:afterId AND e.part>:afterPart))
        ORDER BY e.recordId,e.part LIMIT :limit""")
    suspend fun effectiveEmbeddingPage(fingerprint: String, afterId: String, afterPart: Int, limit: Int): List<ArchiveEmbeddingEntity>
    @Query("""SELECT e.* FROM archive_embeddings e
        JOIN archive_records r ON r.id=e.recordId AND r.deletedAt IS NULL AND r.revision=e.recordRevision
        JOIN archive_index_settings s ON s.id=1 AND s.fingerprint=e.fingerprint AND s.dimensions=e.dimensions
        JOIN archive_index_jobs j ON j.recordId=r.id AND j.recordRevision=r.revision AND j.fingerprint=s.fingerprint AND j.state='READY'
        WHERE e.recordId=:id AND e.part=:part AND e.recordRevision=:revision AND e.fingerprint=:fingerprint""")
    suspend fun effectiveEmbedding(id: String, part: Int, revision: Long, fingerprint: String): ArchiveEmbeddingEntity?
    @Query("SELECT * FROM archive_index_jobs ORDER BY recordId") suspend fun indexJobs(): List<ArchiveIndexJobEntity>
    @Query("SELECT * FROM archive_index_jobs ORDER BY recordId") fun observeIndexJobs(): Flow<List<ArchiveIndexJobEntity>>
    @Query("""SELECT * FROM archive_index_jobs WHERE (state='QUEUED' AND attempts<3) OR
        (state='FAILED' AND attempts<3 AND failureCode IN ('NETWORK','TIMEOUT','RATE_LIMIT','SERVICE','INTERRUPTED'))
        ORDER BY updatedAt,recordId LIMIT 8""") suspend fun runnableIndexJobs(): List<ArchiveIndexJobEntity>
    @Insert suspend fun insertJob(value: ArchiveIndexJobEntity)
    @Update suspend fun updateJob(value: ArchiveIndexJobEntity)
    @Query("SELECT * FROM archive_index_jobs WHERE recordId = :recordId") suspend fun job(recordId: String): ArchiveIndexJobEntity?
    @Query("DELETE FROM archive_index_jobs WHERE recordId = :recordId") suspend fun deleteJob(recordId: String)
    @Query("SELECT * FROM archive_index_jobs WHERE state IN ('QUEUED', 'FAILED') ORDER BY updatedAt, recordId LIMIT :limit") suspend fun queuedJobs(limit: Int): List<ArchiveIndexJobEntity>
    @Query("""UPDATE archive_index_jobs SET state=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'QUEUED' END,
        leaseToken=NULL, failureCode='INTERRUPTED', updatedAt=:now WHERE state='RUNNING'""") suspend fun recoverJobs(now: Long): Int
    @Insert suspend fun insertSettings(value: ArchiveIndexSettingsEntity)
    @Update suspend fun updateSettings(value: ArchiveIndexSettingsEntity)
    @Query("SELECT * FROM archive_index_settings WHERE id = 1") suspend fun settings(): ArchiveIndexSettingsEntity?
    @Query("SELECT revision FROM archive_index_settings WHERE id = 1") fun observeIndexSettingsRevision(): Flow<Long?>
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun enqueueCleanup(value: ArchiveFileCleanupEntity)
    @Query("SELECT * FROM archive_file_cleanup ORDER BY queuedAt LIMIT :limit") suspend fun cleanupQueue(limit: Int): List<ArchiveFileCleanupEntity>
    @Query("DELETE FROM archive_file_cleanup WHERE relativePath = :path") suspend fun finishCleanup(path: String)
    @Query("""SELECT EXISTS(SELECT 1 FROM archive_records WHERE payload IS NOT NULL)
        OR EXISTS(SELECT 1 FROM archive_drafts) OR EXISTS(SELECT 1 FROM archive_messages)
        OR EXISTS(SELECT 1 FROM archive_operations) OR EXISTS(SELECT 1 FROM archive_assets)
        OR EXISTS(SELECT 1 FROM archive_embeddings)
        OR EXISTS(SELECT 1 FROM archive_index_settings WHERE configurationPayload IS NOT NULL)""") suspend fun hasEncryptedData(): Boolean
}
