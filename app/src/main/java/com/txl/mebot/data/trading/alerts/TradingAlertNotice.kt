package com.txl.mebot.data.trading.alerts

import com.txl.mebot.data.local.TradingNewsRecord
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.local.TradingPriceAlertEvent
import com.txl.mebot.data.trading.PositionAlertChoice
import com.txl.mebot.data.trading.TRADING_QUOTE_ASSET
import com.txl.mebot.data.trading.news.TradingPriceAlertNewsChannel
import com.txl.mebot.data.trading.news.TradingPriceAlertNewsOwnerPrefix

internal data class TradingAlertNotice(val tag: String, val title: String, val headline: String = title)
internal const val LegacyPriceAlertDetailSeparator = " · "

internal fun TradingPriceAlertEvent.newsRecord(notice: TradingAlertNotice, unread: Boolean) = TradingNewsRecord(
    deviceId = "$TradingPriceAlertNewsOwnerPrefix$deviceId",
    channel = TradingPriceAlertNewsChannel,
    newsId = "$instanceId:$eventId",
    title = notice.headline,
    publishedAt = triggeredAt,
    url = null,
    noticeClaimed = true,
    unread = unread,
)

internal data class ProbeTarget(val price: String, val direction: String)

private val ProbeTargetPrice = Regex("""  (涨到|跌到) (\S+)  参考价 """)
private val LegacyTargetPrice = Regex("""^(?:目标价|止损价|预期价) (\S+) USDT""")
private val WindowDetail = Regex("""^(?:过去 )?(\d+) 分钟(?:上涨|下跌|阈值) (\d+(?:\.\d+)?)(%)?""")
private val LadderDetail = Regex("""^(?:涨过|跌过) (\d+(?:\.\d+)?)""")

internal fun TradingPriceAlertEvent.probeTarget(message: String?): ProbeTarget? {
    if (kind != TradingAlertKind.TARGET.value) return null
    val text = message.probeText()?.takeIf { it.startsWith("[目标价提醒] $ruleId  ") } ?: return null
    val match = ProbeTargetPrice.find(text) ?: return null
    val price = alertDecimal(match.groupValues[2]) ?: return null
    return ProbeTarget(price, if (match.groupValues[1] == "涨到") "up" else "down")
}

internal fun TradingPriceAlertEvent.notification(
    rule: TradingPriceAlert?,
    message: String?,
    target: ProbeTarget?,
    previousHeadline: String? = null,
): TradingAlertNotice {
    val tag = "$instanceId:$ruleId"
    val asset = symbol.removeSuffix(TRADING_QUOTE_ASSET)
    val previousDetail = previousHeadline?.substringAfter(LegacyPriceAlertDetailSeparator, "")
    when (kind) {
        TradingAlertKind.WINDOW.value -> {
            val detail = probeDetail(message, "[窗口提醒 $ruleId]") ?: previousDetail
            val window = rule?.configuredWindow() ?: detail?.observedWindow()
            val title = window?.let {
                val threshold = if (it.percent) "${it.amount}%" else "${it.amount} USDT"
                val label = if (it.percent) "涨跌幅提醒" else "涨跌价差提醒"
                "$asset $threshold/${it.minutes}min $label"
            } ?: "$asset 价格涨跌提醒"
            return TradingAlertNotice(tag, title)
        }
        TradingAlertKind.LADDER.value -> {
            val detail = probeDetail(message, "[阶梯提醒 $ruleId]") ?: previousDetail
            val level = detail?.let(LadderDetail::find)?.groupValues?.get(1).alertPrice()
            return TradingAlertNotice(tag, alertWithValue(asset, level, "阶梯价位提醒"))
        }
    }

    val legacyPrice = previousDetail?.let(LegacyTargetPrice::find)?.groupValues?.get(1)
    val price = (rule?.threshold ?: target?.price ?: legacyPrice).alertPrice()
    val choice = rule?.positionAlertChoice
    val multiple = choice?.multiple
    return when {
        choice == PositionAlertChoice.STOP -> TradingAlertNotice(
            tag, alertWithValue(asset, price, "止损价提醒"), reachedPrice(asset, "止损价", price),
        )
        multiple != null -> TradingAlertNotice(
            tag, alertWithValue(asset, price, "盈亏 ${multiple}R 提醒"), reachedPrice(asset, "盈亏 ${multiple}R 目标价", price),
        )
        rule?.isOpeningOpportunity == true || previousHeadline?.startsWith("$symbol 开单机会$LegacyPriceAlertDetailSeparator") == true ->
            TradingAlertNotice(tag, alertWithValue(asset, price, "开单机会"))
        else -> TradingAlertNotice(tag, alertWithValue(asset, price, "价格提醒"), reachedPrice(asset, "目标价", price))
    }
}

private data class AlertWindow(val minutes: Int, val amount: String, val percent: Boolean)

private fun TradingPriceAlert.configuredWindow(): AlertWindow? {
    val duration = minutes?.takeIf { it > 0 } ?: return null
    val amount = alertDecimal(threshold) ?: return null
    if (unit != "percent" && unit != "absolute") return null
    return AlertWindow(duration, amount, unit == "percent")
}

private fun String.observedWindow(): AlertWindow? {
    val match = WindowDetail.find(this) ?: return null
    val duration = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val amount = alertDecimal(match.groupValues[2]) ?: return null
    return AlertWindow(duration, amount, match.groupValues[3] == "%")
}

private fun String?.alertPrice(): String? {
    val price = this?.let(::alertDecimal)?.toBigDecimal() ?: return null
    return price.setScale(maxOf(2, price.scale())).toPlainString()
}

private fun alertWithValue(asset: String, value: String?, label: String) = listOfNotNull(asset, value, label).joinToString(" ")

private fun reachedPrice(asset: String, label: String, price: String?) =
    if (price == null) "$asset ${label}已达到" else "$asset $label $price 已达到"

private fun TradingPriceAlertEvent.probeDetail(message: String?, prefix: String): String? =
    message.probeText()?.takeIf { it.startsWith("$prefix ") }
        ?.removePrefix("$prefix ")?.removePrefix("$symbol ")?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.probeText(): String? =
    this?.takeIf { it.length <= 1024 && it.none { char -> char.isISOControl() || char.category == CharCategory.FORMAT } }?.trim()
