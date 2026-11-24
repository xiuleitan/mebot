package com.txl.mebot.data.dayima

import java.time.LocalDate

data class DayimaDateRange(val from: LocalDate, val through: LocalDate) {
    init { require(from <= through) { "日期范围无效" } }
    operator fun contains(date: LocalDate): Boolean = date >= from && date <= through
}

enum class DayimaCycleStatus { ELIGIBLE, MISSING_RECORDS, SPECIAL_PHASE, PENDING_REVIEW }

/** 两次实际开始之间的完整周期，日期差不额外加一天。 */
data class DayimaCycle(
    val startPeriodId: String,
    val endPeriodId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startRevision: Long,
    val endRevision: Long,
    val days: Long,
    val ageYears: Int?,
    val status: DayimaCycleStatus,
    val markerId: String?,
)

data class DayimaCycleFrequency(val days: Long, val count: Int, val proportion: Double, val cycleStartIds: List<String>)

/** 四分位数使用 (n - 1) × p 的线性插值；范围不是置信区间。 */
data class DayimaDistribution(
    val sampleSize: Int,
    val medianDays: Double,
    val lowerQuartileDays: Double,
    val upperQuartileDays: Double,
    val shortestDays: Long,
    val longestDays: Long,
)

data class DayimaBaselineComparison(
    val baselineDays: Double?,
    val shorterCount: Int,
    val equalCount: Int,
    val longerCount: Int,
) {
    val sampleSize: Int get() = shorterCount + equalCount + longerCount
    val shorterProportion: Double? get() = if (sampleSize == 0) null else shorterCount.toDouble() / sampleSize
    val equalProportion: Double? get() = if (sampleSize == 0) null else equalCount.toDouble() / sampleSize
    val longerProportion: Double? get() = if (sampleSize == 0) null else longerCount.toDouble() / sampleSize
}

/** segmentId 与空的滚动值明确标出断点，不能跨过被排除的周期连接趋势。 */
data class DayimaTrendPoint(
    val cycleStartId: String,
    val date: LocalDate,
    val days: Long,
    val ageYears: Int?,
    val status: DayimaCycleStatus,
    val segmentId: String?,
    val rollingMedianDays: Double?,
    val rollingCycleStartIds: List<String>,
)

data class DayimaYearStatistics(val year: Int, val cycleStartIds: List<String>, val distribution: DayimaDistribution?) {
    val sampleSize: Int get() = cycleStartIds.size
}

data class DayimaAgeStatistics(val ageYears: Int, val cycleStartIds: List<String>, val distribution: DayimaDistribution?) {
    val sampleSize: Int get() = cycleStartIds.size
}

data class DayimaPeriodStatistics(
    val range: DayimaDateRange?,
    val cycles: List<DayimaCycle>,
    val eligibleCycleCount: Int,
    val excludedCycleCount: Int,
    val pendingReviewCycleCount: Int,
    val pendingReviewMarkerCount: Int,
    val ageUnavailableCycleCount: Int,
    val distribution: DayimaDistribution?,
    val frequency: List<DayimaCycleFrequency>,
    val comparedWith28Days: DayimaBaselineComparison,
    val comparedWithPersonalMedian: DayimaBaselineComparison,
    val trend: List<DayimaTrendPoint>,
    val years: List<DayimaYearStatistics>,
    val ages: List<DayimaAgeStatistics>,
    val hasEnoughAgeHistory: Boolean,
) {
    val completedCycleCount: Int get() = cycles.size
}

data class DayimaCurrentCycle(val startPeriodId: String, val startDate: LocalDate, val daysSinceStart: Long)

enum class DayimaPredictionStatus { AVAILABLE, NO_RECORDS, INSUFFICIENT_CONTINUOUS_HISTORY, DATE_OUT_OF_RANGE }

data class DayimaPrediction(
    val latestPeriodId: String,
    val latestDate: LocalDate,
    val predictedDate: LocalDate,
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val weightedCycleDays: Long,
    val variationDays: Int,
    val recentIntervals: List<Long>,
    val cycleStartIds: List<String>,
    val rangeHasEnded: Boolean,
)

data class DayimaPredictionResult(val status: DayimaPredictionStatus, val prediction: DayimaPrediction? = null) {
    init { require((status == DayimaPredictionStatus.AVAILABLE) == (prediction != null)) }
}

data class DayimaStatistics(
    val dataVersion: Long,
    val asOfDate: LocalDate,
    val birthDateAvailable: Boolean,
    val ignoredFutureRecordCount: Int,
    val period: DayimaPeriodStatistics,
    val currentCycle: DayimaCurrentCycle?,
    val prediction: DayimaPredictionResult,
)

/** 两段统计来自同一事实快照；各至少三个有效周期时才给出中位数差值。 */
data class DayimaPeriodComparison(
    val dataVersion: Long,
    val asOfDate: LocalDate,
    val first: DayimaPeriodStatistics,
    val second: DayimaPeriodStatistics,
    val medianDifferenceDays: Double?,
)
