package com.txl.mebot.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class HomeReflectionMode { TEXT, IMAGE }

/** 全局的一张寄语卡片，内容跨日沿用；图片与其他业务共用可靠本地媒体。 */
@Entity(tableName = "home_reflection", foreignKeys = [ForeignKey(MediaAssetEntity::class, ["id"], ["imageAssetId"])],
    indices = [Index("imageAssetId")])
data class HomeReflectionEntity(@PrimaryKey val id: Int = 1, val text: String = "",
    val mode: HomeReflectionMode = HomeReflectionMode.TEXT, val imageAssetId: String? = null,
    val revision: Long = 0, val updatedAt: Long = 0)

data class HomeReflectionRecord(@Embedded val content: HomeReflectionEntity = HomeReflectionEntity(), val imageRelativePath: String? = null)

@Dao
interface HomeReflectionDao {
    @Query("SELECT r.*, m.relativePath AS imageRelativePath FROM home_reflection r LEFT JOIN media_assets m ON m.id=r.imageAssetId WHERE r.id=1")
    fun observe(): Flow<HomeReflectionRecord?>
    @Query("SELECT r.*, m.relativePath AS imageRelativePath FROM home_reflection r LEFT JOIN media_assets m ON m.id=r.imageAssetId WHERE r.id=1")
    suspend fun record(): HomeReflectionRecord?
    @Insert suspend fun insert(value: HomeReflectionEntity)
    @Update suspend fun update(value: HomeReflectionEntity): Int
}
