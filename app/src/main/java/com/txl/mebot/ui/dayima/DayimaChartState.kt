package com.txl.mebot.ui.dayima

import android.os.Bundle
import com.txl.mebot.data.dayima.DayimaDateRange
import java.time.LocalDate

internal data class DayimaChartOptions(
    val range: DayimaChartRange = DayimaChartRange.ALL,
    val from: LocalDate? = null,
    val through: LocalDate? = null,
) {
    fun dateRange(today: LocalDate): DayimaDateRange? = when (range) {
        DayimaChartRange.ALL -> null
        DayimaChartRange.LAST_YEAR -> DayimaDateRange(today.minusYears(1), today)
        DayimaChartRange.CUSTOM -> DayimaDateRange(requireNotNull(from), requireNotNull(through))
    }
    fun bundle() = Bundle().apply {
        putString("range", range.name); putString("from", from?.toString()); putString("through", through?.toString())
    }
    companion object {
        fun restore(bundle: Bundle?): DayimaChartOptions = bundle?.let { b -> runCatching {
            val options = DayimaChartOptions(DayimaChartRange.valueOf(requireNotNull(b.getString("range"))),
                b.getString("from")?.let(LocalDate::parse), b.getString("through")?.let(LocalDate::parse))
            if (options.range == DayimaChartRange.CUSTOM) require(requireNotNull(options.from) <= requireNotNull(options.through))
            options
        }.getOrNull() } ?: DayimaChartOptions()
    }
}

internal data class DayimaRangeEditor(val from: String, val through: String, val error: Int? = null) {
    fun bundle() = Bundle().apply { putString("from", from); putString("through", through) }
}

internal data class DayimaChartsState(
    val options: DayimaChartOptions = DayimaChartOptions(),
    val data: DayimaChartData? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val rangeEditor: DayimaRangeEditor? = null,
    val selection: DayimaChartSelection? = null,
)

internal fun DayimaChartSelection.bundle() = Bundle().apply {
    putString("kind", kind.name); putLong("from", fromDays); putLong("through", throughDays)
    putInt("group", group); putStringArrayList("ids", ArrayList(cycleStartIds))
}

internal fun restoreChartSelection(bundle: Bundle?): DayimaChartSelection? = bundle?.let { b -> runCatching {
    DayimaChartSelection(DayimaChartSelectionKind.valueOf(requireNotNull(b.getString("kind"))), b.getLong("from"),
        b.getLong("through"), b.getInt("group"), b.getStringArrayList("ids").orEmpty())
}.getOrNull() }
