package com.txl.mebot.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DayimaWriteKind { BIRTH_DATE, RECORD_PERIOD, EDIT_PERIOD, DELETE_PERIOD, ADD_NOTE, EDIT_NOTE, DELETE_NOTE, SAVE_SPAN, DELETE_SPAN }
enum class DayimaWriteOutcome { CREATED, UPDATED, UNCHANGED, DELETED, ABSENT }

/** 提交回执不依赖聊天或业务记录存在；删除记录后仍可识别旧请求。 */
@Entity(tableName = "dayima_write_receipts", indices = [Index(value = ["requestId"], unique = true)])
data class DayimaWriteReceiptEntity(
    @PrimaryKey(autoGenerate = true) val version: Long = 0,
    val requestId: String,
    val fingerprint: String,
    val kind: DayimaWriteKind,
    val targetId: String,
    val targetRevision: Long?,
    val outcome: DayimaWriteOutcome,
    val relatedNoteId: String? = null,
    val relatedNoteRevision: Long? = null,
    val committedAt: Long,
)
