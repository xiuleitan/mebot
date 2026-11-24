package com.txl.mebot.data.local

import androidx.room.*
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "schedule_editor_drafts", foreignKeys = [ForeignKey(
    entity = ScheduleOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.CASCADE,
)])
data class ScheduleEditorDraftEntity(
    @PrimaryKey val occurrenceId: String,
    val requestId: String,
    val sessionId: String,
    val expectedRevision: Long,
    val expectedTaskRevision: Long,
    val attachmentFingerprint: String,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val time: LocalTime?,
    val isRecurring: Boolean,
    val revision: Long = 1,
    val updatedAt: Long,
)

@Entity(tableName = "schedule_editor_attachments", primaryKeys = ["occurrenceId", "assetId"], foreignKeys = [
    ForeignKey(entity = ScheduleEditorDraftEntity::class, parentColumns = ["occurrenceId"], childColumns = ["occurrenceId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = MediaAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"]),
], indices = [Index(value = ["assetId"])])
data class ScheduleEditorAttachmentEntity(val occurrenceId: String, val assetId: String, val position: Long)

data class ScheduleEditorAttachment(
    @Embedded val link: ScheduleEditorAttachmentEntity,
    @Relation(parentColumn = "assetId", entityColumn = "id") val asset: MediaAssetEntity,
) {
    val isImage get() = asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0
}

@Dao
interface ScheduleEditorDao {
    @Query("SELECT * FROM schedule_editor_drafts WHERE occurrenceId = :id") suspend fun draft(id: String): ScheduleEditorDraftEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(draft: ScheduleEditorDraftEntity)
    @Update suspend fun update(draft: ScheduleEditorDraftEntity)
    @Query("DELETE FROM schedule_editor_drafts WHERE occurrenceId = :id") suspend fun delete(id: String)
    @Transaction @Query("SELECT * FROM schedule_editor_attachments WHERE occurrenceId = :id ORDER BY position, assetId")
    suspend fun attachments(id: String): List<ScheduleEditorAttachment>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun attach(item: ScheduleEditorAttachmentEntity)
    @Query("DELETE FROM schedule_editor_attachments WHERE occurrenceId = :id AND assetId = :assetId") suspend fun remove(id: String, assetId: String)
}
