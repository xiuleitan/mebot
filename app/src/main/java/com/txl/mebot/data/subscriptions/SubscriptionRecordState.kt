package com.txl.mebot.data.subscriptions

import com.txl.mebot.data.local.SubscriptionEntity
import com.txl.mebot.data.local.SubscriptionStatus
import java.time.LocalDate

/** 到期由日期派生，不再通过归档操作改写原记录；旧版结束状态继续可读。 */
fun SubscriptionEntity.hasExpired(today: LocalDate): Boolean = !autoRenew && validUntil?.isBefore(today) == true

fun SubscriptionEntity.hasEnded(today: LocalDate): Boolean =
    status in setOf(SubscriptionStatus.ARCHIVED, SubscriptionStatus.DELETED) || hasExpired(today)
