package com.txl.mebot.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "home_routing_settings")
data class HomeRoutingSettingsEntity(@PrimaryKey val id: Int = 1, val endpoint: String = "", val revision: Long = 0)

/** 发送回执把首页快照与原 Bot 的独立任务绑定；已提交的消息不会因重试重复生成。 */
@Entity(tableName = "home_dispatches", indices = [Index(value = ["snapshotKey"], unique = true),
    Index(value = ["botId", "targetId"]), Index(value = ["handedOff", "dismissed", "createdAt"])])
data class HomeDispatchEntity(
    @PrimaryKey val id: String, val snapshotKey: String, val botId: String,
    val targetId: String, val messageId: String, val createdAt: Long,
    val handedOff: Boolean = false, val dismissed: Boolean = false,
)

@Dao
interface HomeRoutingDao {
    @Query("SELECT * FROM home_routing_settings WHERE id=1") suspend fun settings(): HomeRoutingSettingsEntity?
    @Query("SELECT * FROM home_routing_settings WHERE id=1") fun observeSettings(): Flow<HomeRoutingSettingsEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSettings(settings: HomeRoutingSettingsEntity)
    @Query("UPDATE home_routing_settings SET endpoint=:endpoint, revision=revision+1 WHERE id=1 AND revision=:revision")
    suspend fun saveSettings(endpoint: String, revision: Long): Int
    @Query("SELECT * FROM home_dispatches WHERE snapshotKey=:key") suspend fun dispatch(key: String): HomeDispatchEntity?
    @Query("SELECT * FROM home_dispatches WHERE id=:id") suspend fun dispatchById(id: String): HomeDispatchEntity?
    @Query("SELECT * FROM home_dispatches WHERE handedOff=1 AND dismissed=0 ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun active(): HomeDispatchEntity?
    @Insert suspend fun insert(dispatch: HomeDispatchEntity)
    @Query("UPDATE home_dispatches SET handedOff=1, dismissed=:dismissed WHERE id=:id") suspend fun handedOff(id: String, dismissed: Boolean)
    @Query("UPDATE home_dispatches SET dismissed=1 WHERE id=:id") suspend fun dismiss(id: String)
}
