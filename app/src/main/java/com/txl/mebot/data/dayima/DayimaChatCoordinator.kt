package com.txl.mebot.data.dayima

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class DayimaClaim(val message: DayimaChatMessageEntity, val token: String, val context: DayimaAiContext,
    val records: DayimaRecordsSnapshot, val reminders: DayimaReminderSettingsEntity?, val consent: String?,
    val images: List<DayimaMessageImage>, val explicitImageStart: Boolean)

/** 模型请求在事务外；操作、正式业务、回执和助手消息在同一事务提交。 */
class DayimaChatCoordinator(private val database: MebotDatabase, private val records: DayimaRepository,
    private val chats: DayimaConversationRepository, private val interpreter: DayimaTextInterpreter,
    private val clock: Clock = Clock.systemUTC(), private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val dao = database.dayimaDao()

    suspend fun process(sessionId: String, messageId: String) = withContext(Dispatchers.IO) {
        val claim = try { prepare(sessionId, messageId) }
        catch (error: CancellationException) { throw error }
        catch (_: DayimaChatChanged) { throw DayimaChatException(DayimaChatFailure.CHANGED) }
        catch (_: Exception) { throw DayimaChatException(DayimaChatFailure.LOCAL_SAVE) }
            ?: return@withContext
        try {
            if (claim.images.isNotEmpty() && Regex("(?:取消|不要|不)(?:添加|保存)(?:这(?:些|张))?(?:图片|照片)").containsMatchIn(claim.message.content)) {
                finish(claim, DayimaAiDecision.Answer("本次未将图片添加到开始记录，图片仍保留在当前聊天中。"), skipImages = true)
                return@withContext
            }
            if (claim.images.isNotEmpty() && claim.message.content.isBlank()) {
                finish(claim, DayimaAiDecision.Ask("这些图片要放到哪次开始记录？请说明日期；若是今天实际开始，可以说“今天来了”。", DayimaOperationAction.NOTE))
                return@withContext
            }
            var context = claim.context
            var decision: DayimaAiDecision? = null
            for (round in 0..3) {
                database.withTransaction { guard(claim) }
                val next = interpreter.interpret(context)
                if (next !is DayimaAiDecision.Lookup) { decision = next; break }
                if (round == 3 || next.query.kind !in setOf(DayimaQueryKind.LATEST, DayimaQueryKind.STATISTICS, DayimaQueryKind.REMINDERS))
                    throw DayimaChatException(DayimaChatFailure.INVALID_RESULT)
                val result = query(claim, next.query)
                context = context.copy(reads = context.reads + result.modelData)
            }
            val resolved = requireNotNull(decision)
            if (claim.images.isNotEmpty() && !claim.explicitImageStart && resolved is DayimaAiDecision.Write &&
                resolved.mutations.filterIsInstance<DayimaChatMutation.Record>().any { mutation -> claim.records.periods.none { it.startDate == mutation.date } }) {
                finish(claim, DayimaAiDecision.Ask("这天是经期实际开始的日期，还是只想给已有记录添加图片？请说明对应的开始日期。", DayimaOperationAction.RECORD))
                return@withContext
            }
            val plan = if (resolved is DayimaAiDecision.Write) try { prepareDayimaMutations(resolved.mutations, claim.records, claim.context.lastTargets,
                claim.message.id, today(), claim.message.content, claim.consent, claim.reminders) } catch (question: DayimaClarification) {
                finish(claim, DayimaAiDecision.Ask(question.question, resolved.mutations.singleOrNull()?.action()))
                return@withContext
            } else null
            val result = if (resolved is DayimaAiDecision.Query) query(claim, resolved.query) else null
            try { finish(claim, resolved, plan, result) }
            catch (question: DayimaClarification) { finish(claim, DayimaAiDecision.Ask(question.question, DayimaOperationAction.NOTE)) }
        } catch (error: CancellationException) {
            // 退出的界面不重放网络结果；启动恢复会把未完成请求显示为可重试。
            throw error
        } catch (error: Exception) {
            val failure = when (error) {
                is DayimaChatException -> error.failure
                is DayimaChatChanged, is DayimaConflict, is DayimaDateConflict, is DayimaNotFound -> DayimaChatFailure.CHANGED
                else -> DayimaChatFailure.LOCAL_SAVE
            }
            runCatching { database.withTransaction {
                val op = dao.operation(messageId)
                if (op?.attemptId == claim.token && op.state == DayimaOperationState.PENDING && dao.session(sessionId) != null)
                    chats.updateOperation(op, DayimaOperationState.FAILED, failure = failure.name)
            } }
            throw DayimaChatException(failure).apply { if (error is DayimaChatChanged) initCause(error) }
        }
    }

    private suspend fun prepare(sessionId: String, messageId: String): DayimaClaim? = database.withTransaction {
        chats.active(sessionId)
        val message = dao.chatMessage(messageId)?.takeIf { it.sessionId == sessionId && it.role == DayimaMessageRole.USER } ?: throw DayimaChatChanged()
        if (dao.latestUserMessage(sessionId)?.id != messageId) throw DayimaChatChanged()
        val op = dao.operation(messageId) ?: throw DayimaChatChanged()
        if (op.state in setOf(DayimaOperationState.SUCCEEDED, DayimaOperationState.WAITING_FOR_INPUT) || op.state == DayimaOperationState.PENDING && op.attemptId != null) return@withTransaction null
        val token = UUID.randomUUID().toString()
        chats.updateOperation(op, DayimaOperationState.PENDING, question = null, attempt = token)
        val metadata = JSONObject(op.resultJson ?: "{}")
        val sentZone = metadata.optString("zone").takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: zone()
        val source = records.recordsSnapshot()
        val recent = dao.messages(sessionId, 40, message.sequence + 1).asReversed()
        val kept = mutableListOf<DayimaChatMessageEntity>()
        var size = 0
        for (item in recent) {
            val masked = if (item.role == DayimaMessageRole.ASSISTANT && item.id.startsWith("dayima:result:"))
                item.copy(content = "本地操作或查询结果已在手机展示；需要具体数字时请按需查询。") else item
            if (size + masked.content.length > 24_000 || kept.size >= 30) break
            kept += masked; size += masked.content.length
        }
        var targets: List<DayimaChatTarget> = emptyList()
        for (previous in dao.recentChatOperations(sessionId, 60)) {
            if (previous.state != DayimaOperationState.SUCCEEDED || previous.action == DayimaOperationAction.CONSULT) continue
            val result = runCatching { JSONObject(previous.resultJson ?: "{}") }.getOrNull() ?: continue
            if (result.has("targets")) { targets = result.getJSONArray("targets").targets(); break }
        }
        val parent = metadata.optString("clarification_of").takeIf { it.isNotBlank() }?.let { id ->
            dao.chatMessage(id)?.takeIf { it.sessionId == sessionId && it.role == DayimaMessageRole.USER }
        }
        val sourceIds = metadata.optJSONArray("media_sources")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.distinct()
        }.orEmpty()
        val imageIntents = mutableListOf(message.content, parent?.content.orEmpty())
        for (id in sourceIds) {
            val origin = dao.chatMessage(id) ?: throw DayimaChatChanged()
            if (origin.sessionId != sessionId || origin.role != DayimaMessageRole.USER || origin.sequence > message.sequence) throw DayimaChatChanged()
            imageIntents += origin.content
        }
        val images = database.dayimaMediaDao().messageImages(sessionId, sourceIds).distinctBy { it.asset.id }
        DayimaClaim(message, token, DayimaAiContext(Instant.ofEpochMilli(message.createdAt).atZone(sentZone), kept.asReversed(), targets,
            historyTrimmed = kept.size < recent.size || recent.size == 40, pendingImageCount = images.size), source, dao.reminderSettings(), parent?.content, images,
            imageIntents.any { Regex("来了|开始|第一天|补记|来(?:了)?(?:大姨妈|姨妈|例假|月经)").containsMatchIn(it) })
    }

    private suspend fun query(claim: DayimaClaim, query: DayimaChatQuery): DayimaQueryResult {
        database.withTransaction { guard(claim) }
        val notes = if (query.kind == DayimaQueryKind.NOTES) database.withTransaction {
            guard(claim)
            dao.chatNotes(query.range?.from ?: LocalDate.MIN, query.range?.through ?: today())
        } else emptyList()
        return withContext(Dispatchers.Default) { evaluateDayimaQuery(query, claim.records, today(), claim.reminders, notes) }
    }

    private suspend fun finish(claim: DayimaClaim, decision: DayimaAiDecision,
        plan: List<DayimaPreparedMutation>? = null, query: DayimaQueryResult? = null, skipImages: Boolean = false) = database.withTransaction {
        val op = guard(claim)
        val metadata = JSONObject(op.resultJson ?: "{}")
        if (!skipImages && claim.images.isNotEmpty() && decision !is DayimaAiDecision.Ask && decision !is DayimaAiDecision.Write)
            throw DayimaClarification("图片要添加到哪次开始记录？请说明具体日期，或明确取消添加图片。")
        if (skipImages) metadata.remove("media_sources")
        val (reply, action, targets) = when (decision) {
            is DayimaAiDecision.Ask -> Triple(decision.question, decision.hint, emptyList())
            is DayimaAiDecision.Answer -> Triple(decision.text, DayimaOperationAction.CONSULT, emptyList())
            is DayimaAiDecision.Query -> Triple(requireNotNull(query).text, DayimaOperationAction.QUERY, query.targets)
            is DayimaAiDecision.Write -> {
                if (plan?.any { it.imageTarget != null } == true && claim.images.isEmpty())
                    throw DayimaClarification("请先通过加号选择要添加的图片，再说明对应哪次开始记录。")
                val outcomes = requireNotNull(plan).map { it.apply(records, dao, clock.millis()) }
                val targets = outcomes.flatMap { it.targets }.distinctBy { it.id }
                val replies = outcomes.map { it.text }.filter(String::isNotBlank).toMutableList()
                if (claim.images.isNotEmpty()) {
                    val candidates = mutableListOf<DayimaPeriodEntity>()
                    for (target in targets) dao.period(target.id)?.let { candidates += it }
                    if (candidates.size != 1) throw DayimaClarification("这些图片需要关联到唯一的一次开始记录，请说明具体日期；本次操作尚未保存。")
                    val target = candidates.single()
                    val media = database.dayimaMediaDao()
                    for (image in claim.images) media.insertPeriod(DayimaPeriodMediaEntity(target.id, image.asset.id,
                        image.displayName, media.nextPeriodPosition(target.id), image.messageId, clock.millis()))
                    metadata.put("media_bound_period", target.id)
                    replies += "${claim.images.size} 张图片已保存在 ${target.startDate} 的记录中。"
                }
                Triple(replies.joinToString("\n"), if (decision.mutations.size == 1) decision.mutations.single().action() else DayimaOperationAction.BATCH, targets)
            }
            is DayimaAiDecision.Lookup -> throw DayimaChatException(DayimaChatFailure.INVALID_RESULT)
        }
        require(reply.isNotBlank() && reply.length <= 24_000)
        if (decision is DayimaAiDecision.Write || decision is DayimaAiDecision.Query) metadata.put("targets", JSONArray(targets.map { it.json() }))
        metadata.put("reply", reply)
        val prefix = when (decision) { is DayimaAiDecision.Answer -> "consult"; is DayimaAiDecision.Ask -> "ask"; else -> "result" }
        dao.insertMessage(DayimaChatMessageEntity("dayima:$prefix:${claim.message.id}", claim.message.sessionId,
            dao.lastMessageSequence(claim.message.sessionId) + 1, DayimaMessageRole.ASSISTANT, reply, clock.millis()))
        chats.updateOperation(op, if (decision is DayimaAiDecision.Ask) DayimaOperationState.WAITING_FOR_INPUT else DayimaOperationState.SUCCEEDED,
            action = action, question = (decision as? DayimaAiDecision.Ask)?.question, result = metadata.toString())
    }

    private suspend fun guard(claim: DayimaClaim): DayimaChatOperationEntity {
        chats.active(claim.message.sessionId)
        val op = dao.operation(claim.message.id) ?: throw DayimaChatChanged()
        if (op.state != DayimaOperationState.PENDING) throw DayimaChatChanged("operation state changed")
        if (op.attemptId != claim.token) throw DayimaChatChanged("attempt changed")
        if (dao.latestUserMessage(claim.message.sessionId)?.id != claim.message.id) throw DayimaChatChanged("latest message changed")
        if (database.dayimaWriteDao().dataVersion() != claim.records.dataVersion) throw DayimaChatChanged("business version changed")
        val currentReminderRevision = dao.reminderSettings()?.revision ?: 0L
        val expectedReminderRevision = claim.reminders?.revision ?: 0L
        if (currentReminderRevision != expectedReminderRevision) throw DayimaChatChanged("reminder version changed")
        return op
    }
    private fun today() = clock.instant().atZone(zone()).toLocalDate()
}

internal fun DayimaChatTarget.json() = JSONObject().put("id", id).put("date", date.toString()).put("revision", revision)
private fun JSONArray.targets(): List<DayimaChatTarget> = (0 until length()).map { i -> getJSONObject(i).let {
    DayimaChatTarget(it.getString("id"), LocalDate.parse(it.getString("date")), it.getLong("revision"))
} }
