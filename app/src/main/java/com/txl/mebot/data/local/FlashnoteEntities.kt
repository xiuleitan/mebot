package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FlashnoteStatus { PENDING, COMPLETED }

data class FlashnoteStatusCount(val status: FlashnoteStatus, val count: Int)

/** 创建时间使用毫秒时间戳；后续编辑和状态变化不改写，两个列表均以此排序。 */
@Entity(
    tableName = "flashnotes",
    indices = [Index(value = ["status", "createdAt", "id"])],
)
data class FlashnoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val content: String,
    val status: FlashnoteStatus = FlashnoteStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val revision: Long = 1,
)

/** 新建草稿不依赖正式事项；每件事项最多一份编辑草稿，删除事项时一并清理。 */
@Entity(
    tableName = "flashnote_drafts",
    foreignKeys = [ForeignKey(
        entity = FlashnoteEntity::class,
        parentColumns = ["id"], childColumns = ["editingNoteId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["editingNoteId"], unique = true), Index("updatedAt")],
)
data class FlashnoteDraftEntity(
    @PrimaryKey val key: String,
    val title: String = "",
    val content: String = "",
    val editingNoteId: String? = null,
    val baseRevision: Long? = null,
    val revision: Long = 0,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val attachmentSignature: String = "",
)

@Entity(tableName = "flashnote_attachments", primaryKeys = ["noteId", "assetId"],
    foreignKeys = [ForeignKey(FlashnoteEntity::class, ["id"], ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"])],
    indices = [Index("assetId"), Index(value = ["noteId", "position"])])
data class FlashnoteAttachmentEntity(val noteId: String, val assetId: String, val displayName: String, val position: Long)

@Entity(tableName = "flashnote_draft_attachments", primaryKeys = ["draftKey", "assetId"],
    foreignKeys = [ForeignKey(FlashnoteDraftEntity::class, ["key"], ["draftKey"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"])],
    indices = [Index("assetId"), Index(value = ["draftKey", "position"])])
data class FlashnoteDraftAttachmentEntity(val draftKey: String, val assetId: String, val displayName: String, val position: Long)

data class FlashnoteAttachment(
    @Embedded val asset: MediaAssetEntity,
    val displayName: String,
    val position: Long,
) {
    val isImage: Boolean get() = asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0
}

data class FlashnoteCardRecord(@Embedded val note: FlashnoteEntity, val coverRelativePath: String?, val attachmentCount: Int)
