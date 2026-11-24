package com.txl.mebot.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 首页独立的待发送草稿；尚未分配给任何 Bot。 */
@Entity(tableName = "home_composer_drafts")
data class HomeComposerDraftEntity(
    @PrimaryKey val id: Int = 1,
    val inputText: String = "",
    val revision: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(tableName = "home_composer_attachments", primaryKeys = ["draftId", "assetId"],
    foreignKeys = [ForeignKey(HomeComposerDraftEntity::class, ["id"], ["draftId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"])],
    indices = [Index("assetId"), Index(value = ["draftId", "position"])])
data class HomeComposerAttachmentEntity(
    val draftId: Int = 1,
    val assetId: String,
    val displayName: String,
    val position: Long,
    val sourceUri: String?,
)

data class HomeComposerAttachment(
    @Embedded val link: HomeComposerAttachmentEntity,
    @Embedded(prefix = "media_") val asset: MediaAssetEntity,
) {
    val isImage: Boolean get() = asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0
}

private const val HomeComposerMediaQuery = """SELECT l.*, a.id AS media_id, a.source AS media_source,
    a.relativePath AS media_relativePath, a.builtinKey AS media_builtinKey, a.originalName AS media_originalName,
    a.mimeType AS media_mimeType, a.byteCount AS media_byteCount, a.width AS media_width, a.height AS media_height,
    a.sha256 AS media_sha256, a.generationPrompt AS media_generationPrompt, a.generator AS media_generator,
    a.createdAt AS media_createdAt FROM home_composer_attachments l JOIN media_assets a ON a.id=l.assetId
    WHERE l.draftId=1 ORDER BY l.position, l.assetId"""

@Dao
interface HomeComposerDao {
    @Query("SELECT * FROM home_composer_drafts WHERE id=1") suspend fun draft(): HomeComposerDraftEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(draft: HomeComposerDraftEntity)
    @Query("""UPDATE home_composer_drafts SET inputText=:text, revision=revision+1, updatedAt=:time
        WHERE id=1 AND revision=:revision""")
    suspend fun saveInput(text: String, revision: Long, time: Long): Int
    @Query(HomeComposerMediaQuery) fun observeAttachments(): Flow<List<HomeComposerAttachment>>
    @Query(HomeComposerMediaQuery) suspend fun attachments(): List<HomeComposerAttachment>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun attach(item: HomeComposerAttachmentEntity)
    @Query("DELETE FROM home_composer_attachments WHERE draftId=1 AND assetId=:id") suspend fun remove(id: String): Int
}
