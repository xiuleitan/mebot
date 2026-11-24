package com.txl.mebot.data.subscriptions

import com.txl.mebot.data.local.SubscriptionCycleUnit
import com.txl.mebot.data.local.SubscriptionEntity
import com.txl.mebot.data.local.SubscriptionStatus
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 日期计算留给下一阶段；存储层先保留周期锚点、预计扣费日与有效期各自的含义。 */
data class SubscriptionValues(
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val cycleUnit: SubscriptionCycleUnit,
    val cycleInterval: Int,
    val anchorDate: LocalDate? = null,
    val anchorAtMonthEnd: Boolean = false,
    val nextBillingDate: LocalDate? = null,
    val validUntil: LocalDate? = null,
    val autoRenew: Boolean = false,
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val account: String? = null,
    val paymentMethod: String? = null,
    val notes: String = "",
    val coverAssetId: String? = null,
    /** null 为旧调用：保留图库；非 null 为本次明确保存的完整有序列表。 */
    val imageAssetIds: List<String>? = null,
) {
    fun validated(): SubscriptionValues {
        val images = imageAssetIds?.distinct()?.onEach(::requireSubscriptionId)
        require(images == null || coverAssetId == null || coverAssetId in images) { "封面必须在订阅图片中" }
        val clean = copy(title = title.trim(), currencyCode = currencyCode.normalizedCurrency(),
            account = account?.trim()?.takeIf(String::isNotEmpty),
            paymentMethod = paymentMethod?.trim()?.takeIf(String::isNotEmpty),
            coverAssetId = (coverAssetId ?: images?.firstOrNull())?.also(::requireSubscriptionId), imageAssetIds = images)
        require(clean.title.isNotBlank() && clean.title.length <= 200) { "请填写不超过 200 字的订阅名称" }
        require(amountMinor >= 0) { "金额不能为负数" }
        require((clean.account?.length ?: 0) <= 500 && (clean.paymentMethod?.length ?: 0) <= 200)
        require(notes.length <= 20_000) { "备注过长" }
        if (cycleUnit == SubscriptionCycleUnit.NONE) {
            require(cycleInterval == 0 && nextBillingDate == null && !autoRenew) { "无周期项目不能自动续费或生成下一扣费日" }
        } else {
            require(cycleInterval in 1..10_000 && anchorDate != null) { "请填写周期和起始日期" }
            require(!autoRenew || nextBillingDate != null) { "自动续费需要预计扣费日" }
        }
        require(!anchorAtMonthEnd || cycleUnit in setOf(SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR))
        require(anchorDate == null || nextBillingDate == null || !nextBillingDate.isBefore(anchorDate))
        // 周期锚点可以是未来首次扣费日；试用有效期可能在它之前结束。
        require(status != SubscriptionStatus.CANCEL_AT_PERIOD_END || !autoRenew) { "已登记关闭续费的项目不能仍标记自动续费" }
        return clean
    }

    internal fun entity(id: String, createdAt: Long, updatedAt: Long, revision: Long) = SubscriptionEntity(
        id, title, amountMinor, currencyCode, cycleUnit, cycleInterval, anchorDate, anchorAtMonthEnd,
        nextBillingDate, validUntil, autoRenew, status, account, paymentMethod, notes, coverAssetId,
        createdAt, updatedAt, revision,
    )

    internal fun json() = JSONObject().apply {
        put("formatVersion", 1); put("title", title); put("amountMinor", amountMinor); put("currencyCode", currencyCode)
        put("cycleUnit", cycleUnit.name); put("cycleInterval", cycleInterval)
        put("anchorDate", anchorDate?.toString() ?: JSONObject.NULL); put("anchorAtMonthEnd", anchorAtMonthEnd)
        put("nextBillingDate", nextBillingDate?.toString() ?: JSONObject.NULL)
        put("validUntil", validUntil?.toString() ?: JSONObject.NULL); put("autoRenew", autoRenew)
        put("status", status.name); put("account", account ?: JSONObject.NULL)
        put("paymentMethod", paymentMethod ?: JSONObject.NULL); put("notes", notes)
        put("coverAssetId", coverAssetId ?: JSONObject.NULL)
        imageAssetIds?.let { put("imageAssetIds", JSONArray(it)) }
    }

    companion object {
        fun from(item: SubscriptionEntity) = SubscriptionValues(item.title, item.amountMinor, item.currencyCode,
            item.cycleUnit, item.cycleInterval, item.anchorDate, item.anchorAtMonthEnd, item.nextBillingDate,
            item.validUntil, item.autoRenew, item.status, item.account, item.paymentMethod, item.notes, item.coverAssetId)

        fun decode(json: JSONObject): SubscriptionValues {
            require(json.getInt("formatVersion") == 1) { "不支持的订阅数据格式" }
            fun text(key: String) = if (json.isNull(key)) null else json.getString(key)
            fun date(key: String) = text(key)?.let(LocalDate::parse)
            return SubscriptionValues(json.getString("title"), json.getLong("amountMinor"), json.getString("currencyCode"),
                SubscriptionCycleUnit.valueOf(json.getString("cycleUnit")), json.getInt("cycleInterval"),
                date("anchorDate"), json.getBoolean("anchorAtMonthEnd"), date("nextBillingDate"), date("validUntil"),
                json.getBoolean("autoRenew"), SubscriptionStatus.valueOf(json.getString("status")),
                text("account"), text("paymentMethod"), json.getString("notes"), text("coverAssetId"),
                json.optJSONArray("imageAssetIds")?.let { array -> (0 until array.length()).map(array::getString) }).validated()
        }
    }
}

internal fun requireSubscriptionId(value: String) {
    require(value.isNotBlank() && value == value.trim() && value.length <= 200) { "无效的记录 ID" }
}

internal fun String.normalizedCurrency(): String = trim().uppercase(Locale.ROOT).also {
    val currency = try { Currency.getInstance(it) } catch (_: IllegalArgumentException) { null }
    require(it.length == 3 && currency != null && currency.defaultFractionDigits >= 0) { "请选择有效币种，如 CNY 或 USD" }
}

/** 请求按内容比较，字段顺序变化不会把重试误认为另一次业务；不使用浮点金额。 */
internal fun canonicalSubscriptionJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonicalSubscriptionJson(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalSubscriptionJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Boolean, is Number -> value.toString()
    else -> error("不支持的草稿字段类型")
}

internal fun SubscriptionEntity.snapshot(): String = canonicalSubscriptionJson(SubscriptionValues.from(this).json().apply {
    put("id", id); put("createdAt", createdAt); put("updatedAt", updatedAt); put("revision", revision)
})
