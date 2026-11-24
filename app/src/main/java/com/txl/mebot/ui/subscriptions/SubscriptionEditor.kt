package com.txl.mebot.ui.subscriptions

import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 表单输入与保存意图一并入草稿；输入错误或保存失败时不丢原始文字。 */
data class SubscriptionEditor(
    val conversationId: String,
    val targetId: String? = null,
    val expectedRevision: Long? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val original: SubscriptionValues? = null,
    val baselineNext: String = "",
    val title: String = "",
    val amount: String = "",
    val currency: String = "CNY",
    val unit: SubscriptionCycleUnit = SubscriptionCycleUnit.MONTH,
    val interval: String = "1",
    val next: String = "",
    val validUntil: String = "",
    val autoRenew: Boolean = true,
    val monthEnd: Boolean = false,
    val account: String = "",
    val paymentMethod: String = "",
    val notes: String = "",
    val coverAssetId: String? = null,
    val imageAssetIds: List<String>? = null,
    val purchaseDate: String? = null,
    val baselinePurchaseDate: String? = null,
) {
    fun values(): SubscriptionValues {
        fun date(text: String, label: String): LocalDate? = if (text.isBlank()) null else try {
            LocalDate.parse(text.trim()).also { require(it.year in 1900..9999) }
        } catch (_: Exception) { throw IllegalArgumentException("请填写正确的$label，例如 2026-10-31") }
        val recurring = unit != SubscriptionCycleUnit.NONE
        val count = if (!recurring) 0 else interval.trim().toIntOrNull()?.takeIf { it in 1..10_000 }
            ?: throw IllegalArgumentException("周期间隔须为 1 到 10000 的整数")
        val billing = if (recurring && autoRenew) date(next, "扣费日期") ?: throw IllegalArgumentException("请填写下次扣费日期") else null
        val expiry = date(validUntil, "有效期")
        val sameRule = original?.let { it.cycleUnit == unit && it.cycleInterval == count && it.anchorAtMonthEnd == monthEnd } == true
        val sameDate = runCatching { LocalDate.parse(baselineNext.trim()) }.getOrNull() == billing
        val anchor = if (sameRule && (!autoRenew || sameDate)) original?.anchorDate else billing ?: expiry
        return SubscriptionValues(title, SubscriptionMoney.parse(amount, currency), currency, unit, count,
            anchorDate = if (recurring) anchor else null, anchorAtMonthEnd = recurring && monthEnd,
            nextBillingDate = if (recurring && !autoRenew) original?.nextBillingDate else billing,
            validUntil = expiry, autoRenew = recurring && autoRenew,
            status = if (recurring && !autoRenew && (original?.autoRenew == true || original?.status == SubscriptionStatus.CANCEL_AT_PERIOD_END))
                SubscriptionStatus.CANCEL_AT_PERIOD_END else SubscriptionStatus.ACTIVE,
            account = account, paymentMethod = paymentMethod, notes = notes, coverAssetId = coverAssetId, imageAssetIds = imageAssetIds).validated()
    }

    fun json() = JSONObject().apply {
        put("formatVersion", 1); put("conversationId", conversationId); put("targetId", targetId ?: JSONObject.NULL)
        put("expectedRevision", expectedRevision ?: JSONObject.NULL); put("requestId", requestId)
        put("original", original?.json() ?: JSONObject.NULL); put("baselineNext", baselineNext)
        put("title", title); put("amount", amount); put("currency", currency); put("unit", unit.name); put("interval", interval)
        put("next", next); put("validUntil", validUntil); put("autoRenew", autoRenew); put("monthEnd", monthEnd)
        put("account", account); put("paymentMethod", paymentMethod); put("notes", notes)
        put("coverAssetId", coverAssetId ?: JSONObject.NULL)
        imageAssetIds?.let { put("imageAssetIds", JSONArray(it)) }
        purchaseDate?.let { put("purchaseDate", it) }
        baselinePurchaseDate?.let { put("baselinePurchaseDate", it) }
    }

    companion object {
        fun from(item: SubscriptionEntity, conversationId: String, today: LocalDate): SubscriptionEditor {
            val base = SubscriptionValues.from(item)
            val suggested = if (item.cycleUnit != SubscriptionCycleUnit.NONE && item.anchorDate != null)
                SubscriptionDates.onOrAfter(item.anchorDate, item.cycleUnit, item.cycleInterval, item.anchorAtMonthEnd, today) else null
            val next = item.nextBillingDate?.takeUnless { it.isBefore(today) } ?: suggested
            return SubscriptionEditor(conversationId, item.id, item.revision, original = base,
                baselineNext = next?.toString().orEmpty(), title = item.title,
                amount = SubscriptionMoney.editable(item.amountMinor, item.currencyCode), currency = item.currencyCode,
                unit = item.cycleUnit, interval = item.cycleInterval.takeIf { it > 0 }?.toString() ?: "1",
                next = next?.toString().orEmpty(), validUntil = item.validUntil?.toString().orEmpty(),
                autoRenew = item.autoRenew, monthEnd = item.anchorAtMonthEnd, account = item.account.orEmpty(),
                paymentMethod = item.paymentMethod.orEmpty(), notes = item.notes, coverAssetId = item.coverAssetId)
        }

        fun decode(json: JSONObject): SubscriptionEditor {
            require(json.getInt("formatVersion") == 1) { "表单草稿版本无法读取，原内容已保留" }
            return SubscriptionEditor(json.getString("conversationId"),
                if (json.isNull("targetId")) null else json.getString("targetId"),
                if (json.isNull("expectedRevision")) null else json.getLong("expectedRevision"), json.getString("requestId"),
                if (json.isNull("original")) null else SubscriptionValues.decode(json.getJSONObject("original")),
                json.getString("baselineNext"), json.getString("title"), json.getString("amount"), json.getString("currency"),
                SubscriptionCycleUnit.valueOf(json.getString("unit")), json.getString("interval"), json.getString("next"),
                json.getString("validUntil"), json.getBoolean("autoRenew"), json.getBoolean("monthEnd"),
                json.getString("account"), json.getString("paymentMethod"), json.getString("notes"),
                if (json.isNull("coverAssetId")) null else json.getString("coverAssetId"),
                json.optJSONArray("imageAssetIds")?.let { array -> (0 until array.length()).map(array::getString) },
                if (json.isNull("purchaseDate")) null else json.getString("purchaseDate"),
                if (json.isNull("baselinePurchaseDate")) null else json.getString("baselinePurchaseDate"))
        }
    }
}
