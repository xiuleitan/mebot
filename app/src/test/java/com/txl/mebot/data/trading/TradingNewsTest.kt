package com.txl.mebot.data.trading

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.R
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.TradingAlertConnection
import com.txl.mebot.data.local.TradingPriceAlertEvent
import com.txl.mebot.data.models.AesGcmCredentialCipher
import com.txl.mebot.data.trading.alerts.ProbeAlertsApi
import com.txl.mebot.data.trading.alerts.TradingAlertsRepository
import com.txl.mebot.data.trading.alerts.newsRecord
import com.txl.mebot.data.trading.alerts.notification
import com.txl.mebot.data.trading.alerts.tradingAlertEvent
import com.txl.mebot.data.trading.news.TradingNewsBatch
import com.txl.mebot.data.trading.news.TradingNewsChannel
import com.txl.mebot.data.trading.news.TradingNewsNotifications
import com.txl.mebot.data.trading.news.TradingNewsPush
import com.txl.mebot.data.trading.news.TradingNewsRepository
import com.txl.mebot.data.trading.news.TradingNewsStory
import com.txl.mebot.data.trading.news.tradingNewsDay
import com.txl.mebot.data.trading.news.tradingNewsPush
import java.io.DataInputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
class TradingNewsTest {
    private val device = "6c50af1a-0ceb-4402-bb92-f0387a04ba32"
    private val now = Instant.parse("2026-11-24T01:00:00Z").toEpochMilli()
    private val day = tradingNewsDay(now)
    private val main = TradingNewsStory("main-1", TradingNewsChannel.MAIN, "测试主频道", now - 20_000, "https://eqn.news/main/1")
    private val tradfi = TradingNewsStory("finance-1", TradingNewsChannel.TRADFI, "测试财经频道", now - 10_000, null)

    @Test
    fun onlyFreshMainNewsIsEligibleForAStandardNotification() {
        val payload = mapOf(
            "type" to "trading_news", "device_id" to device, "instance_id" to "probe-example",
            "news_id" to main.id, "channel" to main.channel.key, "title" to main.title,
            "published_at" to Instant.ofEpochMilli(main.publishedAt).toString(), "url" to checkNotNull(main.url),
        )
        val live = checkNotNull(tradingNewsPush(payload, now))
        assertEquals(TradingNewsPush(device, main), live)
        assertTrue(live.story.shouldNotify(now))
        assertNull(tradingAlertEvent(payload, now))
        val finance = checkNotNull(tradingNewsPush(payload + ("channel" to "tradfi"), now))
        assertFalse(finance.story.shouldNotify(now))
        val old = checkNotNull(tradingNewsPush(payload + ("published_at" to "2026-11-24T00:30:00Z"), now))
        assertFalse(old.story.shouldNotify(now))
        assertNull(tradingNewsPush(payload + ("channel" to "binance"), now))
        assertNull(tradingNewsPush(payload + ("channel" to TradingNewsChannel.PRICE_ALERT.key), now))
        assertNull(tradingNewsPush(payload + ("published_at" to "2026-11-24T02:00:00Z"), now))
        assertNull(tradingNewsPush(payload + ("device_id" to "other-install"), now))
        assertNull(tradingNewsPush(payload + ("instance_id" to "/wrong"), now))
        assertNull(checkNotNull(tradingNewsPush(payload + ("url" to "intent://untrusted"), now)).story.url)
        assertNull(checkNotNull(tradingNewsPush(payload + ("url" to "https://user:pw@eqn.news/main/1"), now)).story.url)
    }

    @Test
    fun receivedPriceAlertsShareTheNewsFeedAndOnlyTheirCopiesExpireAtLocalMidnight() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val time = day.start + 1_000L
        val window = TradingPriceAlertEvent(
            deviceId = device, instanceId = "instance", eventId = 3L, ruleId = "window-rule",
            kind = "window", symbol = "BTCUSDT", markPrice = "65100", triggeredAt = time,
        )
        val target = window.copy(eventId = 2L, ruleId = "target-rule", kind = "target", triggeredAt = time - 500L)
        val expired = target.copy(eventId = 1L, triggeredAt = day.start - 1L)
        val legacy = target.copy(eventId = 4L, ruleId = "legacy-rule", triggeredAt = time - 750L)
        val ordinary = main.copy(publishedAt = day.start - 2L)
        val db = MebotDatabase.create(app)
        try {
            db.tradingAlerts().saveConnection(TradingAlertConnection(
                endpoint = "https://test.invalid/api/v1/", deviceId = device,
                tokenCiphertext = byteArrayOf(1), tokenIv = byteArrayOf(2),
            ))
            val news = TradingNewsRepository(db) { error("The local feed must work offline") }
            val alerts = TradingAlertsRepository(
                db, AesGcmCredentialCipher("test-probe") { error("Claiming a push doesn't decrypt a token") },
                app.packageName, { error("Claiming a push doesn't connect") }, now = { time },
            )
            news.accept(TradingNewsPush(device, ordinary), time)
            news.markOpened(day)
            db.tradingAlerts().recordEvent(target)
            db.tradingAlerts().recordEvent(expired)
            db.tradingAlerts().recordEvent(legacy)
            db.tradingNews().save(listOf(
                expired.newsRecord(expired.notification(null, null, null), unread = true),
                legacy.newsRecord(legacy.notification(null, null, null), unread = false).copy(
                    title = "BTCUSDT 目标价已到达 · 目标价 65000 USDT · 标记价 65100 USDT",
                ),
            ))
            assertFalse(news.hasUnread(day).first())

            val message = "[窗口提醒 window-rule] BTCUSDT 5 分钟上涨 1.3%"
            checkNotNull(alerts.claim(window, message))
            assertNull(alerts.claim(window, message))
            news.refreshPriceAlerts(day)

            val history = news.history(day).first()
            assertEquals(listOf("instance:3", "instance:2", "instance:4", ordinary.id), history.map { it.id })
            assertEquals("BTC 1.3%/5min 涨跌幅提醒", history.first().title)
            assertEquals("BTC 目标价 65000.00 已达到", history[2].title)
            assertEquals(listOf("BTCUSDT", "BTCUSDT", "BTCUSDT", null), history.map { it.alertSymbol })
            assertEquals(history.first().copy(unread = false, alertSymbol = null), news.latest(day).first().first())
            assertEquals(setOf(history.first().key), news.markOpened(day))
            assertFalse(news.hasUnread(day).first())
            db.openHelper.readableDatabase.query("SELECT channel FROM trading_news WHERE deviceId = ?", arrayOf(device)).use {
                assertTrue(it.moveToFirst())
                assertEquals(ordinary.channel.key, it.getString(0))
                assertFalse(it.moveToNext())
            }

            val tomorrow = tradingNewsDay(day.nextStart)
            news.refreshPriceAlerts(tomorrow)
            assertEquals(listOf(ordinary), news.history(tomorrow).first())
            assertEquals(listOf(ordinary), news.latest(tomorrow).first())
            assertEquals(listOf(ordinary.id), news.history(day).first().map { it.id })
            assertEquals(setOf(window, target, expired, legacy), alerts.events("BTCUSDT").first().toSet())
        } finally {
            db.close()
        }
    }

    @Test
    fun alertNewsDayUsesMidnightInTheDevicesTimeZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = Instant.parse("2026-11-24T16:00:00Z").toEpochMilli()
        val before = tradingNewsDay(midnight - 1L, zone)
        assertEquals(midnight, before.nextStart)
        assertFalse(midnight in before)
        assertEquals(midnight, tradingNewsDay(midnight, zone).start)
    }

    @Test
    fun backfillStaysSilentAndLivePushClaimsOnlyOneNoticeAfterAReopen() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = MebotDatabase.create(app)
        val connection = TradingAlertConnection(endpoint = "https://test.invalid/api/v1/", deviceId = device,
            tokenCiphertext = byteArrayOf(1), tokenIv = byteArrayOf(2))
        val previous = main.copy(id = "previous", publishedAt = now - 30 * 60_000)
        try {
            db.tradingAlerts().saveConnection(connection)
            val repository = TradingNewsRepository(db) { TradingNewsBatch(device, listOf(previous, main)) }
            repository.refresh()
            assertEquals(listOf(main), repository.latest(day).first())
            assertEquals(main, repository.accept(TradingNewsPush(device, main), now))
            assertNull(repository.accept(TradingNewsPush(device, tradfi), now))
            assertNull(repository.accept(TradingNewsPush(device, previous), now))
            assertEquals(listOf(main, tradfi), repository.latest(day).first())
        } finally {
            db.close()
        }
        val reopened = MebotDatabase.create(app)
        try {
            val repository = TradingNewsRepository(reopened) { error("Opening local history must not fetch") }
            assertNull(repository.accept(TradingNewsPush(device, main), now))
            assertEquals(listOf(tradfi, main, previous).map { it.copy(unread = true) }, repository.history(day).first())
            reopened.tradingAlerts().saveConnection(connection.copy(deviceId = java.util.UUID.randomUUID().toString()))
            assertNull(repository.accept(TradingNewsPush(device, main.copy(id = "old-session")), now))
            assertTrue(repository.history(day).first().isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun openingTheDrawerMarksThisBatchReadAndLateArrivalsRemainUnreadAcrossReopen() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = MebotDatabase.create(app)
        val delayed = main.copy(id = "delayed", publishedAt = now - 60 * 60_000)
        var stories = listOf(main)
        try {
            db.tradingAlerts().saveConnection(
                TradingAlertConnection(endpoint = "https://test.invalid/api/v1/", deviceId = device,
                    tokenCiphertext = byteArrayOf(1), tokenIv = byteArrayOf(2)),
            )
            val repository = TradingNewsRepository(db) { TradingNewsBatch(device, stories) }
            assertFalse(repository.hasUnread(day).first())
            repository.refresh()
            assertTrue(repository.hasUnread(day).first())
            assertEquals(setOf(main.key), repository.markOpened())
            assertFalse(repository.hasUnread(day).first())
            assertEquals(emptySet<String>(), repository.markOpened())

            // An already read item can still claim its one system notice, without becoming unread again.
            assertEquals(main, repository.accept(TradingNewsPush(device, main), now))
            assertFalse(repository.hasUnread(day).first())
            stories = listOf(main, delayed)
            repository.refresh()
            assertNull(repository.accept(TradingNewsPush(device, tradfi), now))
        } finally {
            db.close()
        }

        val reopened = MebotDatabase.create(app)
        try {
            val repository = TradingNewsRepository(reopened) { error("Opening saved news must work offline") }
            assertEquals(setOf(delayed.key, tradfi.key), repository.history(day).first().filter { it.unread }.map { it.key }.toSet())
            assertTrue(repository.hasUnread(day).first())
            assertEquals(setOf(delayed.key, tradfi.key), repository.markOpened())
            assertFalse(repository.hasUnread(day).first())
            assertTrue(repository.history(day).first().none { it.unread })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun recentNewsUsesTheRegisteredServerAndBearer() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("https", request.url.scheme)
            assertEquals("/api/v1/news", request.url.encodedPath)
            assertEquals("100", request.url.queryParameter("limit"))
            assertEquals("Bearer fixture", request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"instance_id":"probe-example","items":[
                    {"news_id":"main-1","channel":"main","title":"测试主频道","published_at":"2026-11-24T00:59:40Z","url":"https://eqn.news/main/1"},
                    {"news_id":"invalid","channel":"unknown","title":"不支持的频道","published_at":"2026-11-24T00:59:50Z"}
                ]}""".toResponseBody()).build()
        }.build()
        val api = ProbeAlertsApi("https://test.invalid/api/v1/".toHttpUrl(), "fixture", client)
        assertEquals(listOf(main), api.news(now))
    }

    @Test
    fun newsDeviceRegistrationUsesTheIsolatedEndpoint() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/news/devices/$device", request.url.encodedPath)
            assertEquals("Bearer fixture", request.header("Authorization"))
            val payload = JSONObject(Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8())
            assertEquals("fixture-fcm", payload.getString("fcm_token"))
            assertEquals("com.txl.mebot", payload.getString("package_name"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"registered":true,"push_available":false}""".toResponseBody()).build()
        }.build()
        assertFalse(ProbeAlertsApi("https://test.invalid/api/v1/".toHttpUrl(), "fixture", client)
            .registerNews(device, "fixture-fcm", "com.txl.mebot"))
    }

    @Test
    fun pushCheckUsesTheAuthenticatedNewsEndpoint() = runBlocking<Unit> {
        val checkId = "9e3cb056-1772-4f38-9605-4d3f1d25f5db"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/news/push-check", request.url.encodedPath)
            assertEquals("Bearer fixture", request.header("Authorization"))
            val payload = JSONObject(Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8())
            assertEquals(device, payload.getString("device_id"))
            assertEquals(checkId, payload.getString("check_id"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(202).message("fixture")
                .body("""{"accepted":true}""".toResponseBody()).build()
        }.build()
        ProbeAlertsApi("https://test.invalid/api/v1/".toHttpUrl(), "fixture", client)
            .requestPushCheck(device, checkId)
    }

    @Test
    fun mainNewsUsesAShortChimeOnAnUpgradedNotificationChannel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("trading_news", "方程式新闻", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
        })

        TradingNewsNotifications.post(app, main.copy(title = "* ${main.title} A*B"))
        val channel = manager.getNotificationChannel(TradingNewsNotifications.Channel)
        val sound = checkNotNull(channel.sound)
        assertEquals("android.resource://${app.packageName}/raw/trading_news_chime", sound.toString())
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertFalse(channel.shouldVibrate())
        assertNull(manager.getNotificationChannel("trading_news"))
        DataInputStream(checkNotNull(app.contentResolver.openInputStream(sound))).use { input ->
            val header = ByteArray(4)
            input.readFully(header)
            assertEquals("RIFF", String(header, Charsets.US_ASCII))
        }
        val notification = manager.activeNotifications.single().notification
        assertEquals(TradingNewsNotifications.Channel, notification.channelId)
        assertEquals(app.getString(R.string.trading_news_main), notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("${main.title} A*B", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("${main.title} A*B", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(0, notification.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun aBlockedLegacyNewsChannelIsNotReplacedByAnAudibleChannel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("trading_news", "方程式新闻", NotificationManager.IMPORTANCE_NONE))

        TradingNewsNotifications.post(app, main)

        assertNull(manager.getNotificationChannel(TradingNewsNotifications.Channel))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
