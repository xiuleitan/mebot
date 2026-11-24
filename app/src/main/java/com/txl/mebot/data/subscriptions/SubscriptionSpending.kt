package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.json.JSONObject

/** 用户选定的参考汇率快照；离线也能核算，不在打开页面时上传业务记录。 */
object SubscriptionExchangeRates {
    val date: LocalDate = LocalDate.of(2026, 10, 30)
    const val source = "https://www.boc.cn/sourcedb/whpj/index.html"
    // 中国银行2026-10-30中行折算价，牌价单位为100外币兑换人民币。
    private val rates = mapOf("CNY" to BigDecimal.ONE, "USD" to BigDecimal("6.7138"), "HKD" to BigDecimal("0.8559"))
    fun cnyMinor(amountMinor: Long, currency: String): BigInteger? = rates[currency]?.let {
        BigDecimal.valueOf(amountMinor).multiply(it).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
    }
}

data class SubscriptionExpense(val subscriptionId: String, val title: String, val eventId: String, val date: LocalDate,
    val amountMinor: Long, val currencyCode: String, val confirmed: Boolean = false)
data class SubscriptionMonthSpending(val month: YearMonth, val entries: List<SubscriptionExpense>, val cnyMinor: BigInteger,
    val unconverted: List<SubscriptionCurrencyTotal>)
data class SubscriptionSpending(val asOf: LocalDate, val current: SubscriptionMonthSpending, val previous: SubscriptionMonthSpending)

/** 到扣费日才计入；实际记录替换同一计费事件，年费整笔落在对应月份。 */
internal object SubscriptionSpendingCalculator {
    fun initial(record: SubscriptionEntity, changes: List<SubscriptionBillingChange>, zone: ZoneId): SubscriptionExpense {
        val first = changes.firstOrNull()
        val rule = first?.rule ?: SubscriptionBillingRule.from(record)
        val created = first?.date ?: Instant.ofEpochMilli(record.createdAt).atZone(zone).toLocalDate()
        val expiry = if (first == null) record.validUntil else first.validUntil
        val purchase = when {
            !rule.autoRenew && rule.unit == SubscriptionCycleUnit.YEAR && expiry != null -> expiry.minusYears(rule.interval.toLong())
            rule.anchor != null && !rule.anchor.isAfter(created) -> rule.anchor
            rule.autoRenew && rule.next != null -> previous(rule, rule.next)
            expiry != null && rule.unit != SubscriptionCycleUnit.NONE -> previous(rule, expiry)
            else -> created
        }
        val key = if (rule.active && purchase == rule.anchor) SubscriptionForecast.eventId(0) else "purchase:${record.id}"
        return SubscriptionExpense(record.id, record.title, key, purchase, rule.amount, rule.currency)
    }

    private fun previous(rule: SubscriptionBillingRule, date: LocalDate): LocalDate = when (rule.unit) {
        SubscriptionCycleUnit.DAY -> date.minusDays(rule.interval.toLong())
        SubscriptionCycleUnit.WEEK -> date.minusWeeks(rule.interval.toLong())
        SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR -> {
            val months = rule.interval.toLong() * if (rule.unit == SubscriptionCycleUnit.YEAR) 12 else 1
            val month = YearMonth.from(date).minusMonths(months)
            if (rule.monthEnd) month.atEndOfMonth() else month.atDay((rule.anchor?.dayOfMonth ?: date.dayOfMonth).coerceAtMost(month.lengthOfMonth()))
        }
        SubscriptionCycleUnit.NONE -> date
    }

    fun calculate(input: SubscriptionBillingInput, asOf: LocalDate, zone: ZoneId): SubscriptionSpending {
        val currentMonth = YearMonth.from(asOf)
        val months = listOf(currentMonth.minusMonths(1), currentMonth)
        val paid = input.payments.filterNot { it.voided }
        val paidKeys = paid.map { it.subscriptionId to it.billingEventId }.toSet()
        val entries = linkedMapOf<Pair<String, String>, SubscriptionExpense>()
        fun add(entry: SubscriptionExpense) {
            if (!entry.date.isAfter(asOf) && YearMonth.from(entry.date) in months && (entry.subscriptionId to entry.eventId) !in paidKeys)
                entries[entry.subscriptionId to entry.eventId] = entry
        }
        for (record in input.records) {
            val changes = input.changes[record.id].orEmpty()
            add(initial(record, changes, zone))
            for (month in months) for (plan in SubscriptionForecast.events(record, changes, month)) {
                add(SubscriptionExpense(record.id, record.title, plan.eventId, plan.date, plan.amountMinor, plan.currencyCode))
            }
            // 明确登记新一期的非续费服务，只在此次续期月份增加一次。
            changes.filter { it.kind == SubscriptionOperationKind.RENEW_TERM && !it.rule.autoRenew }.forEach { change ->
                add(SubscriptionExpense(record.id, record.title, "term:${change.revision}", change.date, change.rule.amount, change.rule.currency))
            }
        }
        val titles = input.records.associate { it.id to it.title }
        for (payment in paid) if (!payment.paidOn.isAfter(asOf) && YearMonth.from(payment.paidOn) in months) {
            entries[payment.subscriptionId to payment.billingEventId] = SubscriptionExpense(payment.subscriptionId,
                titles[payment.subscriptionId].orEmpty(), payment.billingEventId, payment.paidOn, payment.amountMinor, payment.currencyCode, confirmed = true)
        }
        fun month(value: YearMonth): SubscriptionMonthSpending {
            val rows = entries.values.filter { YearMonth.from(it.date) == value }.sortedWith(compareBy<SubscriptionExpense> { it.date }.thenBy { it.title }.thenBy { it.eventId })
            val cny = rows.fold(BigInteger.ZERO) { total, entry -> total + (SubscriptionExchangeRates.cnyMinor(entry.amountMinor, entry.currencyCode) ?: BigInteger.ZERO) }
            val unconverted = rows.filter { SubscriptionExchangeRates.cnyMinor(it.amountMinor, it.currencyCode) == null }.groupBy { it.currencyCode }
                .map { (currency, values) -> SubscriptionCurrencyTotal(currency, values.fold(BigInteger.ZERO) { total, entry -> total + BigInteger.valueOf(entry.amountMinor) }) }
            return SubscriptionMonthSpending(value, rows, cny, unconverted)
        }
        return SubscriptionSpending(asOf, month(currentMonth), month(currentMonth.minusMonths(1)))
    }
}

class SubscriptionSpendingRepository(private val storage: SubscriptionStorage, private val records: SubscriptionRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val statistics = SubscriptionStatisticsRepository(storage, zone)
    suspend fun summary(asOf: LocalDate): SubscriptionSpending = SubscriptionSpendingCalculator.calculate(statistics.read(), asOf, zone())
    suspend fun initial(id: String): SubscriptionExpense {
        val input = statistics.read(id)
        val record = requireNotNull(input.records.singleOrNull()) { "订阅不存在" }
        val expense = SubscriptionSpendingCalculator.initial(record, input.changes[id].orEmpty(), zone())
        val paid = input.payments.firstOrNull { it.billingEventId == expense.eventId && !it.voided }
        return if (paid == null) expense else expense.copy(date = paid.paidOn, amountMinor = paid.amountMinor, currencyCode = paid.currencyCode, confirmed = true)
    }

    /** 用既有扣费记录保存用户明确修正的日期，与原有周期事件去重，不改周期锚点。 */
    suspend fun correctInitialDate(requestId: String, id: String, revision: Long, date: LocalDate, today: LocalDate) = storage.database.withTransaction {
        require(date.year >= 1900 && !date.isAfter(today)) { "首次扣费日期须为今天或之前" }
        storage.records.operation(requestId)?.takeIf { it.state == SubscriptionOperationState.APPLIED }?.let { receipt ->
            require(receipt.kind in setOf(SubscriptionOperationKind.RECORD_PAYMENT, SubscriptionOperationKind.CORRECT_PAYMENT) &&
                receipt.subscriptionId == id && receipt.expectedRevision == revision) { "日期保存请求已用于其他内容" }
            val json = JSONObject(receipt.payloadJson)
            val values = if (receipt.kind == SubscriptionOperationKind.RECORD_PAYMENT) json
                else json.getJSONObject("request").getJSONObject("values")
            require(values.getString("paidOn") == date.toString()) { "日期保存请求已用于其他内容" }
            return@withTransaction
        }
        val expense = initial(id)
        val existing = storage.records.paymentForEvent(id, expense.eventId)
        if (existing == null) records.recordPayment(requestId, id, revision, expense.eventId, expense.amountMinor, expense.currencyCode, date)
        else records.correctPayment(requestId, id, revision, existing.id, existing.operationId,
            SubscriptionPaymentValues(expense.amountMinor, expense.currencyCode, date, existing.notes))
    }
}
