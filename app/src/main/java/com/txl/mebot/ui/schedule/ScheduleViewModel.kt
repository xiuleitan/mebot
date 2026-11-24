package com.txl.mebot.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.ScheduleOccurrenceEntity
import com.txl.mebot.data.schedule.ScheduleRepository
import com.txl.mebot.data.schedule.ScheduleEditScope
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SchedulePageState(
    val selectedDate: LocalDate,
    val entries: List<ScheduleEntry> = emptyList(),
    val dateCounts: Map<LocalDate, Int> = emptyMap(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val countsFailed: Boolean = false,
    val detailId: String? = null,
    val detail: ScheduleEntry? = null,
    val detailLoading: Boolean = false,
    val detailFailed: Boolean = false,
    val updatingIds: Set<String> = emptySet(),
    val writeFailure: ScheduleWriteFailure? = null,
    val deletionEvent: ScheduleDeletionEvent? = null,
    val stoppedEvent: ScheduleStoppedEvent? = null,
    val dayEntries: Map<LocalDate, List<ScheduleEntry>> = emptyMap(),
    val reminderOpenEvent: ScheduleReminderOpenEvent? = null,
)

data class ScheduleWriteFailure(val occurrenceId: String, val token: String, val isDeletion: Boolean = false, val isStopping: Boolean = false)
data class ScheduleDeletionEvent(val token: String, val occurrenceId: String, val sessionId: String, val wholeTask: Boolean, val cleanupPending: Boolean)
data class ScheduleStoppedEvent(val token: String, val occurrenceId: String)
data class ScheduleReminderOpenEvent(val token: String, val occurrenceId: String)
private data class VisibleDates(val from: LocalDate, val through: LocalDate)

/** 首帧可用已显示的卡片；数据库确认不存在或读取失败后，不再退回旧快照。 */
internal fun SchedulePageState.detailForDisplay(id: String, openingEntry: ScheduleEntry? = null): ScheduleEntry? {
    if (detailId == id && (detailFailed || !detailLoading)) return detail?.takeIf { it.id == id && !detailFailed }
    return sequenceOf(listOfNotNull(detail, openingEntry), entries).plus(dayEntries.values.asSequence())
        .flatten().filter { it.id == id }.maxByOrNull { it.revision }
}

/** 订阅当前及前后一天，点击翻页时临时预读目标一天；持久化后由数据库驱动展示。 */
class ScheduleViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val repository: ScheduleRepository,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(
        application, savedState, (application as MebotApplication).container.schedules,
    )

    private val initialDate = savedState.get<Long>(SELECTED_DATE)?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    private val mutableState = MutableStateFlow(SchedulePageState(initialDate))
    val state = mutableState.asStateFlow()
    private var visible = false
    private var dayJob: Job? = null
    private var countsJob: Job? = null
    private var detailJob: Job? = null
    private var preloadingDate: LocalDate? = null
    private var visibleDates = VisibleDates(initialDate.minusDays(7), initialDate.plusDays(7))

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) {
            observeDay()
            observeCounts()
            observeDetail()
        } else {
            dayJob?.cancel(); countsJob?.cancel(); detailJob?.cancel()
            preloadingDate = null
            mutableState.update { current ->
                current.copy(dayEntries = current.dayEntries.filterKeys { it in dayDates(current.selectedDate) })
            }
        }
    }

    fun selectDate(date: LocalDate) {
        if (mutableState.value.selectedDate == date) { preloadDate(null); return }
        preloadingDate = null
        savedState[SELECTED_DATE] = date.toEpochDay()
        mutableState.update {
            val nearby = it.dayEntries.filterKeys { cached -> cached >= date.minusDays(1) && cached <= date.plusDays(1) }
            it.copy(selectedDate = date, entries = nearby[date].orEmpty(), loading = date !in nearby, loadFailed = false, dayEntries = nearby)
        }
        if (visible) observeDay()
    }

    /** 点击时只多保留目标一天，不提交选中日期，也不清空正在滑出的页面。 */
    fun preloadDate(date: LocalDate?) {
        val selected = mutableState.value.selectedDate
        val target = date?.takeUnless { it == selected }
        if (target == preloadingDate) return
        val previousDates = dayDates(selected)
        preloadingDate = target
        val dates = dayDates(selected)
        if (dates == previousDates) return
        mutableState.update { current -> current.copy(dayEntries = current.dayEntries.filterKeys { it in dates }) }
        if (visible) observeDay()
    }

    private fun dayDates(selected: LocalDate): List<LocalDate> =
        (listOf(selected.minusDays(1), selected, selected.plusDays(1)) + listOfNotNull(preloadingDate)).distinct()

    fun showVisibleDates(from: LocalDate, through: LocalDate) {
        if (from > through || through.toEpochDay() - from.toEpochDay() > 31) return
        if (from >= visibleDates.from.plusDays(2) && through <= visibleDates.through.minusDays(2)) return
        visibleDates = VisibleDates(from.minusDays(7), through.plusDays(7))
        if (visible) observeCounts()
    }

    fun retryDay() = observeDay()
    fun retryCounts() = observeCounts()
    fun retryDetail() = observeDetail()

    suspend fun openFromReminder(id: String) {
        val record = repository.observeDisplayRecord(id).first() ?: return
        selectDate(record.occurrence.date)
        mutableState.update { it.copy(reminderOpenEvent = ScheduleReminderOpenEvent(UUID.randomUUID().toString(), id)) }
    }

    fun consumeReminderOpen(token: String) {
        mutableState.update { if (it.reminderOpenEvent?.token == token) it.copy(reminderOpenEvent = null) else it }
    }

    private fun observeDay() {
        dayJob?.cancel()
        if (!visible) return
        val date = mutableState.value.selectedDate
        val dates = dayDates(date)
        mutableState.update { it.copy(loading = date !in it.dayEntries, loadFailed = false) }
        dayJob = viewModelScope.launch {
            try {
                repository.observeTaskRevisions().collectLatest {
                    repository.materializeRange(date.minusDays(1), date.plusDays(1))
                    dates.firstOrNull { it < date.minusDays(1) || it > date.plusDays(1) }?.let { target ->
                        repository.materializeRange(target, target)
                    }
                    combine(dates.map(repository::observeDisplayDate)) { records ->
                        dates.zip(records.map { day -> day.map { it.asEntry() } }).toMap()
                    }.collect { pages ->
                        mutableState.update { current ->
                            if (current.selectedDate != date || dates != dayDates(date)) current else {
                                val latestPages = pages.mapValues { (day, entries) -> entries.map { incoming ->
                                    // 已提交的完成操作可能先于较早的查询回调返回，拒绝旧版本倒退。
                                    current.dayEntries[day]?.firstOrNull { it.id == incoming.id && it.revision > incoming.revision } ?: incoming
                                }.sortedForDisplay() }
                                current.copy(entries = latestPages[date].orEmpty(), dayEntries = latestPages, loading = false, loadFailed = false)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.update { if (it.selectedDate == date) it.copy(loading = false, loadFailed = true) else it }
            }
        }
    }

    private fun observeCounts() {
        countsJob?.cancel()
        if (!visible) return
        val dates = visibleDates
        mutableState.update { it.copy(countsFailed = false) }
        countsJob = viewModelScope.launch {
            try {
                repository.observeTaskRevisions().collectLatest {
                    repository.materializeRange(dates.from, dates.through)
                    repository.observeDateCounts(dates.from, dates.through).collect { counts ->
                        if (visibleDates == dates) mutableState.update {
                            it.copy(dateCounts = counts.associate { count -> count.date to count.count }, countsFailed = false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (visibleDates == dates) mutableState.update { it.copy(countsFailed = true) }
            }
        }
    }

    fun openDetail(id: String?) {
        if (mutableState.value.detailId == id) return
        mutableState.update { it.copy(
            detailId = id, detail = id?.let { key -> it.detailForDisplay(key) },
            detailLoading = id != null, detailFailed = false,
        ) }
        observeDetail()
    }

    private fun observeDetail() {
        detailJob?.cancel()
        val id = mutableState.value.detailId ?: return
        if (!visible) return
        mutableState.update { it.copy(detailLoading = true, detailFailed = false) }
        detailJob = viewModelScope.launch {
            try {
                repository.observeDisplayRecord(id).collect { record ->
                    val entry = record?.asEntry()
                    mutableState.update { current ->
                        if (current.detailId != id) current else current.copy(
                            detail = if (entry != null && current.detail?.id == id && current.detail.revision > entry.revision) current.detail else entry,
                            detailLoading = false, detailFailed = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.update { if (it.detailId == id) it.copy(detailLoading = false, detailFailed = true) else it }
            }
        }
    }

    fun toggleCompleted(id: String) {
        val current = mutableState.value
        if (id in current.updatingIds) return
        val entry = listOfNotNull(current.detail).plus(current.entries).plus(current.dayEntries.values.flatten()).filter { it.id == id }.maxByOrNull { it.revision } ?: return
        val sessionId = entry.sessionId ?: return
        val requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(updatingIds = it.updatingIds + id, writeFailure = null) }
        viewModelScope.launch {
            try {
                val committed = repository.setCompleted(sessionId, id, !entry.completed, requestId, entry.revision)
                mutableState.update { latest -> latest.copy(
                    entries = latest.entries.map { if (it.id == id) it.withCommittedCompletion(committed) else it }.sortedForDisplay(),
                    dayEntries = latest.dayEntries.mapValues { (_, entries) -> entries.map { if (it.id == id) it.withCommittedCompletion(committed) else it }.sortedForDisplay() },
                    detail = latest.detail?.let { if (it.id == id) it.withCommittedCompletion(committed) else it },
                ) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(writeFailure = ScheduleWriteFailure(id, requestId)) }
            } finally { mutableState.update { it.copy(updatingIds = it.updatingIds - id) } }
        }
    }

    fun deleteEntry(id: String, scope: ScheduleEditScope) {
        val current = mutableState.value
        if (id in current.updatingIds) return
        val entry = listOfNotNull(current.detail).plus(current.entries).firstOrNull { it.id == id } ?: return
        val sessionId = entry.sessionId ?: return
        if (scope !in setOf(ScheduleEditScope.ONCE, ScheduleEditScope.ALL)) return
        val token = UUID.randomUUID().toString()
        val whole = !entry.isRecurring || scope == ScheduleEditScope.ALL
        mutableState.update { it.copy(updatingIds = it.updatingIds + id, writeFailure = null) }
        viewModelScope.launch {
            try {
                if (whole) {
                    val task = repository.taskForSession(sessionId) ?: return@launch
                    repository.deleteTask(sessionId, token, task.revision)
                } else repository.deleteOccurrence(sessionId, id, entry.revision)
                val pending = runCatching {
                    (getApplication<android.app.Application>() as MebotApplication).container.media.cleanPendingDeletions()
                    (getApplication<android.app.Application>() as MebotApplication).container.database.mediaDao().cleanupPaths(1).isNotEmpty()
                }.getOrDefault(true)
                clearSchedulePhotoCache()
                mutableState.update { latest -> latest.copy(
                    entries = latest.entries.filterNot { if (whole) it.sessionId == sessionId else it.id == id },
                    dayEntries = latest.dayEntries.mapValues { (_, entries) -> entries.filterNot { if (whole) it.sessionId == sessionId else it.id == id } },
                    detail = latest.detail?.takeUnless { if (whole) it.sessionId == sessionId else it.id == id },
                    deletionEvent = ScheduleDeletionEvent(token, id, sessionId, whole, pending),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(writeFailure = ScheduleWriteFailure(id, token, true)) }
            } finally { mutableState.update { it.copy(updatingIds = it.updatingIds - id) } }
        }
    }

    fun consumeDeletion(token: String) { mutableState.update { if (it.deletionEvent?.token == token) it.copy(deletionEvent = null) else it } }

    fun stopRecurrence(id: String) {
        val current = mutableState.value
        if (id in current.updatingIds) return
        val entry = listOfNotNull(current.detail).plus(current.entries).firstOrNull { it.id == id } ?: return
        val sessionId = entry.sessionId ?: return
        if (!entry.isRecurring || entry.recurrenceStopped) return
        val token = UUID.randomUUID().toString()
        mutableState.update { it.copy(updatingIds = it.updatingIds + id, writeFailure = null) }
        viewModelScope.launch {
            try {
                val task = requireNotNull(repository.taskForSession(sessionId))
                repository.stopRecurrence(sessionId, token, task.revision)
                mutableState.update { it.copy(stoppedEvent = ScheduleStoppedEvent(token, id)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(writeFailure = ScheduleWriteFailure(id, token, isStopping = true)) }
            } finally { mutableState.update { it.copy(updatingIds = it.updatingIds - id) } }
        }
    }

    fun consumeStopped(token: String) { mutableState.update { if (it.stoppedEvent?.token == token) it.copy(stoppedEvent = null) else it } }

    private companion object { const val SELECTED_DATE = "schedule_selected_date" }
}

private fun ScheduleEntry.withCommittedCompletion(record: ScheduleOccurrenceEntity): ScheduleEntry =
    if (revision > record.revision) this else copy(
        completed = record.completedAt != null, completedAt = record.completedAt, revision = record.revision,
    )
