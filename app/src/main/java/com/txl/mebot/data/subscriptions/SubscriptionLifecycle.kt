package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** 本地日期维护与用户登记动作；既不扣款，也不替用户操作服务商续费。 */
class SubscriptionLifecycle(private val storage: SubscriptionStorage, private val records: SubscriptionRepository,
    private val clock: Clock = Clock.systemUTC(), private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private fun currentDate(): LocalDate = clock.instant().atZone(zone()).toLocalDate()

    suspend fun reconcile(today: LocalDate = currentDate()) = storage.database.withTransaction {
        for (item in storage.records.all()) {
            if (item.status !in setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCEL_AT_PERIOD_END)) continue
            val values = SubscriptionValues.from(item)
            val next = if (item.autoRenew && item.nextBillingDate?.isBefore(today) == true) {
                SubscriptionDates.onOrAfter(requireNotNull(item.anchorDate), item.cycleUnit, item.cycleInterval, item.anchorAtMonthEnd, today)
            } else item.nextBillingDate
            val updated = values.copy(nextBillingDate = next)
            if (updated != values) records.update("maintenance:${item.id}:${item.revision}:${today.toEpochDay()}",
                item.id, item.revision, updated, kind = SubscriptionOperationKind.ADVANCE)
        }
    }

    suspend fun save(requestId: String, id: String?, revision: Long?, values: SubscriptionValues,
        creationConversationId: String? = null, conversationId: String? = null, sourceMessageId: String? = null): SubscriptionEntity = storage.database.withTransaction {
        require(creationConversationId == null || conversationId == null || creationConversationId == conversationId)
        val sourceConversation = creationConversationId ?: conversationId
        val clean = values.validated()
        val receipt = storage.records.operation(requestId)
        if (receipt?.state == SubscriptionOperationState.APPLIED) {
            if (id == null) return@withTransaction records.create(requestId, clean, sourceConversation, sourceMessageId)
            require(receipt.kind in setOf(SubscriptionOperationKind.UPDATE, SubscriptionOperationKind.RESTORE)) { "请求已用于其他操作" }
            return@withTransaction records.update(requestId, id, requireNotNull(revision), clean, conversationId, sourceMessageId, kind = receipt.kind)
        }
        validateForSave(clean)
        if (id == null) records.create(requestId, clean, sourceConversation, sourceMessageId)
        else {
            val original = requireNotNull(records.get(id)) { "订阅不存在" }
            records.update(requestId, id, requireNotNull(revision), clean, conversationId, sourceMessageId,
                kind = if (original.hasEnded(currentDate())) SubscriptionOperationKind.RESTORE
                    else SubscriptionOperationKind.UPDATE)
        }
    }

    internal fun validateForSave(values: SubscriptionValues): SubscriptionValues {
        val clean = values.validated()
        val today = currentDate()
        require(clean.status == SubscriptionStatus.ACTIVE || clean.status == SubscriptionStatus.CANCEL_AT_PERIOD_END)
        if (clean.autoRenew) {
            val next = requireNotNull(clean.nextBillingDate)
            require(!next.isBefore(today)) { "下次扣费日期不能早于今天" }
            require(SubscriptionDates.onOrAfter(requireNotNull(clean.anchorDate), clean.cycleUnit, clean.cycleInterval, clean.anchorAtMonthEnd, next) == next) {
                "扣费日期与周期规则不一致，请核对日期或月末设置"
            }
        } else if (clean.cycleUnit != SubscriptionCycleUnit.NONE || clean.validUntil != null) {
            require(clean.validUntil != null && !clean.validUntil.isBefore(today)) { "请填写今天或之后的有效期" }
        }
        return clean
    }

    suspend fun stopRenewal(requestId: String, id: String, revision: Long, validUntil: LocalDate): SubscriptionEntity = storage.database.withTransaction {
        completed(requestId, id, revision, SubscriptionOperationKind.STOP_RENEWAL) {
            it.optString("validUntil") == validUntil.toString()
        }?.let { return@withTransaction it }
        val item = requireNotNull(records.get(id)) { "订阅不存在" }
        require(item.status == SubscriptionStatus.ACTIVE && item.autoRenew) { "该订阅没有登记自动续费" }
        records.update(requestId, id, revision, SubscriptionValues.from(item).copy(autoRenew = false,
            status = SubscriptionStatus.CANCEL_AT_PERIOD_END,
            validUntil = validUntil), kind = SubscriptionOperationKind.STOP_RENEWAL)
    }

    suspend fun resumeRenewal(requestId: String, id: String, revision: Long, next: LocalDate): SubscriptionEntity = storage.database.withTransaction {
        completed(requestId, id, revision, SubscriptionOperationKind.RESUME_RENEWAL) {
            it.optString("nextBillingDate") == next.toString()
        }?.let { return@withTransaction it }
        val item = requireNotNull(records.get(id)) { "订阅不存在" }
        require(item.status == SubscriptionStatus.CANCEL_AT_PERIOD_END || item.status == SubscriptionStatus.ACTIVE && !item.autoRenew)
        require(item.cycleUnit != SubscriptionCycleUnit.NONE && !next.isBefore(currentDate())) { "请填写今天或之后的扣费日期" }
        require(!item.anchorAtMonthEnd || next.dayOfMonth == next.lengthOfMonth()) { "已选择月底扣费，请选择当月最后一天" }
        val anchor = item.anchorDate?.takeIf {
            SubscriptionDates.onOrAfter(it, item.cycleUnit, item.cycleInterval, item.anchorAtMonthEnd, next) == next
        } ?: next
        records.update(requestId, id, revision, SubscriptionValues.from(item).copy(autoRenew = true,
            status = SubscriptionStatus.ACTIVE, anchorDate = anchor, nextBillingDate = next, validUntil = null),
            kind = SubscriptionOperationKind.RESUME_RENEWAL)
    }

    suspend fun renewTerm(requestId: String, id: String, revision: Long, end: LocalDate): SubscriptionEntity = storage.database.withTransaction {
        completed(requestId, id, revision, SubscriptionOperationKind.RENEW_TERM) { it.optString("validUntil") == end.toString() }?.let { return@withTransaction it }
        val item = requireNotNull(records.get(id)) { "订阅不存在" }
        saveTerm(requestId, id, revision, SubscriptionValues.from(item).copy(autoRenew = false, status = SubscriptionStatus.ACTIVE, validUntil = end))
    }
    internal suspend fun saveTerm(requestId: String, id: String, revision: Long, values: SubscriptionValues,
        conversationId: String? = null, sourceMessageId: String? = null): SubscriptionEntity = storage.database.withTransaction {
        val clean = values.validated()
        if (storage.records.operation(requestId)?.state != SubscriptionOperationState.APPLIED) {
            val item = requireNotNull(records.get(id)) { "订阅不存在" }
            require(!item.autoRenew && !clean.autoRenew) { "自动续费项目不需要手动登记新一期" }
            require(clean.validUntil != null && item.validUntil != null && clean.validUntil.isAfter(item.validUntil)) { "请填写晚于原有效期的新一期结束日期" }
            validateForSave(clean)
        }
        records.update(requestId, id, revision, clean, conversationId, sourceMessageId, SubscriptionOperationKind.RENEW_TERM)
    }
    suspend fun delete(requestId: String, id: String, revision: Long): SubscriptionEntity = setState(requestId, id, revision,
        SubscriptionStatus.DELETED, SubscriptionOperationKind.DELETE)

    private suspend fun setState(requestId: String, id: String, revision: Long, status: SubscriptionStatus,
        kind: SubscriptionOperationKind): SubscriptionEntity = storage.database.withTransaction {
        completed(requestId, id, revision, kind)?.let { return@withTransaction it }
        val item = requireNotNull(records.get(id)) { "订阅不存在" }
        records.update(requestId, id, revision, SubscriptionValues.from(item).copy(status = status, autoRenew = false), kind = kind)
    }

    private suspend fun completed(requestId: String, id: String, revision: Long, kind: SubscriptionOperationKind,
        matches: (JSONObject) -> Boolean = { true }): SubscriptionEntity? {
        val operation = storage.records.operation(requestId) ?: return null
        require(operation.subscriptionId == id && operation.expectedRevision == revision && operation.kind == kind &&
            operation.conversationId == null && operation.sourceMessageId == null && matches(JSONObject(operation.payloadJson))) { "请求 ID 已用于其他内容" }
        return if (operation.state == SubscriptionOperationState.APPLIED) requireNotNull(records.get(id)) else null
    }
}
