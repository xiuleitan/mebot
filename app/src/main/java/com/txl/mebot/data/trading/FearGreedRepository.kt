package com.txl.mebot.data.trading

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal enum class FearGreedMarket(val endpoint: String, val source: String) {
    CRYPTO(
        "https://pro-api.coinmarketcap.com/public-api/v3/fear-and-greed/latest",
        "CoinMarketCap",
    ),
    US_STOCKS(
        "https://raw.githubusercontent.com/whit3rabbit/fear-greed-data/main/fear-greed.csv",
        "CNN Business · GitHub 快照",
    ),
}

internal enum class FearGreedRating {
    EXTREME_FEAR, FEAR, NEUTRAL, GREED, EXTREME_GREED;

    companion object {
        fun fromSource(value: String): FearGreedRating =
            valueOf(value.trim().replace(' ', '_').uppercase(Locale.ROOT))
    }
}

internal data class FearGreedReading(
    val score: Double,
    val rating: FearGreedRating,
    // The US stock snapshot contains a trading date, represented as midnight UTC.
    val dataTime: Instant,
) {
    init {
        require(score in 0.0..100.0)
    }
}

internal data class FearGreedValue(
    val reading: FearGreedReading? = null,
    val refreshFailed: Boolean = false,
)

internal class FearGreedRepository(
    private val cacheDirectory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Snapshot(val reading: FearGreedReading, val checkedOn: LocalDate)

    private val cacheLocks = FearGreedMarket.entries.associateWith { Mutex() }

    fun observe(market: FearGreedMarket): Flow<FearGreedValue> = flow {
        val file = AtomicFile(File(cacheDirectory, market.name + ".json"))
        while (currentCoroutineContext().isActive) {
            // The CNN mirror updates at 23:00 UTC on weekdays. The UTC day rolls over after that.
            val today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
            cacheLocks.getValue(market).withLock {
                val cached = readSnapshot(file)
                emit(FearGreedValue(cached?.reading))
                if (cached?.checkedOn != today) {
                    emit(refresh(market, file, cached, today))
                }
            }
            val nextDay = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            delay((nextDay.toEpochMilli() - clock.millis()).coerceAtLeast(1))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun refresh(
        market: FearGreedMarket,
        file: AtomicFile,
        cached: Snapshot?,
        today: LocalDate,
    ): FearGreedValue = try {
        val incoming = parseFearGreed(market, download(market))
        val reading = cached?.reading?.takeIf { it.dataTime > incoming.dataTime } ?: incoming
        writeSnapshot(file, Snapshot(reading, today))
        FearGreedValue(reading)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        FearGreedValue(cached?.reading, refreshFailed = true)
    }

    private fun readSnapshot(file: AtomicFile): Snapshot? = runCatching {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        Snapshot(
            reading = FearGreedReading(
                json.getDouble("score"),
                FearGreedRating.valueOf(json.getString("rating")),
                Instant.parse(json.getString("dataTime")),
            ),
            checkedOn = LocalDate.parse(json.getString("checkedOn")),
        )
    }.getOrNull()

    private fun writeSnapshot(file: AtomicFile, snapshot: Snapshot) {
        val data = JSONObject()
            .put("score", snapshot.reading.score)
            .put("rating", snapshot.reading.rating.name)
            .put("dataTime", snapshot.reading.dataTime.toString())
            .put("checkedOn", snapshot.checkedOn.toString())
            .toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(data)
            file.finishWrite(stream)
        } catch (error: IOException) {
            file.failWrite(stream)
            throw error
        }
    }

    private suspend fun download(market: FearGreedMarket): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(market.endpoint).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        val source = it.body?.source() ?: throw IOException("Missing market data")
                        if (source.request(512 * 1024L + 1)) throw IOException("Market data exceeds size limit")
                        source.readUtf8()
                    }
                })
            }
        })
    }
}

internal fun parseFearGreed(market: FearGreedMarket, body: String): FearGreedReading = when (market) {
    FearGreedMarket.CRYPTO -> {
        val data = JSONObject(body).getJSONObject("data")
        FearGreedReading(
            score = data.getDouble("value"),
            rating = FearGreedRating.fromSource(data.getString("value_classification")),
            dataTime = Instant.parse(data.getString("update_time")),
        )
    }
    FearGreedMarket.US_STOCKS -> {
        val lines = body.lineSequence().filter { it.isNotBlank() }.iterator()
        require(lines.hasNext() && lines.next().trim().removePrefix("\uFEFF") == "Date,Fear Greed,Rating")
        val fields = lines.asSequence().last().split(',')
        require(fields.size == 3)
        val date = LocalDate.parse(fields[0].trim())
        FearGreedReading(
            score = fields[1].trim().toDouble(),
            rating = FearGreedRating.fromSource(fields[2]),
            dataTime = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
        )
    }
}
