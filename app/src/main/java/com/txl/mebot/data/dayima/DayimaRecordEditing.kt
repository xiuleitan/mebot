package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.MebotDatabase

/** 正文沿用原备注记录；各段保留各自日期和版本，不将旧记录合并丢失。 */
data class DayimaBodyNoteEdit(val id: String, val expectedRevision: Long, val originalContent: String, val content: String)

internal suspend fun MebotDatabase.validateBodyEdit(change: DayimaChange.EditPeriodDetails) {
    require(change.notes.map { it.id }.distinct().size == change.notes.size)
    val current = dayimaDao().notesForPeriod(change.id)
    if (current.map { Triple(it.id, it.revision, it.content) }.toSet() !=
        change.notes.map { Triple(it.id, it.expectedRevision, it.originalContent) }.toSet()) throw DayimaConflict()
    if (!dayimaMediaDao().periodAssetIds(change.id).containsAll(change.removedImageIds)) throw DayimaConflict()
}

/** 日期、正文、移除的图片与回执共用 Repository 的外层事务。 */
internal suspend fun MebotDatabase.applyBodyEdit(change: DayimaChange.EditPeriodDetails, now: Long): Boolean {
    var changed = false
    val dao = dayimaDao()
    for (edit in change.notes) {
        if (edit.content == edit.originalContent) continue
        val value = edit.content.trim()
        val original = dao.note(edit.id) ?: throw DayimaConflict()
        val rows = if (value.isBlank()) dao.deleteNote(edit.id, edit.expectedRevision)
            else dao.updateNote(edit.id, original.occurredDate, value, change.id, now, edit.expectedRevision)
        if (rows != 1) throw DayimaConflict()
        changed = true
    }
    change.additionalContent.trim().takeIf(String::isNotEmpty)?.let {
        dao.insertNote(DayimaNoteEntity(change.requestId, change.date, it, change.id, now, now))
        changed = true
    }
    for (asset in change.removedImageIds.distinct()) {
        if (dayimaMediaDao().removePeriod(change.id, asset) != 1) throw DayimaConflict()
        changed = true
    }
    releaseDayimaImages(this, change.removedImageIds)
    return changed
}

internal suspend fun MebotDatabase.validateSavedBody(change: DayimaChange.EditPeriodDetails) {
    val expected = change.notes.mapNotNull { edit ->
        val value = if (edit.content == edit.originalContent) edit.content else edit.content.trim()
        value.takeIf(String::isNotEmpty)?.let {
            Triple(edit.id, edit.expectedRevision + if (edit.content == edit.originalContent) 0 else 1, it)
        }
    }.toMutableList()
    change.additionalContent.trim().takeIf(String::isNotEmpty)?.let { expected += Triple(change.requestId, 1L, it) }
    if (dayimaDao().notesForPeriod(change.id).map { Triple(it.id, it.revision, it.content) }.toSet() != expected.toSet()) throw DayimaConflict()
    if (dayimaMediaDao().periodAssetIds(change.id).any { it in change.removedImageIds }) throw DayimaConflict()
}
