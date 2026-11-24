package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.TradingConnection
import com.txl.mebot.data.local.TradingTrendHistoryEntity
import com.txl.mebot.data.models.CredentialCipher
import com.txl.mebot.data.models.EncryptedCredential
import com.txl.mebot.data.trading.BinanceTradingApi
import com.txl.mebot.data.trading.TRADING_DAY_MS
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingCredentials
import com.txl.mebot.data.trading.TradingException
import com.txl.mebot.data.trading.TradingProblem
import com.txl.mebot.data.trading.TradingRepository
import com.txl.mebot.data.trading.binanceSignature
import com.txl.mebot.data.trading.tradingDay
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TradingHistorySyncTest {
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
    fun replayWaitsForAStableSnapshotThenArchivesClosedRoundsOnce() = runBlocking<Unit> {
        val account = FakeAccount()
        val database = MebotDatabase.create(context)
        try {
            database.trading().saveConnection(TradingConnection(
                scope = "history-fixture", ciphertext = byteArrayOf(1), iv = byteArrayOf(2),
            ))
            database.trading().storeTrendHistory(TradingTrendHistoryEntity(
                symbol = "BTCUSDT", direction = "BULLISH", recordedAt = account.now - 1_000_000,
            ))
            database.trading().storeTrendHistory(TradingTrendHistoryEntity(
                symbol = "BTCUSDT", direction = "BEARISH", recordedAt = account.now - 100,
            ))
            val repository = TradingRepository(database, FixtureCipher, BinanceTradingApi(account.client))
            suspend fun sync() = repository.syncAccount(
                TradingContract.BTC,
                onPosition = { assertTrue(it.isEmpty()) },
                onPositionError = { error(it.message) },
            )

            sync()
            assertTrue(repository.closedPositions(TradingContract.BTC, 10).first().isEmpty())
            account.now += 5_000
            sync()
            val history = repository.closedPositions(TradingContract.BTC, 10).first()
            assertEquals(listOf(4L, 2L), history.map { it.closingTradeId })
            assertEquals("BULLISH", history.last().trendAtOpen)
            assertEquals("BEARISH", history.first().trendAtOpen)
            assertEquals("0.5", history.last().realizedPnl)
            assertEquals(listOf(1L, 2L), repository.positionReview(history.last()).fills.map { it.tradeId })
            sync()
            assertEquals(history, repository.closedPositions(TradingContract.BTC, 10).first())
            assertFalse(database.trading().fillSync("history-fixture", "BTCUSDT")!!.historyPending)
        } finally {
            database.close()
        }
    }

    @Test
    fun firstSyncOfAnotherContractUsesItsOwnWindowAndArchivesItsOwnFills() = runBlocking<Unit> {
        val account = FakeAccount()
        val btc = TradingContract.BTC
        val eth = TradingContract.ETH
        val scope = "one-account"
        val database = MebotDatabase.create(context)
        try {
            val dao = database.trading()
            dao.saveConnection(TradingConnection(scope = scope, ciphertext = byteArrayOf(1), iv = byteArrayOf(2)))
            val repository = TradingRepository(database, FixtureCipher, BinanceTradingApi(account.client))
            suspend fun sync(contract: TradingContract) = repository.syncAccount(
                contract, onPosition = { assertTrue(it.isEmpty()) }, onPositionError = { error(it.message) },
            )

            account.now += 5_000
            sync(btc)
            val btcCoverage = dao.fillSync(scope, btc.symbol)
            assertTrue(btcCoverage != null)
            assertNull(dao.fillSync(scope, eth.symbol))
            account.now += 30_000
            sync(eth)
            val btcClosed = repository.closedPositions(btc, 10).first().first { it.closingTradeId == 2L }
            val ethClosed = repository.closedPositions(eth, 10).first().single()
            assertEquals(btcClosed.closingTradeId, ethClosed.closingTradeId)
            assertEquals("0.5", btcClosed.realizedPnl)
            assertEquals("8", ethClosed.realizedPnl)
            assertEquals(listOf("1700", "1700"), repository.positionReview(ethClosed).fills.map { it.price })
            assertEquals(btcCoverage, dao.fillSync(scope, btc.symbol))
            val ethCoverage = dao.fillSync(scope, eth.symbol)!!
            assertTrue(ethCoverage.fromTime < btcCoverage!!.throughTime - 60_000)
            assertFalse(ethCoverage.historyPending)
        } finally {
            database.close()
        }
    }

    @Test
    fun onePositionRequestUpdatesAllHeldContractsAndClearsFullyClosedOnes() = runBlocking<Unit> {
        val account = FakeAccount()
        val database = MebotDatabase.create(context)
        try {
            database.trading().saveConnection(TradingConnection(
                scope = "watchlist-fixture", ciphertext = byteArrayOf(1), iv = byteArrayOf(2),
            ))
            val repo = TradingRepository(database, FixtureCipher, BinanceTradingApi(account.client))
            val openSymbols = mutableListOf<Set<String>>()
            val visiblePositions = mutableListOf<List<String>>()
            suspend fun sync() {
                repo.syncAccount(
                    TradingContract.BTC,
                    onPosition = { positions -> visiblePositions += positions.map { it.position.quantity } },
                    onPositionError = { error(it.message) },
                    onOpenPositionSymbols = { openSymbols += it },
                )
            }

            val btc = account.position("BTCUSDT", "BOTH", "0.25")
            val ethLong = account.position("ETHUSDT", "LONG", "1.5")
            val ethShort = account.position("ETHUSDT", "SHORT", "-1.5")
            val unfilled = account.position("HYPEUSDT", "BOTH", "0.000")
            account.positionRows = listOf(btc, ethLong, ethShort, unfilled)
            sync()
            assertEquals(listOf("0.25"), visiblePositions.last())

            account.now += 30_000
            account.positionRows = listOf(ethShort, unfilled)
            sync()
            assertEquals(emptyList<String>(), visiblePositions.last())

            account.now += 30_000
            account.positionRows = listOf(unfilled)
            sync()
            assertEquals(listOf(setOf("BTCUSDT", "ETHUSDT"), setOf("ETHUSDT"), emptySet()), openSymbols)
            assertEquals(3, account.positionRequests)
        } finally {
            database.close()
        }
    }

    @Test
    fun dailyPerformanceSurvivesFailedTransferSyncAndReopensFromRoomWithoutRequests() = runBlocking<Unit> {
        var database = MebotDatabase.create(context)
        val account = FakeAccount()
        val last = tradingDay(account.now) * TRADING_DAY_MS - 1
        val first = last - TRADING_DAY_MS
        try {
            val repo = TradingRepository(database, FixtureCipher, BinanceTradingApi(account.client))
            assertNull(repo.performance().first())
            assertTrue(repo.syncDailyPerformance())
            assertTrue(account.snapshotRequests.isEmpty())
            val connection = TradingConnection(scope = "first-account", ciphertext = byteArrayOf(1), iv = byteArrayOf(2))
            database.trading().saveConnection(connection)
            account.balanceSnapshots = listOf(
                account.snapshot(last, "USDT", "800.00"),
                account.snapshot(first, "USDT", "1000.00"),
                account.snapshot(first - TRADING_DAY_MS, "USDC", "400.00"),
                account.snapshot(first - 31 * TRADING_DAY_MS, "USDT", "1200.00"),
                account.snapshot(last + 2, "USDT", "9900.00"),
            )
            val transfer = account.transfer(1, first + TRADING_DAY_MS / 2, "-250.00")
            account.transferRows = listOf(transfer, transfer, account.transfer(2, first + 1, "-600.00", "USDC"))

            account.transferStatus = 503
            val failed = runCatching { repo.syncDailyPerformance() }.exceptionOrNull() as? TradingException
            assertEquals(TradingProblem.NETWORK, failed?.problem)
            assertEquals(listOf(first, last), database.trading().performanceDays(connection.scope).map { it.time })
            assertNull(repo.performance().first()?.profit)

            account.transferStatus = 200
            assertTrue(repo.syncDailyPerformance())
            val report = checkNotNull(repo.performance().first())
            assertEquals("50.00", report.profit?.toPlainString())
            assertEquals("250.00", report.withdrawn?.toPlainString())
            assertEquals(listOf(first, last), report.returns.map { it.time })
            val request = account.snapshotRequests.single()
            assertEquals("api.binance.com", request.url.host)
            assertEquals("FUTURES", request.url.queryParameter("type"))
            assertEquals("30", request.url.queryParameter("limit"))
            val transferRequest = account.transferRequests.last()
            assertEquals("fapi.binance.com", transferRequest.url.host)
            assertEquals("TRANSFER", transferRequest.url.queryParameter("incomeType"))
            assertEquals((first + 1).toString(), transferRequest.url.queryParameter("startTime"))
            assertEquals(last.toString(), transferRequest.url.queryParameter("endTime"))
            for (signedRequest in listOf(request, transferRequest)) {
                assertEquals("GET", signedRequest.method)
                assertEquals("fixture-key", signedRequest.header("X-MBX-APIKEY"))
                val unsigned = checkNotNull(signedRequest.url.encodedQuery).substringBeforeLast("&signature=")
                assertEquals(binanceSignature("fixture-secret", unsigned), signedRequest.url.queryParameter("signature"))
            }
            assertTrue(repo.syncDailyPerformance())
            assertEquals(1, account.snapshotRequests.size)
            assertEquals(2, account.transferRequests.size)

            account.now += TRADING_DAY_MS
            val next = last + TRADING_DAY_MS
            account.balanceSnapshots = listOf(account.snapshot(next, "USDT", "900.00"))
            account.transferRows = listOf(account.transfer(3, next, "100.00"))
            assertTrue(repo.syncDailyPerformance())
            val updated = checkNotNull(repo.performance().first())
            assertEquals(report.profit, updated.profit)
            assertEquals(report.withdrawn, updated.withdrawn)
            assertEquals(listOf(first, last, next), updated.returns.map { it.time })

            database.close()
            database = MebotDatabase.create(context)
            val offline = OkHttpClient.Builder().addInterceptor { error("Reading stored performance must not reach the network") }.build()
            val reopened = TradingRepository(database, FixtureCipher, BinanceTradingApi(offline))
            assertEquals(updated, reopened.performance().first())
            database.trading().saveConnection(connection.copy(scope = "second-account"))
            assertNull(reopened.performance().first()?.profit)
            reopened.disconnect()
            assertNull(reopened.performance().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun aFullTransferPageSplitsTheTimeWindowWithoutDroppingRows() = runBlocking<Unit> {
        val account = FakeAccount()
        val start = account.now - 10_000
        account.transferRows = (0L..1_000L).map { offset -> account.transfer(offset, start + offset, "-2.50") }
        val exchange = BinanceTradingApi(account.client).account(TradingCredentials("fixture-key", "fixture-secret"))
        val ledger = exchange.walletTransfers(start, start + 1_000)
        assertEquals((start..start + 1_000).toList(), ledger.map { it.time })
        assertEquals(3, account.transferRequests.size)
    }

    private class FakeAccount {
        var now = 1_790_000_000_000L
        var positionRows = emptyList<JSONObject>()
        var balanceSnapshots = emptyList<JSONObject>()
        var transferRows = emptyList<JSONObject>()
        var transferStatus = 200
        val snapshotRequests = CopyOnWriteArrayList<Request>()
        val transferRequests = CopyOnWriteArrayList<Request>()
        var positionRequests = 0
            private set
        private var clocks = 0
        private val fills = listOf(
            fill(1, now - 900_000, "BUY", "0"),
            fill(2, now - 300_000, "SELL", "0.5"),
            fill(3, now + 100, "BUY", "0"),
            fill(4, now + 200, "SELL", "-0.1"),
        )
        private val ethFills = listOf(
            fill(1, now - 900_000, "BUY", "0"),
            fill(2, now - 300_000, "SELL", "8"),
        ).map { it.put("symbol", "ETHUSDT").put("price", "1700") }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> JSONObject().put("serverTime", now + (clocks++ % 2) * 1_000).toString()
                "/sapi/v1/accountSnapshot" -> {
                    snapshotRequests += request
                    JSONObject().put("code", 200).put("snapshotVos", JSONArray(balanceSnapshots)).toString()
                }
                "/fapi/v1/income" -> {
                    transferRequests += request
                    status = transferStatus
                    val start = request.url.queryParameter("startTime")!!.toLong()
                    val end = request.url.queryParameter("endTime")!!.toLong()
                    val limit = request.url.queryParameter("limit")!!.toInt()
                    JSONArray(transferRows.filter { it.getLong("time") in start..end }.take(limit)).toString()
                }
                "/fapi/v3/positionRisk" -> {
                    assertNull(request.url.queryParameter("symbol"))
                    positionRequests++
                    JSONArray(positionRows).toString()
                }
                "/fapi/v1/userTrades" -> {
                    val start = request.url.queryParameter("startTime")!!.toLong()
                    val end = request.url.queryParameter("endTime")!!.toLong()
                    val rows = when (request.url.queryParameter("symbol")) {
                        "BTCUSDT" -> fills
                        "ETHUSDT" -> ethFills
                        else -> error("Unexpected contract")
                    }
                    JSONArray(rows.filter { it.getLong("time") in start..end }).toString()
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toResponseBody()).build()
        }.build()

        private fun fill(id: Long, time: Long, side: String, pnl: String) = JSONObject()
            .put("symbol", "BTCUSDT").put("id", id).put("orderId", id).put("time", time)
            .put("side", side).put("positionSide", "BOTH").put("price", "60000")
            .put("qty", "0.01").put("realizedPnl", pnl)

        fun position(symbol: String, side: String, quantity: String) = JSONObject()
            .put("symbol", symbol).put("positionSide", side).put("positionAmt", quantity)
            .put("entryPrice", "60000").put("markPrice", "61000").put("unRealizedProfit", "250")
            .put("updateTime", now - 60_000)

        fun snapshot(time: Long, asset: String, balance: String): JSONObject {
            val value = JSONObject().put("asset", asset).put("marginBalance", "800.00").put("walletBalance", balance)
            return JSONObject().put("updateTime", time)
                .put("data", JSONObject().put("assets", JSONArray().put(value)))
        }

        fun transfer(id: Long, time: Long, amount: String, asset: String = "USDT"): JSONObject = JSONObject()
            .put("tranId", id).put("time", time).put("income", amount).put("asset", asset).put("incomeType", "TRANSFER")
    }

    private object FixtureCipher : CredentialCipher {
        override fun encrypt(value: String): EncryptedCredential = error("Fixture credentials are already stored")
        override fun decrypt(value: EncryptedCredential): String = """{"key":"fixture-key","secret":"fixture-secret"}"""
    }
}
