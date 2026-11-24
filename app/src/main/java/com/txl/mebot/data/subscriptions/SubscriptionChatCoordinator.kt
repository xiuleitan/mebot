package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.io.File
import java.time.Clock
import java.time.ZoneId
import org.json.JSONObject

internal data class SubscriptionChatMeta(val analysisId: String? = null, val status: String? = null,
    val failure: SubscriptionAnalysisFailure? = null, val proposalId: String? = null) {
    fun json() = JSONObject().put("analysisId", analysisId ?: JSONObject.NULL).put("status", status ?: JSONObject.NULL)
        .put("failure", failure?.name ?: JSONObject.NULL).put("proposalId", proposalId ?: JSONObject.NULL)
    companion object {
        fun read(fields: String): SubscriptionChatMeta {
            val json = JSONObject(fields).optJSONObject("chat") ?: return SubscriptionChatMeta()
            fun text(key: String) = if (json.isNull(key)) null else json.getString(key)
            return SubscriptionChatMeta(text("analysisId"), text("status"), text("failure")?.let(SubscriptionAnalysisFailure::valueOf), text("proposalId"))
        }
    }
}
internal data class SubscriptionChatProposal(val operation: SubscriptionOperationEntity, val values: SubscriptionValues)

/** 模型只能提出当前会话的字段；确认、保存、去重及执行回复由本地事务完成。 */
internal class SubscriptionChatCoordinator(private val storage: SubscriptionStorage, private val records: SubscriptionRepository,
    private val conversations: SubscriptionConversationRepository, private val lifecycle: SubscriptionLifecycle,
    private val interpreter: SubscriptionInterpreter, private val fileFor: suspend (String) -> File?,
    private val imageData: suspend (File) -> String = ::subscriptionImageData,
    private val clock: Clock = Clock.systemUTC(), private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val dao = storage.conversations

    suspend fun send(id: String, messageId: String, input: String, assetIds: List<String>, revision: Long, inputToken: String? = null): Boolean = storage.database.withTransaction {
        require(input.length <= 5_000 && assetIds.size <= 4) { "每次最多发送5000字和4张截图" }
        val repeated = dao.message(messageId)
        if (repeated != null) {
            conversations.sendUserMessage(id, messageId, input, assetIds, revision)
            return@withTransaction SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson).analysisId == messageId
        }
        val before = SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson)
        conversations.sendUserMessage(id, messageId, input, assetIds, revision)
        val cleared = requireNotNull(dao.draft(id))
        val fields = JSONObject(cleared.fieldsJson).apply { remove("chatInputToken"); put("chatLastSentToken", inputToken ?: JSONObject.NULL) }
        conversations.saveDraft(id, cleared.inputText, fields.toString(), cleared.revision)
        if (assetIds.isEmpty() && input.trim().trimEnd('。', '！', '!') in setOf("确认", "确认创建", "确认保存", "确认修改") && before.proposalId != null) {
            confirm(id, before.proposalId)
            return@withTransaction false
        }
        before.proposalId?.let { cancelOperation(it) }
        writeMeta(id, SubscriptionChatMeta(messageId, "pending"))
        true
    }

    suspend fun analyze(id: String, messageId: String) {
        val input = storage.database.withTransaction {
            val draft = requireNotNull(dao.draft(id))
            val meta = SubscriptionChatMeta.read(draft.fieldsJson)
            if (meta.analysisId != messageId || meta.status != "pending" || dao.latestUserMessage(id)?.id != messageId) return@withTransaction null
            val session = requireNotNull(dao.conversation(id))
            val current = session.subscriptionId?.let { records.get(it) }
            val recent = dao.messages(id, 40).asReversed()
            val first = dao.firstMessage(id)
            val required = listOfNotNull(first, recent.lastOrNull()).distinctBy { it.id }
            var length = required.sumOf { it.content.length }
            val kept = required.toMutableList()
            for (message in recent.asReversed()) if (kept.none { it.id == message.id } && length + message.content.length <= 24_000) {
                kept += message; length += message.content.length
            }
            val messages = kept.sortedBy { it.sequence }
            val order = messages.associate { it.id to it.sequence }
            val images = dao.messageMediaFor(id, messages.map { it.id })
                .sortedWith(compareBy<SubscriptionMessageMediaEntity> { order[it.messageId] }.thenBy { it.position })
                .asReversed().distinctBy { it.assetId }.take(4).asReversed()
            AnalysisInput(current, messages, images, clock.instant().atZone(zone()), kept.size < recent.size || recent.size == 40)
        } ?: return
        var omittedImage = false
        val images = input.images.mapNotNull { link ->
            try {
                val file = fileFor(link.assetId) ?: throw SubscriptionAnalysisException(SubscriptionAnalysisFailure.IMAGE)
                SubscriptionAnalysisImage(link.assetId, link.messageId, imageData(file))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (link.messageId == messageId) throw SubscriptionAnalysisException(SubscriptionAnalysisFailure.IMAGE)
                omittedImage = true; null
            }
        }
        val context = SubscriptionAnalysisContext(input.now, input.messages, input.current, images, input.trimmed || omittedImage)
        val interpretation = interpreter.interpret(context)
        storage.database.withTransaction {
            val meta = SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson)
            if (meta.analysisId != messageId || meta.status != "pending") return@withTransaction
            val session = requireNotNull(dao.conversation(id))
            val current = session.subscriptionId?.let { records.get(it) }
            if (dao.latestUserMessage(id)?.id != messageId || current?.id != input.current?.id || current?.revision != input.current?.revision || zone() != input.now.zone)
                throw SubscriptionAnalysisException(SubscriptionAnalysisFailure.CHANGED)
            val replyId = "subscription-ai:$messageId"
            when (interpretation) {
                is SubscriptionInterpretation.Ask -> {
                    require(interpretation.question.isNotBlank() && interpretation.question.length <= 600)
                    conversations.appendAssistantMessage(id, replyId, interpretation.question)
                    writeMeta(id, SubscriptionChatMeta())
                }
                is SubscriptionInterpretation.Reject -> {
                    val reply = when (interpretation.reason) {
                        "MULTIPLE_SUBSCRIPTIONS" -> "一个会话只管理一个订阅。请先说明这次要登记哪一个，其他订阅请新建会话。"
                        "QUERY" -> current?.let { "当前订阅：${it.title}，${SubscriptionMoney.format(it.amountMinor, it.currencyCode)}。完整信息可在卡片详情查看。" }
                            ?: "这个会话还没有创建订阅。请发送订阅的文字或截图。"
                        else -> "这里用于登记当前这一项订阅。请发送服务名称、费用、周期和日期，也可以发送订阅截图。"
                    }
                    conversations.appendAssistantMessage(id, replyId, reply)
                    writeMeta(id, SubscriptionChatMeta())
                }
                is SubscriptionInterpretation.Propose -> {
                    val renewTerm = current != null && !current.autoRenew && !interpretation.values.autoRenew && current.validUntil != null &&
                        interpretation.values.validUntil?.isAfter(current.validUntil) == true && explicitTermRenewal(input.messages.last().content)
                    if (current?.hasEnded(input.now.toLocalDate()) == true &&
                        !renewTerm && !Regex("恢复|重新启用|重新订阅|继续使用").containsMatchIn(input.messages.last().content)) {
                        conversations.appendAssistantMessage(id, replyId, "这项订阅已结束。若要恢复，请明确说恢复订阅并核对下一次扣费日期或有效期；历史和原记录会继续保留。")
                        writeMeta(id, SubscriptionChatMeta()); return@withTransaction
                    }
                    if (current != null && interpretation.values.title.trim() != current.title &&
                        !Regex("改名|名称|标题|名字|更名|叫作|叫做").containsMatchIn(input.messages.last().content)) {
                        conversations.appendAssistantMessage(id, replyId, "这个会话只管理「${current.title}」。若是另一个订阅，请新建会话；若要更名，请明确告诉我修改名称。")
                        writeMeta(id, SubscriptionChatMeta()); return@withTransaction
                    }
                    val unavailable = input.images.map { it.assetId }.toSet() - images.map { it.assetId }.toSet()
                    val gallery = ((current?.let { records.imageIds(it.id) } ?: emptyList()) + dao.newImageIds(id).filterNot { it in unavailable }).distinct()
                    val cover = current?.coverAssetId ?: gallery.firstOrNull()
                    val cancelled = current != null && (current.autoRenew || current.status == SubscriptionStatus.CANCEL_AT_PERIOD_END) &&
                        !interpretation.values.autoRenew && interpretation.values.cycleUnit != SubscriptionCycleUnit.NONE
                    val values = try { lifecycle.validateForSave(interpretation.values.copy(coverAssetId = cover, imageAssetIds = gallery,
                        status = if (cancelled) SubscriptionStatus.CANCEL_AT_PERIOD_END else interpretation.values.status)) }
                    catch (failure: IllegalArgumentException) {
                        conversations.appendAssistantMessage(id, replyId, (failure.message ?: "订阅信息还不完整") + "。请在聊天里补充或更正。")
                        writeMeta(id, SubscriptionChatMeta()); return@withTransaction
                    }
                    val kind = when {
                        renewTerm -> SubscriptionOperationKind.RENEW_TERM
                        current == null -> SubscriptionOperationKind.CREATE
                        current.hasEnded(input.now.toLocalDate()) -> SubscriptionOperationKind.RESTORE
                        else -> SubscriptionOperationKind.UPDATE
                    }
                    val operation = records.prepare("subscription-chat:$messageId", kind, values.json().toString(), id, current?.id, current?.revision, messageId)
                    conversations.appendAssistantMessage(id, replyId, "订阅信息已整理，请核对下方内容后确认${if (current == null) "创建" else "保存"}。需要调整时直接继续说。")
                    writeMeta(id, SubscriptionChatMeta(proposalId = operation.id))
                }
            }
        }
    }

    suspend fun retry(id: String): String? = storage.database.withTransaction {
        val meta = SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson)
        val messageId = meta.analysisId ?: return@withTransaction null
        if (dao.latestUserMessage(id)?.id != messageId) throw SubscriptionConflict()
        writeMeta(id, meta.copy(status = "pending", failure = null)); messageId
    }
    suspend fun fail(id: String, messageId: String, failure: SubscriptionAnalysisFailure) = storage.database.withTransaction {
        val meta = SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson)
        if (meta.analysisId == messageId) writeMeta(id, meta.copy(status = "failed", failure = failure))
    }
    suspend fun confirm(id: String, operationId: String): SubscriptionEntity = storage.database.withTransaction {
        val operation = requireNotNull(storage.records.operation(operationId))
        require(operation.conversationId == id && operation.kind in setOf(SubscriptionOperationKind.CREATE, SubscriptionOperationKind.UPDATE, SubscriptionOperationKind.RESTORE, SubscriptionOperationKind.RENEW_TERM))
        if (operation.state == SubscriptionOperationState.APPLIED) return@withTransaction requireNotNull(records.get(requireNotNull(operation.subscriptionId)))
        val meta = SubscriptionChatMeta.read(requireNotNull(dao.draft(id)).fieldsJson)
        if (meta.proposalId != operationId || operation.state != SubscriptionOperationState.PENDING) throw SubscriptionConflict("待确认内容已改变，请重新核对")
        val session = requireNotNull(dao.conversation(id))
        if (session.subscriptionId != operation.subscriptionId) throw SubscriptionConflict()
        val values = SubscriptionValues.decode(JSONObject(operation.payloadJson))
        val result = if (operation.kind == SubscriptionOperationKind.RENEW_TERM) lifecycle.saveTerm(operation.id, requireNotNull(operation.subscriptionId), requireNotNull(operation.expectedRevision), values, id, operation.sourceMessageId)
            else lifecycle.save(operation.id, operation.subscriptionId, operation.expectedRevision, values, conversationId = id, sourceMessageId = operation.sourceMessageId)
        conversations.appendAssistantMessage(id, "subscription-saved:${operation.id}", "已${if (operation.kind == SubscriptionOperationKind.CREATE) "创建" else "保存"}「${result.title}」。\n" +
            SubscriptionMoney.format(result.amountMinor, result.currencyCode) + (result.nextBillingDate?.let { " · 下次预计扣费 $it" } ?: result.validUntil?.let { " · 有效期至 $it" } ?: ""))
        writeMeta(id, SubscriptionChatMeta())
        result
    }
    private suspend fun cancelOperation(id: String) {
        val operation = storage.records.operation(id) ?: return
        if (operation.state == SubscriptionOperationState.PENDING) storage.records.updateOperation(operation.copy(state = SubscriptionOperationState.CANCELLED, updatedAt = clock.millis()))
    }
    private suspend fun writeMeta(id: String, value: SubscriptionChatMeta) {
        val draft = requireNotNull(dao.draft(id))
        val json = JSONObject(draft.fieldsJson).put("chat", value.json())
        conversations.saveDraft(id, draft.inputText, json.toString(), draft.revision)
    }
    private data class AnalysisInput(val current: SubscriptionEntity?, val messages: List<SubscriptionMessageEntity>,
        val images: List<SubscriptionMessageMediaEntity>, val now: java.time.ZonedDateTime, val trimmed: Boolean)
}

private fun explicitTermRenewal(text: String): Boolean =
    !Regex("不要|没有|没续|尚未|暂不|怎么|如何|能否|是否").containsMatchIn(text) &&
        Regex("已(?:经)?续|续了|已续期|新一期|完成续期|续期成功|续费成功").containsMatchIn(text)
