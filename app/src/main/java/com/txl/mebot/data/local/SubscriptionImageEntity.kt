package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 订阅图库独立于聊天附件；更换封面不改变用户选择的图片顺序。 */
@Entity(tableName = "subscription_images", primaryKeys = ["subscriptionId", "assetId"], foreignKeys = [
    ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"]),
    ForeignKey(MediaAssetEntity::class, ["id"], ["assetId"]),
], indices = [Index("assetId"), Index(value = ["subscriptionId", "position"])])
data class SubscriptionImageEntity(val subscriptionId: String, val assetId: String, val position: Long)
