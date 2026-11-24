package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingClosedPosition
import com.txl.mebot.data.local.TradingConnection
import com.txl.mebot.data.local.TradingFill
import com.txl.mebot.data.local.TradingFillSync
import com.txl.mebot.data.local.TradingLine
import com.txl.mebot.data.local.TradingNewsRecord
import com.txl.mebot.data.local.TradingNote
import com.txl.mebot.data.local.TradingPerformanceDayRecord
import com.txl.mebot.data.local.TradingTrendEntity
import com.txl.mebot.data.local.TradingTrendHistoryEntity
import com.txl.mebot.data.models.AndroidKeystoreCredentialCipher
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.trading.BinanceTradingApi
import com.txl.mebot.data.trading.POSITION_HISTORY_PAGE
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.MarketQuote
import com.txl.mebot.data.trading.TradingException
import com.txl.mebot.data.trading.TradingHistorySummary
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.TradingLineMove
import com.txl.mebot.data.trading.TradingPositionKey
import com.txl.mebot.data.trading.TradingPositionPlan
import com.txl.mebot.data.trading.TradingProblem
import com.txl.mebot.data.trading.TradingRepository
import com.txl.mebot.data.trading.TradingTrend
import com.txl.mebot.data.trading.TradingTrendDirection
import com.txl.mebot.data.trading.binanceSignature
import java.io.File
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TradingPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(MebotDatabase.FILE_NAME)
    }

    @Test
    fun installedSchema27MigratesAndTradeAnnotationsSurviveReplayAndReopen() = runBlocking<Unit> {
        createInstalledSchema(27)
        val fill = TradingFill("example-account", 7L, 9L, 1_790_000_000_000L, "BUY", "LONG", "64000.50", "0.01", "0")
        val line = TradingLine("line", "segment", fill.time, 63_900.0, fill.time + 900_000, 64_200.0)
        val candle = TradingCandle("15m", fill.time, 64_000.0, 64_300.0, 63_900.0, 64_200.0, 8.0)

        val first = MebotDatabase.create(context)
        try {
            val db = first.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            db.query("SELECT noteId FROM home_flashnote_pin").use { cursor ->
                cursor.moveToFirst()
                assertEquals("retained-27", cursor.getString(0))
            }
            db.query("SELECT assetId FROM schedule_editor_attachments").use { cursor ->
                cursor.moveToFirst()
                assertEquals("retained-27", cursor.getString(0))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            val dao = first.trading()
            dao.saveConnection(TradingConnection(scope = fill.scope, ciphertext = null, iv = null))
            dao.storeFills(listOf(fill))
            assertEquals(1, dao.updateReason(fill.scope, fill.tradeId, "等到回踩确认", fill.time))
            dao.storeFills(listOf(fill))
            dao.storeLine(line)
            dao.storeCandles(listOf(candle))
        } finally {
            first.close()
        }
        val reopened = MebotDatabase.create(context)
        try {
            val dao = reopened.trading()
            assertEquals(listOf(fill.copy(reason = "等到回踩确认", reasonUpdatedAt = fill.time)), dao.observeFills().first())
            assertEquals(listOf(line), dao.observeLines().first())
            assertEquals(listOf(candle), dao.candles("15m", Long.MAX_VALUE, 1))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun movingSavedLinesKeepsTheirIdsAndUntouchedEndpointsAcrossReopen() = runBlocking {
        val eth = TradingContract.ETH
        val btc = TradingContract.BTC
        val start = 1_790_000_000_000L
        val horizontal = TradingLine("eth-horizontal", "horizontalStraightLine", null, 1_551.39, null, null, eth.symbol)
        val trend = TradingLine("eth-trend", "segment", start, 1_570.0, start + 900_000L, 1_670.0, eth.symbol)
        val otherContract = horizontal.copy(id = "btc-horizontal", symbol = btc.symbol, price = 60_000.0)
        val movedStart = start - 900_000L
        val movedEnd = start + 1_800_000L
        val database = MebotDatabase.create(context)
        try {
            val dao = database.trading()
            for (line in listOf(horizontal, trend, otherContract)) dao.storeLine(line)
            val repository = TradingRepository(database, AndroidKeystoreCredentialCipher())
            val horizontalMove = TradingLineMove(horizontal.id, 0, null, 1_560.25)
            assertTrue(runCatching { repository.moveLine(btc, horizontalMove) }.isFailure)
            assertTrue(runCatching { repository.moveLine(eth, horizontalMove.copy(endpoint = 1)) }.isFailure)
            repository.moveLine(eth, horizontalMove)
            repository.moveLine(eth, TradingLineMove(trend.id, 0, movedStart, 1_580.0))
            assertEquals(trend.copy(time = movedStart, price = 1_580.0), repository.lines(eth).first().last())
            repository.moveLine(eth, TradingLineMove(trend.id, 1, movedEnd, 1_685.0))
        } finally {
            database.close()
        }
        val reopened = MebotDatabase.create(context)
        try {
            val dao = reopened.trading()
            assertEquals(listOf(
                horizontal.copy(price = 1_560.25),
                trend.copy(time = movedStart, price = 1_580.0, endTime = movedEnd, endPrice = 1_685.0),
            ), dao.observeLines(eth.symbol).first())
            assertEquals(listOf(otherContract), dao.observeLines(btc.symbol).first())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun savedConnectionIsReportedBeforeAccountSyncAndClearsOnDisconnect() = runBlocking<Unit> {
        val saved = TradingConnection(
            scope = "example-account", ciphertext = byteArrayOf(1), iv = byteArrayOf(2),
        )
        val first = MebotDatabase.create(context)
        try {
            val dao = first.trading()
            assertFalse(dao.observeConnectionConfigured().first())
            dao.saveConnection(saved)
        } finally {
            first.close()
        }

        val reopened = MebotDatabase.create(context)
        try {
            val dao = reopened.trading()
            assertTrue(dao.observeConnectionConfigured().first())
            dao.saveConnection(saved.copy(ciphertext = null, iv = null))
            assertFalse(dao.observeConnectionConfigured().first())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun signedQueryUsesTheExactBytes() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            binanceSignature("key", "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun installedSchema28PreservesTradesAndTrendSurvivesReopen() = runBlocking<Unit> {
        createInstalledSchema(28)
        val before = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(before, AndroidKeystoreCredentialCipher())
            assertEquals(MebotDatabase.VERSION, before.openHelper.writableDatabase.version)
            assertNull(repository.trend.first())
            assertEquals("retained-28", before.trading().observeFills().first().single().reason)
            assertEquals("retained-28", before.trading().observeLines().first().single().id)
            repository.saveTrend(TradingTrendDirection.BULLISH, "  突破前高  ", "")
            assertEquals(TradingTrend(TradingTrendDirection.BULLISH, "突破前高", ""), repository.trend.first())
        } finally {
            before.close()
        }

        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, AndroidKeystoreCredentialCipher())
            assertEquals(TradingTrend(TradingTrendDirection.BULLISH, "突破前高", ""), repository.trend.first())
            repository.saveTrend(TradingTrendDirection.SIDEWAYS, "区间内波动", "")
            assertEquals(TradingTrend(TradingTrendDirection.SIDEWAYS, "区间内波动", ""), repository.trend.first())
            assertEquals("retained-28", reopened.trading().observeFills().first().single().reason)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun installedSchema29MovesPriorNotesAndKeepsBothReasonLists() = runBlocking<Unit> {
        createInstalledSchema(29)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            val insert = "INSERT INTO trading_trend (symbol, direction, reason) VALUES (?, ?, ?)"
            db.execSQL(insert, arrayOf("BTCUSDT", "BEARISH", "均线下行\n波动放大"))
            db.execSQL(insert, arrayOf("LONG-FIXTURE", "BULLISH", "突破前高"))
            db.execSQL(insert, arrayOf("RANGE-FIXTURE", "SIDEWAYS", "继续观察"))
        }
        val database = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(database, AndroidKeystoreCredentialCipher())
            assertEquals(TradingTrend(TradingTrendDirection.BEARISH, "", "均线下行\n波动放大"), repository.trend.first())
            val long = database.trading().observeTrend("LONG-FIXTURE").first()!!
            assertEquals("突破前高", long.bullishReason)
            assertEquals("", long.reason)
            val range = database.trading().observeTrend("RANGE-FIXTURE").first()!!
            assertEquals("继续观察", range.reason)
            assertEquals("", range.bullishReason)
            assertEquals("", range.bearishReason)
            repository.saveTrend(TradingTrendDirection.SIDEWAYS, "回踩确认\n买盘回升", "均线下行")
        } finally {
            database.close()
        }
        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, AndroidKeystoreCredentialCipher())
            assertEquals(
                TradingTrend(TradingTrendDirection.SIDEWAYS, "回踩确认\n买盘回升", "均线下行"),
                repository.trend.first(),
            )
            assertEquals("继续观察", reopened.trading().observeTrend("RANGE-FIXTURE").first()?.reason)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun installedSchema30PreservesTrendAndPersistsPlansByAccountAndOpening() = runBlocking<Unit> {
        createInstalledSchema(30)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL(
                "INSERT INTO trading_trend (symbol, direction, reason, bullishReason, bearishReason) VALUES (?, ?, ?, ?, ?)",
                arrayOf("BTCUSDT", "BEARISH", "", "观察反弹", "趋势走弱"),
            )
        }
        val opening = TradingPositionKey("plan-account", "BOTH:LONG:fill:10")
        val later = TradingPositionKey("plan-account", "BOTH:LONG:fill:30")
        val first = MebotDatabase.create(context)
        try {
            val dao = first.trading()
            val repository = TradingRepository(first, AndroidKeystoreCredentialCipher())
            assertEquals(MebotDatabase.VERSION, first.openHelper.writableDatabase.version)
            assertEquals("趋势走弱", repository.trend.first()?.bearishReason)
            assertNull(dao.trendAt("BTCUSDT", 0))
            assertEquals("BEARISH", dao.trendAt("BTCUSDT", Long.MAX_VALUE))
            dao.saveConnection(TradingConnection(scope = opening.scope, ciphertext = byteArrayOf(1), iv = byteArrayOf(2)))
            repository.savePositionPlan(opening, "59,000", "62,000")
            assertEquals(mapOf(opening to TradingPositionPlan("59000", "62000")), repository.positionPlans(TradingContract.BTC).first())
            assertNull(repository.positionPlans(TradingContract.BTC).first()[later])
        } finally {
            first.close()
        }
        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, AndroidKeystoreCredentialCipher())
            assertEquals(TradingPositionPlan("59000", "62000"), repository.positionPlans(TradingContract.BTC).first()[opening])
            assertNull(repository.positionPlans(TradingContract.BTC).first()[later])
            reopened.trading().saveConnection(TradingConnection(
                scope = "other-account", ciphertext = byteArrayOf(1), iv = byteArrayOf(2),
            ))
            assertTrue(repository.positionPlans(TradingContract.BTC).first().isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun installedSchema31PreservesNotesAndKeepsClosedPositionsAcrossReopen() = runBlocking<Unit> {
        createInstalledSchema(31)
        val first = MebotDatabase.create(context)
        val open = TradingFill("history-account", 10, 10, 1_790_000_000_000, "BUY", "BOTH", "60000", "0.01", "0",
            reason = "记录开仓依据")
        val close = open.copy(tradeId = 11, time = open.time + 600_000, side = "SELL", price = "60500", realizedPnl = "5")
        try {
            val dao = first.trading()
            assertEquals(MebotDatabase.VERSION, first.openHelper.writableDatabase.version)
            val legacy = dao.connection()!!
            assertEquals("retained-31", legacy.scope)
            assertNull(dao.fillSync(legacy.scope, TradingContract.BTC.symbol))
            val legacyTrend = dao.observeTrend("retained-31").first()
            assertEquals("retained-31", legacyTrend?.bullishReason)
            dao.saveConnection(TradingConnection(scope = open.scope, ciphertext = null, iv = null))
            dao.storeFills(listOf(open, close))
            dao.saveFillSync(TradingFillSync(open.scope, open.symbol, open.time, close.time, false))
            val rows = com.txl.mebot.data.trading.closedTradingPositions(
                dao.fillsBetween(open.scope, open.time, close.time), emptyList(),
            ).positions
            dao.saveClosedPositions(rows)
            dao.saveClosedPositions(rows)
            assertEquals(1, dao.observeClosedPositions().first().size)
        } finally {
            first.close()
        }
        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, AndroidKeystoreCredentialCipher())
            val history = repository.closedPositions(TradingContract.BTC, 2).first().single()
            assertEquals(close.time, history.closedAt)
            assertEquals("5", history.realizedPnl)
            assertEquals("记录开仓依据", repository.positionReview(history).fills.first().reason)
            assertEquals(open.time, reopened.trading().fillSync(open.scope, open.symbol)?.fromTime)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun installedSchema32KeepsBtcRecordsAndSeparatesMatchingTradesAndCandlesAfterReopen() = runBlocking<Unit> {
        createInstalledSchema(32)
        val btc = TradingContract.BTC
        val eth = TradingContract.ETH
        val hype = TradingContract.HYPE
        val scope = "retained-32"
        val time = 1_790_000_000_000L
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL("UPDATE trading_connection SET fillsSyncedFrom = ?, fillsSyncedThrough = ?", arrayOf(time - 1, time))
            db.execSQL("UPDATE trading_fills SET time = ?, price = '60000', quantity = '0.01', positionSide = 'BOTH', side = 'SELL'", arrayOf(time))
            db.execSQL("UPDATE trading_candles SET interval = '15m', time = ?, close = 60000", arrayOf(time))
            db.execSQL("UPDATE trading_closed_positions SET positionSide = 'BOTH', firstTradeAt = ?, closedAt = ?", arrayOf(time, time))
        }

        val upgraded = MebotDatabase.create(context)
        try {
            val dao = upgraded.trading()
            val connection = dao.connection()!!
            assertTrue(connection.ciphertext!!.contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(connection.iv!!.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(TradingFillSync(scope, btc.symbol, time - 1, time, true), dao.fillSync(scope, btc.symbol))
            assertNull(dao.fillSync(scope, eth.symbol))
            val btcFill = dao.observeFills(btc.symbol).first().single()
            val btcClose = dao.observeClosedPositions(btc.symbol).first().single()
            val btcCandle = dao.candles("15m", Long.MAX_VALUE, 1, btc.symbol).single()
            assertEquals("retained-32", btcFill.reason)
            assertEquals("retained-32", dao.observeLines(btc.symbol).first().single().id)
            assertEquals("retained-32", btcClose.realizedPnl)
            assertEquals(60000.0, btcCandle.close, 0.0)
            dao.storeFills(listOf(btcFill.copy(symbol = eth.symbol, price = "1700", reason = null)))
            dao.saveClosedPositions(listOf(btcClose.copy(symbol = eth.symbol, realizedPnl = "42", openingQuantity = null, closingQuantity = null)))
            dao.storeCandles(listOf(btcCandle.copy(symbol = eth.symbol, close = 1700.0), btcCandle.copy(symbol = hype.symbol, close = 62.827)))
            val repository = TradingRepository(upgraded, AndroidKeystoreCredentialCipher())
            repository.saveReason(dao.observeFills(eth.symbol).first().single(), "ETH 复盘")
            repository.saveTrend(TradingTrendDirection.SIDEWAYS, "测试利多", "")
            dao.saveConnection(connection.copy(ciphertext = null, iv = null))
        } finally {
            upgraded.close()
        }

        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, AndroidKeystoreCredentialCipher())
            assertFalse(repository.connected())
            assertEquals("retained-32", repository.fills(btc, time).first().single().reason)
            assertEquals("ETH 复盘", repository.fills(eth, time).first().single().reason)
            assertTrue(repository.fills(eth, time + 1).first().isEmpty())
            val ethClose = repository.closedPositions(eth, 2).first().single()
            assertEquals("42", ethClose.realizedPnl)
            assertEquals("1700", repository.positionReview(ethClose).fills.single().price)
            assertEquals("retained-32", repository.closedPositions(btc, 2).first().single().realizedPnl)
            assertTrue(repository.closedPositions(hype, 2).first().isEmpty())
            assertTrue(repository.lines(eth).first().isEmpty())
            assertEquals("测试利多", repository.trend.first()?.bullishReason)
            assertEquals(60000.0, repository.cachedCandles(btc, TradingInterval.M15).single().close, 0.0)
            assertEquals(1700.0, repository.cachedCandles(eth, TradingInterval.M15).single().close, 0.0)
            assertEquals(62.827, repository.cachedCandles(hype, TradingInterval.M15).single().close, 0.0)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun historySummaryIncludesAllLocalHistoryAndUpdatesForTheActiveAccountAndContract() = runBlocking<Unit> {
        val database = MebotDatabase.create(context)
        try {
            val dao = database.trading()
            val repository = TradingRepository(database, AndroidKeystoreCredentialCipher())
            val eth = TradingContract.ETH
            val account = TradingConnection(scope = "history-account", ciphertext = null, iv = null)
            val oldest = TradingClosedPosition(
                scope = account.scope, symbol = eth.symbol, positionSide = "BOTH", closingTradeId = 1,
                side = "LONG", firstTradeId = 0, firstTradeAt = 1, openingKnown = true, closedAt = 2,
                openingQuantity = null, closingQuantity = null, realizedPnl = "-2.41000001", trendAtOpen = null,
            )
            val latestPage = List(POSITION_HISTORY_PAGE) { index ->
                oldest.copy(closingTradeId = index + 2L, closedAt = 3L + index, realizedPnl = "0.10")
            }
            val breakEven = oldest.copy(closingTradeId = 98, closedAt = 199, realizedPnl = "0")
            val otherAccount = oldest.copy(scope = "other", realizedPnl = "400")
            val btc = oldest.copy(symbol = TradingContract.BTC.symbol, realizedPnl = "1000")
            dao.saveConnection(account)
            dao.saveClosedPositions(latestPage + listOf(oldest, breakEven, otherAccount, btc))
            assertEquals(POSITION_HISTORY_PAGE, repository.closedPositions(eth, POSITION_HISTORY_PAGE).first().size)
            assertEquals("1000", repository.closedPositionSummary(TradingContract.BTC).first().netPnl.toPlainString())
            assertEquals(TradingHistorySummary(BigDecimal.ZERO, null), repository.closedPositionSummary(TradingContract.HYPE).first())

            val summaries = Channel<TradingHistorySummary>()
            val collector = launch { repository.closedPositionSummary(eth).collect { summaries.send(it) } }
            try {
                val initial = withTimeout(10_000) { summaries.receive() }
                assertEquals("-0.01000001", initial.netPnl.toPlainString())
                assertEquals(POSITION_HISTORY_PAGE * 100.0 / (POSITION_HISTORY_PAGE + 2), initial.winRatePercent!!, 0.0)
                dao.saveClosedPositions(listOf(oldest.copy(closingTradeId = 99, closedAt = 200, realizedPnl = "0.05")))
                val updated = withTimeout(10_000) { summaries.receive() }
                assertEquals("0.03999999", updated.netPnl.toPlainString())
                assertEquals((POSITION_HISTORY_PAGE + 1) * 100.0 / (POSITION_HISTORY_PAGE + 3), updated.winRatePercent!!, 0.0)
                dao.saveConnection(account.copy(scope = otherAccount.scope))
                val switched = withTimeout(10_000) { summaries.receive() }
                assertEquals("400", switched.netPnl.toPlainString())
                assertEquals(100.0, switched.winRatePercent!!, 0.0)
            } finally {
                collector.cancelAndJoin()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun installedSchema33PreservesHistoryAndRetainsAddedUsdtContractsAcrossReopen() = runBlocking<Unit> {
        createInstalledSchema(33)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL("UPDATE trading_closed_positions SET symbol = 'BTCUSDT', realizedPnl = '42.125'")
        }
        val downloads = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = when (request.url.encodedPath) {
                "/fapi/v1/exchangeInfo" -> {
                    downloads.incrementAndGet()
                    JSONObject().put("symbols", JSONArray()
                        .put(exchangeContract("SOL", "0.0100"))
                        .put(exchangeContract("1000PEPE", "0.00000010"))
                        .put(exchangeContract("币安人生", "0.0001000"))
                        .put(exchangeContract("QQQ", "0.01000").put("contractType", "TRADIFI_PERPETUAL"))
                        .put(exchangeContract("AAPL", "0.01000").put("contractType", "TRADIFI_PERPETUAL"))
                        .put(exchangeContract("SETTLING", "0.01")
                            .put("contractType", "TRADIFI_PERPETUAL").put("status", "SETTLING"))
                        .put(exchangeContract("HALTED", "0.01").put("status", "CLOSE"))
                        .put(exchangeContract("EXPIRY", "0.01").put("contractType", "CURRENT_QUARTER"))).toString()
                }
                "/fapi/v1/ticker/24hr" -> when (request.url.queryParameter("symbol")) {
                    "SOLUSDT" -> """{"symbol":"SOLUSDT","lastPrice":"125.80","priceChangePercent":"-1.85"}"""
                    "QQQUSDT" -> """{"symbol":"QQQUSDT","lastPrice":"627.11","priceChangePercent":"0.65"}"""
                    else -> error("Unexpected ticker")
                }
                "/fapi/v1/klines" -> {
                    assertEquals("QQQUSDT", request.url.queryParameter("symbol"))
                    assertTrue(request.url.queryParameter("interval") in setOf("1m", "15m"))
                    """[[1795358700000,"627.10","627.20","626.97","627.11","1059.15"]]"""
                }
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        val cipher = AndroidKeystoreCredentialCipher()
        val sol = TradingContract("SOLUSDT", "SOL", 2)
        val pepe = TradingContract("1000PEPEUSDT", "1000PEPE", 7)
        val chinese = TradingContract("币安人生USDT", "币安人生", 4)
        val qqq = TradingContract("QQQUSDT", "QQQ", 2)
        val aapl = TradingContract("AAPLUSDT", "AAPL", 2)
        val upgraded = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(upgraded, cipher, BinanceTradingApi(client))
            suspend fun rejection(input: String) =
                (runCatching { repository.addWatchedContract(input) }.exceptionOrNull() as? TradingException)?.problem

            assertEquals(TradingContract.defaults, repository.watchedContracts.first())
            assertEquals("42.125", repository.closedPositionSummary(TradingContract.BTC).first().netPnl.toPlainString())
            assertEquals(TradingProblem.CONTRACT_ALREADY_ADDED, rejection("eth/usdt"))
            assertEquals(TradingProblem.CONTRACT_INPUT, rejection("SOL/USDC"))
            assertEquals(0, downloads.get())

            assertEquals(sol, repository.addWatchedContract(" sol "))
            assertEquals(pepe, repository.addWatchedContract("1000pepe-usdt"))
            assertEquals(chinese, repository.addWatchedContract("币安人生"))
            assertEquals(qqq, repository.addWatchedContract("QQQusdt"))
            assertEquals(aapl, repository.addWatchedContract("aapl"))
            assertEquals(TradingProblem.CONTRACT_ALREADY_ADDED, rejection("SOLUSDT"))
            assertEquals(TradingProblem.CONTRACT_ALREADY_ADDED, rejection("QQQ"))
            assertEquals(TradingProblem.CONTRACT_UNAVAILABLE, rejection("HALTED"))
            assertEquals(TradingProblem.CONTRACT_UNAVAILABLE, rejection("EXPIRY"))
            assertEquals(TradingProblem.CONTRACT_UNAVAILABLE, rejection("SETTLING"))
            assertEquals(MarketQuote(125.80, -1.85), repository.quote(sol))
            assertEquals(MarketQuote(627.11, 0.65), repository.quote(qqq))
            val qqqCandle = repository.recent(qqq, TradingInterval.M15).single()
            assertEquals("QQQUSDT", qqqCandle.symbol)
            assertEquals(627.11, qqqCandle.close, 0.0)
            val minute = repository.recent(qqq, TradingInterval.M1).single()
            assertEquals("1m", minute.interval)
            assertEquals(listOf(minute), repository.cachedCandles(qqq, TradingInterval.M1))
            assertEquals(listOf(qqqCandle), repository.cachedCandles(qqq, TradingInterval.M15))
            assertEquals(1, downloads.get())
        } finally {
            upgraded.close()
        }
        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, cipher)
            val restored = repository.watchedContracts.first()
            assertEquals(TradingContract.defaults, restored.take(3))
            assertEquals(setOf(sol, pepe, chinese, qqq, aapl), restored.drop(3).toSet())
            assertEquals(8, restored.size)
            assertEquals(627.11, repository.cachedCandles(qqq, TradingInterval.M15).single().close, 0.0)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun sharedTrendStartsFromBtcAndAppliesToEveryContractWithoutRewritingPastDecisions() = runBlocking<Unit> {
        val btc = TradingContract.BTC.symbol
        val eth = TradingContract.ETH.symbol
        val past = System.currentTimeMillis() - 60_000L
        val cipher = AndroidKeystoreCredentialCipher()
        val before = MebotDatabase.create(context)
        val bearish = TradingTrendEntity(btc, "BEARISH", "", "", "BTC 的判断")
        val bullish = TradingTrendEntity(eth, "BULLISH", "", "ETH 的判断", "")
        try {
            val dao = before.trading()
            dao.saveTrend(bearish)
            dao.saveTrend(bullish)
            dao.storeTrendHistory(TradingTrendHistoryEntity(symbol = btc, direction = bearish.direction, recordedAt = past - 2_000L))
            dao.storeTrendHistory(TradingTrendHistoryEntity(symbol = eth, direction = bullish.direction, recordedAt = past - 1_000L))

            val repository = TradingRepository(before, cipher)
            assertEquals(TradingTrend(TradingTrendDirection.BEARISH, "", "BTC 的判断"), repository.trend.first())
            assertEquals("BEARISH", dao.trendAt(eth, Long.MAX_VALUE))
            assertEquals("BEARISH", dao.trendAt("TQQQUSDT", Long.MAX_VALUE))

            repository.saveTrend(TradingTrendDirection.SIDEWAYS, "共同利多理由", "共同利空理由")
            for (symbol in listOf(btc, eth, "TQQQUSDT")) {
                assertEquals("SIDEWAYS", dao.trendAt(symbol, Long.MAX_VALUE))
            }
            assertEquals("BEARISH", dao.trendAt(btc, past))
            assertEquals("BULLISH", dao.trendAt(eth, past))
            assertNull(dao.trendAt("TQQQUSDT", past))
            assertEquals(bullish, dao.observeTrend(eth).first())
        } finally {
            before.close()
        }

        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, cipher)
            assertEquals(
                TradingTrend(TradingTrendDirection.SIDEWAYS, "共同利多理由", "共同利空理由"),
                repository.trend.first(),
            )
            assertEquals("SIDEWAYS", reopened.trading().trendAt("SOLUSDT", Long.MAX_VALUE))
            assertEquals("BULLISH", reopened.trading().trendAt(eth, past))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun sharedTrendUsesTheLatestSavedDecisionWhenBtcWasUnset() = runBlocking<Unit> {
        val database = MebotDatabase.create(context)
        try {
            val dao = database.trading()
            dao.saveTrend(TradingTrendEntity("ETHUSDT", "BULLISH", "", "较早记录", ""))
            dao.saveTrend(TradingTrendEntity("SOLUSDT", "BEARISH", "原始理由", "", "较新记录"))
            dao.storeTrendHistory(TradingTrendHistoryEntity(symbol = "ETHUSDT", direction = "BULLISH", recordedAt = 1L))
            dao.storeTrendHistory(TradingTrendHistoryEntity(symbol = "SOLUSDT", direction = "BEARISH", recordedAt = 2L))
            val repository = TradingRepository(database, AndroidKeystoreCredentialCipher())
            assertEquals(
                TradingTrend(TradingTrendDirection.BEARISH, "", "较新记录", "原始理由"),
                repository.trend.first(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun installedSchema34KeepsTradeHistoryAndConsolidatesNotesOnlyOnce() = runBlocking<Unit> {
        createInstalledSchema(34)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL("UPDATE trading_closed_positions SET symbol = 'ETHUSDT', realizedPnl = '26.80'")
        }
        val eth = TradingContract.ETH
        val tqqq = TradingContract("TQQQUSDT", "TQQQ", 2)
        val ethNote = "  观察反弹强度\n等待下一根 K 线确认  "
        val tqqqNote = "先记录计划\n复盘 📓"
        val edited = "全局计划\n下单前复核止损"
        val cipher = AndroidKeystoreCredentialCipher()
        val before = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(before, cipher)
            assertEquals(MebotDatabase.VERSION, before.openHelper.writableDatabase.version)
            assertEquals("26.8", repository.closedPositionSummary(eth).first().netPnl.toPlainString())
            assertTrue(repository.watchedContracts.first().any { it.symbol == "retained-34" })
            before.trading().saveNote(TradingNote(eth.symbol, ethNote))
            before.trading().saveNote(TradingNote(tqqq.symbol, tqqqNote))
            assertEquals("【ETHUSDT】\n$ethNote\n\n【TQQQUSDT】\n$tqqqNote", repository.note.first())
            assertEquals(edited, repository.saveNote(edited))
        } finally {
            before.close()
        }

        val reopened = MebotDatabase.create(context)
        try {
            val repository = TradingRepository(reopened, cipher)
            assertEquals(edited, repository.note.first())
            repository.saveNote("")
            assertEquals("", TradingRepository(reopened, cipher).note.first())
            assertEquals(ethNote, reopened.trading().note(eth.symbol))
            assertEquals(tqqqNote, reopened.trading().note(tqqq.symbol))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun installedSchema35KeepsTheWatchlistAndTradeNotesWhenContractsAreReorderedOrRemoved() = runBlocking<Unit> {
        createInstalledSchema(35)
        val qqq = TradingContract("QQQUSDT", "QQQ", 2)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL("UPDATE trading_watchlist SET symbol = 'QQQUSDT', asset = 'QQQ', priceDecimals = 2")
            db.execSQL("UPDATE trading_notes SET symbol = 'QQQUSDT', text = '旧复盘'")
        }
        val btc = TradingContract.BTC
        val eth = TradingContract.ETH
        val hype = TradingContract.HYPE
        val cipher = AndroidKeystoreCredentialCipher()
        withDatabase { database ->
            val repository = TradingRepository(database, cipher)
            assertEquals(MebotDatabase.VERSION, database.openHelper.writableDatabase.version)
            assertEquals(TradingContract.defaults + qqq, repository.watchedContracts.first())
            val desired = listOf(qqq, hype, eth, btc)
            repository.reorderWatchedContracts(desired.map { it.symbol })
            assertTrue(runCatching { repository.reorderWatchedContracts(listOf(btc.symbol, btc.symbol)) }.isFailure)
            assertEquals(desired, repository.watchedContracts.first())
            repository.removeWatchedContract(btc.symbol)
            repository.removeWatchedContract(qqq.symbol)
            assertEquals("旧复盘", repository.note.first())
            assertEquals(listOf(hype, eth), repository.watchedContracts.first())
        }
        withDatabase { database ->
            val repository = TradingRepository(database, cipher)
            assertEquals(listOf(hype, eth), repository.watchedContracts.first())
            assertEquals(btc, repository.addWatchedContract("btc"))
            assertEquals(listOf(hype, eth, btc), repository.watchedContracts.first())
            assertEquals("旧复盘", repository.note.first())
            for (contract in TradingContract.defaults) repository.removeWatchedContract(contract.symbol)
        }
        withDatabase { database ->
            assertTrue(TradingRepository(database, cipher).watchedContracts.first().isEmpty())
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun customWatchlistImagesAreCopiedAndOnlyReleasedWhenTheirLastLinkIsRemoved() = runBlocking<Unit> {
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        val original = png(Color.BLUE)
        val replacement = png(Color.YELLOW)
        val eth = TradingContract.ETH.symbol
        val hype = TradingContract.HYPE.symbol
        val cipher = AndroidKeystoreCredentialCipher()
        val paths = withDatabase { database ->
            val repository = TradingRepository(database, cipher, media = LocalMediaStore(context, database))
            repository.saveWatchedIcon(eth) { original.inputStream() }
            repository.saveWatchedIcon(hype) { original.inputStream() }
            val first = requireNotNull(repository.watchedIconPath(eth).first())
            val id = requireNotNull(database.trading().watchedContract(eth)?.iconAssetId)
            assertEquals(0, database.mediaDao().deleteIfUnused(id))
            assertEquals(first, repository.watchedIconPath(hype).first())
            assertTrue(File(context.filesDir, first).readBytes().contentEquals(original))
            assertTrue(runCatching { repository.saveWatchedIcon(eth) { byteArrayOf(1, 2, 3).inputStream() } }.isFailure)
            assertEquals(first, repository.watchedIconPath(eth).first())
            repository.saveWatchedIcon(eth) { replacement.inputStream() }
            first to requireNotNull(repository.watchedIconPath(eth).first())
        }
        withDatabase { database ->
            val storage = LocalMediaStore(context, database)
            val repository = TradingRepository(database, cipher, media = storage)
            assertEquals(paths.first, repository.watchedIconPath(hype).first())
            assertEquals(paths.second, repository.watchedIconPath(eth).first())
            repository.removeWatchedContract(eth)
            storage.cleanPendingDeletions()
            assertTrue(File(context.filesDir, paths.first).isFile)
            assertFalse(File(context.filesDir, paths.second).exists())
            assertNull(repository.watchedIconPath(eth).first())
        }
    }

    private suspend fun <T> withDatabase(run: suspend (MebotDatabase) -> T): T {
        val database = MebotDatabase.create(context)
        return try {
            run(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun installedSchema36KeepsCustomWatchlistIconsAndNotesWhenAddingPriceAlerts() = runBlocking<Unit> {
        createInstalledSchema(36)
        withDatabase { database ->
            val db = database.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            val trading = database.trading()
            assertEquals("retained-36", trading.observeNote("retained-36").first())
            assertEquals("retained-36", trading.watchedContract("retained-36")?.iconAssetId)
            assertNull(database.tradingAlerts().connection())
            assertEquals(emptyList<Any>(), database.tradingAlerts().observeRules("BTCUSDT").first())
        }
    }

    @Test
    fun installedSchema37PreservesTheWatchlistAndPersistsGroupedOrder() = runBlocking<Unit> {
        createInstalledSchema(37)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL("UPDATE trading_watchlist SET symbol = 'QQQUSDT', asset = 'QQQ', hidden = 0, sortOrder = 0")
            db.execSQL(
                "INSERT INTO trading_watchlist (symbol, asset, priceDecimals, addedAt, sortOrder, hidden) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("BZUSDT", "BZ", 2, 2L, 1, 0),
            )
            db.execSQL(
                "INSERT INTO trading_watchlist (symbol, asset, priceDecimals, addedAt, sortOrder, hidden) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("TQQQUSDT", "TQQQ", 2, 3L, 2, 0),
            )
        }
        val original = listOf("QQQUSDT", "BZUSDT", "TQQQUSDT", "BTCUSDT", "ETHUSDT", "HYPEUSDT")
        val cipher = AndroidKeystoreCredentialCipher()
        withDatabase { database ->
            val db = database.openHelper.writableDatabase
            val repo = TradingRepository(database, cipher)
            assertEquals(MebotDatabase.VERSION, db.version)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals(original, repo.watchedContracts.first().map { it.symbol })
            assertTrue(repo.watchlist.first().all { it.category == null })

            repo.setWatchlistCategory("QQQUSDT", "  大盘  ")
            repo.setWatchlistCategory("TQQQUSDT", "大盘")
            repo.setWatchlistCategory("BZUSDT", "原油")
            val grouped = listOf("QQQUSDT", "TQQQUSDT", "BZUSDT", "BTCUSDT", "ETHUSDT", "HYPEUSDT")
            assertEquals(grouped, repo.watchedContracts.first().map { it.symbol })
            assertTrue(runCatching { repo.reorderWatchedContracts(original) }.isFailure)
            assertEquals(grouped, repo.watchedContracts.first().map { it.symbol })
            repo.reorderWatchedContracts(listOf("TQQQUSDT", "QQQUSDT") + grouped.drop(2))
        }
        withDatabase { database ->
            val repo = TradingRepository(database, cipher)
            val entries = repo.watchlist.first()
            assertEquals(listOf("TQQQUSDT", "QQQUSDT", "BZUSDT"), entries.take(3).map { it.contract.symbol })
            assertEquals(listOf("大盘", "大盘", "原油", null, null, null), entries.map { it.category })
            assertEquals("retained-37", database.trading().watchedContract("QQQUSDT")?.iconAssetId)
            assertEquals("retained-37", database.tradingAlerts().connection()?.endpoint)

            repo.setWatchlistCategory("BZUSDT", null)
            assertNull(repo.watchlist.first().first { it.contract.symbol == "BZUSDT" }.category)
            assertTrue(repo.watchlist.first().none { it.category == "原油" })
        }
    }

    @Test
    fun installedSchema38KeepsTheWatchlistAndPersistsDailyPerformance() = runBlocking<Unit> {
        createInstalledSchema(38)
        val first = TradingPerformanceDayRecord("retained-38", 20_000L, 1_728_086_399_999L, "100.00")
        val next = TradingPerformanceDayRecord(
            "retained-38", 20_001L, 1_728_172_799_999L, "99.00",
            transfersFrom = first.time, deposited = "0", withdrawn = "2.00", weightedNet = "-1.00",
        )
        withDatabase { database ->
            val db = database.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            val trading = database.trading()
            assertEquals("retained-38", trading.watchedContract("retained-38")?.category)
            assertEquals("retained-38", trading.observeNote("retained-38").first())
            assertEquals("retained-38", database.tradingAlerts().connection()?.endpoint)
            assertTrue(trading.performanceDays("retained-38").isEmpty())
            trading.savePerformanceDays(listOf(first, next))
        }
        withDatabase { database ->
            assertEquals(listOf(first, next), database.trading().performanceDays("retained-38"))
            assertTrue(database.trading().performanceDays("second-account").isEmpty())
        }
    }

    @Test
    fun installedSchema39KeepsDailyPerformanceAndPersistsNews() = runBlocking<Unit> {
        createInstalledSchema(39)
        val article = TradingNewsRecord("retained-39", "main", "fixture-1", "Test headline", 1L, null)
        withDatabase { database ->
            val db = database.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, db.version)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals("retained-39", database.tradingAlerts().connection()?.endpoint)
            assertEquals("retained-39", database.trading().performanceDays("retained-39").single().balance)
            assertEquals("retained-39", database.trading().observeNote("retained-39").first())
            assertTrue(database.tradingNews().observeHistory(100, dayStart = 0L, dayEnd = Long.MAX_VALUE).first().isEmpty())
            database.tradingNews().save(listOf(article))
        }
        withDatabase { database ->
            assertEquals(listOf(article), database.tradingNews().observeHistory(100, dayStart = 0L, dayEnd = Long.MAX_VALUE).first().map { it.record })
        }
    }

    @Test
    fun installedSchema40KeepsExistingNewsAndNoticeClaimsWhenAddingUnread() = runBlocking<Unit> {
        createInstalledSchema(40)
        val legacy = TradingNewsRecord(
            "retained-40", "main", "legacy", "Previously stored news", 1L,
            "https://example.invalid/legacy", noticeClaimed = true,
        )
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { db ->
            db.execSQL(
                "UPDATE trading_news SET channel = ?, newsId = ?, title = ?, url = ?",
                arrayOf(legacy.channel, legacy.newsId, legacy.title, legacy.url),
            )
        }
        withDatabase { database ->
            val room = database.openHelper.writableDatabase
            assertEquals(MebotDatabase.VERSION, room.version)
            room.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals("retained-40", database.trading().observeNote("retained-40").first())
            assertEquals(listOf(legacy), database.tradingNews().observeHistory(100, dayStart = 0L, dayEnd = Long.MAX_VALUE).first().map { it.record })
            assertFalse(database.tradingNews().observeHasUnread(100, dayStart = 0L, dayEnd = Long.MAX_VALUE).first())
            assertEquals(0, database.tradingNews().claimNotice(legacy.deviceId, legacy.channel, legacy.newsId))
            database.tradingNews().save(listOf(legacy.copy(newsId = "new", publishedAt = 2L, unread = true)))
        }
        withDatabase { database ->
            assertTrue(database.tradingNews().observeHasUnread(100, dayStart = 0L, dayEnd = Long.MAX_VALUE).first())
            assertEquals(listOf("new", "legacy"), database.tradingNews().observeHistory(100, dayStart = 0L, dayEnd = Long.MAX_VALUE).first().map { it.record.newsId })
        }
    }

    private fun exchangeContract(asset: String, tick: String): JSONObject = JSONObject()
        .put("symbol", asset + "USDT")
        .put("baseAsset", asset)
        .put("quoteAsset", "USDT")
        .put("marginAsset", "USDT")
        .put("contractType", "PERPETUAL")
        .put("status", "TRADING")
        .put("filters", JSONArray().put(JSONObject().put("filterType", "PRICE_FILTER").put("tickSize", tick)))

    private fun createInstalledSchema(version: Int) {
        val relative = "schemas/com.txl.mebot.data.local.MebotDatabase/$version.json"
        val schema = JSONObject((File(relative).takeIf(File::exists) ?: File("app/$relative")).readText())
            .getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val path = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile?.mkdirs() }
        val marker = "$" + "{TABLE_NAME}"
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace(marker, name))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(i).getString("createSql").replace(marker, name))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.beginTransaction()
            try {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val fields = entity.getJSONArray("fields")
                    val columns = (0 until fields.length()).joinToString(", ") {
                        "\"" + fields.getJSONObject(it).getString("columnName") + "\""
                    }
                    val args = Array<Any>(fields.length()) {
                        when (fields.getJSONObject(it).getString("affinity")) {
                            "INTEGER" -> 1L
                            "REAL" -> 1.25
                            "BLOB" -> byteArrayOf(1, 2, 3)
                            else -> "retained-$version"
                        }
                    }
                    val placeholders = args.joinToString(", ") { "?" }
                    db.execSQL("INSERT INTO " + entity.getString("tableName") + " (" + columns + ") VALUES (" + placeholders + ")", args)
                }
                db.version = version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
