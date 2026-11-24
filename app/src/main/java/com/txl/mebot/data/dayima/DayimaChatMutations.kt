package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.*
import java.time.LocalDate

internal data class DayimaMutationOutcome(val text: String, val targets: List<DayimaChatTarget> = emptyList())
internal data class DayimaPreparedMutation(val change: DayimaChange? = null, val reminder: DayimaReminderSettingsEntity? = null,
    val reminderRevision: Long = 0, val deleted: DayimaChatTarget? = null, val imageTarget: DayimaChatTarget? = null) {
    suspend fun apply(repository: DayimaRepository, dao: DayimaDao, now: Long): DayimaMutationOutcome {
        imageTarget?.let { return DayimaMutationOutcome("", listOf(it)) }
        if (reminder != null) {
            if (reminderRevision == 0L) dao.insertReminderSettings(reminder.copy(updatedAt = now)) else
                if (dao.updateReminderSettings(reminder.prepareEnabled, reminder.missingRecordEnabled, reminder.reminderTime,
                    reminder.prepareDaysBefore, now, reminderRevision) != 1) throw DayimaChatChanged()
            return DayimaMutationOutcome("提醒规则已保存。\n${reminder.summary()}")
        }
        return when (val result = repository.execute(requireNotNull(change))) {
            is DayimaWriteResult.PeriodSaved -> {
                val target = DayimaChatTarget(result.period.id, result.period.startDate, result.period.revision)
                DayimaMutationOutcome(when {
                    change is DayimaChange.EditPeriod -> "已将这次开始日期更正为 ${target.date}。"
                    result.created -> "已记录 ${target.date} 开始${if (result.note != null) "，备注也已保存" else ""}。"
                    result.note != null -> "${target.date} 已有开始记录，未重复新增，备注已添加。"
                    else -> "${target.date} 已有开始记录，未重复添加。"
                }, listOf(target))
            }
            is DayimaWriteResult.BirthDateSaved -> DayimaMutationOutcome(if (result.profile.birthDate == null) "已移除出生日期。" else "出生日期已保存；年龄相关展示会重新计算。")
            is DayimaWriteResult.NoteSaved -> {
                val period = result.note.periodId?.let { dao.period(it) }
                DayimaMutationOutcome("已保存 ${result.note.occurredDate} 的备注${if (result.note.periodId == null) "（独立备注）" else ""}。",
                    period?.let { listOf(DayimaChatTarget(it.id, it.startDate, it.revision)) }.orEmpty())
            }
            is DayimaWriteResult.Deleted -> DayimaMutationOutcome("已删除 ${deleted?.date ?: "指定日期"} 的开始记录，关联备注保留到独立备注，相邻周期已重新计算。", listOfNotNull(deleted))
            else -> throw DayimaChatChanged()
        }
    }
}

internal fun prepareDayimaMutations(mutations: List<DayimaChatMutation>, snapshot: DayimaRecordsSnapshot,
    lastTargets: List<DayimaChatTarget>, requestId: String, today: LocalDate, requestText: String, clarificationText: String?,
    reminders: DayimaReminderSettingsEntity? = null): List<DayimaPreparedMutation> {
    fun target(ref: DayimaTargetRef): DayimaPeriodEntity {
        if (ref.last) {
            if (lastTargets.size != 1) throw DayimaClarification("请说明要操作哪次开始记录的日期，当前聊天没有唯一的“刚才那条”。")
            val last = lastTargets.single()
            return snapshot.periods.find { it.id == last.id && it.revision == last.revision && it.startDate == last.date }
                ?: throw DayimaClarification("刚才引用的记录已更改或不存在，请说明现在要操作哪天的记录。")
        }
        return snapshot.periods.find { it.startDate == ref.date } ?: throw DayimaClarification("没有找到 ${ref.date} 的开始记录，请核对要操作的日期。")
    }
    fun date(value: LocalDate) { if (value > today || value.year !in 1..9999) throw DayimaClarification("实际发生日期和出生日期不能在未来，请提供准确的历史日期。") }
    fun text(value: String) { if (value.isBlank() || value.length > 4000) throw DayimaClarification("请提供不超过 4000 字的备注内容。") }
    val changedIds = mutableSetOf<String>()
    var reminderPrepared = false
    return mutations.mapIndexed { index, mutation ->
        val id = "$requestId:$index"
        when (mutation) {
            is DayimaChatMutation.Record -> {
                date(mutation.date); mutation.note?.let(::text)
                if (Regex("可能|快要|还没来|没有来").containsMatchIn(requestText) && !Regex("记录|补记|帮我记").containsMatchIn(requestText))
                    throw DayimaClarification("这是已经实际开始了，还是只是感觉可能快来了？只有实际开始才记为经期第一天。")
                DayimaPreparedMutation(DayimaChange.RecordPeriod(mutation.date, mutation.note, id))
            }
            is DayimaChatMutation.Edit -> {
                date(mutation.date)
                val row = target(mutation.target)
                if (!changedIds.add(row.id)) throw DayimaClarification("同一条记录有多项日期操作，请说明最终要保留的日期。")
                if (snapshot.periods.any { it.startDate == mutation.date && it.id != row.id }) throw DayimaClarification("${mutation.date} 已有另一条开始记录，请核对更正后的日期。")
                DayimaPreparedMutation(DayimaChange.EditPeriod(row.id, row.revision, mutation.date, id))
            }
            is DayimaChatMutation.Delete -> {
                fun explicit(text: String) = Regex("删除|删掉|删了|移除|delete|remove", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                    !Regex("不要删|别删|不删|先不删").containsMatchIn(text)
                if (Regex("不要删|别删|不删|先不删").containsMatchIn(requestText) || !explicit(requestText) && clarificationText?.let(::explicit) != true)
                    throw DayimaClarification("本次未删除记录。若确实需要删除，请说明要删哪天的开始记录。")
                val row = target(mutation.target)
                if (!changedIds.add(row.id)) throw DayimaClarification("同一条记录有多项日期操作，请说明希望如何处理。")
                DayimaPreparedMutation(DayimaChange.DeletePeriod(row.id, row.revision, id), deleted = DayimaChatTarget(row.id, row.startDate, row.revision))
            }
            is DayimaChatMutation.Note -> {
                date(mutation.date); text(mutation.text)
                DayimaPreparedMutation(DayimaChange.AddNote(mutation.date, mutation.text, mutation.target?.let(::target)?.id, id))
            }
            is DayimaChatMutation.Images -> {
                val row = target(mutation.target)
                date(row.startDate)
                DayimaPreparedMutation(imageTarget = DayimaChatTarget(row.id, row.startDate, row.revision))
            }
            is DayimaChatMutation.Birth -> {
                mutation.date?.let(::date)
                DayimaPreparedMutation(DayimaChange.SaveBirthDate(mutation.date, snapshot.profile?.revision ?: 0, id))
            }
            is DayimaChatMutation.Reminders -> {
                if (reminderPrepared) throw DayimaClarification("请把希望的提醒时刻、开关和提前天数合并说明。")
                reminderPrepared = true
                if (mutation.daysBefore?.let { it !in 0..30 } == true || mutation.time?.let { it.second != 0 || it.nano != 0 } == true)
                    throw DayimaClarification("准备提醒可提前 0～30 天，提醒时刻请精确到分钟。")
                val previous = reminders ?: DayimaReminderSettingsEntity(updatedAt = 0)
                DayimaPreparedMutation(reminder = previous.copy(prepareEnabled = mutation.prepare ?: previous.prepareEnabled,
                    missingRecordEnabled = mutation.missing ?: previous.missingRecordEnabled, reminderTime = mutation.time ?: previous.reminderTime,
                    prepareDaysBefore = mutation.daysBefore ?: previous.prepareDaysBefore, revision = (reminders?.revision ?: 0) + 1), reminderRevision = reminders?.revision ?: 0)
            }
        }
    }
}

internal fun DayimaChatMutation.action(): DayimaOperationAction = when (this) {
    is DayimaChatMutation.Record -> DayimaOperationAction.RECORD
    is DayimaChatMutation.Edit, is DayimaChatMutation.Birth -> DayimaOperationAction.EDIT
    is DayimaChatMutation.Delete -> DayimaOperationAction.DELETE
    is DayimaChatMutation.Note -> DayimaOperationAction.NOTE
    is DayimaChatMutation.Images -> DayimaOperationAction.NOTE
    is DayimaChatMutation.Reminders -> DayimaOperationAction.REMINDER
}
