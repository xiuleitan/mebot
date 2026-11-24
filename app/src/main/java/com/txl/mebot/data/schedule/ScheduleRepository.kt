package com.txl.mebot.data.schedule

import androidx.room.withTransaction
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ReminderStatus
import com.txl.mebot.data.local.ScheduleAction
import com.txl.mebot.data.local.ScheduleAttachmentEntity
import com.txl.mebot.data.local.ScheduleLocalAttachmentEntity
import com.txl.mebot.data.local.ScheduleMessageEntity
import com.txl.mebot.data.local.ScheduleOccurrenceEntity
import com.txl.mebot.data.local.ScheduleOperationEntity
import com.txl.mebot.data.local.ScheduleRecurrenceEntity
import com.txl.mebot.data.local.ScheduleReminderEntity
import com.txl.mebot.data.local.ScheduleReminderLeadEntity
import com.txl.mebot.data.local.ScheduleSessionEntity
import com.txl.mebot.data.local.ScheduleTaskEntity
import com.txl.mebot.data.local.ScheduleTaskVersionEntity
import com.txl.mebot.data.local.ScheduleExclusionEntity
import com.txl.mebot.data.local.ScheduleOccurrenceLeadEntity
import com.txl.mebot.data.local.MediaCleanupEntity
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject

/** 所有写操作在本地校验并提交事务；模型只能通过这些业务接口请求变更。 */
class ScheduleRepository(
    private val database: MebotDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val dao = database.scheduleDao()
    private fun now() = clock.millis()
    private fun today() = clock.instant().atZone(zone()).toLocalDate()
    private fun newId() = UUID.randomUUID().toString()

    suspend fun openSession(id: String = newId()): ScheduleSessionEntity = database.withTransaction {
        require(id.isNotBlank())
        dao.session(id) ?: ScheduleSessionEntity(id, now(), now()).also { dao.insertSession(it) }
    }

    suspend fun appendMessage(
        sessionId: String,
        messageId: String,
        role: MessageRole,
        content: String,
        assetIds: List<String> = emptyList(),
        occurrenceContextId: String? = null,
    ): ScheduleMessageEntity = database.withTransaction {
        requireSession(sessionId)
        require(messageId.isNotBlank() && (content.isNotBlank() || assetIds.isNotEmpty()))
        val existing = dao.message(messageId)
        if (existing != null) {
            require(existing.sessionId == sessionId && existing.role == role && existing.content == content && existing.occurrenceContextId == occurrenceContextId) {
                "消息 ID 已用于其他内容"
            }
            val linked = dao.attachmentsForMessage(messageId).map { it.assetId }.toSet()
            require(linked == assetIds.toSet()) { "消息 ID 已用于其他附件" }
            return@withTransaction existing
        }
        if (occurrenceContextId != null) {
            val occurrence = requireNotNull(dao.occurrence(occurrenceContextId))
            require(dao.task(occurrence.taskId)?.sessionId == sessionId)
        }
        val message = ScheduleMessageEntity(messageId, sessionId, dao.nextMessageSequence(sessionId), role, content, now(), occurrenceContextId)
        dao.insertMessage(message)
        val attachmentNames = if (assetIds.isEmpty()) emptyMap() else database.scheduleMediaDao().attachments(sessionId).associate { it.asset.id to it.link.displayName }
        for ((position, assetId) in assetIds.distinct().withIndex()) {
            val asset = requireNotNull(database.mediaDao().asset(assetId)) { "附件尚未保存到本地" }
            dao.insertAttachment(ScheduleAttachmentEntity(sessionId, assetId, messageId, now(), position, attachmentNames[assetId] ?: asset.originalName))
            val media = database.scheduleMediaDao()
            media.insert(ScheduleLocalAttachmentEntity(sessionId, assetId, asset.originalName ?: "附件", media.nextPosition(sessionId), now(), pendingSend = false, draftOnly = false))
        }
        if (assetIds.isNotEmpty()) database.scheduleMediaDao().markSent(sessionId, assetIds)
        dao.touchSession(sessionId, now())
        message
    }

    suspend fun recentMessages(sessionId: String, limit: Int = 50) =
        dao.recentMessages(sessionId, limit.coerceIn(1, 100)).asReversed()
    suspend fun sessionExists(sessionId: String) = dao.session(sessionId) != null

    suspend fun attachments(sessionId: String) = dao.attachments(sessionId).distinctBy { it.assetId }
    suspend fun operationHistory(sessionId: String, limit: Int = 50) = dao.operations(sessionId, limit.coerceIn(1, 100))
    suspend fun taskForSession(sessionId: String, includeDeleted: Boolean = false): ScheduleTaskEntity? =
        dao.taskForSession(sessionId)?.takeIf { includeDeleted || it.deletedAt == null }

    suspend fun recurrenceForSession(sessionId: String): RecurrenceRule? {
        val task = taskForSession(sessionId) ?: return null
        return dao.recurrence(task.id, task.revision)?.asRule()
    }

    suspend fun reminderMinutesForSession(sessionId: String): List<Int> {
        val task = taskForSession(sessionId) ?: return emptyList()
        return dao.reminderLeads(task.id, task.revision)
    }

    /** 取范围起点当天有效的模板，避免未来预定的修改反过来覆盖较早日期。 */
    suspend fun draftForDate(sessionId: String, date: LocalDate): ScheduleDraft {
        val task = requireActiveTask(sessionId)
        val version = requireNotNull(dao.versionForDate(task.id, date) ?: dao.version(task.id, task.revision))
        return ScheduleDraft(version.title, version.detail, version.date, version.time,
            dao.recurrence(task.id, version.revision)?.asRule(), dao.reminderLeads(task.id, version.revision), version.coverAssetId)
    }

    suspend fun createTask(sessionId: String, requestId: String, draft: ScheduleDraft): ScheduleTaskEntity =
        database.withTransaction {
            requireSession(sessionId)
            val fingerprint = fingerprint("create", sessionId, *draft.fingerprintParts())
            if (isReplay(requestId, fingerprint)) return@withTransaction requireNotNull(dao.taskForSession(sessionId))
            check(dao.taskForSession(sessionId) == null) { "一个会话只能创建一项任务，请继续编辑已有任务" }
            val attachments = database.scheduleMediaDao().attachments(sessionId)
            val cover = attachments.firstOrNull { it.isImage && it.link.isCover } ?: attachments.firstOrNull { it.isImage }
            val resolved = draft.copy(coverAssetId = cover?.asset?.id ?: draft.coverAssetId).resolved(today())
            if (cover != null) database.scheduleMediaDao().selectCover(sessionId, cover.asset.id)
            requireCover(resolved.coverAssetId)
            val task = ScheduleTaskEntity(
                id = newId(), sessionId = sessionId, title = resolved.title, detail = resolved.detail,
                date = requireNotNull(resolved.date), time = resolved.time, coverAssetId = resolved.coverAssetId,
                createdAt = now(), updatedAt = now(),
            )
            dao.insertTask(task)
            saveVersion(task, resolved, minOf(today(), task.date))
            materializeFirst(task, resolved.recurrence)
            record(requestId, fingerprint, task, ScheduleAction.CREATE, null, snapshot(task))
            task
        }

    /** 从指定发生日（含当天）更新模板；过去和已完成记录不受影响。 */
    suspend fun updateTask(
        sessionId: String, requestId: String, draft: ScheduleDraft, expectedRevision: Long,
        changedFields: Set<String>? = null,
        effectiveFrom: LocalDate? = null,
    ): ScheduleTaskEntity = database.withTransaction {
        val fingerprint = fingerprint("update", sessionId, expectedRevision.toString(), effectiveFrom?.toString(), changedFields?.sorted()?.joinToString(","), *draft.fingerprintParts())
        if (isReplay(requestId, fingerprint)) return@withTransaction requireNotNull(dao.taskForSession(sessionId))
        val old = requireActiveTask(sessionId)
        check(old.revision == expectedRevision) { "任务已经修改，请读取最新记录后重试" }
        val existingTitle = if (draft.title.trim() == old.title) old.title
            else dao.versionForDate(old.id, effectiveFrom ?: today())?.title
        val resolved = draft.resolved(old.date, existingTitle)
        requireCover(resolved.coverAssetId)
        val before = snapshot(old)
        val oldRule = dao.recurrence(old.id, old.revision)
        val from = effectiveFrom ?: today()
        require(from >= today()) { "后续修改不能改写历史日期" }
        val updated = old.copy(
            title = resolved.title, detail = resolved.detail, date = requireNotNull(resolved.date),
            time = resolved.time, coverAssetId = resolved.coverAssetId,
            revision = old.revision + 1, updatedAt = now(),
        )
        dao.updateTask(updated)
        saveVersion(updated, resolved, from)
        val version = requireNotNull(dao.version(updated.id, updated.revision))
        for (occurrence in dao.occurrencesForTask(old.id)) {
            if (oldRule == null && resolved.recurrence == null &&
                occurrence.occurrenceKey.startsWith("once:") && occurrence.taskRevision == old.revision
            ) {
                val replacement = occurrence.withVersion(version).copy(date = updated.date)
                dao.updateOccurrence(replacement)
                replaceReminders(replacement)
            } else if (occurrence.completedAt == null && occurrence.date >= today() &&
                (occurrence.originalDate >= from || resolved.recurrence?.enabled == false && occurrence.date >= from)) {
                val remains = if (resolved.recurrence == null) {
                    occurrence.occurrenceKey.startsWith("once:") && occurrence.date == updated.date
                } else {
                    !occurrence.occurrenceKey.startsWith("once:") && resolved.recurrence.includes(updated.date, occurrence.originalDate)
                }
                var replacement = occurrence.withVersion(version).copy(suppressedByRule = !remains)
                if (occurrence.isException && changedFields != null) {
                    val oldDraft = occurrenceDraft(occurrence)
                    val merged = SchedulePatch(resolved, changedFields).applyTo(oldDraft).resolved(occurrence.date, oldDraft.title)
                    replacement = replacement.copy(title = merged.title, detail = merged.detail, time = merged.time, coverAssetId = occurrence.coverAssetId, isException = true)
                    dao.deleteOccurrenceLeads(occurrence.id)
                    dao.insertOccurrenceLeads(requireNotNull(merged.reminderMinutes).map { ScheduleOccurrenceLeadEntity(occurrence.id, it) })
                } else if (occurrence.isException) {
                    dao.deleteOccurrenceLeads(occurrence.id)
                    replacement = replacement.copy(isException = false)
                }
                dao.updateOccurrence(replacement)
                replaceReminders(replacement)
            }
        }
        materializeFirst(updated, resolved.recurrence, maxOf(from, updated.date))
        record(requestId, fingerprint, updated, ScheduleAction.UPDATE, before, snapshot(updated))
        updated
    }

    /** 停止重复保留历史、完成记录和共用会话；与彻底删除任务不同。 */
    suspend fun stopRecurrence(sessionId: String, requestId: String, expectedRevision: Long): ScheduleTaskEntity = database.withTransaction {
        val task = requireActiveTask(sessionId)
        val rule = requireNotNull(dao.recurrence(task.id, task.revision)?.asRule()) { "这不是周期任务" }
        val draft = ScheduleDraft(task.title, task.detail, task.date, task.time, rule.copy(enabled = false),
            dao.reminderLeads(task.id, task.revision), task.coverAssetId)
        val stopped = updateTask(sessionId, requestId, draft, expectedRevision, setOf("recurrence"))
        dao.occurrencesForTask(task.id).forEach { dao.cancelReminders(it.id) }
        stopped
    }

    /** 用户明确删除后移除任务、会话及全部历史；重试以记录不存在作为成功依据。 */
    suspend fun deleteTask(sessionId: String, requestId: String, expectedRevision: Long): Unit =
        database.withTransaction {
            require(requestId.isNotBlank())
            val old = dao.taskForSession(sessionId) ?: return@withTransaction
            check(old.revision == expectedRevision) { "任务已经修改，请读取最新记录后重试" }
            val media = dao.taskMedia(old.id, sessionId)
            database.scheduleMediaDao().deleteForSession(sessionId)
            dao.deleteQuestions(sessionId); dao.deleteAttachments(sessionId); dao.deleteMessages(sessionId)
            dao.deleteDraft(sessionId); dao.deleteOperations(sessionId)
            dao.occurrencesForTask(old.id).forEach { removeOccurrence(it) }
            dao.deleteExclusions(old.id); dao.deleteRecurrences(old.id); dao.deleteTaskLeads(old.id)
            dao.deleteVersions(old.id); dao.deleteTaskRow(old.id); dao.deleteSession(sessionId)
            for (asset in media) if (database.mediaDao().deleteIfUnused(asset.id) == 1) {
                asset.relativePath?.let { database.mediaDao().queueCleanup(MediaCleanupEntity(it)) }
            }
        }

    suspend fun occurrenceDraft(occurrence: ScheduleOccurrenceEntity): ScheduleDraft = ScheduleDraft(
        occurrence.title, occurrence.detail, occurrence.date, occurrence.time,
        dao.recurrence(occurrence.taskId, occurrence.taskRevision)?.asRule(),
        if (occurrence.isException) dao.occurrenceLeads(occurrence.id) else dao.reminderLeads(occurrence.taskId, occurrence.taskRevision),
        occurrence.coverAssetId,
    )

    suspend fun editOccurrence(sessionId: String, id: String, patch: SchedulePatch, requestId: String, expectedRevision: Long): ScheduleOccurrenceEntity = database.withTransaction {
        val task = requireActiveTask(sessionId)
        val old = requireNotNull(dao.occurrence(id))
        require(old.taskId == task.id && !old.suppressedByRule && "recurrence" !in patch.fields)
        val proposed = patch.applyTo(occurrenceDraft(old))
        // 单次改期可移到周期结束之后；既有可执行请求保留原指纹和重试语义。
        val resolved = proposed.copy(recurrence = proposed.recurrence?.takeUnless {
            it.endDate?.isBefore(proposed.date ?: old.date) == true
        }).resolved(old.date, old.title)
        val token = fingerprint("edit-once", sessionId, id, expectedRevision.toString(), *resolved.fingerprintParts())
        if (isReplay(requestId, token)) return@withTransaction requireNotNull(dao.occurrence(id))
        check(old.revision == expectedRevision)
        val updated = old.copy(title = resolved.title, detail = resolved.detail, date = requireNotNull(resolved.date), time = resolved.time,
            revision = old.revision + 1, updatedAt = now(), isException = true)
        dao.updateOccurrence(updated); dao.deleteOccurrenceLeads(id)
        dao.insertOccurrenceLeads(requireNotNull(resolved.reminderMinutes).map { ScheduleOccurrenceLeadEntity(id, it) })
        replaceReminders(updated)
        record(requestId, token, task, ScheduleAction.UPDATE, occurrenceSnapshot(old), occurrenceSnapshot(updated))
        updated
    }

    suspend fun deleteOccurrence(sessionId: String, id: String, expectedRevision: Long) = database.withTransaction {
        val task = requireActiveTask(sessionId)
        val old = dao.occurrence(id) ?: return@withTransaction
        require(old.taskId == task.id)
        check(old.revision == expectedRevision)
        dao.insertExclusion(ScheduleExclusionEntity(task.id, old.occurrenceKey))
        dao.deleteOccurrenceOperations(task.id, id)
        dao.clearOccurrenceContext(id)
        removeOccurrence(old)
    }

    private suspend fun removeOccurrence(item: ScheduleOccurrenceEntity) {
        val editingMedia = database.scheduleEditor().attachments(item.id).map { it.asset }
        dao.deleteOccurrenceReminders(item.id); dao.deleteOccurrenceLeads(item.id); dao.deleteOccurrence(item.id)
        for (asset in editingMedia) if (database.mediaDao().deleteIfUnused(asset.id) == 1) asset.relativePath?.let {
            database.mediaDao().queueCleanup(MediaCleanupEntity(it))
        }
    }

    suspend fun setCompleted(
        sessionId: String, occurrenceId: String, completed: Boolean, requestId: String, expectedRevision: Long,
    ): ScheduleOccurrenceEntity = database.withTransaction {
        val fingerprint = fingerprint("complete", sessionId, occurrenceId, completed.toString(), expectedRevision.toString())
        if (isReplay(requestId, fingerprint)) return@withTransaction requireNotNull(dao.occurrence(occurrenceId))
        val task = requireActiveTask(sessionId)
        val occurrence = requireNotNull(dao.occurrence(occurrenceId)) { "日程记录不存在" }
        require(occurrence.taskId == task.id) { "这条记录不属于当前会话" }
        check(occurrence.revision == expectedRevision) { "日程记录已经修改，请读取最新记录后重试" }
        com.txl.mebot.data.home.HomeEventStore(database, clock, zone).rememberSchedule(occurrence)
        val updated = occurrence.copy(
            completedAt = if (completed) occurrence.completedAt ?: now() else null,
            revision = occurrence.revision + 1, updatedAt = now(),
        )
        dao.updateOccurrence(updated)
        replaceReminders(updated)
        record(
            requestId, fingerprint, task, if (completed) ScheduleAction.COMPLETE else ScheduleAction.RESTORE,
            occurrenceSnapshot(occurrence), occurrenceSnapshot(updated),
        )
        updated
    }

    /** 按查看范围展开周期规则；历史日期使用当时有效的结构化版本。 */
    suspend fun materializeRange(from: LocalDate, through: LocalDate) = materializeRangeInternal(from, through, false)

    suspend fun materializeReminderRange(from: LocalDate, through: LocalDate) = materializeRangeInternal(from, through, true)

    private suspend fun materializeRangeInternal(from: LocalDate, through: LocalDate, remindersOnly: Boolean) {
        require(ChronoUnit.DAYS.between(from, through) in 0..365) { "每次读取最多一年的日期范围" }
        database.withTransaction {
            var date = from
            val reminderVersions = mutableMapOf<Pair<String, Long>, Boolean>()
            while (date <= through) {
                for (version in dao.versionsForDate(date)) {
                    if (remindersOnly) {
                        val key = version.taskId to version.revision
                        val eligible = reminderVersions[key] ?: (version.time != null &&
                            dao.reminderLeads(version.taskId, version.revision).isNotEmpty()).also { reminderVersions[key] = it }
                        if (!eligible) continue
                    }
                    materialize(version, date)
                }
                date = date.plusDays(1)
            }
        }
    }

    fun observeDate(date: LocalDate) = dao.observeDate(date).distinctUntilChanged()
    fun observeTaskRevisions() = dao.observeTaskRevisions().distinctUntilChanged()
    fun observeTaskForSession(sessionId: String) = dao.observeTaskForSession(sessionId).distinctUntilChanged()
    fun observeConversationRecord(sessionId: String, selectedId: String? = null) = dao.observeConversationRecord(sessionId, today(), selectedId).distinctUntilChanged()
    fun observeDisplayDate(date: LocalDate) = dao.observeDisplayDate(date).distinctUntilChanged()
    fun observeDisplayRecord(id: String) = dao.observeDisplayRecord(id).distinctUntilChanged()
    fun observeDateCounts(from: LocalDate, through: LocalDate) = dao.observeDateCounts(from, through).distinctUntilChanged()
    suspend fun history(from: LocalDate, through: LocalDate, limit: Int = 50, offset: Int = 0): List<ScheduleOccurrenceEntity> {
        require(from <= through && offset >= 0)
        return dao.history(from, through, limit.coerceIn(1, 100), offset)
    }

    /** 仅是待调度记录，PENDING 不表示已向 Android 成功登记闹钟。 */
    suspend fun pendingReminders(from: Long, through: Long, limit: Int = 100): List<ScheduleReminderEntity> {
        require(from <= through)
        return dao.pendingReminders(from, through, limit.coerceIn(1, 500))
    }

    private suspend fun requireSession(sessionId: String) = requireNotNull(dao.session(sessionId)) { "会话不存在" }
    private suspend fun requireActiveTask(sessionId: String) =
        requireNotNull(taskForSession(sessionId)) { "会话尚未创建任务或任务已删除" }
    private suspend fun requireCover(id: String?) {
        if (id != null) {
            val asset = requireNotNull(database.mediaDao().asset(id)) { "封面尚未保存到本地" }
            require(asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0) { "封面必须是图片" }
        }
    }

    private suspend fun saveVersion(task: ScheduleTaskEntity, draft: ScheduleDraft, effectiveFrom: LocalDate) {
        dao.insertVersion(ScheduleTaskVersionEntity(
            taskId = task.id, revision = task.revision, effectiveFrom = effectiveFrom,
            title = task.title, detail = task.detail, date = task.date, time = task.time,
            coverAssetId = task.coverAssetId, createdAt = now(),
        ))
        draft.recurrence?.let { rule ->
            dao.insertRecurrence(ScheduleRecurrenceEntity(
                taskId = task.id, taskRevision = task.revision, pattern = rule.pattern,
                intervalDays = rule.intervalDays, weekDayMask = rule.weekDays.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
                monthDay = rule.monthDay, endDate = rule.endDate, enabled = rule.enabled,
            ))
        }
        dao.insertLeads(requireNotNull(draft.reminderMinutes).map { ScheduleReminderLeadEntity(task.id, task.revision, it) })
    }

    private suspend fun materializeFirst(
        task: ScheduleTaskEntity, recurrence: RecurrenceRule?, notBefore: LocalDate = task.date,
    ) {
        val version = requireNotNull(dao.version(task.id, task.revision))
        if (recurrence == null) {
            materialize(version, task.date)
        } else if (recurrence.enabled) {
            // 按月 29/30/31 日时可能需要跳过一个月份；缺少该日的月份不补到月底。
            var next = recurrence.nextOnOrAfter(task.date, notBefore)
            // 被单次删除或已完成的日期不能使后续修改跳回旧的起始日。
            repeat(366) {
                val date = next ?: return
                val item = materialize(version, date)
                if (item != null && !item.suppressedByRule && item.completedAt == null) return
                next = recurrence.nextOnOrAfter(task.date, date.plusDays(1))
            }
        }
    }

    private suspend fun materialize(version: ScheduleTaskVersionEntity, date: LocalDate): ScheduleOccurrenceEntity? {
        if (date < version.effectiveFrom) return null
        val rule = dao.recurrence(version.taskId, version.revision)?.asRule()
        if (rule == null && date != version.date) return null
        if (rule != null && !rule.includes(version.date, date)) return null
        // 普通任务的显式编辑会移动同一张卡片；不要按旧版本额外重建一张普通任务卡片。
        if (rule == null && dao.task(version.taskId)?.revision != version.revision) return null
        val key = if (rule == null) "once:${version.revision}" else "date:${date.toEpochDay()}"
        if (dao.isExcluded(version.taskId, key)) return null
        val existing = if (rule == null) {
            dao.occurrencesForTask(version.taskId).firstOrNull {
                it.occurrenceKey.startsWith("once:") && it.taskRevision == version.revision && !it.suppressedByRule
            }
        } else dao.occurrenceForKey(version.taskId, key)
        if (existing != null) return existing
        val occurrence = ScheduleOccurrenceEntity(
            id = newId(), taskId = version.taskId, occurrenceKey = key,
            title = version.title, detail = version.detail, date = date, time = version.time,
            coverAssetId = version.coverAssetId, taskRevision = version.revision, createdAt = now(), updatedAt = now(),
        )
        dao.insertOccurrence(occurrence)
        replaceReminders(occurrence)
        return occurrence
    }

    private fun ScheduleOccurrenceEntity.withVersion(version: ScheduleTaskVersionEntity) = copy(
        title = version.title, detail = version.detail, time = version.time, coverAssetId = version.coverAssetId,
        taskRevision = version.revision, revision = revision + 1, updatedAt = now(),
    )

    private suspend fun replaceReminders(occurrence: ScheduleOccurrenceEntity) {
        dao.cancelReminders(occurrence.id)
        val time = occurrence.time ?: return
        if (occurrence.completedAt != null || occurrence.suppressedByRule) return
        val effective = dao.versionForDate(occurrence.taskId, occurrence.date)
        if (effective != null && dao.recurrence(occurrence.taskId, effective.revision)?.enabled == false) return
        val currentZone = zone()
        val start = occurrence.date.atTime(time).atZone(currentZone).toInstant().toEpochMilli()
        val leads = if (occurrence.isException) dao.occurrenceLeads(occurrence.id) else dao.reminderLeads(occurrence.taskId, occurrence.taskRevision)
        dao.insertReminders(leads.map { lead ->
            val trigger = Math.subtractExact(start, lead.toLong() * 60_000L)
            ScheduleReminderEntity(
                id = newId(), occurrenceId = occurrence.id, occurrenceRevision = occurrence.revision,
                minutesBefore = lead, triggerAt = trigger, zoneId = currentZone.id,
                status = if (trigger < now()) ReminderStatus.MISSED else ReminderStatus.PENDING,
                createdAt = now(),
            )
        })
    }

    private suspend fun isReplay(requestId: String, fingerprint: String): Boolean {
        require(requestId.isNotBlank())
        val operation = dao.operation(requestId) ?: return false
        require(operation.fingerprint == fingerprint) { "请求 ID 已用于其他操作" }
        return true
    }

    private suspend fun record(
        requestId: String, fingerprint: String, task: ScheduleTaskEntity, action: ScheduleAction,
        before: String?, after: String,
    ) {
        dao.insertOperation(ScheduleOperationEntity(
            requestId, task.sessionId, task.id, dao.nextOperationSequence(task.sessionId),
            action, fingerprint, before, after, now(),
        ))
        dao.touchSession(task.sessionId, now())
    }

    private suspend fun snapshot(task: ScheduleTaskEntity): String = JSONObject().apply {
        put("id", task.id); put("title", task.title); put("detail", task.detail)
        put("date", task.date.toString()); put("time", task.time?.toString() ?: JSONObject.NULL)
        put("coverAssetId", task.coverAssetId ?: JSONObject.NULL); put("revision", task.revision)
        put("deletedAt", task.deletedAt ?: JSONObject.NULL)
        put("recurrence", dao.recurrence(task.id, task.revision)?.let {
            JSONObject().put("pattern", it.pattern.name).put("intervalDays", it.intervalDays)
                .put("weekDayMask", it.weekDayMask).put("monthDay", it.monthDay ?: JSONObject.NULL)
                .put("endDate", it.endDate?.toString() ?: JSONObject.NULL).put("enabled", it.enabled)
        } ?: JSONObject.NULL)
        put("reminderMinutes", org.json.JSONArray(dao.reminderLeads(task.id, task.revision)))
    }.toString()

    private fun occurrenceSnapshot(item: ScheduleOccurrenceEntity): String = JSONObject().apply {
        put("id", item.id); put("taskId", item.taskId); put("title", item.title); put("detail", item.detail)
        put("date", item.date.toString()); put("time", item.time?.toString() ?: JSONObject.NULL)
        put("completedAt", item.completedAt ?: JSONObject.NULL); put("revision", item.revision)
    }.toString()
}

internal fun ScheduleRecurrenceEntity.asRule() = RecurrenceRule(
    pattern, intervalDays, DayOfWeek.values().filter { weekDayMask and (1 shl (it.value - 1)) != 0 }.toSet(),
    monthDay, endDate, enabled,
)

/** 单次改期后仍以原发生日确定“本次及以后”的范围，不改变周期的相位。 */
internal val ScheduleOccurrenceEntity.originalDate: LocalDate
    get() = occurrenceKey.removePrefix("date:").toLongOrNull()?.let(LocalDate::ofEpochDay) ?: date

private fun ScheduleDraft.fingerprintParts(): Array<String?> = arrayOf(
    title.trim(), detail, date?.toString(), time?.toString(), coverAssetId,
    reminderMinutes?.distinct()?.sorted()?.joinToString(","), recurrence?.pattern?.name,
    recurrence?.intervalDays?.toString(), recurrence?.weekDays?.sortedBy { it.value }?.joinToString(",") { it.name },
    recurrence?.monthDay?.toString(), recurrence?.endDate?.toString(), recurrence?.enabled?.toString(),
)

private fun fingerprint(vararg parts: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val bytes = part?.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
        if (bytes != null) digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
