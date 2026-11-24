package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaPeriodEntity

/** 概览、资料与历史日期来自同一事务；列表不预先读取所有备注正文。 */
data class DayimaOverview(
    val records: DayimaRecordsSnapshot,
    val statistics: DayimaStatistics,
    val noteCounts: Map<String, Int>,
    val independentNoteCount: Int,
    val markers: List<DayimaSpanMarkerState>,
    val phases: DayimaPhaseEstimate = DayimaCyclePhases.estimate(statistics),
)

data class DayimaPeriodDetail(
    val dataVersion: Long,
    val period: DayimaPeriodEntity,
    val notes: List<DayimaNoteEntity>,
    val nextPeriod: DayimaPeriodEntity?,
    val marker: DayimaSpanMarkerState?,
    val images: List<DayimaImage> = emptyList(),
)

data class DayimaNotePage(val dataVersion: Long, val notes: List<DayimaNoteEntity>, val hasMore: Boolean)
