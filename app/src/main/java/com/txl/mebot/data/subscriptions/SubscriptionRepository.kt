package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class SubscriptionConflict(message: String = "记录已改变，请重新读取后再保存") : IllegalStateException(message)

/** 只提供本地事务，不调用模型、生成预计扣费流水或调度通知。 */
class SubscriptionRepository(private val storage: SubscriptionStorage, private val clock: Clock = Clock.systemUTC()) {
    private val dao = storage.records
    private val conversations = storage.conversations

    fun observeOngoing() = dao.observeOngoing()
    fun observeAll() = dao.observeAll()
    fun observe(id: String) = dao.observeSubscription(id)
    suspend fun get(id: String) = dao.subscription(id)
    suspend fun imageIds(id: String) = dao.imageIds(id)
    fun observeImageIds(id: String) = dao.observeImageIds(id)
    suspend fun history(id: String, limit: Int = 50, offset: Int = 0) = dao.changes(id, limit.coerceIn(1, 200), offset.coerceAtLeast(0))
    suspend fun historyBefore(id: String, before: Long, limit: Int = 50) = dao.changesBefore(id, before, limit.coerceIn(1, 200))
    suspend fun payments(id: String, limit: Int = 50, offset: Int = 0) = dao.payments(id, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    /** 同一事务写订阅、绑定、快照和执行结果；相同请求重试不会创建第二条。 */
    suspend fun create(requestId: String, values: SubscriptionValues, conversationId: String? = null,
        sourceMessageId: String? = null): SubscriptionEntity = storage.database.withTransaction {
        val clean = values.validated()
        val payload = canonicalSubscriptionJson(clean.json())
        val existing = matchingOperation(requestId, SubscriptionOperationKind.CREATE, payload, conversationId, null, null, sourceMessageId)
        if (existing?.state == SubscriptionOperationState.APPLIED) {
            return@withTransaction requireNotNull(dao.subscription(requireNotNull(existing.resultId)))
        }
        checkConversation(conversationId, null, sourceMessageId)
        validateCover(clean.coverAssetId, conversationId)
        val images = clean.imageAssetIds ?: listOfNotNull(clean.coverAssetId)
        images.forEach { validateCover(it, conversationId) }
        val now = clock.millis()
        val item = clean.entity(UUID.randomUUID().toString(), now, now, 1)
        val operation = existing ?: operation(requestId, conversationId, null, sourceMessageId,
            SubscriptionOperationKind.CREATE, payload, null, now).also { dao.insertOperation(it) }
        dao.insert(item)
        saveImages(item.id, images)
        if (conversationId != null && conversations.bind(conversationId, item.id, now) != 1) throw SubscriptionConflict("会话已绑定其他订阅")
        applied(operation, item, null, item.id, now)
        item
    }

    /** 乐观版本校验；改价、改期、归档等写入保留原始快照。具体生命周期策略由后续业务层调用。 */
    suspend fun update(requestId: String, id: String, expectedRevision: Long, values: SubscriptionValues,
        conversationId: String? = null, sourceMessageId: String? = null,
        kind: SubscriptionOperationKind = SubscriptionOperationKind.UPDATE): SubscriptionEntity = storage.database.withTransaction {
        require(kind !in setOf(SubscriptionOperationKind.CREATE, SubscriptionOperationKind.RECORD_PAYMENT,
            SubscriptionOperationKind.CORRECT_PAYMENT, SubscriptionOperationKind.VOID_PAYMENT))
        val clean = values.validated()
        val payload = canonicalSubscriptionJson(clean.json())
        val existing = matchingOperation(requestId, kind, payload, conversationId, id, expectedRevision, sourceMessageId)
        if (existing?.state == SubscriptionOperationState.APPLIED) return@withTransaction requireNotNull(dao.subscription(id))
        val before = requireNotNull(dao.subscription(id)) { "订阅不存在" }
        if (before.revision != expectedRevision) throw SubscriptionConflict()
        checkConversation(conversationId, id, sourceMessageId)
        val oldImages = dao.imageIds(id)
        val images = clean.imageAssetIds ?: (oldImages.filter { it != before.coverAssetId || clean.coverAssetId != null } + listOfNotNull(clean.coverAssetId)).distinct()
        images.forEach { imageId -> validateCover(imageId, if (imageId in oldImages) null else conversationId) }
        val now = clock.millis()
        val operation = existing ?: operation(requestId, conversationId, id, sourceMessageId,
            kind, payload, expectedRevision, now).also { dao.insertOperation(it) }
        val item = clean.copy(coverAssetId = clean.coverAssetId ?: images.firstOrNull()).entity(id, before.createdAt, now, Math.addExact(before.revision, 1))
        check(dao.update(item) == 1)
        saveImages(id, images)
        applied(operation, item, before, item.id, now, oldImages)
        for (removed in oldImages - images.toSet()) {
            val asset = storage.media.asset(removed)
            if (storage.media.deleteIfUnused(removed) == 1) asset?.relativePath?.let { storage.media.queueCleanup(MediaCleanupEntity(it)) }
        }
        item
    }

    /** 只有调用方明确确认的实际扣费才进入此入口，金额不读取订阅当前价格。 */
    suspend fun recordPayment(requestId: String, id: String, expectedRevision: Long, billingEventId: String,
        amountMinor: Long, currencyCode: String, paidOn: LocalDate, notes: String = "",
        conversationId: String? = null, sourceMessageId: String? = null): SubscriptionPaymentEntity = storage.database.withTransaction {
        requireSubscriptionId(billingEventId)
        require(amountMinor >= 0 && notes.length <= 20_000)
        val currency = currencyCode.normalizedCurrency()
        val payload = canonicalSubscriptionJson(JSONObject().put("billingEventId", billingEventId)
            .put("amountMinor", amountMinor).put("currencyCode", currency).put("paidOn", paidOn.toString()).put("notes", notes))
        val existing = matchingOperation(requestId, SubscriptionOperationKind.RECORD_PAYMENT, payload,
            conversationId, id, expectedRevision, sourceMessageId)
        if (existing?.state == SubscriptionOperationState.APPLIED) return@withTransaction requireNotNull(dao.payment(requireNotNull(existing.resultId)))
        val before = requireNotNull(dao.subscription(id)) { "订阅不存在" }
        if (before.revision != expectedRevision) throw SubscriptionConflict()
        checkConversation(conversationId, id, sourceMessageId)
        val now = clock.millis()
        val operation = existing ?: operation(requestId, conversationId, id, sourceMessageId,
            SubscriptionOperationKind.RECORD_PAYMENT, payload, expectedRevision, now).also { dao.insertOperation(it) }
        val payment = SubscriptionPaymentEntity(UUID.randomUUID().toString(), id, billingEventId, amountMinor, currency, paidOn, notes, requestId, now)
        dao.insertPayment(payment) // 稳定扣费事件唯一索引同时阻止另一个请求重复登记。
        val item = before.copy(updatedAt = now, revision = Math.addExact(before.revision, 1))
        check(dao.update(item) == 1)
        applied(operation, item, before, payment.id, now)
        payment
    }

    suspend fun correctPayment(requestId: String, id: String, expectedRevision: Long, paymentId: String,
        expectedOperationId: String, values: SubscriptionPaymentValues): SubscriptionPaymentEntity =
        mutatePayment(requestId, id, expectedRevision, paymentId, expectedOperationId, values.validated(), false)

    suspend fun voidPayment(requestId: String, id: String, expectedRevision: Long, paymentId: String,
        expectedOperationId: String): SubscriptionPaymentEntity =
        mutatePayment(requestId, id, expectedRevision, paymentId, expectedOperationId, null, true)

    /** 显式更正的原值、新值与流水更新一同提交；普通订阅编辑从不调用此入口。 */
    private suspend fun mutatePayment(requestId: String, id: String, expectedRevision: Long, paymentId: String,
        expectedOperationId: String, values: SubscriptionPaymentValues?, void: Boolean): SubscriptionPaymentEntity = storage.database.withTransaction {
        requireSubscriptionId(requestId); requireSubscriptionId(paymentId); requireSubscriptionId(expectedOperationId)
        val kind = if (void) SubscriptionOperationKind.VOID_PAYMENT else SubscriptionOperationKind.CORRECT_PAYMENT
        val request = JSONObject().put("paymentId", paymentId).put("expectedOperationId", expectedOperationId)
            .put("values", values?.json() ?: JSONObject.NULL)
        val oldReceipt = dao.operation(requestId)
        if (oldReceipt != null) {
            require(oldReceipt.kind == kind && oldReceipt.subscriptionId == id && oldReceipt.expectedRevision == expectedRevision &&
                canonicalSubscriptionJson(JSONObject(oldReceipt.payloadJson).getJSONObject("request")) == canonicalSubscriptionJson(request)) { "请求 ID 已用于其他内容" }
            check(oldReceipt.state == SubscriptionOperationState.APPLIED) { "操作尚未完成，请重新核对" }
            return@withTransaction requireNotNull(dao.payment(paymentId))
        }
        val beforePayment = requireNotNull(dao.payment(paymentId)) { "扣费记录不存在" }
        require(beforePayment.subscriptionId == id) { "扣费记录不属于当前订阅" }
        if (beforePayment.operationId != expectedOperationId) throw SubscriptionConflict("扣费记录已改变，请重新读取")
        val before = requireNotNull(dao.subscription(id)) { "订阅不存在" }
        if (before.revision != expectedRevision) throw SubscriptionConflict()
        val wasVoided = requireNotNull(dao.operation(beforePayment.operationId)).kind == SubscriptionOperationKind.VOID_PAYMENT
        require(!void || !wasVoided) { "这条扣费记录已撤销" }
        val input = values ?: SubscriptionPaymentValues.from(beforePayment)
        val afterPayment = beforePayment.copy(amountMinor = input.amountMinor, currencyCode = input.currencyCode,
            paidOn = input.paidOn, notes = input.notes, operationId = requestId)
        val payload = canonicalSubscriptionJson(JSONObject().put("request", request)
            .put("billingEventId", beforePayment.billingEventId).put("before", beforePayment.paymentSnapshot(wasVoided))
            .put("after", afterPayment.paymentSnapshot(void)))
        val now = clock.millis()
        val op = operation(requestId, null, id, null, kind, payload, expectedRevision, now)
        dao.insertOperation(op)
        if (dao.correctPayment(paymentId, expectedOperationId, input.amountMinor, input.currencyCode,
            input.paidOn, input.notes, requestId) != 1) throw SubscriptionConflict()
        val item = before.copy(revision = Math.addExact(before.revision, 1), updatedAt = now)
        check(dao.update(item) == 1)
        applied(op, item, before, paymentId, now)
        afterPayment
    }

    /** 将确认前的内容保存入库；执行时仍要重新校验版本、绑定与相同请求内容。 */
    suspend fun prepare(requestId: String, kind: SubscriptionOperationKind, payloadJson: String,
        conversationId: String?, subscriptionId: String?, expectedRevision: Long?, sourceMessageId: String? = null): SubscriptionOperationEntity =
        storage.database.withTransaction {
            require(payloadJson.length <= 100_000)
            val payload = canonicalSubscriptionJson(JSONObject(payloadJson))
            matchingOperation(requestId, kind, payload, conversationId, subscriptionId, expectedRevision, sourceMessageId)?.let { return@withTransaction it }
            if (kind == SubscriptionOperationKind.CREATE) require(subscriptionId == null && expectedRevision == null)
            else require(subscriptionId != null && expectedRevision != null)
            checkConversation(conversationId, subscriptionId, sourceMessageId)
            if (subscriptionId != null) {
                val item = requireNotNull(dao.subscription(subscriptionId))
                if (item.revision != expectedRevision) throw SubscriptionConflict()
            }
            operation(requestId, conversationId, subscriptionId, sourceMessageId, kind, payload, expectedRevision, clock.millis())
                .also { dao.insertOperation(it) }
        }

    private suspend fun applied(operation: SubscriptionOperationEntity, item: SubscriptionEntity,
        before: SubscriptionEntity?, resultId: String, now: Long, beforeImages: List<String>? = null) {
        check(dao.updateOperation(operation.copy(subscriptionId = item.id, state = SubscriptionOperationState.APPLIED,
            resultId = resultId, failure = null, updatedAt = now)) == 1)
        val images = dao.imageIds(item.id)
        fun snapshot(record: SubscriptionEntity, ids: List<String>) = canonicalSubscriptionJson(JSONObject(record.snapshot()).put("imageAssetIds", JSONArray(ids)))
        dao.insertChange(SubscriptionChangeEntity(UUID.randomUUID().toString(), item.id, operation.id,
            operation.kind, before?.revision, item.revision, before?.let { snapshot(it, beforeImages ?: images) }, snapshot(item, images),
            before?.coverAssetId, item.coverAssetId, now))
        operation.conversationId?.let { conversations.touch(it, now) }
    }

    private suspend fun saveImages(id: String, images: List<String>) {
        dao.clearImages(id)
        dao.insertImages(images.mapIndexed { index, asset -> SubscriptionImageEntity(id, asset, index.toLong()) })
    }

    private suspend fun matchingOperation(id: String, kind: SubscriptionOperationKind, payload: String,
        conversationId: String?, subscriptionId: String?, expectedRevision: Long?, sourceMessageId: String?): SubscriptionOperationEntity? {
        requireSubscriptionId(id)
        val old = dao.operation(id) ?: return null
        // CREATE 成功后才填入 subscriptionId，重试仍以原创建意图比较。
        require(old.kind == kind && old.payloadJson == payload && old.conversationId == conversationId &&
            (kind == SubscriptionOperationKind.CREATE || old.subscriptionId == subscriptionId) &&
            old.expectedRevision == expectedRevision && old.sourceMessageId == sourceMessageId) { "请求 ID 已用于其他操作" }
        check(old.state in setOf(SubscriptionOperationState.PENDING, SubscriptionOperationState.APPLIED)) { "该操作已取消或失败，请重新确认" }
        return old
    }

    private suspend fun checkConversation(id: String?, subscriptionId: String?, sourceMessageId: String?) {
        require(id != null || sourceMessageId == null)
        if (id == null) return
        val session = requireNotNull(conversations.conversation(id)) { "会话不存在" }
        require(session.subscriptionId == subscriptionId) { "该会话不能操作其他订阅" }
        sourceMessageId?.let {
            val message = requireNotNull(conversations.message(it)) { "原消息不存在" }
            require(message.conversationId == id && message.role == MessageRole.USER) { "操作必须来自当前会话的用户消息" }
        }
    }

    private suspend fun validateCover(id: String?, conversationId: String?) {
        if (id == null) return
        val asset = requireNotNull(storage.media.asset(id)) { "图片不存在" }
        require(asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0) { "封面必须是图片" }
        if (conversationId != null) require(conversations.mediaLinks(conversationId).any { it.assetId == id }) { "图片不属于当前会话" }
    }

    private fun operation(id: String, conversationId: String?, subscriptionId: String?, sourceMessageId: String?,
        kind: SubscriptionOperationKind, payload: String, expectedRevision: Long?, now: Long) =
        SubscriptionOperationEntity(id, conversationId, subscriptionId, sourceMessageId, kind,
            SubscriptionOperationState.PENDING, payload, expectedRevision, createdAt = now, updatedAt = now)
}
