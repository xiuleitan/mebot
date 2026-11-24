package com.txl.mebot.ui.flashnote

import androidx.compose.runtime.Immutable
import com.txl.mebot.R
import com.txl.mebot.data.local.FlashnoteEntity
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.data.local.FlashnoteCardRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Immutable
data class FlashnoteEntry(
    val id: String,
    val title: String,
    val detail: String,
    val createdAt: Long,
    val completed: Boolean = false,
    val revision: Long = 1,
    val image: Int? = null,
    val coverRelativePath: String? = null,
    val attachmentCount: Int = 0,
) {
    // 卡片仅做长度裁切，正文不因 AI 标题或首行重复而被改写、去空白或删行。
    val summary: String get() = detail
}

private val CreationLabel = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
internal fun FlashnoteEntry.creationLabel(): String = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).format(CreationLabel)

internal fun FlashnoteEntity.asEntry() = FlashnoteEntry(
    id = id, title = title, detail = content, createdAt = createdAt,
    completed = status == FlashnoteStatus.COMPLETED, revision = revision,
)

internal fun FlashnoteCardRecord.asEntry() = note.asEntry().copy(coverRelativePath = coverRelativePath, attachmentCount = attachmentCount)

/** 仅供 Android Studio 预览，不注入正式页面或数据库。 */
internal fun previewFlashnoteEntries(): List<FlashnoteEntry> = listOf(
    FlashnoteEntry("read", "整理想读的书", "把收藏的书单按主题整理一下，挑几本最想读的书。",
        LocalDateTime.of(2026, 10, 17, 9, 20).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), image = R.drawable.schedule_review),
    FlashnoteEntry("home", "给家里做一次收纳", "先从书桌和常用抽屉开始，让每天取东西更方便。",
        LocalDateTime.of(2026, 10, 19, 15, 40).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), image = R.drawable.schedule_meeting),
    FlashnoteEntry("cook", "试做一顿新的晚餐", "找一道一直想学的家常菜，记下食材和做法。",
        LocalDateTime.of(2026, 10, 20, 19, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), image = R.drawable.schedule_dinner),
)
