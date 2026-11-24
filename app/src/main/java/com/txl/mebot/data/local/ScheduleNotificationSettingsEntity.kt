package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 保留旧版铃声元数据及 files/ringtones 的路径，兼容已经安装过的数据库。 */
@Entity(tableName = "schedule_notification_settings")
data class ScheduleNotificationSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val ringtonePath: String? = null,
    val ringtoneName: String? = null,
    val byteCount: Long = 0,
    val durationMillis: Long = 0,
    val updatedAt: Long,
)
