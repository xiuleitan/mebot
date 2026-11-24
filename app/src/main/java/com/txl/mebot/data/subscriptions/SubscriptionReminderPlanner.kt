package com.txl.mebot.data.subscriptions

import com.txl.mebot.data.local.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

internal data class SubscriptionReminderCandidate(val subscriptionId: String, val cycleId: String,
    val targetType: SubscriptionReminderTarget, val targetDate: LocalDate, val triggerAt: Long, val zoneId: String,
    val subscriptionRevision: Long, val settingsRevision: Long)

/** 日期和稳定周期均由本地历史重建，提醒不使用模型，也不生成付款记录。 */
internal object SubscriptionReminderPlanner {
    fun next(record: SubscriptionEntity, history: List<SubscriptionBillingChange>, payments: List<SubscriptionPaymentFact>,
        settings: SubscriptionReminderSettingsEntity, now: ZonedDateTime): SubscriptionReminderCandidate? {
        if (!settings.enabled || !record.autoRenew || record.status != SubscriptionStatus.ACTIVE) return null
        require(settings.subscriptionId == record.id && settings.minuteOfDay in 0..1439)
        val today = now.toLocalDate()
        val cursors = SubscriptionForecast.cursors(record, history)
        val identities = identities(record, history, cursors)
        val target: LocalDate
        val cycle: String
        run {
            val rule = SubscriptionBillingRule.from(record)
            if (!rule.active) return null
            val cursor = cursors.values.lastOrNull() ?: return null
            val paid = payments.filter { it.subscriptionId == record.id && !it.voided }.map { it.billingEventId }.toHashSet()
            fun unpaid(from: LocalDate): Pair<LocalDate, String> {
                var date = requireNotNull(rule.onOrAfter(maxOf(from, requireNotNull(record.nextBillingDate))))
                while (true) {
                    val raw = rule.index(date)
                    val index = cursor.offset + raw
                    require(index >= 0)
                    val id = SubscriptionForecast.eventId(index)
                    if (id !in paid) return date to id
                    date = rule.date(Math.addExact(raw, 1))
                }
            }
            val first = unpaid(today.plusDays(1))
            val advanceDay = today.plusDays(3)
            val onTime = unpaid(advanceDay)
            // 短周期优先今天应提前三天提醒的那期，不一次补发几期历史提醒。
            val chosen = if (onTime.first == advanceDay) onTime else first
            target = chosen.first; cycle = identities.automatic(chosen.second)
        }
        val time = LocalTime.ofSecondOfDay(settings.minuteOfDay * 60L)
        val trigger = target.minusDays(3).atTime(time).atZone(now.zone).toInstant().toEpochMilli()
        return SubscriptionReminderCandidate(record.id, cycle, SubscriptionReminderTarget.AUTO_CHARGE, target, trigger, now.zone.id, record.revision, settings.revision)
    }

    private class Identities(val epoch: String, val aliases: Map<String, String>, val expiry: String) {
        fun automatic(billingId: String) = aliases[billingId] ?: epoch + billingId
    }
    private fun identities(record: SubscriptionEntity, history: List<SubscriptionBillingChange>, cursors: Map<Long, SubscriptionForecast.Cursor>): Identities {
        var before: SubscriptionBillingChange? = null
        var previousCursor: SubscriptionForecast.Cursor? = null
        var epoch = ""
        var expiry = "term:${history.firstOrNull()?.revision ?: 1}"
        val aliases = mutableMapOf<String, String>()
        fun automatic(index: Long) = SubscriptionForecast.eventId(index).let { aliases[it] ?: epoch + it }
        for (change in history) {
            val after = change.rule
            val old = before
            val cursor = cursors[change.revision]
            if (old != null) {
                val oldTarget = old.validUntil ?: old.rule.next
                val newTarget = if (after.active) after.next else change.validUntil
                val ended = old.rule.status in setOf(SubscriptionStatus.ARCHIVED, SubscriptionStatus.DELETED) ||
                    !old.rule.autoRenew && old.validUntil?.isBefore(change.date) == true
                val nextOldCycle = old.rule.next?.takeIf { old.rule.anchor != null && old.rule.unit != SubscriptionCycleUnit.NONE }?.let {
                    old.rule.date(Math.addExact(old.rule.index(it), 1))
                }
                val renewed = change.kind == SubscriptionOperationKind.RENEW_TERM || ended && change.kind == SubscriptionOperationKind.RESTORE && newTarget != null && newTarget != oldTarget &&
                    (old.validUntil != null || nextOldCycle != null && !newTarget.isBefore(nextOldCycle))
                if (renewed) {
                    epoch = "renewal:${change.revision}/"; aliases.clear(); expiry = "term:${change.revision}"
                }
                if (old.rule.active && !after.active) {
                    val raw = old.rule.next?.let { previousCursor?.offset?.plus(old.rule.index(it)) }
                    raw?.coerceAtLeast(0)?.let { expiry = automatic(it) }
                } else if (!old.rule.active && after.active && !renewed) {
                    val raw = after.next?.let { cursor?.offset?.plus(after.index(it)) }
                    raw?.coerceAtLeast(0)?.let { aliases[SubscriptionForecast.eventId(it)] = expiry }
                }
            }
            before = change; previousCursor = cursor
        }
        // 没有变更历史的导入对象仍有确定标识；正式对象总有创建快照。
        if (history.isEmpty() && record.autoRenew) return Identities("", emptyMap(), expiry)
        return Identities(epoch, aliases, expiry)
    }
}
