package com.txl.mebot.ui.subscriptions

import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.SubscriptionMoney
import com.txl.mebot.data.subscriptions.hasExpired
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun LocalDate.subscriptionDate(): String = format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
internal fun LocalDate.subscriptionFullDate(): String = format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
internal fun SubscriptionEntity.price(): String = runCatching { SubscriptionMoney.format(amountMinor, currencyCode) }.getOrDefault("$currencyCode —")
internal fun SubscriptionEntity.cycleText(): String = when {
    cycleUnit == SubscriptionCycleUnit.NONE -> if (validUntil == null) "一次性 / 长期有效" else "一次性"
    cycleUnit == SubscriptionCycleUnit.MONTH && cycleInterval == 1 -> "月付"
    cycleUnit == SubscriptionCycleUnit.MONTH && cycleInterval == 3 -> "季付"
    cycleUnit == SubscriptionCycleUnit.MONTH && cycleInterval == 6 -> "半年付"
    cycleUnit == SubscriptionCycleUnit.YEAR && cycleInterval == 1 -> "年付"
    else -> "每 $cycleInterval " + when (cycleUnit) {
        SubscriptionCycleUnit.DAY -> "天"; SubscriptionCycleUnit.WEEK -> "周"; SubscriptionCycleUnit.MONTH -> "个月"; SubscriptionCycleUnit.YEAR -> "年"; else -> "次"
    }
}
internal fun SubscriptionEntity.priceSuffix(): String = when {
    cycleUnit == SubscriptionCycleUnit.NONE -> "一次性"
    cycleUnit == SubscriptionCycleUnit.MONTH && cycleInterval == 1 -> "/ 月"
    cycleUnit == SubscriptionCycleUnit.MONTH && cycleInterval == 3 -> "/ 季"
    cycleUnit == SubscriptionCycleUnit.MONTH && cycleInterval == 6 -> "/ 半年"
    cycleUnit == SubscriptionCycleUnit.YEAR && cycleInterval == 1 -> "/ 年"
    cycleInterval == 1 -> "/ " + if (cycleUnit == SubscriptionCycleUnit.DAY) "天" else "周"
    else -> "/ " + cycleText().removePrefix("每 ")
}
internal fun SubscriptionEntity.statusText(today: LocalDate = LocalDate.now()): String = if (hasExpired(today) && status != SubscriptionStatus.DELETED) "已到期" else when (status) {
    SubscriptionStatus.ACTIVE -> if (autoRenew) "持续中 · 自动续费" else "持续中 · 不自动续费"
    SubscriptionStatus.CANCEL_AT_PERIOD_END -> "已取消续费"
    SubscriptionStatus.ARCHIVED -> "已结束"
    SubscriptionStatus.DELETED -> "已移出列表，保留历史"
}
internal fun SubscriptionEntity.billingDateText(today: LocalDate = LocalDate.now()): String = when {
    hasExpired(today) -> "已到期"
    status == SubscriptionStatus.CANCEL_AT_PERIOD_END -> "已取消续费"
    autoRenew -> nextBillingDate?.subscriptionFullDate() ?: "未填写"
    else -> "未开启自动续费"
}
internal fun SubscriptionEntity.dateSummary(today: LocalDate = LocalDate.now()): String = when {
    status == SubscriptionStatus.ARCHIVED || status == SubscriptionStatus.DELETED -> statusText(today)
    hasExpired(today) -> "已到期"
    status == SubscriptionStatus.CANCEL_AT_PERIOD_END -> "已取消续费"
    autoRenew && nextBillingDate != null -> "下次扣费 ${nextBillingDate.subscriptionDate()}"
    validUntil != null -> "有效期至 ${validUntil.subscriptionDate()}"
    else -> "长期有效"
}
