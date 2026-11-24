package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** 只有加密后的凭据入库；AES 密钥由 Android Keystore 持有。 */
@Entity(tableName = "model_settings")
data class ModelSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val languageModel: String,
    val languageEndpoint: String,
    val imageModel: String,
    val imageEndpoint: String,
    val imageSize: Int,
    val keyCiphertext: ByteArray?,
    val keyIv: ByteArray?,
    val revision: Long,
    val updatedAt: Long,
)

@Dao
interface ModelSettingsDao {
    @Query("SELECT * FROM model_settings WHERE id = 1")
    suspend fun read(): ModelSettingsEntity?
    @Upsert suspend fun save(settings: ModelSettingsEntity)
}
