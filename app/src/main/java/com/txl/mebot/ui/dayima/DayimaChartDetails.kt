package com.txl.mebot.ui.dayima

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.dayima.DayimaCycleStatus
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun DayimaChartDetails(state: DayimaChartsState, model: DayimaViewModel?, enabled: Boolean) {
    val colors = scheduleColors()
    if (state.failed) { DayimaLoadError { model?.charts?.rebuild() }; return }
    val data = state.data
    if (state.loading || data == null) { DayimaLoading(); return }
    val selection = state.selection ?: return
    val rows = remember(data, selection) { selection.cycles(data) }
    var limit by rememberSaveable(selection) { mutableIntStateOf(30) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(when (selection.kind) {
            DayimaChartSelectionKind.HISTOGRAM -> if (selection.fromDays == selection.throughDays)
                stringResource(R.string.dayima_chart_day_label, selection.fromDays) else stringResource(R.string.dayima_chart_days_range, selection.fromDays, selection.throughDays)
            DayimaChartSelectionKind.YEAR -> stringResource(R.string.dayima_chart_selected_year, selection.group)
            DayimaChartSelectionKind.AGE -> stringResource(R.string.dayima_chart_selected_age, selection.group)
            DayimaChartSelectionKind.POINTS -> stringResource(R.string.dayima_chart_selected_points)
            DayimaChartSelectionKind.ALL -> stringResource(R.string.dayima_chart_selected_all)
        }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dayima_chart_selected_counts, rows.size, rows.count { it.status == DayimaCycleStatus.ELIGIBLE }),
            style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        if (selection.kind == DayimaChartSelectionKind.HISTOGRAM && data.statistics.period.eligibleCycleCount > 0) Text(
            stringResource(R.string.dayima_chart_count_percent, rows.size, (rows.size.toDouble() / data.statistics.period.eligibleCycleCount).dayimaPercent()),
            color = colors.secondary)
        val group = when (selection.kind) {
            DayimaChartSelectionKind.YEAR -> data.years.groups.find { it.key == selection.group }
            DayimaChartSelectionKind.AGE -> data.ages.groups.find { it.key == selection.group }
            else -> null
        }
        group?.let {
            val distribution = it.distribution
            Text(if (distribution == null) stringResource(R.string.dayima_chart_sparse_group) else stringResource(R.string.dayima_chart_group_summary,
                distribution.medianDays.dayimaNumber(), distribution.lowerQuartileDays.dayimaNumber(), distribution.upperQuartileDays.dayimaNumber(), distribution.sampleSize),
                style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        }
        if (data.statistics.period.range?.let { range -> rows.any { it.startDate !in range } } == true) Text(
            stringResource(R.string.dayima_chart_window_source), style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        if (rows.isEmpty()) Text(stringResource(R.string.dayima_chart_selected_empty), color = colors.secondary)
        rows.take(limit).forEach { cycle ->
            Surface(shape = RoundedCornerShape(18.dp), color = colors.past, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dayima_chart_day_label, cycle.days), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.dayima_chart_cycle_dates, cycle.startDate.dayimaDate(), cycle.endDate.dayimaDate()),
                        style = MaterialTheme.typography.bodyMedium)
                    cycle.ageYears?.let { Text(stringResource(R.string.dayima_chart_cycle_age, it), color = colors.secondary, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(cycle.status.dayimaLabel()), color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model?.openDetail(cycle.startPeriodId) }, enabled = enabled) { Text(stringResource(R.string.dayima_chart_open_start)) }
                        TextButton(onClick = { model?.openDetail(cycle.endPeriodId) }, enabled = enabled) { Text(stringResource(R.string.dayima_chart_open_end)) }
                    }
                }
            }
        }
        if (rows.size > limit) TextButton(onClick = { limit += 30 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dayima_load_more)) }
    }
}
