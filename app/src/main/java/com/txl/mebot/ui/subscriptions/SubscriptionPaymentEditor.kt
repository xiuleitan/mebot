package com.txl.mebot.ui.subscriptions

import com.txl.mebot.data.subscriptions.*
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject

internal data class SubscriptionPaymentEditor(
    val subscriptionId: String, val conversationId: String, val key: String, val expectedRevision: Long,
    val requestId: String = UUID.randomUUID().toString(), val paymentId: String? = null,
    val paymentOperationId: String? = null, val plan: SubscriptionPlannedCharge? = null,
    val amount: String, val currency: String, val date: String, val notes: String = "",
) {
    fun values(): SubscriptionPaymentValues {
        val paidOn = try { LocalDate.parse(date.trim()) } catch (_: java.time.DateTimeException) {
            throw IllegalArgumentException("请填写有效的实际扣费日期")
        }
        return SubscriptionPaymentValues(SubscriptionMoney.parse(amount, currency), currency, paidOn, notes).validated()
    }
    fun json() = JSONObject().put("subscriptionId", subscriptionId).put("conversationId", conversationId).put("key", key)
        .put("expectedRevision", expectedRevision).put("requestId", requestId).put("paymentId", paymentId ?: JSONObject.NULL)
        .put("paymentOperationId", paymentOperationId ?: JSONObject.NULL).put("amount", amount).put("currency", currency)
        .put("date", date).put("notes", notes).put("plan", plan?.let {
            JSONObject().put("eventId", it.eventId).put("date", it.date.toString()).put("amountMinor", it.amountMinor).put("currencyCode", it.currencyCode)
        } ?: JSONObject.NULL)
    companion object {
        fun decode(json: JSONObject): SubscriptionPaymentEditor {
            val id = json.getString("subscriptionId")
            fun optional(name: String) = if (json.isNull(name)) null else json.getString(name)
            val plan = json.optJSONObject("plan")?.let { SubscriptionPlannedCharge(id, it.getString("eventId"),
                LocalDate.parse(it.getString("date")), it.getLong("amountMinor"), it.getString("currencyCode")) }
            return SubscriptionPaymentEditor(id, json.getString("conversationId"), json.getString("key"), json.getLong("expectedRevision"),
                json.getString("requestId"), optional("paymentId"), optional("paymentOperationId"), plan,
                json.getString("amount"), json.getString("currency"), json.getString("date"), json.optString("notes"))
        }
    }
}
