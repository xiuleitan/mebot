package com.txl.mebot.data.trading.alerts

import androidx.room.withTransaction
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.TradingAlertConnection
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.local.TradingPriceAlertEvent
import com.txl.mebot.data.models.CredentialCipher
import com.txl.mebot.data.models.EncryptedCredential
import com.txl.mebot.data.trading.PositionAlertChoice
import com.txl.mebot.data.trading.TradingPositionDetails
import com.txl.mebot.data.trading.alertTarget
import com.txl.mebot.data.trading.normalizeUsdtSymbol
import com.txl.mebot.data.trading.news.TradingNewsBatch
import com.txl.mebot.data.trading.news.tradingNewsDay
import com.txl.mebot.data.trading.parsePositionPlanPrice
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

internal class TradingAlertsRepository(
    private val database: MebotDatabase,
    private val cipher: CredentialCipher,
    private val packageName: String,
    private val client: (HttpUrl) -> OkHttpClient,
    private val deviceToken: suspend () -> String = ::firebaseAlertToken,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.tradingAlerts()
    private val operations = Mutex()
    private val pushCheck = TradingPushCheck()

    val endpoint = dao.observeEndpoint().distinctUntilChanged()
    fun hasActiveAlerts(symbol: String) = dao.observeHasActiveAlerts(symbol).distinctUntilChanged()
    fun rules(symbol: String) = dao.observeRules(symbol).distinctUntilChanged()
    fun openRules(symbol: String) = dao.observeOpenRules(symbol).distinctUntilChanged()
    fun events(symbol: String) = dao.observeEvents(symbol).distinctUntilChanged()

    suspend fun recentNews(): TradingNewsBatch? {
        val connection = dao.connection() ?: return null
        val stories = api(connection).news(now())
        return TradingNewsBatch(connection.deviceId, stories)
    }

    suspend fun registerNewsDevice(): Boolean {
        val connection = dao.connection() ?: return true
        return api(connection).registerNews(connection.deviceId, deviceToken(), packageName)
    }

    suspend fun verifyPush(deviceId: String): Boolean {
        val connection = dao.connection()?.takeIf { it.deviceId == deviceId } ?: return false
        return pushCheck.verify(deviceId) { checkId ->
            val api = api(connection)
            try {
                api.requestPushCheck(deviceId, checkId)
            } catch (failure: ProbeAlertException) {
                if (failure.httpStatus != 409) throw failure
                if (!api.registerNews(deviceId, deviceToken(), packageName)) throw failure
                api.requestPushCheck(deviceId, checkId)
            }
        }
    }

    fun receivePushCheck(deviceId: String?, checkId: String?) = pushCheck.receive(deviceId, checkId)

    fun positionRules(position: TradingPositionDetails): Flow<List<TradingPositionAlert>> {
        val prefix = position.alertRequestPrefix()
        val openedAt = position.openingFill?.time ?: position.position.updatedAt
        return dao.observePositionRules(position.key.symbol, "$prefix*", openedAt).map { rules ->
            rules.mapNotNull { rule -> rule.positionAlertChoice?.let { TradingPositionAlert(it, rule) } }
        }.distinctUntilChanged()
    }

    suspend fun setPositionAlert(
        position: TradingPositionDetails,
        choice: PositionAlertChoice,
        stopLoss: String,
        liveMarkPrice: BigDecimal?,
    ): TradingPriceAlert = operations.withLock {
        val target = position.position.alertTarget(parsePositionPlanPrice(stopLoss), choice)
            ?: throw ProbeAlertException("请检查止损价和提醒价")
        val threshold = alertDecimal(target.price) ?: throw ProbeAlertException("请检查提醒价")
        val connection = requireConnection()
        val session = register(connection)
        if (!session.ready) throw ProbeAlertException("服务器尚未启用 FCM 推送")

        val choiceRules = positionRules(position).first().filter { it.choice == choice }
        val same = choiceRules.firstOrNull { it.rule.threshold == threshold }?.rule
        if (same?.status == "pending") return@withLock submit(session.api, same)
        if (same?.status == "active") return@withLock same

        val mark = liveMarkPrice ?: position.position.markPrice.toBigDecimalOrNull()
        if (mark != null && mark.signum() > 0) {
            val difference = mark.compareTo(threshold.toBigDecimal())
            if (if (target.direction == "up") difference >= 0 else difference <= 0) {
                throw ProbeAlertException("当前标记价已到达提醒价")
            }
        }

        val kind = TradingAlertKind.TARGET
        val existing = dao.matchingRule(connection.deviceId, session.instanceId, position.key.symbol,
            kind.value, threshold, null, null)
        if (existing != null && choiceRules.none { it.rule.requestId == existing.requestId }) {
            if (existing.status == "pending" || existing.serverId in session.api.activeIds(kind.resource, connection.deviceId)) {
                throw ProbeAlertException("这个价位已有提醒，请在图表闹钟里管理")
            }
            markInactive(existing.requestId)
        }

        choiceRules.forEach { cancelRule(session.api, it.rule) }
        val rule = TradingPriceAlert(
            requestId = "${position.alertRequestPrefix()}${choice.name}:${UUID.randomUUID()}",
            deviceId = connection.deviceId, instanceId = session.instanceId, symbol = position.key.symbol,
            kind = kind.value, threshold = threshold, minutes = null, unit = null,
            createdAt = maxOf(now(), position.position.updatedAt), direction = target.direction,
        )
        dao.saveRule(rule)
        submit(session.api, rule)
    }

    suspend fun connect(address: String, accessToken: String): Boolean = operations.withLock {
        val endpoint = probeEndpoint(address) ?: throw ProbeAlertException("请输入 HTTPS 地址，路径以 /api/v1 结尾")
        val token = accessToken.trim()
        if (token.length !in 1..256 || token.any { it !in '!'..'~' }) {
            throw ProbeAlertException("请输入提醒服务器的访问令牌")
        }
        val previous = dao.connection()
        val previousToken = if (previous?.endpoint == endpoint.toString()) {
            try { openToken(previous) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        val deviceId = previous?.deviceId?.takeIf { previousToken == token } ?: UUID.randomUUID().toString()
        val api = withContext(Dispatchers.IO) { ProbeAlertsApi(endpoint, token, client(endpoint)) }
        api.instanceId()
        val ready = api.register(deviceId, deviceToken(), packageName)
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(token) }
        dao.saveConnection(TradingAlertConnection(endpoint = endpoint.toString(), deviceId = deviceId,
            tokenCiphertext = encrypted.ciphertext, tokenIv = encrypted.iv))
        ready
    }

    suspend fun syncOpenRules() = operations.withLock {
        val row = requireConnection()
        val api = api(row)
        val instance = api.instanceId()
        dao.expireRules(row.deviceId, instance)
        val active = dao.activeRules(row.deviceId, instance)
        if (active.isEmpty()) return@withLock

        val remote = active.groupBy { resource(it) }.mapValues { (resource, _) ->
            api.activeIds(resource, row.deviceId)
        }
        database.withTransaction {
            active.filter { it.serverId !in remote.getValue(resource(it)) }.forEach { rule ->
                markInactive(rule.requestId)
            }
        }
    }

    suspend fun registerRefreshedToken() = operations.withLock {
        val row = dao.connection() ?: return@withLock
        val api = api(row)
        api.register(row.deviceId, deviceToken(), packageName)
        Unit
    }

    suspend fun create(draft: TradingAlertDraft): TradingPriceAlert = operations.withLock {
        val kind = draft.mode.kind
        val threshold = alertDecimal(draft.threshold) ?: throw ProbeAlertException("请输入大于零的数值")
        if (normalizeUsdtSymbol(draft.symbol) != draft.symbol || draft.symbol.length > 64 ||
            kind == TradingAlertKind.WINDOW && (draft.minutes == null || draft.minutes <= 0 || draft.unit !in listOf("percent", "absolute"))) {
            throw ProbeAlertException("请检查合约、窗口分钟数和单位")
        }
        val connection = requireConnection()
        val session = register(connection)
        if (!session.ready) throw ProbeAlertException("服务器尚未启用 FCM 推送")
        val minutes = draft.minutes.takeIf { kind == TradingAlertKind.WINDOW }
        val unit = draft.unit.takeIf { kind == TradingAlertKind.WINDOW }
        val openingOpportunity = draft.mode == TradingAlertMode.OPENING_OPPORTUNITY
        val existing = dao.matchingRule(connection.deviceId, session.instanceId, draft.symbol, kind.value, threshold, minutes, unit)
        if (existing != null &&
            (existing.status == "pending" || existing.serverId in session.api.activeIds(kind.resource, connection.deviceId))
        ) {
            if (existing.status == "cancelling") throw ProbeAlertException("该价位有一条提醒正在撤销，请先完成取消")
            if (existing.isOpeningOpportunity != openingOpportunity) {
                throw ProbeAlertException("同一个价位已有其他类型的提醒，取消后可重新设置")
            }
            return@withLock if (existing.status == "pending") submit(session.api, existing) else existing
        }
        val requestId = UUID.randomUUID().toString().let { if (openingOpportunity) OpeningOpportunityPrefix + it else it }
        val newRule = TradingPriceAlert(
            requestId, connection.deviceId, session.instanceId,
            draft.symbol, kind.value, threshold, minutes, unit, now(),
        )
        database.withTransaction {
            existing?.let { markInactive(it.requestId) }
            dao.saveRule(newRule)
        }
        submit(session.api, newRule)
    }

    suspend fun retry(requestId: String): TradingPriceAlert = operations.withLock {
        val row = requireConnection()
        val rule = dao.rule(requestId)?.takeIf { it.deviceId == row.deviceId && it.canRetry }
            ?: throw ProbeAlertException("请刷新提醒列表后重试")
        val session = register(row)
        if (!session.ready) throw ProbeAlertException("服务器尚未启用 FCM 推送")
        if (session.instanceId != rule.instanceId) throw ProbeAlertException("服务器已重启，请重新创建提醒")
        submit(session.api, rule)
    }

    suspend fun cancel(requestId: String) = operations.withLock {
        val row = requireConnection()
        val rule = dao.rule(requestId)?.takeIf { it.deviceId == row.deviceId && it.canCancel }
            ?: return@withLock
        val api = api(row)
        val instance = api.instanceId()
        dao.expireRules(row.deviceId, instance)
        if (instance != rule.instanceId) return@withLock
        cancelRule(api, rule)
    }

    suspend fun claim(event: TradingPriceAlertEvent, message: String? = null): TradingAlertNotice? = database.withTransaction {
        if (dao.connection()?.deviceId != event.deviceId || dao.recordEvent(event) == -1L) return@withTransaction null
        val target = event.probeTarget(message)
        val rule = dao.ruleForEvent(event.deviceId, event.instanceId, event.symbol, event.ruleId, event.kind)
            ?: target?.let {
                // A trigger can arrive before the POST reply has recorded the server rule ID.
                dao.matchingRule(event.deviceId, event.instanceId, event.symbol, event.kind, it.price, null, null)
                    ?.takeIf { pending ->
                        pending.status == "pending" && (pending.direction == null || pending.direction == it.direction)
                    }
            }
        if (event.kind == TradingAlertKind.TARGET.value) {
            dao.markTriggered(event.deviceId, event.instanceId, event.ruleId, event.symbol)
        }
        val notice = event.notification(rule, message, target)
        val day = tradingNewsDay(now())
        val news = database.tradingNews()
        news.deleteExpiredPriceAlerts(day.start)
        if (event.triggeredAt in day) news.save(listOf(event.newsRecord(notice, unread = true)))
        notice
    }

    private suspend fun register(row: TradingAlertConnection): ConnectedProbe {
        val api = api(row)
        val instance = api.instanceId()
        dao.expireRules(row.deviceId, instance)
        val ready = api.register(row.deviceId, deviceToken(), packageName)
        return ConnectedProbe(api, instance, ready)
    }

    private suspend fun submit(api: ProbeAlertsApi, rule: TradingPriceAlert): TradingPriceAlert {
        val reply = try { api.create(rule) }
        catch (failure: ProbeAlertException) {
            if (failure.httpStatus in setOf(400, 422)) dao.saveRule(rule.copy(status = "rejected"))
            throw failure
        }
        if (reply.id != null && rule.direction != null && reply.direction != rule.direction) {
            // The server chooses a crossing direction from its own current mark. A position
            // alert that has already reached its target must not watch the reverse crossing.
            if (reply.duplicate) {
                return rule.copy(status = "reached").also { dao.saveRule(it) }
            }
            val cancelling = rule.copy(serverId = reply.id, status = "cancelling")
            dao.saveRule(cancelling)
            return cancelSubmittedRule(api, cancelling, "reached")
        }
        return database.withTransaction {
            val status = when {
                reply.id == null -> "at_target"
                rule.kind == TradingAlertKind.TARGET.value && dao.didTrigger(rule.deviceId, rule.instanceId, reply.id, rule.symbol) -> "triggered"
                else -> "active"
            }
            rule.copy(serverId = reply.id, direction = reply.direction, status = status).also { dao.saveRule(it) }
        }
    }

    private suspend fun cancelRule(api: ProbeAlertsApi, rule: TradingPriceAlert) {
        val current = dao.rule(rule.requestId) ?: return
        // A timed-out POST may have succeeded. Replay its original idempotency key to resolve
        // the server ID before deleting it, so a lost response cannot strand a reminder.
        val submitted = if (current.status == "pending") submit(api, current) else current
        if (submitted.status == "active" || submitted.status == "cancelling") {
            cancelSubmittedRule(api, submitted, "cancelled")
        }
    }

    private suspend fun cancelSubmittedRule(
        api: ProbeAlertsApi,
        rule: TradingPriceAlert,
        status: String,
    ): TradingPriceAlert {
        try { api.cancel(rule) }
        catch (failure: ProbeAlertException) { if (failure.httpStatus != 404) throw failure }
        return database.withTransaction {
            val current = dao.rule(rule.requestId) ?: return@withTransaction rule
            if (current.status != "active" && current.status != "cancelling") return@withTransaction current
            current.copy(status = status).also { dao.saveRule(it) }
        }
    }

    private suspend fun requireConnection(): TradingAlertConnection =
        dao.connection() ?: throw ProbeAlertException("请先配置提醒服务器")

    private fun resource(rule: TradingPriceAlert): String =
        TradingAlertKind.entries.first { it.value == rule.kind }.resource

    private suspend fun markInactive(id: String) {
        val current = dao.rule(id)
        if (current?.status == "active" || current?.status == "cancelling") dao.saveRule(current.copy(status = "inactive"))
    }

    private suspend fun openToken(row: TradingAlertConnection): String = withContext(Dispatchers.IO) {
        cipher.decrypt(EncryptedCredential(row.tokenCiphertext, row.tokenIv))
    }

    private suspend fun api(row: TradingAlertConnection): ProbeAlertsApi {
        val endpoint = requireNotNull(probeEndpoint(row.endpoint))
        val token = try { openToken(row) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ProbeAlertException("无法读取访问令牌，请重新连接") }
        return withContext(Dispatchers.IO) { ProbeAlertsApi(endpoint, token, client(endpoint)) }
    }

    private data class ConnectedProbe(val api: ProbeAlertsApi, val instanceId: String, val ready: Boolean)
}

internal data class TradingPositionAlert(val choice: PositionAlertChoice, val rule: TradingPriceAlert)

private const val OpeningOpportunityPrefix = "opening-opportunity:"

internal val TradingPriceAlert.isOpeningOpportunity: Boolean
    get() = kind == TradingAlertKind.TARGET.value && requestId.startsWith(OpeningOpportunityPrefix)

internal val TradingPriceAlert.positionAlertChoice: PositionAlertChoice?
    get() {
        if (kind != TradingAlertKind.TARGET.value || !requestId.startsWith("position-")) return null
        val name = requestId.substringBefore(':').substringAfterLast('-')
        return PositionAlertChoice.entries.firstOrNull { it.name == name }
    }

internal val TradingPriceAlert.canCancel: Boolean
    get() = status == "active" || status == "cancelling" || (status == "pending" &&
        (requestId.startsWith("position-") || isOpeningOpportunity || kind == TradingAlertKind.LADDER.value))

internal val TradingPriceAlert.canRetry: Boolean
    get() = status == "pending" && kind != TradingAlertKind.LADDER.value

private fun TradingPositionDetails.alertRequestPrefix(): String {
    // Scope the local request key by account, contract and hedge side. The opening time
    // bounds queries to the current round, including alerts made before its fills synced.
    val owner = "${key.scope}/${key.symbol}/${position.positionSide}/${position.side}"
    val hash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).toHexString()
    return "position-$hash-"
}

private suspend fun firebaseAlertToken(): String {
    if (runCatching { FirebaseApp.getInstance() }.isFailure) {
        throw ProbeAlertException("此安装包尚未配置 Firebase 推送")
    }
    return withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { request ->
            val messaging = FirebaseMessaging.getInstance().apply { isAutoInitEnabled = true }
            messaging.token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result else null
                if (!token.isNullOrBlank()) request.resume(token)
                else request.resumeWithException(ProbeAlertException("暂时无法取得手机的推送令牌", true))
            }
        }
    } ?: throw ProbeAlertException("获取推送令牌超时，请检查网络和 Google Play 服务", true)
}
