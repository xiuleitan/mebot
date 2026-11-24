package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "dayima_draft_media", primaryKeys = ["sessionId", "assetId"],
    foreignKeys = [ForeignKey(DayimaChatSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.NO_ACTION)],
    indices = [Index("assetId"), Index(value = ["sessionId", "position"])])
data class DayimaDraftMediaEntity(val sessionId: String, val assetId: String, val displayName: String,
    val position: Int, val createdAt: Long)

@Entity(tableName = "dayima_message_media", primaryKeys = ["messageId", "assetId"],
    foreignKeys = [ForeignKey(DayimaChatMessageEntity::class, ["id"], ["messageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.NO_ACTION)],
    indices = [Index("assetId"), Index(value = ["messageId", "position"])])
data class DayimaMessageMediaEntity(val messageId: String, val assetId: String, val displayName: String,
    val position: Int, val createdAt: Long)

@Entity(tableName = "dayima_period_media", primaryKeys = ["periodId", "assetId"],
    foreignKeys = [ForeignKey(DayimaPeriodEntity::class, ["id"], ["periodId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(DayimaChatMessageEntity::class, ["id"], ["sourceMessageId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("assetId"), Index("sourceMessageId"), Index(value = ["periodId", "position"])])
data class DayimaPeriodMediaEntity(val periodId: String, val assetId: String, val displayName: String,
    val position: Int, val sourceMessageId: String?, val createdAt: Long)
