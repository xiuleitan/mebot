package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaNoteEntity
import com.txl.mebot.data.local.DayimaReminderSettingsEntity
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class DayimaQueryResult(val text: String, val modelData: JSONObject, val targets: List<DayimaChatTarget> = emptyList())

/** 数字与保存回执由本地生成；向模型只提供本次请求的汇总，不提供生日或全部原始历史。 */
internal fun evaluateDayimaQuery(query: DayimaChatQuery, records: DayimaRecordsSnapshot, today: LocalDate,
    reminders: DayimaReminderSettingsEntity?, notes: List<DayimaNoteEntity> = emptyList()): DayimaQueryResult {
    val stats = DayimaStatisticsCalculator.calculate(records, today, query.range)
    val scope = query.range?.let { "${it.from} 至 ${it.through}（按周期开始日）" } ?: "全部已记录历史"
    val json = JSONObject().put("kind", query.kind.name).put("data_version", records.dataVersion).put("as_of", today.toString())
    return when (query.kind) {
        DayimaQueryKind.LATEST -> {
            val latest = records.periods.filter { it.startDate <= today }.maxByOrNull { it.startDate }
            val previous = stats.period.cycles.lastOrNull()
            val text = if (latest == null) "还没有经期开始记录。可以直接说“今天来了”，也可以手动补记。"
                else "最近一次开始：${latest.startDate}。距上次开始 ${stats.currentCycle!!.daysSinceStart} 天。\n" +
                    (previous?.let { "最近完整周期：${it.startDate} 至 ${it.endDate}，${it.days} 天。\n" } ?: "") + stats.prediction.summary()
            json.put("latest_date", latest?.startDate?.toString() ?: JSONObject.NULL).put("prediction", stats.prediction.summary())
                .put("latest_completed_days", previous?.days ?: JSONObject.NULL)
            DayimaQueryResult(text, json, latest?.let { listOf(DayimaChatTarget(it.id, it.startDate, it.revision)) }.orEmpty())
        }
        DayimaQueryKind.HISTORY -> {
            val rows = records.periods.filter { it.startDate <= today && (query.range == null || it.startDate in query.range) }.sortedByDescending { it.startDate }
            val text = "$scope：共 ${rows.size} 次开始记录。" + if (rows.isEmpty()) "" else
                "\n${rows.take(60).joinToString("、") { it.startDate.toString() }}${if (rows.size > 60) "\n此处显示最近 60 条，全部记录可在主界面历史中查看。" else ""}"
            DayimaQueryResult(text, json.put("count", rows.size), rows.takeIf { it.size == 1 }?.map { DayimaChatTarget(it.id, it.startDate, it.revision) }.orEmpty())
        }
        DayimaQueryKind.NOTES -> {
            val sorted = notes.sortedByDescending { it.occurredDate }
            DayimaQueryResult("$scope${if (sorted.size > 20) "（显示最近 20 条）" else "：${sorted.size} 条备注"}。" + sorted.take(20).joinToString("", prefix = if (sorted.isEmpty()) "" else "\n") {
                "${it.occurredDate}：${it.content.take(240)}${if (it.content.length > 240) "…（全文可在详情查看）" else ""}\n"
            } + if (sorted.size > 20) "其余备注可在主界面查看。" else "", json.put("count_shown", minOf(sorted.size, 20)))
        }
        DayimaQueryKind.STATISTICS -> {
            val p = stats.period; val comparison = p.comparedWith28Days
            json.put("scope", scope).put("eligible", p.eligibleCycleCount).put("excluded", p.excludedCycleCount).put("pending", p.pendingReviewCycleCount)
                .put("median_days", p.distribution?.medianDays ?: JSONObject.NULL)
                .put("lower_quartile_days", p.distribution?.lowerQuartileDays ?: JSONObject.NULL)
                .put("upper_quartile_days", p.distribution?.upperQuartileDays ?: JSONObject.NULL)
                .put("shortest_days", p.distribution?.shortestDays ?: JSONObject.NULL)
                .put("longest_days", p.distribution?.longestDays ?: JSONObject.NULL)
                .put("shorter_than_28", comparison.shorterCount).put("equal_28", comparison.equalCount).put("longer_than_28", comparison.longerCount)
                .put("equal_28_proportion", comparison.equalProportion ?: JSONObject.NULL)
                .put("prediction", stats.prediction.summary())
            if (query.includeAge) json.put("birth_date_available", stats.birthDateAvailable).put("age_history_sufficient", p.hasEnoughAgeHistory)
                .put("age_groups", JSONArray(p.ages.map { JSONObject().put("age", it.ageYears).put("count", it.sampleSize)
                    .put("median_days", it.distribution?.medianDays ?: JSONObject.NULL) }))
            val distribution = p.distribution?.let { "中位数 ${it.medianDays.number()} 天，居中一半范围 ${it.lowerQuartileDays.number()}～${it.upperQuartileDays.number()} 天。\n" }.orEmpty()
            val text = "$scope：${p.eligibleCycleCount} 个有效完整周期，${p.excludedCycleCount} 个已排除，${p.pendingReviewCycleCount} 个待复核。\n" + distribution +
                "以 28 天为参照：短于 ${comparison.shorterCount} 次，恰好 ${comparison.equalCount} 次${comparison.equalProportion?.let { "（${String.format(Locale.CHINA, "%.1f", it * 100)}%）" }.orEmpty()}，长于 ${comparison.longerCount} 次。28 天是比较基准。\n" +
                if (p.frequency.isEmpty()) "当前没有可统计的完整周期。" else "天数频次：${p.frequency.take(40).joinToString("、") { "${it.days} 天 ${it.count} 次" }}。" +
                    if (p.frequency.size > 40) "其余天数可在分布图查看。" else ""
            val ageText = if (!query.includeAge) "" else if (!stats.birthDateAvailable) "\n未填写有效出生日期，暂不能按年龄汇总。" else if (p.ages.isEmpty()) "\n所选周期没有可用年龄，请核对出生日期。" else
                "\n年龄汇总：" + p.ages.joinToString("；") { "${it.ageYears} 岁 ${it.sampleSize} 个周期" + (it.distribution?.let { d -> "，中位数 ${d.medianDays.number()} 天" } ?: "（样本不足 3 个，暂不汇总）") } +
                    if (!p.hasEnoughAgeHistory) "。年龄历史或分组样本不足，暂不总结年龄变化。" else "。年龄变化不能说明具体原因。"
            DayimaQueryResult(text + ageText, json)
        }
        DayimaQueryKind.COMPARE -> {
            val a = requireNotNull(query.range); val b = requireNotNull(query.secondRange)
            val result = DayimaStatisticsCalculator.compare(records, today, a, b)
            fun period(label: String, data: DayimaPeriodStatistics) = "$label：${data.eligibleCycleCount} 个有效周期" +
                (data.distribution?.let { "，中位数 ${it.medianDays.number()} 天" } ?: "") + "。"
            val difference = result.medianDifferenceDays
            val text = period("${a.from} 至 ${a.through}", result.first) + "\n" + period("${b.from} 至 ${b.through}", result.second) + "\n" +
                if (difference == null) "至少每段各有 3 个有效周期才比较整体变化。" else
                    "第二段中位数比第一段${if (difference >= 0) "长" else "短"} ${kotlin.math.abs(difference).number()} 天。这是历史分布比较，不能据此判断变化原因。"
            DayimaQueryResult(text, json.put("first_count", result.first.eligibleCycleCount).put("second_count", result.second.eligibleCycleCount)
                .put("median_difference_days", difference ?: JSONObject.NULL))
        }
        DayimaQueryKind.REMINDERS -> {
            val settings = reminders ?: DayimaReminderSettingsEntity(updatedAt = 0)
            DayimaQueryResult((if (reminders == null) "尚未保存自定义提醒规则，以下为默认值。\n" else "已保存的提醒规则：\n") + settings.summary(), json.put("prepare", settings.prepareEnabled).put("missing", settings.missingRecordEnabled)
                .put("time", settings.reminderTime.toString()).put("days_before", settings.prepareDaysBefore).put("notification_delivery", "local_background_requires_permission_and_prediction"))
        }
    }
}

internal fun DayimaReminderSettingsEntity.summary() =
    "准备提醒${if (prepareEnabled) "开启" else "关闭"}，提前 $prepareDaysBefore 天。缺失记录提醒${if (missingRecordEnabled) "开启" else "关闭"}。有可用的周期预测时，提醒会按日期显示在首页。"
internal fun DayimaPredictionResult.summary() = prediction?.let {
    "下次预计 ${it.predictedDate}，估计范围 ${it.rangeStart} 至 ${it.rangeEnd}。" + if (it.rangeHasEnded) "范围已过，若已经开始可补记实际日期。" else ""
} ?: "当前连续记录不足，暂不能估计下次日期。"
private fun Double.number() = if (this == toLong().toDouble()) toLong().toString() else String.format(Locale.CHINA, "%.1f", this)
