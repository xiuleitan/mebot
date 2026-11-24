package com.txl.mebot.data.subscriptions

import com.txl.mebot.data.local.SubscriptionPaymentEntity
import java.time.LocalDate
import org.json.JSONObject

data class SubscriptionPaymentValues(val amountMinor: Long, val currencyCode: String, val paidOn: LocalDate, val notes: String = "") {
    fun validated(): SubscriptionPaymentValues {
        require(amountMinor >= 0 && notes.length <= 20_000) { "请核对金额和备注长度" }
        return copy(currencyCode = currencyCode.normalizedCurrency())
    }
    internal fun json() = JSONObject().put("amountMinor", amountMinor).put("currencyCode", currencyCode)
        .put("paidOn", paidOn.toString()).put("notes", notes)
    companion object {
        fun from(item: SubscriptionPaymentEntity) = SubscriptionPaymentValues(item.amountMinor, item.currencyCode, item.paidOn, item.notes)
        fun decode(json: JSONObject) = SubscriptionPaymentValues(json.getLong("amountMinor"), json.getString("currencyCode"),
            LocalDate.parse(json.getString("paidOn")), json.optString("notes")).validated()
    }
}

internal fun SubscriptionPaymentEntity.paymentSnapshot(voided: Boolean) = SubscriptionPaymentValues.from(this).json().apply {
    put("id", id); put("subscriptionId", subscriptionId); put("billingEventId", billingEventId)
    put("operationId", operationId); put("confirmedAt", confirmedAt); put("voided", voided)
}
