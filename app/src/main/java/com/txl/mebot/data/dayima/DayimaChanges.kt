package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaProfileEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaSpanReason
import com.txl.mebot.data.local.DayimaWriteKind
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** 创建一次请求后保留同一对象或 requestId 重试；更改内容时创建新的请求。 */
sealed interface DayimaChange {
    val requestId: String

    data class RecordPeriod(val date: LocalDate, val note: String? = null,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class EditPeriod(val id: String, val expectedRevision: Long, val date: LocalDate,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class EditPeriodDetails(val id: String, val expectedRevision: Long, val date: LocalDate,
        val notes: List<DayimaBodyNoteEdit>, val additionalContent: String = "", val removedImageIds: List<String> = emptyList(),
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class DeletePeriod(val id: String, val expectedRevision: Long,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class SaveBirthDate(val date: LocalDate?, val expectedRevision: Long = 0,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class AddNote(val date: LocalDate, val content: String, val periodId: String? = null,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class EditNote(val id: String, val expectedRevision: Long, val date: LocalDate,
        val content: String, val periodId: String?,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class DeleteNote(val id: String, val expectedRevision: Long,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class SaveSpan(val startPeriodId: String, val endPeriodId: String,
        val startPeriodRevision: Long, val endPeriodRevision: Long, val reason: DayimaSpanReason,
        val detail: String = "", val id: String? = null, val expectedRevision: Long = 0,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
    data class DeleteSpan(val id: String, val expectedRevision: Long,
        override val requestId: String = UUID.randomUUID().toString()) : DayimaChange
}

class DayimaNotFound : IllegalStateException("记录已不存在，请刷新后重试")
class DayimaConflict : IllegalStateException("记录已改变，请刷新后重试")
class DayimaDateConflict : IllegalStateException("目标日期已有另一条经期记录")
class DayimaRequestConflict : IllegalStateException("同一请求的内容已改变，请重新提交")

enum class DayimaDeletedKind { PERIOD, NOTE, SPAN }

sealed interface DayimaWriteResult {
    val dataVersion: Long
    val replayed: Boolean
    val changed: Boolean

    data class PeriodSaved(val period: DayimaPeriodEntity, val note: DayimaNoteEntity?, val created: Boolean,
        override val changed: Boolean, override val dataVersion: Long, override val replayed: Boolean) : DayimaWriteResult
    data class BirthDateSaved(val profile: DayimaProfileEntity, override val changed: Boolean,
        override val dataVersion: Long, override val replayed: Boolean) : DayimaWriteResult
    data class NoteSaved(val note: DayimaNoteEntity, val created: Boolean, override val changed: Boolean,
        override val dataVersion: Long, override val replayed: Boolean) : DayimaWriteResult
    data class SpanSaved(val marker: DayimaSpanMarkerEntity, val created: Boolean, override val changed: Boolean,
        override val dataVersion: Long, override val replayed: Boolean) : DayimaWriteResult
    data class Deleted(val kind: DayimaDeletedKind, val id: String, val existed: Boolean,
        override val dataVersion: Long, override val replayed: Boolean) : DayimaWriteResult {
        override val changed: Boolean get() = existed && !replayed
    }
}

/** 同一事务内读取的事实快照，供后续统计复用。 */
data class DayimaRecordsSnapshot(
    val dataVersion: Long,
    val profile: DayimaProfileEntity?,
    val periods: List<DayimaPeriodEntity>,
    val spanMarkers: List<DayimaSpanMarkerEntity>,
)

data class DayimaSpanMarkerState(val marker: DayimaSpanMarkerEntity, val needsReview: Boolean)

internal val DayimaChange.kind: DayimaWriteKind get() = when (this) {
    is DayimaChange.RecordPeriod -> DayimaWriteKind.RECORD_PERIOD
    is DayimaChange.EditPeriod, is DayimaChange.EditPeriodDetails -> DayimaWriteKind.EDIT_PERIOD
    is DayimaChange.DeletePeriod -> DayimaWriteKind.DELETE_PERIOD
    is DayimaChange.SaveBirthDate -> DayimaWriteKind.BIRTH_DATE
    is DayimaChange.AddNote -> DayimaWriteKind.ADD_NOTE
    is DayimaChange.EditNote -> DayimaWriteKind.EDIT_NOTE
    is DayimaChange.DeleteNote -> DayimaWriteKind.DELETE_NOTE
    is DayimaChange.SaveSpan -> DayimaWriteKind.SAVE_SPAN
    is DayimaChange.DeleteSpan -> DayimaWriteKind.DELETE_SPAN
}

/** 固定字段顺序与长度前缀区分 null、换行及分隔符，不依赖 JSON 键顺序。 */
internal fun DayimaChange.fingerprint(): String {
    val fields: List<Any?> = when (this) {
        is DayimaChange.RecordPeriod -> listOf(date, note)
        is DayimaChange.EditPeriod -> listOf(id, expectedRevision, date)
        is DayimaChange.EditPeriodDetails -> listOf("details_v1", id, expectedRevision, date, additionalContent,
            removedImageIds.distinct().size) + removedImageIds.distinct().sorted() + listOf(notes.size) + notes.sortedBy { it.id }.flatMap {
                listOf(it.id, it.expectedRevision, it.originalContent, it.content)
            }
        is DayimaChange.DeletePeriod -> listOf(id, expectedRevision)
        is DayimaChange.SaveBirthDate -> listOf(date, expectedRevision)
        is DayimaChange.AddNote -> listOf(date, content, periodId)
        is DayimaChange.EditNote -> listOf(id, expectedRevision, date, content, periodId)
        is DayimaChange.DeleteNote -> listOf(id, expectedRevision)
        is DayimaChange.SaveSpan -> listOf(startPeriodId, endPeriodId, startPeriodRevision,
            endPeriodRevision, reason.name, detail, id, expectedRevision)
        is DayimaChange.DeleteSpan -> listOf(id, expectedRevision)
    }
    val canonical = (listOf("v1", kind.name) + fields).joinToString("") { value ->
        value?.toString()?.let { "${it.length}:$it" } ?: "-1:"
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
