package com.txl.mebot.ui.dayima

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaOverview
import com.txl.mebot.data.dayima.DayimaStatisticsCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 图表只持有派生数据；来源、筛选变化时取消旧计算，后台计算完再发布匹配的版本。 */
internal class DayimaChartController(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val current: () -> DayimaChartsState,
    private val publish: (DayimaChartsState) -> Unit,
) {
    private var source: DayimaOverview? = null
    private var job: Job? = null
    private var generation = 0L

    fun updateSource(value: DayimaOverview) { source = value; rebuild() }
    fun stop() { ++generation; job?.cancel(); source = null }

    fun rebuild() {
        val snapshot = source ?: return
        val options = current().options
        val dateRange = options.dateRange(snapshot.statistics.asOfDate)
        val request = ++generation
        job?.cancel()
        publish(current().copy(data = null, loading = true, failed = false))
        job = scope.launch {
            try {
                val data = withContext(Dispatchers.Default) {
                    val statistics = if (dateRange == null) snapshot.statistics else
                        DayimaStatisticsCalculator.calculate(snapshot.records, snapshot.statistics.asOfDate, dateRange)
                    DayimaChartData.calculate(statistics, snapshot.statistics.period.cycles)
                }
                if (request == generation) publish(current().copy(data = data, loading = false, failed = false))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (request == generation) publish(current().copy(data = null, loading = false, failed = true))
            }
        }
    }

    fun selectRange(range: DayimaChartRange) {
        if (range == DayimaChartRange.CUSTOM) { beginRange(); return }
        options(current().options.copy(range = range), recalculate = true)
        select(null)
    }
    private fun options(value: DayimaChartOptions, recalculate: Boolean = false) {
        savedState[OPTIONS] = value.bundle()
        publish(current().copy(options = value))
        if (recalculate) rebuild()
    }

    fun beginRange() {
        val today = source?.statistics?.asOfDate ?: return
        val options = current().options
        val editor = DayimaRangeEditor((options.from ?: today.minusYears(1)).toString(), (options.through ?: today).toString())
        rangeEditor(editor)
    }
    fun changeFrom(value: String) { current().rangeEditor?.let { rangeEditor(it.copy(from = value, error = null)) } }
    fun changeThrough(value: String) { current().rangeEditor?.let { rangeEditor(it.copy(through = value, error = null)) } }
    fun cancelRange() = rangeEditor(null)
    private fun rangeEditor(value: DayimaRangeEditor?) {
        savedState[RANGE_EDITOR] = value?.bundle()
        publish(current().copy(rangeEditor = value))
    }
    fun applyRange() {
        val editor = current().rangeEditor ?: return
        val from = parseDayimaDate(editor.from)
        val through = parseDayimaDate(editor.through)
        val error = when {
            from == null || through == null -> R.string.dayima_invalid_date
            from > through -> R.string.dayima_chart_range_order
            source?.statistics?.asOfDate?.let { through > it } == true -> R.string.dayima_chart_range_future
            else -> null
        }
        if (error != null) { rangeEditor(editor.copy(error = error)); return }
        rangeEditor(null)
        select(null)
        options(current().options.copy(range = DayimaChartRange.CUSTOM, from = from, through = through), recalculate = true)
    }

    fun select(value: DayimaChartSelection?) {
        savedState[SELECTION] = value?.bundle()
        publish(current().copy(selection = value))
    }

    companion object {
        private const val OPTIONS = "dayima.charts.options"
        private const val RANGE_EDITOR = "dayima.charts.range_editor"
        private const val SELECTION = "dayima.charts.selection"
        fun restore(savedState: SavedStateHandle): DayimaChartsState {
            // 筛选入口已移除，恢复时清除旧范围和草稿，避免静默隐藏历史记录。
            savedState.remove<Bundle>(OPTIONS)
            savedState.remove<Bundle>(RANGE_EDITOR)
            return DayimaChartsState(selection = restoreChartSelection(savedState[SELECTION]))
        }
    }
}
