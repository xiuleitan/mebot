package com.txl.mebot.data.trading

import android.app.Application
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FearGreedRepositoryTest {
    @get:Rule val cache = TemporaryFolder()

    @Test
    fun parsesCryptoRatingScoreAndSourceTime() {
        assertEquals(
            FearGreedReading(18.0, FearGreedRating.EXTREME_FEAR, Instant.parse("2026-11-22T04:08:10Z")),
            parseFearGreed(FearGreedMarket.CRYPTO, crypto(18, "2026-11-22T04:08:10Z")),
        )
    }

    @Test
    fun parsesLatestTradingDayWithoutInventingAnIntradayTime() {
        assertEquals(
            FearGreedReading(9.67, FearGreedRating.EXTREME_FEAR, Instant.parse("2026-11-20T00:00:00Z")),
            parseFearGreed(FearGreedMarket.US_STOCKS, StockCsv),
        )
    }

    @Test
    fun restoresCacheAcrossReopeningAndRefreshesAfterUtcDayChanges() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val client = clientReturning {
            requests.incrementAndGet()
            crypto(18, "2026-11-22T04:08:10Z")
        }

        val initial = pair(repository(client, "2026-11-22T12:00:00Z"), FearGreedMarket.CRYPTO)
        assertNull(initial.first().reading)
        assertEquals(18.0, initial.last().reading!!.score, 0.0)
        assertEquals(1, requests.get())

        val restored = mutableListOf<FearGreedValue>()
        withTimeoutOrNull(400) {
            repository(client, "2026-11-22T23:59:59Z").observe(FearGreedMarket.CRYPTO).take(2).toList(restored)
        }
        assertEquals(listOf(initial.last()), restored)
        assertEquals(1, requests.get())

        val nextDay = pair(repository(client, "2026-11-23T00:00:00Z"), FearGreedMarket.CRYPTO)
        assertEquals(initial.last(), nextDay.first())
        assertEquals(2, requests.get())
    }

    @Test
    fun failedStockUpdateKeepsItsCacheWhileCryptoUpdates() = runBlocking<Unit> {
        val first = repository(clientReturning { market ->
            when (market) {
                FearGreedMarket.CRYPTO -> crypto(18, "2026-11-22T04:08:10Z")
                FearGreedMarket.US_STOCKS -> StockCsv
            }
        }, "2026-11-22T12:00:00Z")
        val oldStock = pair(first, FearGreedMarket.US_STOCKS).last()
        pair(first, FearGreedMarket.CRYPTO)

        val next = repository(clientReturning { market ->
            when (market) {
                FearGreedMarket.CRYPTO -> crypto(20, "2026-11-23T05:08:10Z")
                FearGreedMarket.US_STOCKS -> "Invalid CSV response"
            }
        }, "2026-11-23T12:00:00Z")
        val stock = async { pair(next, FearGreedMarket.US_STOCKS).last() }
        val crypto = async { pair(next, FearGreedMarket.CRYPTO).last() }
        assertEquals(oldStock.reading, stock.await().reading)
        assertTrue(stock.await().refreshFailed)
        assertEquals(20.0, crypto.await().reading!!.score, 0.0)
        assertFalse(crypto.await().refreshFailed)
    }

    private fun repository(client: OkHttpClient, time: String) =
        FearGreedRepository(cache.root, client, Clock.fixed(Instant.parse(time), ZoneOffset.UTC))

    private suspend fun pair(repository: FearGreedRepository, market: FearGreedMarket): List<FearGreedValue> =
        withTimeout(5_000) { repository.observe(market).take(2).toList() }

    private fun clientReturning(body: (FearGreedMarket) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.header("Authorization") == null && request.header("X-CMC_PRO_API_KEY") == null)
        val market = FearGreedMarket.entries.single { it.endpoint == request.url.toString() }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body(market).toResponseBody()).build()
    }.build()

    private fun crypto(score: Int, time: String) = """
        {"data":{"value":$score,"value_classification":"Extreme fear","update_time":"$time"}}
    """.trimIndent()

    private companion object {
        val StockCsv = """
            Date,Fear Greed,Rating
            2026-11-19,15.87,extreme fear
            2026-11-20,9.67,extreme fear

        """.trimIndent()
    }
}
