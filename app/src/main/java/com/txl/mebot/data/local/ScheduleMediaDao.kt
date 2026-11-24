package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 附件直接属于任务会话，选择后即可持久保存，不制造聊天消息。 */
@Entity(tableName = "schedule_local_attachments", primaryKeys = ["sessionId", "assetId"],
    foreignKeys = [ForeignKey(ScheduleSessionEntity::class, ["id"], ["sessionId"]),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"])],
    indices = [Index("assetId"), Index(value = ["sessionId", "position"])])
data class ScheduleLocalAttachmentEntity(
    val sessionId: String,
    val assetId: String,
    val displayName: String,
    val position: Long,
    val addedAt: Long,
    val isCover: Boolean = false,
    @ColumnInfo(defaultValue = "1") val pendingSend: Boolean = true,
    @ColumnInfo(defaultValue = "1") val draftOnly: Boolean = true,
)

data class ScheduleLocalAttachment(
    @Embedded val link: ScheduleLocalAttachmentEntity,
    @Embedded(prefix = "media_") val asset: MediaAssetEntity,
) {
    val isImage: Boolean get() = asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0
}

/** 供文字模型理解附件指代，仅含数量和宽泛类型，不携带媒体或文件元数据。 */
data class ScheduleAttachmentSummary(val totalCount: Int = 0, val imageCount: Int = 0, val fileCount: Int = 0) {
    init {
        require(totalCount >= 0 && imageCount >= 0 && fileCount >= 0 && totalCount.toLong() == imageCount.toLong() + fileCount)
    }
}

data class ScheduleMessageAttachment(
    @Embedded val link: ScheduleAttachmentEntity,
    @Embedded(prefix = "media_") val asset: MediaAssetEntity,
) {
    val isImage: Boolean get() = asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0
    fun asLocal() = ScheduleLocalAttachment(ScheduleLocalAttachmentEntity(link.sessionId, asset.id,
        link.displayName ?: asset.originalName ?: "附件", link.position.toLong(), link.addedAt, pendingSend = false, draftOnly = false), asset)
}

data class ScheduleMessageAttachmentCount(val messageId: String, val totalCount: Int, val imageCount: Int, val fileCount: Int)

private const val AttachmentSelect = """
 SELECT l.*, a.id AS media_id, a.source AS media_source, a.relativePath AS media_relativePath,
 a.builtinKey AS media_builtinKey, a.originalName AS media_originalName, a.mimeType AS media_mimeType,
 a.byteCount AS media_byteCount, a.width AS media_width, a.height AS media_height,
 a.sha256 AS media_sha256, a.generationPrompt AS media_generationPrompt, a.generator AS media_generator,
 a.createdAt AS media_createdAt FROM schedule_local_attachments l JOIN media_assets a ON a.id = l.assetId
 WHERE l.sessionId = :sessionId ORDER BY l.position, l.assetId
"""

@Dao
interface ScheduleMediaDao {
    @Query("""SELECT r.messageId, COUNT(*) AS totalCount,
        SUM(CASE WHEN a.mimeType GLOB 'image/*' AND a.width > 0 AND a.height > 0 THEN 1 ELSE 0 END) AS imageCount,
        SUM(CASE WHEN a.mimeType GLOB 'image/*' AND a.width > 0 AND a.height > 0 THEN 0 ELSE 1 END) AS fileCount
        FROM schedule_attachments r JOIN media_assets a ON a.id = r.assetId
        WHERE r.sessionId = :sessionId AND r.messageId IN (:messageIds) GROUP BY r.messageId""")
    suspend fun messageAttachmentCounts(sessionId: String, messageIds: List<String>): List<ScheduleMessageAttachmentCount>
    @Query("""SELECT r.*, a.id AS media_id, a.source AS media_source, a.relativePath AS media_relativePath,
        a.builtinKey AS media_builtinKey, a.originalName AS media_originalName, a.mimeType AS media_mimeType,
        a.byteCount AS media_byteCount, a.width AS media_width, a.height AS media_height,
        a.sha256 AS media_sha256, a.generationPrompt AS media_generationPrompt, a.generator AS media_generator,
        a.createdAt AS media_createdAt FROM schedule_attachments r JOIN media_assets a ON a.id = r.assetId
        JOIN schedule_messages m ON m.id = r.messageId
        WHERE r.sessionId = :sessionId AND m.id IN
        (SELECT id FROM schedule_messages WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit)
        ORDER BY m.sequence, r.position, r.addedAt, r.assetId""")
    fun observeMessageAttachments(sessionId: String, limit: Int): Flow<List<ScheduleMessageAttachment>>
    @Query("SELECT EXISTS(SELECT 1 FROM schedule_attachments WHERE sessionId = :sessionId AND messageId = :messageId AND assetId = :assetId)")
    suspend fun hasMessageAttachment(sessionId: String, messageId: String, assetId: String): Boolean
    @Query("SELECT * FROM schedule_attachments WHERE sessionId = :sessionId AND messageId = :messageId AND assetId = :assetId")
    suspend fun messageAttachment(sessionId: String, messageId: String, assetId: String): ScheduleAttachmentEntity?
    @Query("UPDATE schedule_local_attachments SET pendingSend = :pending WHERE sessionId = :sessionId AND assetId = :assetId")
    suspend fun markPending(sessionId: String, assetId: String, pending: Boolean)
    @Query("UPDATE schedule_local_attachments SET pendingSend = 0, draftOnly = 0 WHERE sessionId = :sessionId AND assetId IN (:assetIds)")
    suspend fun markSent(sessionId: String, assetIds: List<String>)
    @Query("""SELECT COUNT(*) AS totalCount, COALESCE(SUM(isImage), 0) AS imageCount,
        COALESCE(SUM(1 - isImage), 0) AS fileCount FROM (
            SELECT CASE WHEN a.mimeType GLOB 'image/*' AND a.width > 0 AND a.height > 0 THEN 1 ELSE 0 END AS isImage
            FROM schedule_local_attachments l JOIN media_assets a ON a.id = l.assetId
            WHERE l.sessionId = :sessionId
        )""")
    suspend fun attachmentSummary(sessionId: String): ScheduleAttachmentSummary
    @Query(AttachmentSelect) fun observeAttachments(sessionId: String): Flow<List<ScheduleLocalAttachment>>
    @Query(AttachmentSelect) suspend fun attachments(sessionId: String): List<ScheduleLocalAttachment>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: ScheduleLocalAttachmentEntity): Long
    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM schedule_local_attachments WHERE sessionId = :sessionId")
    suspend fun nextPosition(sessionId: String): Long
    @Query("UPDATE schedule_local_attachments SET isCover = (assetId = :assetId) WHERE sessionId = :sessionId")
    suspend fun selectCover(sessionId: String, assetId: String?)
    @Query("UPDATE schedule_tasks SET coverAssetId = :assetId, updatedAt = :now WHERE sessionId = :sessionId")
    suspend fun updateTaskCover(sessionId: String, assetId: String?, now: Long)
    @Query("UPDATE schedule_task_versions SET coverAssetId = :assetId WHERE taskId IN (SELECT id FROM schedule_tasks WHERE sessionId = :sessionId)")
    suspend fun updateVersionCovers(sessionId: String, assetId: String?)
    @Query("UPDATE schedule_occurrences SET coverAssetId = :assetId WHERE taskId IN (SELECT id FROM schedule_tasks WHERE sessionId = :sessionId)")
    suspend fun updateOccurrenceCovers(sessionId: String, assetId: String?)
    @Query("DELETE FROM schedule_local_attachments WHERE sessionId = :sessionId AND assetId = :assetId")
    suspend fun remove(sessionId: String, assetId: String)
    @Query("DELETE FROM schedule_attachments WHERE sessionId = :sessionId AND assetId = :assetId")
    suspend fun removeLegacyReferences(sessionId: String, assetId: String)
    @Query("DELETE FROM schedule_local_attachments WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
