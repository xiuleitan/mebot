package com.txl.mebot.data.subscriptions

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale

object SubscriptionMoney {
    fun parse(text: String, code: String): Long {
        val scale = Currency.getInstance(code.normalizedCurrency()).defaultFractionDigits
        val clean = text.trim()
        require(Regex("[0-9]+(?:\\.[0-9]+)?").matches(clean)) { "请输入有效金额" }
        return try {
            BigDecimal(clean).setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact()
                .also { require(it >= 0) }
        } catch (_: ArithmeticException) { throw IllegalArgumentException("金额过大或小数位数不符合所选币种") }
    }

    fun editable(amountMinor: Long, code: String): String = BigDecimal.valueOf(amountMinor,
        Currency.getInstance(code.normalizedCurrency()).defaultFractionDigits).toPlainString()

    fun format(amountMinor: Long, code: String): String = format(BigInteger.valueOf(amountMinor), code)
    fun format(amountMinor: BigInteger, code: String): String {
        val currency = code.normalizedCurrency()
        val scale = Currency.getInstance(currency).defaultFractionDigits
        val pattern = "#,##0" + if (scale > 0) "." + "0".repeat(scale) else ""
        val amount = DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).format(BigDecimal(amountMinor, scale))
        val prefix = when (currency) { "CNY" -> "¥"; "USD" -> "US$"; "EUR" -> "€"; "GBP" -> "£"; "JPY" -> "JP¥"; else -> "$currency " }
        return prefix + amount
    }
}
