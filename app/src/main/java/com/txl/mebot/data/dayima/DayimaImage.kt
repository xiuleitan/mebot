package com.txl.mebot.data.dayima

import androidx.room.Embedded
import com.txl.mebot.data.local.MediaAssetEntity

/** 图片原件由共用媒体库持有；此模型仅供本地预览与记录详情使用。 */
data class DayimaImage(@Embedded val asset: MediaAssetEntity, val displayName: String)

data class DayimaMessageImage(@Embedded val asset: MediaAssetEntity, val displayName: String, val messageId: String) {
    fun image() = DayimaImage(asset, displayName)
}
