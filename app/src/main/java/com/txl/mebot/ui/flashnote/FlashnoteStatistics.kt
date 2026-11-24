package com.txl.mebot.ui.flashnote

import androidx.lifecycle.SavedStateHandle
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.FlashnoteDayCount
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 当前数量由主页面订阅；这里仅保存已展开月份的历史统计。 */
data class FlashnoteStatisticsState(
    val expanded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val month: YearMonth = YearMonth.from(today),
    val selectedDay: LocalDate = today,
    val firstDay: LocalDate? = null,
    val days: List<FlashnoteDayCount> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val minimumMonth: YearMonth get() = YearMonth.from(firstDay ?: today)
    val canGoPrevious: Boolean get() = month > minimumMonth
    val canGoNext: Boolean get() = month < YearMonth.from(today)
    val firstSelectableDay: Int get() = firstDay?.takeIf { YearMonth.from(it) == month }?.dayOfMonth ?: 1
    val lastSelectableDay: Int get() = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
    val total: Long get() = days.sumOf { it.count }
    val activeDays: Int get() = days.count { it.count > 0 }
    val selectedCount: Long get() = days.firstOrNull { it.day == selectedDay.toEpochDay() }?.count ?: 0
}

/** 按需订阅、取消旧月份结果；日期变化不会把正在回看的月份切走。 */
internal class FlashnoteStatistics(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val repository: FlashnoteRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val dayZone: () -> ZoneId = ZoneId::systemDefault,
    private val onChange: (FlashnoteStatisticsState) -> Unit,
) {
    private fun today() = LocalDate.now(clock.withZone(dayZone()))
    private val initialToday = today()
    private val initialMonth = savedState.get<String>(MONTH)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?.coerceAtMost(YearMonth.from(initialToday)) ?: YearMonth.from(initialToday)
    private val initialSelection = savedState.get<String>(DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.takeIf { YearMonth.from(it) == initialMonth && it <= initialToday }
        ?: initialMonth.atEndOfMonth().coerceAtMost(initialToday)
    private var current = FlashnoteStatisticsState(expanded = savedState[EXPANDED] ?: false,
        today = initialToday, month = initialMonth, selectedDay = initialSelection)
    private var visible = false
    private var generation = 0L
    private var query: Job? = null
    private var midnight: Job? = null

    init { onChange(current) }

    private fun update(change: (FlashnoteStatisticsState) -> FlashnoteStatisticsState) {
        current = change(current)
        onChange(current)
    }

    fun setVisible(value: Boolean) {
        visible = value
        refreshDate(reload = false)
        subscribe()
    }

    fun setExpanded(value: Boolean) {
        if (current.expanded == value) return
        savedState[EXPANDED] = value
        update { it.copy(expanded = value) }
        refreshDate(reload = false)
        subscribe()
    }

    fun selectMonth(step: Int) {
        if (step !in listOf(-1, 1) || current.loading) return
        if (step < 0 && !current.canGoPrevious || step > 0 && !current.canGoNext) return
        changeMonth(current.month.plusMonths(step.toLong()))
    }

    private fun changeMonth(month: YearMonth) {
        savedState[MONTH] = month.toString()
        val selected = month.atEndOfMonth().coerceAtMost(current.today)
        savedState[DAY] = selected.toString()
        update { it.copy(month = month, selectedDay = selected, days = emptyList()) }
        subscribe()
    }

    fun selectDay(day: Int) {
        if (day !in current.firstSelectableDay..current.lastSelectableDay || current.loading || current.failed) return
        val selected = current.month.atDay(day)
        savedState[DAY] = selected.toString()
        update { it.copy(selectedDay = selected) }
    }

    fun retry() = subscribe()

    fun refreshDate(reload: Boolean = true, force: Boolean = false) {
        val date = today()
        if (date == current.today && !force) return
        val followingToday = current.selectedDay == current.today && current.month == YearMonth.from(current.today)
        val month = if (followingToday || current.month > YearMonth.from(date)) YearMonth.from(date) else current.month
        val selected = if (followingToday) date else current.selectedDay.coerceAtMost(date)
        savedState[MONTH] = month.toString(); savedState[DAY] = selected.toString()
        update { it.copy(today = date, month = month, selectedDay = selected,
            days = if (month == it.month) it.days else emptyList()) }
        if (reload) {
            if (force) { midnight?.cancel(); midnight = null }
            subscribe()
        }
    }

    private fun subscribe() {
        query?.cancel()
        val token = ++generation
        if (!visible || !current.expanded) {
            midnight?.cancel(); midnight = null
            update { it.copy(loading = false) }
            return
        }
        val month = current.month
        val until = month.plusMonths(1).atDay(1).coerceAtMost(current.today.plusDays(1))
        update { it.copy(loading = true, failed = false) }
        query = scope.launch {
            try {
                combine(repository.observeHistoryBounds(), repository.observeCompletionDays(month.atDay(1).toEpochDay(), until.toEpochDay())) { bounds, rows -> bounds to rows }
                    .collect { (bounds, rows) ->
                        if (!visible || !current.expanded || token != generation) return@collect
                        val origin = listOfNotNull(bounds.firstCompletionDay?.let(LocalDate::ofEpochDay),
                            bounds.firstCreatedAt?.let { Instant.ofEpochMilli(it).atZone(dayZone()).toLocalDate() })
                            .minOrNull()?.coerceAtMost(current.today) ?: current.today
                        val minimum = YearMonth.from(origin)
                        update { it.copy(firstDay = origin) }
                        if (month < minimum) { changeMonth(minimum); return@collect }
                        val first = if (YearMonth.from(origin) == month) origin else month.atDay(1)
                        val selected = current.selectedDay.coerceAtLeast(first).coerceAtMost(until.minusDays(1))
                        savedState[DAY] = selected.toString()
                        update { it.copy(days = rows, selectedDay = selected, loading = false, failed = false) }
                    }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token == generation) update { it.copy(loading = false, failed = true) }
            }
        }
        if (midnight?.isActive != true) midnight = scope.launch {
            while (isActive) {
                val next = today().plusDays(1).atStartOfDay(dayZone()).toInstant()
                delay((Duration.between(clock.instant(), next).toMillis() + 50).coerceAtLeast(500))
                refreshDate()
            }
        }
    }

    private companion object {
        const val EXPANDED = "flashnote.statistics.expanded"
        const val MONTH = "flashnote.statistics.month"
        const val DAY = "flashnote.statistics.day"
    }
}
