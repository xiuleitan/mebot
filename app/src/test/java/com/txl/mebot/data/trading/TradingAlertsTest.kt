package com.txl.mebot.data.trading

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.local.TradingPriceAlertEvent
import com.txl.mebot.data.models.AesGcmCredentialCipher
import com.txl.mebot.data.models.EncryptedCredential
import com.txl.mebot.data.trading.alerts.ProbeAlertException
import com.txl.mebot.data.trading.alerts.TradingAlertDraft
import com.txl.mebot.data.trading.alerts.TradingAlertMode
import com.txl.mebot.data.trading.alerts.TradingAlertNotice
import com.txl.mebot.data.trading.alerts.TradingAlertNotifications
import com.txl.mebot.data.trading.alerts.TradingAlertsRepository
import com.txl.mebot.data.trading.alerts.canCancel
import com.txl.mebot.data.trading.alerts.canRetry
import com.txl.mebot.data.trading.alerts.notification
import com.txl.mebot.data.trading.alerts.probeEndpoint
import com.txl.mebot.data.trading.alerts.probeTarget
import com.txl.mebot.data.trading.alerts.tradingAlertEvent
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TradingAlertsTest {
    @Test
    fun aLostReplyReusesTheSavedRequestKeyAndTheTriggerCanOnlyRingOnce() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = MebotDatabase.create(app)
        val cipher = AesGcmCredentialCipher("test-probe") { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        val retries = AtomicInteger()
        val recorded = CopyOnWriteArrayList<Pair<String?, String>>()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            val text = request.body?.let { body -> Buffer().apply { body.writeTo(this) }.readUtf8() }.orEmpty()
            val response = when {
                request.url.encodedPath.endsWith("/health") -> """{"instance_id":"local-instance"}"""
                "/devices/" in request.url.encodedPath -> """{"registered":true,"push_available":true}"""
                request.url.encodedPath.endsWith("/alerts") -> {
                    recorded += request.header("Idempotency-Key") to text
                    if (retries.incrementAndGet() == 1) throw IOException("lost response")
                    """{"items":[{"status":"duplicate","rule":{"id":"7","direction":"up"}}]}"""
                }
                else -> """{"error":"unexpected request"}"""
            }
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(response.toResponseBody()).build()
        }.build()
        val now = Instant.parse("2026-11-23T08:15:00Z").toEpochMilli()
        fun repository() = TradingAlertsRepository(database, cipher, "com.txl.mebot", { client }, { "test-device-token" }, { now })
        try {
            val first = repository()
            assertTrue(first.connect("https://alerts.example", "local-bearer"))
            val connection = requireNotNull(database.tradingAlerts().connection())
            assertEquals("local-bearer", cipher.decrypt(EncryptedCredential(connection.tokenCiphertext, connection.tokenIv)))
            assertFalse(connection.tokenCiphertext.contentEquals("local-bearer".toByteArray()))
            assertTrue(runCatching {
                first.create(TradingAlertDraft("BTCUSDT", TradingAlertMode.TARGET, "65000.00"))
            }.exceptionOrNull() is ProbeAlertException)
            val pending = first.rules("BTCUSDT").first().single()
            assertEquals("pending", pending.status)
            assertFalse(first.hasActiveAlerts("BTCUSDT").first())
            val reopened = repository()
            val active = reopened.retry(pending.requestId)
            assertEquals("active", active.status)
            assertTrue(reopened.hasActiveAlerts("BTCUSDT").first())
            assertFalse(reopened.hasActiveAlerts("ETHUSDT").first())
            database.tradingAlerts().saveRule(active.copy(requestId = "other-device", deviceId = UUID.randomUUID().toString()))
            assertEquals(2, recorded.size)
            assertEquals(recorded[0], recorded[1])
            val sent = JSONObject(recorded[0].second)
            assertEquals("65000", sent.getString("target_price"))
            assertEquals(connection.deviceId, sent.getString("device_id"))

            val event = requireNotNull(tradingAlertEvent(eventFields(connection.deviceId), now))
            assertNull(reopened.claim(event.copy(deviceId = UUID.randomUUID().toString())))
            val notice = requireNotNull(reopened.claim(event))
            assertEquals("BTC 65000.00 价格提醒", notice.title)
            assertEquals("BTC 目标价 65000.00 已达到", notice.headline)
            assertNull(repository().claim(event))
            assertEquals("triggered", reopened.rules("BTCUSDT").first().single().status)
            assertFalse(reopened.hasActiveAlerts("BTCUSDT").first())
            assertEquals(listOf(event), reopened.events("BTCUSDT").first())
        } finally {
            database.close()
        }
    }

    @Test
    fun openRulesKeepOlderLiveAlertsAndRemoveRulesThatFinishedOnTheServer() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = MebotDatabase.create(app)
        val server = PositionAlertServer()
        val cipher = AesGcmCredentialCipher("test-probe") { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        val tokenReads = AtomicInteger()
        val repository = TradingAlertsRepository(database, cipher, "com.txl.mebot", { server.client }, {
            tokenReads.incrementAndGet()
            "test-token"
        })
        try {
            assertTrue(repository.connect("https://alerts.example", "local-bearer"))
            val stillOpen = repository.create(TradingAlertDraft("ETHUSDT", TradingAlertMode.TARGET, "1790"))
            val finished = repository.create(TradingAlertDraft("ETHUSDT", TradingAlertMode.TARGET, "1800"))
            repeat(55) { index ->
                database.tradingAlerts().saveRule(
                    finished.copy(
                        requestId = "previous-$index", serverId = "past-$index",
                        createdAt = finished.createdAt + index + 1, status = "triggered",
                    ),
                )
            }
            server.finish(checkNotNull(finished.serverId))
            val readsBeforeSync = tokenReads.get()
            repository.syncOpenRules()
            assertEquals(readsBeforeSync, tokenReads.get())
            assertEquals(listOf(stillOpen), repository.openRules("ETHUSDT").first())
            assertEquals("inactive", database.tradingAlerts().rule(finished.requestId)?.status)
            assertEquals("triggered", database.tradingAlerts().rule("previous-0")?.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun pushParserAcceptsOnlyFreshPriceAlertsAndTheEndpointRequiresHttps() {
        val now = Instant.parse("2026-11-23T08:15:00Z").toEpochMilli()
        val data = eventFields(UUID.randomUUID().toString())
        assertEquals("65100", tradingAlertEvent(data, now)?.markPrice)
        assertNull(tradingAlertEvent(data, now + 301_000L))
        assertNull(tradingAlertEvent(data + ("type" to "some_other_message"), now))
        assertNull(tradingAlertEvent(data + ("mark_price" to "-1"), now))
        assertNull(tradingAlertEvent(data + ("device_id" to "wrong"), now))
        assertNull(probeEndpoint("http://192.168.3.92:8765/api/v1/"))
        assertNull(probeEndpoint("https://user:pass@example.com/api/v1/"))
        assertEquals("https://example.com/price/api/v1/", probeEndpoint("https://example.com/price/api/v1")?.toString())
    }

    @Test
    fun positionAlertTiersCoexistAndCanBeUpdatedOrCancelledIndependently() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = MebotDatabase.create(app)
        val server = PositionAlertServer()
        val now = 2_000L
        val key = TradingPositionKey("account", "short-1", "ETHUSDT")
        val position = TradingPosition("SHORT", "BOTH", 1_000, "10", "1725.93", "1724.60", "13.2")
        val details = TradingPositionDetails(position, key, key, null, null)
        val cipher = AesGcmCredentialCipher("test-probe") { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        fun repository() = TradingAlertsRepository(database, cipher, "com.txl.mebot", { server.client }, { "test-token" }, { now })
        try {
            val repo = repository()
            assertTrue(repo.connect("https://alerts.example", "local-bearer"))
            val manual = repo.create(TradingAlertDraft("ETHUSDT", TradingAlertMode.TARGET, "1790"))
            val second = repo.setPositionAlert(details, PositionAlertChoice.R2, "1,769", null)
            val stop = repo.setPositionAlert(details, PositionAlertChoice.STOP, "1769", null)
            assertEquals("1639.79", second.threshold)
            assertEquals("down", second.direction)
            assertEquals("up", stop.direction)
            assertTrue(repo.positionRules(details.copy(key = key.copy(scope = "another-account"))).first().isEmpty())
            assertTrue(repo.positionRules(details.copy(position = position.copy(side = "LONG"))).first().isEmpty())
            assertTrue(repo.positionRules(details.copy(position = position.copy(updatedAt = now + 1))).first().isEmpty())

            server.failDelete = true
            val beforeThird = server.changes.size
            val third = repo.setPositionAlert(details, PositionAlertChoice.R3, "1769", null)
            assertEquals(listOf("POST 1596.72"), server.changes.drop(beforeThird))
            assertEquals(setOf(second, third, stop), repository().positionRules(details).first().map { it.rule }.toSet())

            assertTrue(runCatching {
                repo.setPositionAlert(details, PositionAlertChoice.R2, "1780", null)
            }.exceptionOrNull() is ProbeAlertException)
            assertEquals("DELETE ${second.serverId}", server.changes.last())
            assertEquals(setOf(second, third, stop), repo.positionRules(details).first().map { it.rule }.toSet())

            server.failDelete = false
            val adjusted = repo.setPositionAlert(details, PositionAlertChoice.R2, "1780", null)
            assertEquals(listOf("DELETE ${second.serverId}", "POST 1617.79"), server.changes.takeLast(2))
            assertEquals(setOf(adjusted, third, stop), repo.positionRules(details).first().map { it.rule }.toSet())

            repo.cancel(adjusted.requestId)
            assertEquals("DELETE ${adjusted.serverId}", server.changes.last())
            assertEquals("cancelled", database.tradingAlerts().rule(adjusted.requestId)?.status)
            assertEquals(setOf(third, stop), repo.positionRules(details).first().map { it.rule }.toSet())

            val beforeReached = server.changes.size
            assertTrue(runCatching {
                repo.setPositionAlert(details, PositionAlertChoice.R1, "1769", "1675".toBigDecimal())
            }.exceptionOrNull() is ProbeAlertException)
            assertEquals(beforeReached, server.changes.size)

            server.loseNextPostReply = true
            assertTrue(runCatching {
                repo.setPositionAlert(details, PositionAlertChoice.R4, "1769", null)
            }.exceptionOrNull() is ProbeAlertException)
            val pending = repo.positionRules(details).first().single { it.choice == PositionAlertChoice.R4 }.rule
            assertEquals("pending", pending.status)
            repository().cancel(pending.requestId)
            assertEquals(server.postKeys[server.postKeys.lastIndex - 1], server.postKeys.last())
            assertEquals(36, server.postKeys.last().length)
            assertEquals("cancelled", database.tradingAlerts().rule(pending.requestId)?.status)
            assertEquals(setOf(third, stop), repo.positionRules(details).first().map { it.rule }.toSet())

            server.mark = "1600"
            val reached = repo.setPositionAlert(details, PositionAlertChoice.R1, "1769", null)
            assertEquals("reached", reached.status)
            assertEquals(listOf("POST 1682.86", "DELETE ${reached.serverId}"), server.changes.takeLast(2))
            assertEquals(setOf(third, stop), repo.positionRules(details).first().map { it.rule }.toSet())

            val conflict = runCatching {
                repo.setPositionAlert(details, PositionAlertChoice.STOP, "1790", null)
            }.exceptionOrNull() as? ProbeAlertException
            assertEquals("这个价位已有提醒，请在图表闹钟里管理", conflict?.detail)
            val active = repo.rules("ETHUSDT").first().filter { it.status == "active" }
            assertEquals(setOf("1596.72", "1769", "1790"), active.map { it.threshold }.toSet())

            val event = TradingPriceAlertEvent(
                deviceId = third.deviceId, instanceId = third.instanceId, eventId = 1,
                ruleId = requireNotNull(third.serverId), kind = third.kind, symbol = third.symbol,
                markPrice = third.threshold, triggeredAt = now,
            )
            val riskNotice = requireNotNull(repository().claim(event))
            assertEquals("ETH 1596.72 盈亏 3R 提醒", riskNotice.title)
            assertEquals("ETH 盈亏 3R 目标价 1596.72 已达到", riskNotice.headline)
            assertEquals(listOf(stop), repo.positionRules(details).first().map { it.rule })
            repository().cancel(stop.requestId)
            assertEquals("DELETE ${stop.serverId}", server.changes.last())
            assertTrue(repository().positionRules(details).first().isEmpty())

            val renewedStop = repo.setPositionAlert(details, PositionAlertChoice.STOP, "1769", null)
            val stopEvent = event.copy(eventId = 2, ruleId = requireNotNull(renewedStop.serverId), markPrice = "1769.2")
            val stopNotice = requireNotNull(repository().claim(stopEvent))
            assertEquals("ETH 1769.00 止损价提醒", stopNotice.title)
            assertEquals("ETH 止损价 1769.00 已达到", stopNotice.headline)
            assertTrue(repo.positionRules(details).first().isEmpty())

            // Simulate an FCM arriving while the app still awaits this POST's response.
            server.mark = position.markPrice
            val firstTier = repo.setPositionAlert(details, PositionAlertChoice.R1, "1769", null)
            database.tradingAlerts().saveRule(firstTier.copy(status = "pending", serverId = null))
            val firstTierEvent = event.copy(eventId = 3, ruleId = requireNotNull(firstTier.serverId), markPrice = "1682.5")
            val firstTierNotice = requireNotNull(repository().claim(
                firstTierEvent, "[目标价提醒] ${firstTierEvent.ruleId}  ETHUSDT  跌到 1682.86  参考价 1724.60  标记价 1682.5",
            ))
            assertEquals("ETH 1682.86 盈亏 1R 提醒", firstTierNotice.title)
            assertEquals("triggered", repo.retry(firstTier.requestId).status)
            assertEquals("active", database.tradingAlerts().rule(manual.requestId)?.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun openingOpportunityUsesTheTargetApiAndKeepsItsNotificationLabel() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = MebotDatabase.create(app)
        val server = PositionAlertServer()
        val cipher = AesGcmCredentialCipher("test-probe") { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        fun repository() = TradingAlertsRepository(database, cipher, "com.txl.mebot", { server.client }, { "test-token" })
        try {
            val repo = repository()
            assertTrue(repo.connect("https://alerts.example", "local-bearer"))
            val target = TradingAlertDraft("ETHUSDT", TradingAlertMode.TARGET, "1750")
            val opportunity = TradingAlertDraft("ETHUSDT", TradingAlertMode.OPENING_OPPORTUNITY, "1760.00")
            val ordinaryRule = repo.create(target)
            val openingRule = repo.create(opportunity)
            assertEquals("target", openingRule.kind)
            assertEquals("1760", openingRule.threshold)
            assertNull(openingRule.minutes)
            assertNull(openingRule.unit)
            assertEquals(openingRule, repository().create(opportunity))
            assertEquals(listOf("POST 1750", "POST 1760"), server.changes.toList())
            assertEquals(36, server.postKeys.last().length)

            val conflict = runCatching { repo.create(target.copy(threshold = "1760")) }.exceptionOrNull()
            assertTrue(conflict is ProbeAlertException)
            assertEquals(listOf("POST 1750", "POST 1760"), server.changes.toList())

            val event = TradingPriceAlertEvent(
                deviceId = openingRule.deviceId, instanceId = openingRule.instanceId, eventId = 1,
                ruleId = requireNotNull(openingRule.serverId), kind = "target", symbol = openingRule.symbol,
                markPrice = "1760.5", triggeredAt = openingRule.createdAt + 1,
            )
            val notice = requireNotNull(repository().claim(event))
            assertEquals("ETH 1760.00 开单机会", notice.title)
            assertEquals(notice.title, notice.headline)
            assertEquals("triggered", database.tradingAlerts().rule(openingRule.requestId)?.status)
            assertEquals("active", database.tradingAlerts().rule(ordinaryRule.requestId)?.status)
            assertNull(repo.claim(event))
        } finally {
            database.close()
        }
    }

    @Test
    fun alertCopyUsesTheConfiguredWindowOrThePriceLevelWithoutTheMarkPrice() {
        val now = Instant.parse("2026-11-23T08:15:00Z").toEpochMilli()
        val target = requireNotNull(tradingAlertEvent(eventFields(UUID.randomUUID().toString()), now))
        val window = target.copy(kind = "window")
        val setting = TradingPriceAlert(
            requestId = "window-test", deviceId = window.deviceId, instanceId = window.instanceId,
            symbol = window.symbol, kind = window.kind, threshold = "5", minutes = 15,
            unit = "percent", createdAt = now,
        )
        val actual = window.notification(
            setting, "[窗口提醒 7] BTCUSDT 过去 15 分钟上涨 5.36%，基准 61788，标记价 65100", null,
        )
        assertEquals("BTC 5%/15min 涨跌幅提醒", actual.title)
        assertEquals(actual.title, actual.headline)
        assertEquals(actual.title, window.notification(setting, null, null).title)
        assertEquals("BTC 0.0205 USDT/15min 涨跌价差提醒", window.notification(
            setting.copy(threshold = "0.02050", unit = "absolute"), null, null,
        ).title)

        val ladder = target.copy(kind = "ladder", ruleId = "8")
            .notification(null, "[阶梯提醒 8] BTCUSDT 涨过 65000，标记价 65100", null)
        assertEquals("BTC 65000.00 阶梯价位提醒", ladder.title)
        assertTrue(actual.tag != ladder.tag)
        val oldLadder = setting.copy(kind = "ladder")
        assertTrue(oldLadder.canCancel)
        assertFalse(oldLadder.canRetry)

        val parsedTarget = target.probeTarget("[目标价提醒] 7  BTCUSDT  涨到 65000  参考价 64000  标记价 65100")
        assertEquals("BTC 目标价 65000.00 已达到", target.notification(null, null, parsedTarget).headline)
        assertEquals("BTC 价格提醒", target.notification(null, null, null).title)
    }

    @Test
    fun systemPriceNotificationContainsTheSingleCompactTitle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val title = "TQQQ 47.63 价格提醒"
        TradingAlertNotifications.notice(app, TradingAlertNotice("probe:1", title))

        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }

    private class PositionAlertServer {
        @Volatile var failDelete = false
        @Volatile var loseNextPostReply = false
        @Volatile var mark = "1724.60"
        val postKeys = CopyOnWriteArrayList<String>()
        val changes = CopyOnWriteArrayList<String>()
        private var nextId = 0
        private val active = mutableMapOf<String, JSONObject>()
        private val replies = mutableMapOf<String, String>()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor { respond(it.request()) }.build()

        @Synchronized
        fun finish(id: String) {
            checkNotNull(active.remove(id))
        }

        @Synchronized
        private fun respond(request: Request): Response {
            val path = request.url.encodedPath
            var code = 200
            val body = when {
                path.endsWith("/health") -> """{"instance_id":"position-test"}"""
                "/devices/" in path -> """{"registered":true,"push_available":true}"""
                path.endsWith("/alerts") && request.method == "GET" -> JSONObject().put("items", JSONArray(active.values)).toString()
                path.endsWith("/alerts") && request.method == "POST" -> {
                    val key = requireNotNull(request.header("Idempotency-Key"))
                    val input = Buffer()
                    requireNotNull(request.body).writeTo(input)
                    val price = JSONObject(input.readUtf8()).getString("target_price")
                    postKeys += key
                    changes += "POST $price"
                    val reply = replies.getOrPut(key) {
                        val id = (++nextId).toString()
                        val direction = if (price.toBigDecimal() > mark.toBigDecimal()) "up" else "down"
                        val rule = JSONObject().put("id", id).put("direction", direction)
                        active[id] = rule
                        """{"items":[{"status":"created","rule":$rule}]}"""
                    }
                    if (loseNextPostReply) {
                        loseNextPostReply = false
                        throw IOException("lost POST response")
                    }
                    reply
                }
                "/alerts/" in path && request.method == "DELETE" -> {
                    val id = path.substringAfterLast('/')
                    changes += "DELETE $id"
                    code = if (failDelete) 503 else if (active.remove(id) == null) 404 else 204
                    ""
                }
                else -> error("Unexpected test request: ${request.method} $path")
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("test response").body(body.toResponseBody()).build()
        }
    }

    private fun eventFields(device: String) = mapOf(
        "type" to "trading_price_alert", "device_id" to device,
        "instance_id" to "local-instance", "event_id" to "1", "rule_id" to "7",
        "kind" to "target", "symbol" to "BTCUSDT", "mark_price" to "65100.00",
        "triggered_at" to "2026-11-23T08:15:00.000Z",
    )
}
