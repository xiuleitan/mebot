package com.txl.mebot.data.local

import androidx.room.*
import java.security.MessageDigest

/** 按实际展示内容去重；文字输入草稿不逐次归档。历史引用保护已换下的原图。 */
@Entity(tableName = "home_reflection_history",
    foreignKeys = [ForeignKey(MediaAssetEntity::class, ["id"], ["imageAssetId"])],
    indices = [Index("imageAssetId"), Index("lastUsedAt")])
data class HomeReflectionHistoryEntity(
    @PrimaryKey val id: String,
    val mode: HomeReflectionMode,
    val text: String = "",
    val imageAssetId: String? = null,
    val firstUsedAt: Long,
    val lastUsedAt: Long,
)

data class HomeReflectionHistoryRecord(@Embedded val content: HomeReflectionHistoryEntity, val imageRelativePath: String? = null)

internal fun reflectionTextHistoryId(text: String): String = "text:" +
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun reflectionImageHistoryId(assetId: String) = "image:$assetId"
internal fun reflectionHistoryId(content: HomeReflectionEntity): String? = when (content.mode) {
    HomeReflectionMode.TEXT -> content.text.takeIf { it.isNotBlank() }?.let(::reflectionTextHistoryId)
    HomeReflectionMode.IMAGE -> content.imageAssetId?.let(::reflectionImageHistoryId)
}

@Dao
interface HomeReflectionHistoryDao {
    @Query("""SELECT h.*, m.relativePath AS imageRelativePath FROM home_reflection_history h
        LEFT JOIN media_assets m ON m.id=h.imageAssetId ORDER BY h.lastUsedAt DESC, h.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun page(limit: Int, offset: Int): List<HomeReflectionHistoryRecord>
    @Query("SELECT * FROM home_reflection_history WHERE id=:id")
    suspend fun entry(id: String): HomeReflectionHistoryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entry: HomeReflectionHistoryEntity)
    @Query("UPDATE home_reflection_history SET lastUsedAt=:time WHERE id=:id")
    suspend fun markUsed(id: String, time: Long)
    @Update suspend fun update(entry: HomeReflectionHistoryEntity): Int
    @Query("DELETE FROM home_reflection_history WHERE id=:id") suspend fun delete(id: String): Int
}
