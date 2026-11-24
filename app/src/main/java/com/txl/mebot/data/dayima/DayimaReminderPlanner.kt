package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaReminderSettingsEntity
import java.time.LocalDate
import java.time.ZonedDateTime

enum class DayimaReminderKind { PREPARE, MISSING_RECORD }

data class DayimaReminderCandidate(
    val id: String, val periodId: String, val periodDate: LocalDate, val periodRevision: Long,
    val kind: DayimaReminderKind, val predictedDate: LocalDate, val rangeStart: LocalDate, val rangeEnd: LocalDate,
    val reminderDate: LocalDate, val minuteOfDay: Int, val triggerAt: Long, val zoneId: String,
    val dataVersion: Long, val ruleFingerprint: String,
)

/** 复用已有确定性预测；只建立当前真实周期的两类计划，不调用模型或合成经期记录。 */
object DayimaReminderPlanner {
    fun fingerprint(settings: DayimaReminderSettingsEntity) =
        "v1:${settings.prepareEnabled}:${settings.missingRecordEnabled}:${settings.prepareDaysBefore}:${settings.reminderTime}"

    fun candidates(records: DayimaRecordsSnapshot, settings: DayimaReminderSettingsEntity, now: ZonedDateTime): List<DayimaReminderCandidate> {
        require(settings.prepareDaysBefore in 0..30 && settings.reminderTime.second == 0 && settings.reminderTime.nano == 0)
        if (!settings.prepareEnabled && !settings.missingRecordEnabled) return emptyList()
        val prediction = DayimaStatisticsCalculator.calculate(records, now.toLocalDate()).prediction.prediction ?: return emptyList()
        val period = records.periods.firstOrNull { it.id == prediction.latestPeriodId } ?: return emptyList()
        val result = mutableListOf<DayimaReminderCandidate>()
        fun add(kind: DayimaReminderKind, day: LocalDate) {
            result += DayimaReminderCandidate("dayima:${period.id}:${kind.name}", period.id, period.startDate, period.revision,
                kind, prediction.predictedDate, prediction.rangeStart, prediction.rangeEnd, day,
                settings.reminderTime.hour * 60 + settings.reminderTime.minute,
                day.atTime(settings.reminderTime).atZone(now.zone).toInstant().toEpochMilli(), now.zone.id,
                records.dataVersion, fingerprint(settings))
        }
        if (settings.prepareEnabled) add(DayimaReminderKind.PREPARE, prediction.rangeStart.minusDays(settings.prepareDaysBefore.toLong()))
        if (settings.missingRecordEnabled) add(DayimaReminderKind.MISSING_RECORD, prediction.rangeEnd.plusDays(1))
        return result
    }
}
