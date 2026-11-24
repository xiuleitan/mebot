package com.txl.mebot.data.dayima

import androidx.room.withTransaction
import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaPeriodEntity
import com.txl.mebot.data.local.DayimaProfileEntity
import com.txl.mebot.data.local.DayimaSpanMarkerEntity
import com.txl.mebot.data.local.DayimaWriteOutcome
import com.txl.mebot.data.local.DayimaWriteReceiptEntity
import com.txl.mebot.data.local.MebotDatabase
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** 本地业务写入与提交回执始终处于同一事务，不依赖聊天、模型或页面。 */
class DayimaRepository(
    private val database: MebotDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val dayZone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = database.dayimaDao()
    private val writes = database.dayimaWriteDao()

    /** 将“今天”在创建请求时固定；跨午夜重试仍提交原日期。 */
    fun recordTodayRequest(note: String? = null) = DayimaChange.RecordPeriod(today(), note)

    suspend fun profile() = dao.profile()
    suspend fun period(id: String) = dao.period(id)
    suspend fun latestPeriod() = dao.latestPeriod()
    suspend fun periods() = dao.periods()
    suspend fun note(id: String) = dao.note(id)
    suspend fun notesForPeriod(id: String) = dao.notesForPeriod(id)
    fun observeProfile() = dao.observeProfile().distinctUntilChanged()
    fun observePeriods() = dao.observePeriods().distinctUntilChanged()
    fun observeDataVersion() = writes.observeDataVersion().distinctUntilChanged()

    suspend fun notesBetween(from: LocalDate, through: LocalDate): List<DayimaNoteEntity> {
        require(from <= through) { "日期范围无效" }
        return dao.notesBetween(from, through)
    }

    suspend fun recordsSnapshot(): DayimaRecordsSnapshot = withContext(Dispatchers.IO) {
        database.withTransaction {
            DayimaRecordsSnapshot(writes.dataVersion(), dao.profile(), dao.periods(), dao.spanMarkers())
        }
    }

    suspend fun spanMarkers(): List<DayimaSpanMarkerState> = withContext(Dispatchers.IO) {
        reviewDayimaSpans(recordsSnapshot())
    }

    suspend fun statistics(range: DayimaDateRange? = null): DayimaStatistics = withContext(Dispatchers.Default) {
        val snapshot = recordsSnapshot()
        DayimaStatisticsCalculator.calculate(snapshot, today(), range)
    }

    suspend fun compareStatistics(first: DayimaDateRange, second: DayimaDateRange): DayimaPeriodComparison = withContext(Dispatchers.Default) {
        val snapshot = recordsSnapshot()
        DayimaStatisticsCalculator.compare(snapshot, today(), first, second)
    }

    /** 仅在被订阅期间刷新；同时响应提交和本地日期变化，不启动常驻后台任务。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatistics(range: DayimaDateRange? = null) = combine(observeDataVersion(), statisticsDays()) { _, date -> date }
        .mapLatest { date ->
            withContext(Dispatchers.Default) { DayimaStatisticsCalculator.calculate(recordsSnapshot(), date, range) }
        }.distinctUntilChanged()

    /** 可见页面共用一次事实读取；完整日期用于统计，备注正文只在详情／分页时读取。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOverview() = combine(observeDataVersion(), statisticsDays()) { _, date -> date }
        .mapLatest { date -> overview(date) }.distinctUntilChanged()

    private suspend fun overview(date: LocalDate): DayimaOverview {
        val (records, counts, independentCount) = withContext(Dispatchers.IO) {
            database.withTransaction {
                Triple(DayimaRecordsSnapshot(writes.dataVersion(), dao.profile(), dao.periods(), dao.spanMarkers()),
                    dao.periodNoteCounts().associate { it.periodId to it.count }, dao.independentNoteCount())
            }
        }
        return withContext(Dispatchers.Default) {
            DayimaOverview(records, DayimaStatisticsCalculator.calculate(records, date), counts, independentCount, reviewDayimaSpans(records))
        }
    }

    suspend fun periodDetail(id: String): DayimaPeriodDetail? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val period = dao.period(id) ?: return@withTransaction null
            val next = dao.nextPeriod(period.startDate, today())
            val marker = next?.let { dao.spanMarkerForPair(id, it.id) }?.let {
                reviewDayimaSpans(DayimaRecordsSnapshot(0, null, listOf(period, requireNotNull(next)), listOf(it))).single()
            }
            DayimaPeriodDetail(writes.dataVersion(), period, dao.notesForPeriod(id), next, marker, database.dayimaMediaDao().periodImages(id))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePeriodDetail(id: String) = combine(observeDataVersion(), database.dayimaMediaDao().observePeriodImages(id)) { _, _ -> Unit }
        .mapLatest { periodDetail(id) }.distinctUntilChanged()

    suspend fun independentNotesPage(limit: Int = 30, offset: Int = 0): DayimaNotePage = withContext(Dispatchers.IO) {
        require(limit in 1..100 && offset >= 0) { "分页范围无效" }
        database.withTransaction {
            val rows = dao.independentNotes(limit + 1, offset)
            DayimaNotePage(writes.dataVersion(), rows.take(limit), rows.size > limit)
        }
    }

    private fun statisticsDays() = flow {
        while (true) {
            val zone = dayZone()
            val now = clock.instant()
            val date = now.atZone(zone).toLocalDate()
            emit(date)
            val waitMillis = try {
                Duration.between(now, date.plusDays(1).atStartOfDay(zone).toInstant()).toMillis()
            } catch (_: DateTimeException) { 60_000L }
            // 本地日期没有变化时不重新计算；可在页面可见时跟随时钟／时区调整。
            delay(waitMillis.coerceIn(1L, 60_000L))
        }
    }.distinctUntilChanged()

    suspend fun execute(change: DayimaChange): DayimaWriteResult = withContext(Dispatchers.IO) {
        require(change.requestId.isNotBlank()) { "请求标识不能为空" }
        val fingerprint = change.fingerprint()
        database.withTransaction {
            writes.receipt(change.requestId)?.let { receipt ->
                if (receipt.fingerprint != fingerprint || receipt.kind != change.kind) throw DayimaRequestConflict()
                return@withTransaction result(change, receipt, replayed = true)
            }
            val now = clock.millis()
            val currentDate = today()
            val receipt = when (change) {
                is DayimaChange.RecordPeriod -> record(change, currentDate, now)
                is DayimaChange.EditPeriod -> editPeriod(change, currentDate, now)
                is DayimaChange.EditPeriodDetails -> editPeriodDetails(change, currentDate, now)
                is DayimaChange.DeletePeriod -> deletePeriod(change, now)
                is DayimaChange.SaveBirthDate -> saveBirthDate(change, currentDate, now)
                is DayimaChange.AddNote -> addNote(change, currentDate, now)
                is DayimaChange.EditNote -> editNote(change, currentDate, now)
                is DayimaChange.DeleteNote -> deleteNote(change, now)
                is DayimaChange.SaveSpan -> saveSpan(change, now)
                is DayimaChange.DeleteSpan -> deleteSpan(change, now)
            }.copy(fingerprint = fingerprint)
            val committed = receipt.copy(version = writes.insert(receipt))
            com.txl.mebot.data.home.HomeEventStore(database, clock, dayZone).resolvePeriods()
            result(change, committed, replayed = false)
        }
    }

    private suspend fun record(change: DayimaChange.RecordPeriod, today: LocalDate, now: Long): DayimaWriteReceiptEntity {
        date(change.date, today)
        change.note?.let(::content)
        val existing = dao.periodOn(change.date)
        val period = existing ?: DayimaPeriodEntity(change.requestId, change.date, now, now).also {
            if (dao.period(it.id) != null) throw DayimaConflict()
            dao.insertPeriod(it)
            invalidateSpans(dao.spanMarkersCovering(change.date), now)
        }
        val note = change.note?.let { text ->
            DayimaNoteEntity(change.requestId, change.date, text, period.id, now, now).also { dao.insertNote(it) }
        }
        val outcome = when {
            existing == null -> DayimaWriteOutcome.CREATED
            note != null -> DayimaWriteOutcome.UPDATED
            else -> DayimaWriteOutcome.UNCHANGED
        }
        return receipt(change, period.id, period.revision, outcome, now).copy(
            relatedNoteId = note?.id, relatedNoteRevision = note?.revision,
        )
    }

    private suspend fun editPeriod(change: DayimaChange.EditPeriod, today: LocalDate, now: Long): DayimaWriteReceiptEntity {
        target(change.id, change.expectedRevision)
        date(change.date, today)
        val original = dao.period(change.id) ?: throw DayimaNotFound()
        revision(original.revision, change.expectedRevision)
        if (original.startDate == change.date) return receipt(change, original.id, original.revision, DayimaWriteOutcome.UNCHANGED, now)
        if (dao.periodOn(change.date) != null) throw DayimaDateConflict()
        changed(dao.updatePeriodDate(original.id, change.date, now, original.revision))
        invalidateSpans(dao.spanMarkersForPeriod(original.id) + dao.spanMarkersCovering(change.date), now)
        return receipt(change, original.id, original.revision + 1, DayimaWriteOutcome.UPDATED, now)
    }

    private suspend fun deletePeriod(change: DayimaChange.DeletePeriod, now: Long): DayimaWriteReceiptEntity {
        target(change.id, change.expectedRevision)
        val original = dao.period(change.id) ?: return receipt(change, change.id, null, DayimaWriteOutcome.ABSENT, now)
        revision(original.revision, change.expectedRevision)
        // 主动解除关联并增加子记录版本，令旧编辑请求失效；外键仍作为最后一道约束。
        for (note in dao.notesForPeriod(original.id)) {
            changed(dao.updateNote(note.id, note.occurredDate, note.content, null, now, note.revision))
        }
        for (marker in dao.spanMarkersForPeriod(original.id)) {
            changed(dao.detachSpanPeriod(marker.id, original.id, now, marker.revision))
        }
        val assets = database.dayimaMediaDao().periodAssetIds(original.id)
        changed(dao.deletePeriod(original.id, original.revision))
        releaseDayimaImages(database, assets)
        return receipt(change, original.id, null, DayimaWriteOutcome.DELETED, now)
    }

    private suspend fun editPeriodDetails(change: DayimaChange.EditPeriodDetails, today: LocalDate, now: Long): DayimaWriteReceiptEntity {
        target(change.id, change.expectedRevision)
        date(change.date, today)
        val original = dao.period(change.id) ?: throw DayimaNotFound()
        revision(original.revision, change.expectedRevision)
        database.validateBodyEdit(change)
        val moved = original.startDate != change.date
        if (moved && dao.periodOn(change.date) != null) throw DayimaDateConflict()
        val bodyChanged = database.applyBodyEdit(change, now)
        if (!moved && !bodyChanged) return receipt(change, original.id, original.revision, DayimaWriteOutcome.UNCHANGED, now)
        changed(dao.updatePeriodDate(original.id, change.date, now, original.revision))
        if (moved) invalidateSpans(dao.spanMarkersForPeriod(original.id) + dao.spanMarkersCovering(change.date), now)
        return receipt(change, original.id, original.revision + 1, DayimaWriteOutcome.UPDATED, now)
    }

    private suspend fun saveBirthDate(change: DayimaChange.SaveBirthDate, today: LocalDate, now: Long): DayimaWriteReceiptEntity {
        require(change.expectedRevision >= 0) { "资料版本无效" }
        change.date?.let { date(it, today) }
        val original = dao.profile()
        revision(original?.revision ?: 0, change.expectedRevision)
        if (original == null) {
            dao.insertProfile(DayimaProfileEntity(birthDate = change.date, updatedAt = now))
            return receipt(change, "1", 1, DayimaWriteOutcome.CREATED, now)
        }
        if (original.birthDate == change.date) return receipt(change, "1", original.revision, DayimaWriteOutcome.UNCHANGED, now)
        changed(dao.updateBirthDate(change.date, now, original.revision))
        return receipt(change, "1", original.revision + 1, DayimaWriteOutcome.UPDATED, now)
    }

    private suspend fun addNote(change: DayimaChange.AddNote, today: LocalDate, now: Long): DayimaWriteReceiptEntity {
        date(change.date, today)
        content(change.content)
        parentPeriod(change.periodId)
        val note = DayimaNoteEntity(change.requestId, change.date, change.content, change.periodId, now, now)
        dao.insertNote(note)
        return receipt(change, note.id, note.revision, DayimaWriteOutcome.CREATED, now)
    }

    private suspend fun editNote(change: DayimaChange.EditNote, today: LocalDate, now: Long): DayimaWriteReceiptEntity {
        target(change.id, change.expectedRevision)
        date(change.date, today)
        content(change.content)
        val original = dao.note(change.id) ?: throw DayimaNotFound()
        revision(original.revision, change.expectedRevision)
        parentPeriod(change.periodId)
        if (original.occurredDate == change.date && original.content == change.content && original.periodId == change.periodId) {
            return receipt(change, original.id, original.revision, DayimaWriteOutcome.UNCHANGED, now)
        }
        changed(dao.updateNote(original.id, change.date, change.content, change.periodId, now, original.revision))
        return receipt(change, original.id, original.revision + 1, DayimaWriteOutcome.UPDATED, now)
    }

    private suspend fun deleteNote(change: DayimaChange.DeleteNote, now: Long): DayimaWriteReceiptEntity {
        target(change.id, change.expectedRevision)
        val original = dao.note(change.id) ?: return receipt(change, change.id, null, DayimaWriteOutcome.ABSENT, now)
        revision(original.revision, change.expectedRevision)
        changed(dao.deleteNote(original.id, original.revision))
        return receipt(change, original.id, null, DayimaWriteOutcome.DELETED, now)
    }

    private suspend fun saveSpan(change: DayimaChange.SaveSpan, now: Long): DayimaWriteReceiptEntity {
        val (start, end) = spanParents(change)
        val original = change.id?.let { id ->
            target(id, change.expectedRevision)
            (dao.spanMarker(id) ?: throw DayimaNotFound()).also { revision(it.revision, change.expectedRevision) }
        }
        if (original == null) require(change.expectedRevision == 0L) { "新跨度版本无效" }
        val samePair = dao.spanMarkerForPair(start.id, end.id)
        if (samePair != null && samePair.id != original?.id) throw DayimaConflict()
        val marker = DayimaSpanMarkerEntity(original?.id ?: change.requestId, start.id, end.id,
            start.startDate, end.startDate, start.revision, end.revision, change.reason, change.detail,
            original?.createdAt ?: now, now, original?.revision ?: 1)
        if (original == null) {
            dao.insertSpanMarker(marker)
            return receipt(change, marker.id, marker.revision, DayimaWriteOutcome.CREATED, now)
        }
        if (original.copy(updatedAt = now) == marker) return receipt(change, marker.id, marker.revision, DayimaWriteOutcome.UNCHANGED, now)
        changed(dao.updateSpanMarker(marker.id, start.id, end.id, start.startDate, end.startDate, start.revision,
            end.revision, marker.reason, marker.detail, now, original.revision))
        return receipt(change, marker.id, original.revision + 1, DayimaWriteOutcome.UPDATED, now)
    }

    private suspend fun deleteSpan(change: DayimaChange.DeleteSpan, now: Long): DayimaWriteReceiptEntity {
        target(change.id, change.expectedRevision)
        val original = dao.spanMarker(change.id) ?: return receipt(change, change.id, null, DayimaWriteOutcome.ABSENT, now)
        revision(original.revision, change.expectedRevision)
        changed(dao.deleteSpanMarker(original.id, original.revision))
        return receipt(change, original.id, null, DayimaWriteOutcome.DELETED, now)
    }

    /** 回执只验证并返回仍有效的事实，不重放写入；目标后来改变或删除时拒绝旧请求。 */
    private suspend fun result(change: DayimaChange, receipt: DayimaWriteReceiptEntity, replayed: Boolean): DayimaWriteResult {
        val version = writes.dataVersion()
        val created = receipt.outcome == DayimaWriteOutcome.CREATED
        val changed = !replayed && receipt.outcome in setOf(DayimaWriteOutcome.CREATED, DayimaWriteOutcome.UPDATED, DayimaWriteOutcome.DELETED)
        return when (change) {
            is DayimaChange.RecordPeriod, is DayimaChange.EditPeriod, is DayimaChange.EditPeriodDetails -> {
                val period = dao.period(receipt.targetId) ?: throw DayimaConflict()
                revision(period.revision, receipt.targetRevision)
                val date = when (change) {
                    is DayimaChange.RecordPeriod -> change.date
                    is DayimaChange.EditPeriod -> change.date
                    is DayimaChange.EditPeriodDetails -> change.date
                    else -> error("Unexpected period change")
                }
                if (period.startDate != date) throw DayimaConflict()
                val note = receipt.relatedNoteId?.let { id ->
                    (dao.note(id) ?: throw DayimaConflict()).also {
                        revision(it.revision, receipt.relatedNoteRevision)
                        if (it.periodId != period.id || it.occurredDate != date || it.content != (change as DayimaChange.RecordPeriod).note) throw DayimaConflict()
                    }
                }
                if (change is DayimaChange.EditPeriodDetails) database.validateSavedBody(change)
                DayimaWriteResult.PeriodSaved(period, note, created, changed, version, replayed)
            }
            is DayimaChange.SaveBirthDate -> {
                val profile = dao.profile() ?: throw DayimaConflict()
                revision(profile.revision, receipt.targetRevision)
                if (profile.birthDate != change.date) throw DayimaConflict()
                DayimaWriteResult.BirthDateSaved(profile, changed, version, replayed)
            }
            is DayimaChange.AddNote, is DayimaChange.EditNote -> {
                val note = dao.note(receipt.targetId) ?: throw DayimaConflict()
                revision(note.revision, receipt.targetRevision)
                val expected = when (change) {
                    is DayimaChange.AddNote -> Triple(change.date, change.content, change.periodId)
                    is DayimaChange.EditNote -> Triple(change.date, change.content, change.periodId)
                    else -> error("Unexpected note change")
                }
                if (Triple(note.occurredDate, note.content, note.periodId) != expected) throw DayimaConflict()
                DayimaWriteResult.NoteSaved(note, created, changed, version, replayed)
            }
            is DayimaChange.SaveSpan -> {
                spanParents(change)
                val marker = dao.spanMarker(receipt.targetId) ?: throw DayimaConflict()
                revision(marker.revision, receipt.targetRevision)
                DayimaWriteResult.SpanSaved(marker, created, changed, version, replayed)
            }
            is DayimaChange.DeletePeriod, is DayimaChange.DeleteNote, is DayimaChange.DeleteSpan -> {
                val kind = when (change) {
                    is DayimaChange.DeletePeriod -> { if (dao.period(receipt.targetId) != null) throw DayimaConflict(); DayimaDeletedKind.PERIOD }
                    is DayimaChange.DeleteNote -> { if (dao.note(receipt.targetId) != null) throw DayimaConflict(); DayimaDeletedKind.NOTE }
                    else -> { if (dao.spanMarker(receipt.targetId) != null) throw DayimaConflict(); DayimaDeletedKind.SPAN }
                }
                DayimaWriteResult.Deleted(kind, receipt.targetId, receipt.outcome == DayimaWriteOutcome.DELETED, version, replayed)
            }
        }
    }

    private suspend fun spanParents(change: DayimaChange.SaveSpan): Pair<DayimaPeriodEntity, DayimaPeriodEntity> {
        target(change.startPeriodId, change.startPeriodRevision)
        target(change.endPeriodId, change.endPeriodRevision)
        val start = dao.period(change.startPeriodId) ?: throw DayimaNotFound()
        val end = dao.period(change.endPeriodId) ?: throw DayimaNotFound()
        revision(start.revision, change.startPeriodRevision)
        revision(end.revision, change.endPeriodRevision)
        require(start.startDate < end.startDate) { "跨度日期顺序无效" }
        require(dao.periodsBetween(start.startDate, end.startDate).size == 2) { "请只标记相邻两次记录之间的跨度" }
        return start to end
    }

    private suspend fun parentPeriod(id: String?) {
        if (id != null) {
            require(id.isNotBlank()) { "关联记录标识不能为空" }
            if (dao.period(id) == null) throw DayimaNotFound()
        }
    }

    private suspend fun invalidateSpans(markers: List<DayimaSpanMarkerEntity>, now: Long) {
        for (marker in markers.distinctBy { it.id }) {
            if (!marker.needsReview) changed(dao.markSpanForReview(marker.id, now, marker.revision))
        }
    }

    private fun receipt(change: DayimaChange, id: String, revision: Long?, outcome: DayimaWriteOutcome, now: Long) =
        DayimaWriteReceiptEntity(requestId = change.requestId, fingerprint = "", kind = change.kind,
            targetId = id, targetRevision = revision, outcome = outcome, committedAt = now)

    private fun today(): LocalDate = clock.instant().atZone(dayZone()).toLocalDate()
    private fun date(date: LocalDate, today: LocalDate) { require(date <= today) { "实际发生日期或出生日期不能在未来" } }
    private fun content(content: String) { require(content.isNotBlank()) { "备注内容不能为空" } }
    private fun target(id: String, revision: Long) {
        require(id.isNotBlank()) { "记录标识不能为空" }
        require(revision > 0) { "记录版本无效" }
    }
    private fun revision(actual: Long, expected: Long?) { if (actual != expected) throw DayimaConflict() }
    private fun changed(rows: Int) { if (rows != 1) throw DayimaConflict() }
}
