package com.txl.mebot.data.schedule

import androidx.room.withTransaction
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ScheduleMessageEntity
import com.txl.mebot.data.local.ScheduleTaskEntity
import com.txl.mebot.data.local.ScheduleOccurrenceEntity
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val ScheduleAnalysisMaxAttempts = 10

data class ScheduleCreationResult(
    val savedDate: LocalDate? = null, val deletedSessionId: String? = null,
    val deletedOccurrenceId: String? = null, val cleanupPending: Boolean = false,
    val createdOccurrenceId: String? = null,
)

private data class AnalysisInput(
    val message: ScheduleMessageEntity, val task: ScheduleTaskEntity?, val context: ScheduleAnalysisContext,
    val selected: ScheduleOccurrenceEntity?,
)

/** 网络解释在事务外执行；校验、任务写入、助手回复和追问答复在同一事务内提交。 */
class ScheduleCreationCoordinator(
    private val database: MebotDatabase,
    private val schedules: ScheduleRepository,
    private val conversations: ScheduleConversationRepository,
    private val interpreter: ScheduleTextInterpreter,
    private val clock: Clock = Clock.systemUTC(),
    private val cleanMedia: suspend () -> Unit = {},
    private val covers: ScheduleCoverRepository? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val dao = database.scheduleDao()
    private val chats = database.conversationDao()

    suspend fun process(
        sessionId: String, messageId: String, onAttempt: (Int) -> Unit = {},
    ): ScheduleCreationResult = withContext(Dispatchers.IO) {
        val original = prepareSafely(sessionId, messageId) ?: return@withContext ScheduleCreationResult()
        val local = when {
            original.task?.deletedAt != null -> ScheduleInterpretation.Ask("这条日程已删除，不能在原会话中创建另一项任务。请点击“新建日程”开始。")
            hasSecondPrecisionRequest(original.message.content) -> ScheduleInterpretation.Reject(UnsupportedScheduleRequest.SECONDS)
            else -> null
        }
        val attempts = if (local == null) ScheduleAnalysisMaxAttempts else 1
        repeat(attempts) { attempt ->
            val input = if (attempt == 0) original else {
                delay(250)
                val latest = prepareSafely(sessionId, messageId) ?: return@withContext ScheduleCreationResult()
                // 重试仍属于原请求，不把旧消息重新应用到后来改过的任务或发生记录。
                if (latest.task?.id != original.task?.id || latest.task?.revision != original.task?.revision ||
                    latest.selected?.id != original.selected?.id || latest.selected?.revision != original.selected?.revision ||
                    latest.context.now.zone != original.context.now.zone) changed()
                latest
            }
            try {
                if (local == null) onAttempt(attempt + 1)
                val interpretation = local ?: interpreter.interpret(input.context)
                return@withContext commitAndSchedule(input, interpretation)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: ScheduleAnalysisException) {
                if (local != null || failure.failure != ScheduleAnalysisFailure.INVALID_RESULT || attempt == attempts - 1) throw failure
            } catch (_: Exception) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.LOCAL_SAVE) }
        }
        throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
    }

    private suspend fun prepareSafely(sessionId: String, messageId: String): AnalysisInput? =
        try { prepare(sessionId, messageId) }
        catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ScheduleAnalysisException) { throw failure
        } catch (_: Exception) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.LOCAL_SAVE) }

    private suspend fun commitAndSchedule(input: AnalysisInput, interpretation: ScheduleInterpretation): ScheduleCreationResult {
        val sessionId = input.message.sessionId
        return try {
            val result = commit(input, interpretation)
            if (interpretation is ScheduleInterpretation.Save && result.savedDate != null) {
                // 任务已提交后才调度；调度失败不把已保存日程误报为失败，启动时可恢复主库队列。
                try { covers?.startForSession(sessionId)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { /* 队列仍保存在本机。 */ }
            }
            if (result.deletedSessionId != null || result.deletedOccurrenceId != null) {
                val pending = runCatching { cleanMedia(); database.mediaDao().cleanupPaths(1).isNotEmpty() }.getOrDefault(true)
                result.copy(cleanupPending = pending)
            } else result
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ScheduleAnalysisException) { throw failure
        } catch (_: Exception) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.LOCAL_SAVE) }
    }

    private suspend fun prepare(sessionId: String, messageId: String): AnalysisInput? = database.withTransaction {
        val message = dao.message(messageId) ?: changed()
        if (message.sessionId != sessionId || message.role != MessageRole.USER || chats.latestUserMessage(sessionId)?.id != messageId) changed()
        if (chats.hasReplyAfter(sessionId, message.sequence)) return@withTransaction null
        val task = dao.taskForSession(sessionId)
        val selected = if (message.occurrenceContextId != null) {
            dao.occurrence(message.occurrenceContextId)?.takeIf { it.taskId == task?.id && !it.suppressedByRule }
        } else task?.let { value -> dao.occurrencesForTask(value.id).filter { !it.suppressedByRule }
            .sortedWith(compareBy<ScheduleOccurrenceEntity> { it.date < clock.instant().atZone(zone()).toLocalDate() }.thenBy { it.date }).firstOrNull() }
        val today = clock.instant().atZone(zone()).toLocalDate()
        val snapshot = task?.let { schedules.draftForDate(sessionId, maxOf(today, selected?.originalDate ?: today)) }
        val recent = chats.contextMessages(sessionId, message.sequence, 60).asReversed()
        val first = chats.firstUserMessage(sessionId)
        val candidates = (listOfNotNull(first) + recent).distinctBy { it.id }.sortedBy { it.sequence }
        // 完整保留首条需求和最近消息；历史以整条为单位截取，不从中间截断文字。
        val required = listOfNotNull(first, message).distinctBy { it.id }
        var length = required.sumOf { it.content.length }
        if (length > 24_000) throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
        val kept = required.toMutableList()
        for (item in candidates.asReversed()) {
            if (kept.any { it.id == item.id }) continue
            if (length + item.content.length <= 24_000) { kept += item; length += item.content.length }
        }
        AnalysisInput(message, task, ScheduleAnalysisContext(
            clock.instant().atZone(zone()), kept.sortedBy { it.sequence }, snapshot, chats.pendingQuestions(sessionId),
            historyTrimmed = kept.size != candidates.size || recent.size == 60,
            selected = selected?.let { schedules.occurrenceDraft(it) },
            selectedOriginalDate = selected?.originalDate,
            attachments = database.scheduleMediaDao().attachmentSummary(sessionId),
            messageAttachments = database.scheduleMediaDao().messageAttachmentCounts(sessionId, kept.map { it.id }).associate {
                it.messageId to com.txl.mebot.data.local.ScheduleAttachmentSummary(it.totalCount, it.imageCount, it.fileCount)
            },
        ), selected)
    }

    private suspend fun commit(input: AnalysisInput, interpretation: ScheduleInterpretation): ScheduleCreationResult = database.withTransaction {
        val sessionId = input.message.sessionId
        if (dao.session(sessionId) == null || chats.latestUserMessage(sessionId)?.id != input.message.id) changed()
        if (chats.hasReplyAfter(sessionId, input.message.sequence)) return@withTransaction ScheduleCreationResult()
        val current = dao.taskForSession(sessionId)
        if (current?.id != input.task?.id || current?.revision != input.task?.revision || zone() != input.context.now.zone) changed()
        if (input.selected != null && dao.occurrence(input.selected.id)?.revision != input.selected.revision) changed()
        val known = input.context.questions.associateBy { it.id }
        if (interpretation.answeredQuestionIds.any { it !in known }) invalid()
        val obsoleteContentQuestions = if (interpretation is ScheduleInterpretation.Save || interpretation is ScheduleInterpretation.Edit) {
            input.context.questions.filter { unnecessaryAttachmentContentQuestion(input.context, it.question) }.map { it.id }
        } else emptyList()
        for (id in interpretation.answeredQuestionIds + obsoleteContentQuestions) conversations.resolveQuestion(sessionId, id, input.message.id)

        val replyId = "schedule-ai:${input.message.id}"
        when (interpretation) {
            is ScheduleInterpretation.Save -> {
                if (current != null) invalid()
                val draft = try { interpretation.draft.copy(coverAssetId = current?.coverAssetId).resolved(input.context.now.toLocalDate()) }
                catch (_: IllegalArgumentException) { invalid() }
                val upcoming = upcomingDate(draft)
                val planned = draft.time?.let { (upcoming ?: requireNotNull(draft.date)).atTime(it).atZone(zone()).toInstant() }
                val clarification = when {
                    draft.recurrence?.enabled == false ->
                        "这项周期任务目前设为暂停，暂时不会出现日程卡片。请提供开始执行的日期，或说明先创建一次任务。"
                    draft.recurrence != null && draft.recurrence.nextOnOrAfter(requireNotNull(draft.date), draft.date) == null ->
                        "按这个开始日期、结束日期和周期规则，没有可执行的日程。请调整开始日期、结束日期或周期。"
                    planned != null && requireNotNull(draft.reminderMinutes).isNotEmpty() && !planned.isAfter(clock.instant()) ->
                        "这个提醒时间已经过去了。请提供未来的日期和时间；如果只想保留历史记录，请说明不需要提醒。"
                    else -> null
                }
                if (clarification != null) {
                    conversations.recordQuestion(sessionId, replyId, clarification)
                    return@withTransaction ScheduleCreationResult()
                }
                val requestId = "schedule-text:${input.message.id}"
                val task = schedules.createTask(sessionId, requestId, draft)
                covers?.queueForTask(task)
                if (draft.recurrence != null && upcoming != null) schedules.materializeRange(upcoming, upcoming)
                val cards = dao.occurrencesForTask(task.id).filter { !it.suppressedByRule && it.taskRevision == task.revision }
                val displayDate = cards.filter { it.date >= (upcoming ?: input.context.now.toLocalDate()) }.minOfOrNull { it.date }
                    ?: cards.maxOfOrNull { it.date }
                val verb = "已创建"
                schedules.appendMessage(sessionId, replyId, MessageRole.ASSISTANT,
                    "$verb「${draft.title}」。\n${draft.copy(date = displayDate ?: draft.date).summary()}\n${if (draft.time == null || draft.reminderMinutes.isNullOrEmpty()) "不设定时提醒。" else "提醒已随日程保存，可在「通知提醒」查看提醒方式和登记状态。"}")
                ScheduleCreationResult(displayDate, createdOccurrenceId = cards.firstOrNull { it.date == displayDate }?.id)
            }
            is ScheduleInterpretation.Edit -> {
                if (current == null || current.deletedAt != null) invalid()
                val original = requireNotNull(input.context.current)
                val periodic = original.recurrence != null || input.context.selected?.recurrence != null || "recurrence" in interpretation.patch.fields
                if (periodic && interpretation.scope !in setOf(ScheduleEditScope.ONCE, ScheduleEditScope.FUTURE)) {
                    conversations.recordQuestion(sessionId, replyId, "你想只修改本次日程，还是修改本次及之后的日程？也可以指定从哪一天开始修改。请说明修改范围。")
                    return@withTransaction ScheduleCreationResult()
                }
                if (interpretation.scope == ScheduleEditScope.ONCE && "recurrence" in interpretation.patch.fields) {
                    conversations.recordQuestion(sessionId, replyId, "周期规则需要修改后续日程。本次可以单独修改标题、详情、日期、时间和提醒。")
                    return@withTransaction ScheduleCreationResult()
                }
                val target = if (periodic && interpretation.scope == ScheduleEditScope.ONCE) targetOccurrence(input, interpretation.targetDate) else null
                val today = input.context.now.toLocalDate()
                val from = if (periodic && target == null) maxOf(today, interpretation.targetDate ?: input.selected?.originalDate ?: today) else today
                val base = target?.let { schedules.occurrenceDraft(it) } ?: if (periodic) schedules.draftForDate(sessionId, from) else original.copy(coverAssetId = current.coverAssetId)
                val draft = try { interpretation.patch.applyTo(base).resolved(requireNotNull(base.date), base.title) }
                catch (_: IllegalArgumentException) { invalid() }
                val rescheduled = interpretation.patch.fields.any { it in setOf("date", "time", "recurrence", "reminder_minutes") }
                if ("recurrence" in interpretation.patch.fields && base.recurrence?.enabled == true && draft.recurrence?.enabled == false) {
                    if (!explicitStop(input.message.content)) {
                        conversations.recordQuestion(sessionId, replyId, "如需停止整个周期，请明确说“停止整个周期”；日程目前保持不变。")
                        return@withTransaction ScheduleCreationResult()
                    }
                    return@withTransaction stop(input, replyId)
                }
                val upcoming = if (target == null) upcomingDate(draft, from) else draft.date
                if (rescheduled && target == null && draft.recurrence?.enabled == true && upcoming == null) {
                    conversations.recordQuestion(sessionId, replyId, "从这个日期开始，周期规则已没有后续日程。请调整开始日期、结束日期或周期。")
                    return@withTransaction ScheduleCreationResult()
                }
                val planned = draft.time?.let { (upcoming ?: requireNotNull(draft.date)).atTime(it).atZone(zone()).toInstant() }
                if (rescheduled && draft.recurrence?.enabled != false && planned != null && requireNotNull(draft.reminderMinutes).isNotEmpty() && !planned.isAfter(clock.instant())) {
                    conversations.recordQuestion(sessionId, replyId, "修改后的提醒时间已经过去了，请提供未来的时间，或明确不需要提醒。")
                    return@withTransaction ScheduleCreationResult()
                }
                val date = if (target != null) {
                    schedules.editOccurrence(sessionId, target.id, interpretation.patch, "schedule-text:${input.message.id}", target.revision).date
                } else {
                    schedules.updateTask(sessionId, "schedule-text:${input.message.id}", draft, current.revision, interpretation.patch.fields, from)
                    if (draft.recurrence != null && upcoming != null) schedules.materializeRange(upcoming, upcoming)
                    dao.occurrencesForTask(current.id).filter { !it.suppressedByRule && it.taskRevision == current.revision + 1 }
                        .filter { it.date >= (upcoming ?: input.context.now.toLocalDate()) }.minOfOrNull { it.date } ?: draft.date
                }
                schedules.appendMessage(sessionId, replyId, MessageRole.ASSISTANT,
                    "已${if (target != null) "修改本次" else "更新"}「${draft.title}」${if (periodic && target == null) "（从${from.monthValue}月${from.dayOfMonth}日开始，含当天；过去及已完成记录保留）" else ""}。\n${draft.copy(date = date ?: draft.date).summary()}")
                ScheduleCreationResult(date?.takeUnless { draft.recurrence?.enabled == false })
            }
            is ScheduleInterpretation.Stop -> stop(input, replyId)
            is ScheduleInterpretation.Delete -> {
                if (!explicitDelete(input)) {
                    conversations.recordQuestion(sessionId, replyId, "本次没有明确删除指令，日程保持不变。需要删除时请直接说“删除这个日程”。")
                    return@withTransaction ScheduleCreationResult()
                }
                if (current == null) {
                    conversations.recordQuestion(sessionId, replyId, "这个会话还没有创建任务。若要丢弃这份新建草稿，请点击顶部“清空”。")
                    return@withTransaction ScheduleCreationResult()
                }
                if ((input.context.current?.recurrence != null || input.context.selected?.recurrence != null) && interpretation.scope !in setOf(ScheduleEditScope.ONCE, ScheduleEditScope.ALL)) {
                    conversations.recordQuestion(sessionId, replyId, "你想删除本次日程，还是整个周期的所有日程？请说明删除范围。")
                    return@withTransaction ScheduleCreationResult()
                }
                if ((input.context.current?.recurrence != null || input.context.selected?.recurrence != null) && interpretation.scope == ScheduleEditScope.ONCE) {
                    val target = targetOccurrence(input, interpretation.targetDate)
                    schedules.deleteOccurrence(sessionId, target.id, target.revision)
                    schedules.appendMessage(sessionId, replyId, MessageRole.ASSISTANT, "已删除${target.date.monthValue}月${target.date.dayOfMonth}日这次日程，其余周期日程继续保留。")
                    ScheduleCreationResult(deletedOccurrenceId = target.id)
                } else {
                    schedules.deleteTask(sessionId, "schedule-text:${input.message.id}", current.revision)
                    ScheduleCreationResult(deletedSessionId = sessionId)
                }
            }
            is ScheduleInterpretation.Ask -> {
                val question = interpretation.question.trim()
                if (question.isBlank() || question.length > 600) invalid()
                if (unnecessaryAttachmentContentQuestion(input.context, question)) invalid()
                conversations.recordQuestion(sessionId, replyId, question)
                ScheduleCreationResult()
            }
            is ScheduleInterpretation.Reject -> {
                val question = when (interpretation.reason) {
                    UnsupportedScheduleRequest.SECONDS -> "日程目前精确到分钟，不支持秒级提醒。请改成例如“5分钟后提醒我”，或提供具体的小时和分钟。"
                    UnsupportedScheduleRequest.MULTIPLE_TASKS -> "这段描述包含不同时间的多项活动。一个会话只管理一项日程，请先说明本次要安排哪项；其余活动请点击“新建日程”分别创建。"
                    UnsupportedScheduleRequest.QUERY -> "请点击日程卡片查看详情。这个会话用于补充、修改或删除当前任务。"
                    UnsupportedScheduleRequest.UNRELATED -> "这里用于安排当前这一项日程。请告诉我想做的事情，以及日期或具体时间。"
                }
                conversations.recordQuestion(sessionId, replyId, question)
                ScheduleCreationResult()
            }
        }
    }

    private suspend fun targetOccurrence(input: AnalysisInput, date: LocalDate?): ScheduleOccurrenceEntity {
        val task = requireNotNull(input.task)
        if (date == null) return input.selected ?: changed()
        if (input.selected?.date == date || input.selected?.originalDate == date) return requireNotNull(dao.occurrence(requireNotNull(input.selected).id))
        schedules.materializeRange(date, date)
        return dao.occurrencesForTask(task.id).firstOrNull { it.date == date && !it.suppressedByRule } ?: changed()
    }

    private suspend fun stop(input: AnalysisInput, replyId: String): ScheduleCreationResult {
        val sessionId = input.message.sessionId
        if (!explicitStop(input.message.content) || input.context.current?.recurrence == null) {
            conversations.recordQuestion(sessionId, replyId, "如需停止重复，请明确说“停止整个周期”。只关闭提醒或删除本次，不会停止整个周期。")
            return ScheduleCreationResult()
        }
        val task = requireNotNull(input.task)
        schedules.stopRecurrence(sessionId, "schedule-text:${input.message.id}", task.revision)
        schedules.appendMessage(sessionId, replyId, MessageRole.ASSISTANT,
            "已停止「${task.title}」的整个周期。今天及之后未完成的日程和提醒不再继续，过去及已完成记录、共用会话和封面仍保留。")
        return ScheduleCreationResult()
    }

    private fun upcomingDate(draft: ScheduleDraft, from: LocalDate = clock.instant().atZone(zone()).toLocalDate()): LocalDate? {
        val rule = draft.recurrence ?: return draft.date
        val start = requireNotNull(draft.date)
        val now = clock.instant().atZone(zone())
        var next = rule.nextOnOrAfter(start, maxOf(start, now.toLocalDate(), from))
        if (next != null && draft.time != null && !next.atTime(draft.time).atZone(zone()).toInstant().isAfter(clock.instant())) {
            next = rule.nextOnOrAfter(start, next.plusDays(1))
        }
        return next
    }
}

private fun explicitStop(text: String): Boolean {
    if (Regex("(?:不要|别|先不|暂不|不用|不需要|不想|取消)\\s*(?:停止|暂停)|(?:怎么|如何|为什么|是否已经).*(?:停止|暂停)").containsMatchIn(text)) return false
    if (Regex("(?:停止|暂停|关闭|取消|以后(?:不用|不需要)).*(?:提醒|闹钟|响铃)").containsMatchIn(text)) return false
    if (Regex("(?:停止|暂停).*(?:本次|这次)|(?:本次|这次).*(?:停止|暂停)").containsMatchIn(text)) return false
    return Regex("停止|暂停|不再重复|不再每天|取消.*(?:周期|重复)|以后(?:不用|不需要)").containsMatchIn(text)
}

private fun explicitDelete(input: AnalysisInput): Boolean {
    fun command(text: String): Boolean {
        if (Regex("(?:不要|别|先不|暂不|暂时不|不用|不需要|不想|取消)\\s*(?:删除|删掉|删|移除)").containsMatchIn(text)) return false
        if (Regex("(?:怎么|如何|为什么|是否已经).*(?:删除|删掉)|(?:删除|删掉).*(?:了没|了吗|后.*恢复)").containsMatchIn(text)) return false
        if (Regex("清单.*[里中的]|详情|备注|附件|封面|图片|文件").containsMatchIn(text)) return false
        val pointer = Regex("日程|任务|这个|这条|这项|它|本次|这次|整个周期|全部|所有").containsMatchIn(text)
        return Regex("删除|删掉|删去|移除|删了(?:它|这个|这项|本次|整个)").containsMatchIn(text) &&
            (pointer || Regex("^(?:请|帮我)?(?:删除|删掉|移除)(?:吧|了)?[。！!\\s]*$").matches(text.trim()))
    }
    if (command(input.message.content)) return true
    return input.context.questions.any { "删除" in it.question } &&
        Regex("整个周期|全部|所有|本次|这次|今天|明天").containsMatchIn(input.message.content) &&
        !Regex("不要|别删|不删|取消").containsMatchIn(input.message.content) &&
        input.context.messages.filter { it.role == MessageRole.USER && it.id != input.message.id }.takeLast(3).any { command(it.content) }
}

/** 在联网前拒绝明确的秒级时刻／提醒，避免模型把它悄悄舍入。 */
internal fun hasSecondPrecisionRequest(text: String): Boolean {
    val amount = "(?:\\d+(?:\\.\\d+)?|[一二三四五六七八九十两百半]+)"
    val unit = "(?:秒(?:钟)?|s(?:ec(?:ond)?s?)?)"
    return Regex("(?:$amount\\s*$unit\\s*(?:后|之后|以后|内|提醒|响铃)|(?:提前|每隔|每)\\s*$amount\\s*$unit|\\d{1,2}[:：]\\d{2}[:：]\\d{2}|$amount\\s*点(?:\\s*$amount\\s*分)?\\s*$amount\\s*秒|^\\s*$amount\\s*$unit\\s*$)", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)
}

private fun ScheduleDraft.summary(): String = buildString {
    val day = requireNotNull(date)
    append(day.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)))
    time?.let { append(" "); append(it.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))) }
    recurrence?.let { rule ->
        append(" · ")
        append(when (rule.pattern) {
            RecurrencePattern.DAILY -> "每天"
            RecurrencePattern.WEEKLY -> "每周" + rule.weekDays.sortedBy { it.value }.joinToString("、") { "一二三四五六日"[it.value - 1].toString() }
            RecurrencePattern.MONTHLY -> "每月${rule.monthDay}日"
            RecurrencePattern.INTERVAL -> "每${rule.intervalDays}天"
        })
        rule.endDate?.let { append("，至$it") }
        if (!rule.enabled) append("（已暂停）")
    }
    if (time != null) {
        val reminders = requireNotNull(reminderMinutes)
        append("\n")
        append(if (reminders.isEmpty()) "不设提醒" else reminders.sortedDescending().joinToString("、") { if (it == 0) "准点提醒" else "提前${it}分钟" })
    }
}

private fun changed(): Nothing = throw ScheduleAnalysisException(ScheduleAnalysisFailure.CHANGED)
private fun invalid(): Nothing = throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)
