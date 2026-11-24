package com.txl.mebot.ui.dayima

import android.os.Bundle
import com.txl.mebot.data.dayima.DayimaOverview
import com.txl.mebot.data.dayima.DayimaPeriodDetail
import com.txl.mebot.data.dayima.DayimaBodyNoteEdit
import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaSpanReason
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal enum class DayimaHistory { PERIODS, NOTES }
internal enum class DayimaEditorKind { RECORD, BIRTH, PERIOD, NOTE, SPAN }
internal enum class DayimaDeleteKind { PERIOD, NOTE, SPAN }

/** 仅保存正在输入的界面状态；正式业务写入仍通过 Repository 的事务完成。 */
internal data class DayimaEditor(
    val kind: DayimaEditorKind,
    val date: String = "",
    val content: String = "",
    val targetId: String? = null,
    val revision: Long = 0,
    val periodId: String? = null,
    val endPeriodId: String? = null,
    val startRevision: Long = 0,
    val endRevision: Long = 0,
    val reason: DayimaSpanReason = DayimaSpanReason.MISSING_RECORDS,
    val requestId: String = UUID.randomUUID().toString(),
    val error: Int? = null,
    val bodyNotes: List<DayimaBodyNoteEdit> = emptyList(),
    val removedImageIds: List<String> = emptyList(),
) {
    fun bundle() = Bundle().apply {
        putString("kind", kind.name); putString("date", date); putString("content", content)
        putString("target", targetId); putLong("revision", revision); putString("period", periodId)
        putString("end", endPeriodId); putLong("startRevision", startRevision); putLong("endRevision", endRevision)
        putString("reason", reason.name); putString("request", requestId)
        putParcelableArrayList("body_notes", ArrayList(bodyNotes.map { note -> Bundle().apply {
            putString("id", note.id); putLong("revision", note.expectedRevision)
            putString("original", note.originalContent); putString("content", note.content)
        } }))
        putStringArrayList("removed_images", ArrayList(removedImageIds))
    }

    companion object {
        @Suppress("DEPRECATION")
        fun restore(bundle: Bundle?): DayimaEditor? = bundle?.let { value -> runCatching {
            DayimaEditor(DayimaEditorKind.valueOf(requireNotNull(value.getString("kind"))),
                value.getString("date").orEmpty(), value.getString("content").orEmpty(), value.getString("target"),
                value.getLong("revision"), value.getString("period"), value.getString("end"),
                value.getLong("startRevision"), value.getLong("endRevision"),
                DayimaSpanReason.valueOf(requireNotNull(value.getString("reason"))), requireNotNull(value.getString("request")),
                bodyNotes = value.getParcelableArrayList<Bundle>("body_notes").orEmpty().map { note ->
                    DayimaBodyNoteEdit(requireNotNull(note.getString("id")), note.getLong("revision"),
                        note.getString("original").orEmpty(), note.getString("content").orEmpty())
                }, removedImageIds = value.getStringArrayList("removed_images").orEmpty())
        }.getOrNull() }
    }
}

internal data class DayimaDeleteTarget(
    val kind: DayimaDeleteKind, val id: String, val revision: Long, val label: String,
    val requestId: String = UUID.randomUUID().toString(), val error: Int? = null,
) {
    fun bundle() = Bundle().apply {
        putString("kind", kind.name); putString("id", id); putLong("revision", revision)
        putString("label", label); putString("request", requestId)
    }
    companion object {
        fun restore(bundle: Bundle?): DayimaDeleteTarget? = bundle?.let { value -> runCatching {
            DayimaDeleteTarget(DayimaDeleteKind.valueOf(requireNotNull(value.getString("kind"))),
                requireNotNull(value.getString("id")), value.getLong("revision"), value.getString("label").orEmpty(),
                requireNotNull(value.getString("request")))
        }.getOrNull() }
    }
}

internal data class DayimaNotice(val text: Int, val token: String = UUID.randomUUID().toString())

internal data class DayimaPageState(
    val today: LocalDate = LocalDate.now(),
    val overview: DayimaOverview? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val history: DayimaHistory = DayimaHistory.PERIODS,
    val detailId: String? = null,
    val detail: DayimaPeriodDetail? = null,
    val detailLoading: Boolean = false,
    val detailFailed: Boolean = false,
    val independentNotes: List<DayimaNoteEntity> = emptyList(),
    val notesLoading: Boolean = false,
    val notesFailed: Boolean = false,
    val notesHaveMore: Boolean = false,
    val editor: DayimaEditor? = null,
    val deletion: DayimaDeleteTarget? = null,
    val reviewingMarkers: Boolean = false,
    val charts: DayimaChartsState = DayimaChartsState(),
    val busy: Boolean = false,
    val notice: DayimaNotice? = null,
)

internal fun parseDayimaDate(text: String): LocalDate? = runCatching {
    val input = text.trim()
    LocalDate.parse(input, if (input.matches(Regex("\\d{8}"))) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE)
}.getOrNull()
