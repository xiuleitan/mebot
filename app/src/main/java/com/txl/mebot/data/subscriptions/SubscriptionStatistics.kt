package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.json.JSONObject

data class SubscriptionCurrencyTotal(val currencyCode: String, val amountMinor: BigInteger)
data class SubscriptionPlannedCharge(val subscriptionId: String, val eventId: String, val date: LocalDate,
    val amountMinor: Long, val currencyCode: String)
data class SubscriptionSpendingSummary(val month: YearMonth, val estimated: List<SubscriptionCurrencyTotal>,
    val previousActual: List<SubscriptionCurrencyTotal>, val previousConfirmedCount: Int, val currentConfirmedCount: Int,
    val unconfirmedPlanCount: Int)

/** 仅保留计算所需字段，不将聊天、媒体或完整历史备注加载进统计模型。 */
internal data class SubscriptionBillingRule(val amount: Long, val currency: String, val unit: SubscriptionCycleUnit,
    val interval: Int, val anchor: LocalDate?, val monthEnd: Boolean, val next: LocalDate?, val autoRenew: Boolean,
    val status: SubscriptionStatus) {
    val active: Boolean get() = autoRenew && status == SubscriptionStatus.ACTIVE && unit != SubscriptionCycleUnit.NONE && anchor != null
    fun date(index: Long): LocalDate {
        val start = requireNotNull(anchor)
        require(index >= 0)
        return when (unit) {
            SubscriptionCycleUnit.DAY -> start.plusDays(Math.multiplyExact(index, interval.toLong()))
            SubscriptionCycleUnit.WEEK -> start.plusDays(Math.multiplyExact(index, interval * 7L))
            SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR -> {
                val months = Math.multiplyExact(index, interval.toLong() * if (unit == SubscriptionCycleUnit.YEAR) 12 else 1)
                val target = YearMonth.from(start).plusMonths(months)
                if (monthEnd) target.atEndOfMonth() else target.atDay(start.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
            }
            SubscriptionCycleUnit.NONE -> error("无周期项目没有扣费计划")
        }
    }
    fun index(date: LocalDate): Long = when (unit) {
        SubscriptionCycleUnit.DAY -> ChronoUnit.DAYS.between(requireNotNull(anchor), date) / interval
        SubscriptionCycleUnit.WEEK -> ChronoUnit.DAYS.between(requireNotNull(anchor), date) / (interval * 7L)
        SubscriptionCycleUnit.MONTH -> ChronoUnit.MONTHS.between(YearMonth.from(requireNotNull(anchor)), YearMonth.from(date)) / interval
        SubscriptionCycleUnit.YEAR -> (date.year.toLong() - requireNotNull(anchor).year) / interval
        SubscriptionCycleUnit.NONE -> 0
    }
    fun onOrAfter(from: LocalDate) = SubscriptionDates.onOrAfter(requireNotNull(anchor), unit, interval, monthEnd, from)
    fun sameSchedule(other: SubscriptionBillingRule) = unit == other.unit && interval == other.interval && anchor == other.anchor && monthEnd == other.monthEnd
    companion object {
        fun from(item: SubscriptionEntity) = SubscriptionBillingRule(item.amountMinor, item.currencyCode, item.cycleUnit,
            item.cycleInterval, item.anchorDate, item.anchorAtMonthEnd, item.nextBillingDate, item.autoRenew, item.status)
        fun decode(text: String): SubscriptionBillingRule {
            val json = JSONObject(text)
            require(json.getInt("formatVersion") == 1)
            fun date(name: String) = if (json.isNull(name)) null else LocalDate.parse(json.getString(name))
            return SubscriptionBillingRule(json.getLong("amountMinor"), json.getString("currencyCode"),
                SubscriptionCycleUnit.valueOf(json.getString("cycleUnit")), json.getInt("cycleInterval"), date("anchorDate"),
                json.getBoolean("anchorAtMonthEnd"), date("nextBillingDate"), json.getBoolean("autoRenew"), SubscriptionStatus.valueOf(json.getString("status")))
        }
    }
}
internal data class SubscriptionBillingChange(val revision: Long, val kind: SubscriptionOperationKind, val date: LocalDate,
    val rule: SubscriptionBillingRule, val paymentEventId: String?, val validUntil: LocalDate? = null)
internal data class SubscriptionBillingInput(val records: List<SubscriptionEntity>,
    val changes: Map<String, List<SubscriptionBillingChange>>, val payments: List<SubscriptionPaymentFact>)

/** 计费事件使用连续周期编号，改价或同一期改期不会变成第二笔扣费。 */
internal object SubscriptionForecast {
    data class Cursor(val offset: Long, val pendingIndex: Long?)
    private data class Segment(val rule: SubscriptionBillingRule, val from: LocalDate, val first: LocalDate,
        val offset: Long, var until: LocalDate? = null, var maxIndex: Long = Long.MAX_VALUE) {
        fun lastIndex(): Long? {
            val end = until ?: return null
            if (!end.isAfter(from) || !end.isAfter(first) || !end.isAfter(requireNotNull(rule.anchor))) return null
            val candidate = requireNotNull(rule.onOrAfter(end.minusDays(1)))
            val raw = rule.index(candidate) - if (!candidate.isBefore(end)) 1 else 0
            if (raw < 0) return null
            val last = (offset + raw).coerceAtMost(maxIndex - 1)
            return last.takeIf { it >= 0 }
        }
    }
    fun eventId(index: Long) = "cycle:$index"
    private fun eventIndex(id: String?): Long? = id?.takeIf { it.startsWith("cycle:") }?.removePrefix("cycle:")?.toLongOrNull()?.takeIf { it >= 0 }

    private data class Timeline(val segments: List<Segment>, val cursors: Map<Long, Cursor>)
    fun cursors(record: SubscriptionEntity, history: List<SubscriptionBillingChange>): Map<Long, Cursor> =
        timeline(record, history, record.anchorDate ?: LocalDate.of(1970, 1, 1)).cursors

    private fun timeline(record: SubscriptionEntity, history: List<SubscriptionBillingChange>, fallbackDate: LocalDate): Timeline {
        val segments = mutableListOf<Segment>()
        var current: SubscriptionBillingRule? = null
        var offset = 0L
        var open: Segment? = null
        val consumed = mutableSetOf<Long>()
        val cursors = mutableMapOf<Long, Cursor>()
        fun remember(revision: Long, rule: SubscriptionBillingRule) {
            var pending = if (rule.anchor != null && rule.next != null && rule.unit != SubscriptionCycleUnit.NONE)
                (offset + rule.index(rule.next)).coerceAtLeast(0) else null
            while (pending != null && pending in consumed) pending = Math.addExact(pending, 1)
            cursors[revision] = Cursor(offset, pending)
        }
        val changes = history.ifEmpty { listOf(SubscriptionBillingChange(1, SubscriptionOperationKind.CREATE,
            record.anchorDate ?: fallbackDate, SubscriptionBillingRule.from(record), null)) }
        for (change in changes) {
            val after = change.rule
            val paidIndex = eventIndex(change.paymentEventId)
            when (change.kind) {
                SubscriptionOperationKind.RECORD_PAYMENT, SubscriptionOperationKind.CORRECT_PAYMENT -> paidIndex?.let(consumed::add)
                SubscriptionOperationKind.VOID_PAYMENT -> paidIndex?.let(consumed::remove)
                else -> Unit
            }
            val before = current
            if (before == null) {
                if (after.active) {
                    val anchor = requireNotNull(after.anchor)
                    open = Segment(after, anchor, anchor, 0).also(segments::add)
                }
                current = after
                remember(change.revision, after)
                continue
            }
            if (change.kind in setOf(SubscriptionOperationKind.ADVANCE, SubscriptionOperationKind.RECORD_PAYMENT,
                    SubscriptionOperationKind.CORRECT_PAYMENT, SubscriptionOperationKind.VOID_PAYMENT) || before == after) {
                current = after; remember(change.revision, after); continue
            }
            open?.until = change.date
            open = null
            if (after.active) {
                val changedSchedule = !before.active || !before.sameSchedule(after) || before.next != after.next
                if (changedSchedule) {
                    val oldPending = if (before.anchor != null && before.unit != SubscriptionCycleUnit.NONE)
                        offset + before.index(before.next ?: requireNotNull(before.onOrAfter(change.date))) else 0L
                    val oldPast = if (!before.active) segments.mapNotNull { it.lastIndex() }.maxOrNull()?.let { Math.addExact(it, 1) } ?: 0 else 0
                    var carry = maxOf(0, oldPending, oldPast)
                    while (carry in consumed) carry = Math.addExact(carry, 1)
                    val next = requireNotNull(after.next)
                    offset = carry - after.index(next)
                    // 同一个尚未扣费周期整体改期时，旧日期即使位于别的月份，也不再重复预计。
                    segments.forEach { it.maxIndex = minOf(it.maxIndex, carry) }
                }
                open = Segment(after, change.date, after.next ?: requireNotNull(after.anchor), offset).also(segments::add)
            }
            current = after
            remember(change.revision, after)
        }
        return Timeline(segments, cursors)
    }

    fun events(record: SubscriptionEntity, history: List<SubscriptionBillingChange>, month: YearMonth): List<SubscriptionPlannedCharge> {
        val segments = timeline(record, history, month.atDay(1)).segments
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        val result = linkedMapOf<String, SubscriptionPlannedCharge>()
        for (segment in segments) {
            val first = maxOf(start, segment.from, segment.first)
            val last = minOf(end, segment.until ?: end)
            if (!first.isBefore(last)) continue
            var date = requireNotNull(segment.rule.onOrAfter(first))
            while (date.isBefore(last)) {
                val index = segment.offset + segment.rule.index(date)
                if (index >= 0 && index < segment.maxIndex) {
                    val id = eventId(index)
                    result[id] = SubscriptionPlannedCharge(record.id, id, date, segment.rule.amount, segment.rule.currency)
                }
                val raw = segment.rule.index(date)
                date = segment.rule.date(Math.addExact(raw, 1))
            }
        }
        return result.values.sortedBy { it.date }
    }
}

class SubscriptionStatisticsRepository(private val storage: SubscriptionStorage,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    internal suspend fun read(subscriptionId: String? = null): SubscriptionBillingInput = storage.database.withTransaction {
        val records = if (subscriptionId == null) storage.records.all() else listOfNotNull(storage.records.subscription(subscriptionId))
        val changes = mutableMapOf<String, MutableList<SubscriptionBillingChange>>()
        var cursor = ""
        var revision = 0L
        while (true) {
            val page = storage.records.billingHistoryPage(subscriptionId, cursor, revision, 200)
            for (row in page) changes.getOrPut(row.subscriptionId, ::mutableListOf).add(SubscriptionBillingChange(row.revision, row.kind,
                Instant.ofEpochMilli(row.createdAt).atZone(zone()).toLocalDate(), SubscriptionBillingRule.decode(row.afterJson),
                row.paymentPayload?.let { JSONObject(it).optString("billingEventId").takeIf(String::isNotBlank) },
                JSONObject(row.afterJson).let { if (it.isNull("validUntil")) null else LocalDate.parse(it.getString("validUntil")) }))
            if (page.size < 200) break
            cursor = page.last().subscriptionId; revision = page.last().revision
        }
        SubscriptionBillingInput(records, changes, storage.records.paymentFacts(subscriptionId))
    }

    suspend fun summary(month: YearMonth): SubscriptionSpendingSummary = calculate(read(), month)
    internal fun calculate(input: SubscriptionBillingInput, month: YearMonth): SubscriptionSpendingSummary {
        val active = input.payments.filterNot { it.voided }
        val paidEvents = active.map { it.subscriptionId to it.billingEventId }.toHashSet()
        val plans = input.records.flatMap { SubscriptionForecast.events(it, input.changes[it.id].orEmpty(), month) }
            .filter { (it.subscriptionId to it.eventId) !in paidEvents }
        val current = active.filter { YearMonth.from(it.paidOn) == month }
        val previous = active.filter { YearMonth.from(it.paidOn) == month.minusMonths(1) }
        fun totals(amounts: List<Pair<String, Long>>): List<SubscriptionCurrencyTotal> = amounts.groupBy({ it.first }, { it.second })
            .map { (currency, values) -> SubscriptionCurrencyTotal(currency, values.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) }) }
            .sortedWith(compareBy<SubscriptionCurrencyTotal> { it.currencyCode != "CNY" }.thenBy { it.currencyCode })
        return SubscriptionSpendingSummary(month, totals(current.map { it.currencyCode to it.amountMinor } + plans.map { it.currencyCode to it.amountMinor }),
            totals(previous.map { it.currencyCode to it.amountMinor }), previous.size, current.size, plans.size)
    }
}

data class SubscriptionBillingMonth(val record: SubscriptionEntity, val month: YearMonth, val plans: List<SubscriptionPlannedCharge>,
    val paidEvents: Map<String, SubscriptionPaymentFact>, val payments: List<SubscriptionPaymentRow>, val hasMore: Boolean)

class SubscriptionBillingRepository(private val storage: SubscriptionStorage, private val records: SubscriptionRepository,
    private val statistics: SubscriptionStatisticsRepository, private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    fun today(): LocalDate = clock.instant().atZone(zone()).toLocalDate()
    suspend fun month(id: String, month: YearMonth, limit: Int = 20, offset: Int = 0): SubscriptionBillingMonth = storage.database.withTransaction {
        val input = statistics.read(id)
        val record = requireNotNull(input.records.singleOrNull()) { "订阅不存在" }
        val bounded = limit.coerceIn(1, 100)
        val rows = storage.records.paymentRows(id, month.atDay(1), month.plusMonths(1).atDay(1), bounded + 1, offset.coerceAtLeast(0))
        SubscriptionBillingMonth(record, month, SubscriptionForecast.events(record, input.changes[id].orEmpty(), month),
            input.payments.filterNot { it.voided }.associateBy { it.billingEventId }, rows.take(bounded), rows.size > bounded)
    }
    suspend fun record(requestId: String, id: String, expectedRevision: Long, plan: SubscriptionPlannedCharge?,
        values: SubscriptionPaymentValues): SubscriptionPaymentEntity = storage.database.withTransaction {
        validateDate(values)
        val eventId = plan?.eventId ?: "manual:$requestId"
        val receipt = storage.records.operation(requestId)
        if (receipt?.state != SubscriptionOperationState.APPLIED && plan != null) {
            require(plan.subscriptionId == id) { "扣费计划不属于当前订阅" }
            val input = statistics.read(id)
            val item = requireNotNull(input.records.singleOrNull())
            val current = SubscriptionForecast.events(item, input.changes[id].orEmpty(), YearMonth.from(plan.date))
            require(current.any { it.eventId == eventId && it.date == plan.date }) { "扣费计划已改变，请重新核对" }
        }
        records.recordPayment(requestId, id, expectedRevision, eventId, values.amountMinor, values.currencyCode, values.paidOn, values.notes)
    }
    suspend fun correct(requestId: String, id: String, revision: Long, payment: SubscriptionPaymentEntity,
        values: SubscriptionPaymentValues): SubscriptionPaymentEntity {
        validateDate(values)
        return records.correctPayment(requestId, id, revision, payment.id, payment.operationId, values)
    }
    suspend fun void(requestId: String, id: String, revision: Long, payment: SubscriptionPaymentEntity) =
        records.voidPayment(requestId, id, revision, payment.id, payment.operationId)
    private fun validateDate(values: SubscriptionPaymentValues) {
        values.validated()
        require(values.paidOn.year >= 1900 && !values.paidOn.isAfter(today())) { "实际扣费日期不能晚于今天" }
    }
}
