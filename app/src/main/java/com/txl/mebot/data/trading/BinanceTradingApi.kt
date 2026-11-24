package com.txl.mebot.data.trading

import android.os.SystemClock
import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingFill
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

internal class BinanceTradingApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build(),
) {
    private val futures = "https://fapi.binance.com".toHttpUrl()
    private val wallet = "https://api.binance.com".toHttpUrl()
    private val catalogLock = Mutex()
    private var contracts = emptyMap<String, TradingContract>()
    private var catalogExpiresAt = 0L

    suspend fun findContract(symbol: String): TradingContract? = catalogLock.withLock {
        if (SystemClock.elapsedRealtime() >= catalogExpiresAt) {
            contracts = decode {
                val entries = JSONObject(get(url(futures, "fapi/v1/exchangeInfo"))).getJSONArray("symbols")
                buildMap {
                    for (index in 0 until entries.length()) {
                        val row = entries.getJSONObject(index)
                        val contract = parseListedContract(row) ?: continue
                        put(contract.symbol, contract)
                    }
                }
            }
            catalogExpiresAt = SystemClock.elapsedRealtime() + 5 * 60_000L
        }
        contracts[symbol]
    }

    suspend fun candles(contract: TradingContract, interval: TradingInterval, before: Long? = null): List<TradingCandle> {
        val params = mutableMapOf("symbol" to contract.symbol, "interval" to interval.apiValue, "limit" to CANDLE_PAGE.toString())
        before?.let { params["endTime"] = (it - 1).toString() }
        return decode {
            val items = JSONArray(get(url(futures, "fapi/v1/klines", params)))
            List(items.length()) { index ->
                val row = items.getJSONArray(index)
                validatedCandle(
                    contract, interval, row.getLong(0), row.getDouble(1), row.getDouble(2),
                    row.getDouble(3), row.getDouble(4), row.getDouble(5),
                )
            }.distinctBy { it.time }.sortedBy { it.time }
        }
    }

    suspend fun quote(contract: TradingContract): MarketQuote = decode {
        val ticker = JSONObject(get(url(futures, "fapi/v1/ticker/24hr", mapOf("symbol" to contract.symbol))))
        require(ticker.getString("symbol") == contract.symbol)
        marketQuote(ticker.getDouble("lastPrice"), ticker.getDouble("priceChangePercent"))
    }

    fun market(contract: TradingContract, interval: TradingInterval): Flow<MarketUpdate> = callbackFlow {
        val stream = "wss://fstream.binance.com/market/stream?streams=" +
            "${contract.candleStream(interval)}/${contract.tickerStream}/${contract.markPriceStream}"
        val socket = client.newWebSocket(Request.Builder().url(stream).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMarketUpdate(text, interval, contract)?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(TradingException(problemFor(response?.code ?: 0, null)))
            }
        })
        awaitClose { socket.cancel() }
    }

    suspend fun serverTime(): Long = decode { JSONObject(get(url(futures, "fapi/v1/time"))).getLong("serverTime") }

    suspend fun account(credentials: TradingCredentials): Account =
        Account(credentials, serverTime(), SystemClock.elapsedRealtime())

    internal inner class Account(
        private val credentials: TradingCredentials,
        val serverTime: Long,
        private val syncedAt: Long,
    ) {
        suspend fun verifyReadOnly() {
            val restrictions = decode { JSONObject(signed(wallet, "sapi/v1/account/apiRestrictions")) }
            if (!restrictions.optBoolean("enableReading")) throw TradingException(TradingProblem.ACCESS)
            if (WritablePermissions.any { restrictions.optBoolean(it) }) {
                throw TradingException(TradingProblem.WRITE_PERMISSION)
            }
        }

        suspend fun dailyWalletBalances(): List<TradingBalancePoint> = decode {
            val from = serverTime - TimeUnit.DAYS.toMillis(30) + 60_000
            val params = mapOf(
                "type" to "FUTURES", "limit" to "30",
                "startTime" to from.toString(), "endTime" to serverTime.toString(),
            )
            val response = JSONObject(signed(wallet, "sapi/v1/accountSnapshot", params))
            val code = response.getInt("code")
            if (code != 200) throw TradingException(problemFor(200, code))
            val snapshots = response.getJSONArray("snapshotVos")
            buildMap {
                for (index in 0 until snapshots.length()) {
                    val snapshot = snapshots.getJSONObject(index)
                    val time = snapshot.getLong("updateTime")
                    if (time !in from..serverTime) continue
                    val assets = snapshot.getJSONObject("data").getJSONArray("assets")
                    val balance = assets.usdtBalance("walletBalance") ?: continue
                    put(time, TradingBalancePoint(time, balance))
                }
            }.values.sortedBy { it.time }
        }

        suspend fun walletTransfers(from: Long, through: Long): List<TradingWalletTransfer> = decode {
            require(from <= through)
            val pending = ArrayDeque(listOf(from..through))
            buildMap {
                while (pending.isNotEmpty()) {
                    val window = pending.removeFirst()
                    val rows = JSONArray(signed(futures, "fapi/v1/income", mapOf(
                        "incomeType" to "TRANSFER",
                        "startTime" to window.first.toString(),
                        "endTime" to window.last.toString(),
                        "limit" to TransferPageSize.toString(),
                    )))
                    if (rows.length() >= TransferPageSize) {
                        // A full page may be truncated. Narrow the time window before trusting it.
                        require(window.first < window.last)
                        require(rows.getJSONObject(0).getLong("time") in window)
                        require(rows.getJSONObject(rows.length() - 1).getLong("time") in window)
                        val middle = window.first + (window.last - window.first) / 2
                        pending.addFirst((middle + 1)..window.last)
                        pending.addFirst(window.first..middle)
                        continue
                    }
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        val time = row.getLong("time")
                        require(row.getString("incomeType") == "TRANSFER" && time in window)
                        if (row.getString("asset") != TRADING_QUOTE_ASSET) continue
                        val amount = row.getString("income").toBigDecimal()
                        require(amount.toDouble().isFinite())
                        val transfer = TradingWalletTransfer(time, amount)
                        val previous = put(row.getLong("tranId"), transfer)
                        require(previous == null || previous == transfer)
                    }
                }
            }.values.toList()
        }

        suspend fun positions(contract: TradingContract): TradingPositionSnapshot<TradingPosition> = decode {
            val rows = JSONArray(signed(futures, "fapi/v3/positionRisk"))
            val openSymbols = mutableSetOf<String>()
            val positions = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val symbol = row.getString("symbol")
                    if (!symbol.endsWith(TRADING_QUOTE_ASSET)) continue
                    val amount = row.getString("positionAmt").toBigDecimal()
                    if (amount.signum() == 0) continue
                    openSymbols.add(symbol)
                    if (symbol != contract.symbol) continue
                    val side = row.getString("positionSide")
                    require(side in PositionSides)
                    add(TradingPosition(
                        side = if (side == "LONG" || side == "BOTH" && amount.signum() > 0) "LONG" else "SHORT",
                        positionSide = side,
                        updatedAt = row.optLong("updateTime"),
                        quantity = amount.abs().toPlainString(),
                        entryPrice = decimal(row, "entryPrice"),
                        markPrice = decimal(row, "markPrice"),
                        unrealizedPnl = decimal(row, "unRealizedProfit"),
                    ))
                }
            }
            TradingPositionSnapshot(positions, openSymbols)
        }

        suspend fun fills(scope: String, contract: TradingContract, start: Long, end: Long): List<TradingFill> = decode {
            val params = mapOf(
                "symbol" to contract.symbol, "startTime" to start.toString(), "endTime" to end.toString(),
                "limit" to TRADE_PAGE.toString(),
            )
            val rows = JSONArray(signed(futures, "fapi/v1/userTrades", params))
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                require(row.getString("symbol") == contract.symbol)
                val side = row.getString("side")
                val positionSide = row.getString("positionSide")
                val time = row.getLong("time")
                require(side in FillSides && positionSide in PositionSides && time in start..end)
                TradingFill(
                    scope = scope,
                    tradeId = row.getLong("id"),
                    orderId = row.getLong("orderId"),
                    time = time,
                    side = side,
                    positionSide = positionSide,
                    price = decimal(row, "price"),
                    quantity = decimal(row, "qty"),
                    realizedPnl = decimal(row, "realizedPnl"),
                    symbol = contract.symbol,
                )
            }
        }

        private suspend fun signed(base: HttpUrl, path: String, params: Map<String, String> = emptyMap()): String {
            val timestamp = serverTime + SystemClock.elapsedRealtime() - syncedAt
            val unsigned = url(base, path, params + mapOf("recvWindow" to "5000", "timestamp" to timestamp.toString()))
            val signature = binanceSignature(credentials.secret, checkNotNull(unsigned.encodedQuery))
            val target = unsigned.newBuilder().addQueryParameter("signature", signature).build()
            return get(target, credentials.key)
        }
    }

    private suspend fun get(url: HttpUrl, key: String? = null): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply {
            key?.let { header("X-MBX-APIKEY", it) }
        }.get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(TradingException(TradingProblem.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val source = it.body?.source() ?: throw TradingException(TradingProblem.RESPONSE)
                        if (source.request(ResponseLimit + 1L)) throw TradingException(TradingProblem.RESPONSE)
                        val content = source.readUtf8()
                        if (!it.isSuccessful) {
                            val apiCode = runCatching { JSONObject(content).getInt("code") }.getOrNull()
                            throw TradingException(problemFor(it.code, apiCode))
                        }
                        content
                    }
                }.recoverCatching { error ->
                    throw if (error is IOException) TradingException(TradingProblem.NETWORK) else error
                }
                continuation.resumeWith(result)
            }
        })
    }

    private companion object {
        const val ResponseLimit = 2_000_000L
        const val TransferPageSize = 1_000
        val FillSides = setOf("BUY", "SELL")
        val PositionSides = setOf("BOTH", "LONG", "SHORT")
        val WritablePermissions = setOf(
            "enableFutures", "enableSpotAndMarginTrading", "enableMargin", "enableWithdrawals",
            "enableInternalTransfer", "permitsUniversalTransfer", "enableVanillaOptions",
            "enablePortfolioMarginTrading", "enableFixApiTrade", "enableCopyTrading",
        )
    }
}

private val PerpetualContractTypes = setOf("PERPETUAL", "TRADIFI_PERPETUAL")

private fun parseListedContract(row: JSONObject): TradingContract? {
    val contractType = row.optString("contractType")
    if (contractType !in PerpetualContractTypes || row.optString("status") != "TRADING" ||
        row.optString("quoteAsset") != TRADING_QUOTE_ASSET || row.optString("marginAsset") != TRADING_QUOTE_ASSET
    ) return null

    val symbol = row.optString("symbol")
    val asset = row.optString("baseAsset")
    if (asset.isEmpty() || normalizeUsdtSymbol(symbol) != symbol) return null
    val filters = row.optJSONArray("filters") ?: return null
    val tickSize = (0 until filters.length()).asSequence()
        .mapNotNull { filters.optJSONObject(it) }
        .firstOrNull { it.optString("filterType") == "PRICE_FILTER" }
        ?.optString("tickSize")
        ?.toBigDecimalOrNull()
        ?.takeIf { it.signum() > 0 }
        ?: return null
    val decimals = tickSize.stripTrailingZeros().scale().coerceIn(0, 18)
    return TradingContract(symbol, asset, decimals)
}

private val TradingContract.tickerStream get() = "$streamSymbol@ticker"
private val TradingContract.markPriceStream get() = "$streamSymbol@markPrice@1s"
private fun TradingContract.candleStream(interval: TradingInterval): String = "$streamSymbol@kline_${interval.apiValue}"

internal fun parseMarketUpdate(
    message: String,
    interval: TradingInterval,
    contract: TradingContract = TradingContract.BTC,
): MarketUpdate? = runCatching {
    val envelope = JSONObject(message)
    val data = envelope.getJSONObject("data")
    when (envelope.getString("stream")) {
        contract.candleStream(interval) -> {
            val candle = data.getJSONObject("k")
            require(candle.getString("s") == contract.symbol && candle.getString("i") == interval.apiValue)
            MarketTick(
                validatedCandle(
                    contract, interval, candle.getLong("t"), candle.getDouble("o"), candle.getDouble("h"),
                    candle.getDouble("l"), candle.getDouble("c"), candle.getDouble("v"),
                ),
                candle.getBoolean("x"),
            )
        }
        contract.tickerStream -> {
            require(data.getString("s") == contract.symbol && data.getString("e") == "24hrTicker")
            marketQuote(data.getDouble("c"), data.getDouble("P"))
        }
        contract.markPriceStream -> {
            require(data.getString("s") == contract.symbol && data.getString("e") == "markPriceUpdate")
            val markPrice = data.getString("p").toBigDecimal()
            require(markPrice.signum() > 0)
            MarketMarkPrice(markPrice)
        }
        else -> null
    }
}.getOrNull()

private fun url(origin: HttpUrl, path: String, params: Map<String, String> = emptyMap()): HttpUrl =
    origin.newBuilder().addPathSegments(path).apply {
        params.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

internal fun binanceSignature(secret: String, query: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(query.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun problemFor(status: Int, apiCode: Int?): TradingProblem = when {
    status == 451 -> TradingProblem.UNSUPPORTED_LOCATION
    status == 418 || status == 429 || apiCode == -1003 -> TradingProblem.RATE_LIMIT
    status == 401 || status == 403 || apiCode in setOf(-2014, -2015, -1022) -> TradingProblem.ACCESS
    status == 0 || status >= 500 -> TradingProblem.NETWORK
    else -> TradingProblem.RESPONSE
}

private suspend fun <T> decode(parse: suspend () -> T): T = withContext(Dispatchers.Default) {
    try {
        parse()
    } catch (error: CancellationException) {
        throw error
    } catch (error: TradingException) {
        throw error
    } catch (_: Exception) {
        throw TradingException(TradingProblem.RESPONSE)
    }
}

private fun decimal(row: JSONObject, name: String): String = row.getString(name).also { it.toBigDecimal() }

private fun JSONArray.usdtBalance(field: String): BigDecimal? =
    (0 until length()).asSequence().map(::getJSONObject)
        .firstOrNull { it.getString("asset") == TRADING_QUOTE_ASSET }
        ?.getString(field)?.toBigDecimal()?.also { require(it.toDouble().isFinite()) }

private fun marketQuote(lastPrice: Double, changePercent: Double): MarketQuote {
    require(lastPrice.isFinite() && lastPrice > 0 && changePercent.isFinite())
    return MarketQuote(lastPrice, changePercent)
}

private fun validatedCandle(
    contract: TradingContract, interval: TradingInterval, time: Long, open: Double, high: Double,
    low: Double, close: Double, volume: Double,
): TradingCandle {
    require(time > 0 && listOf(open, high, low, close).all { it.isFinite() && it > 0 })
    require(volume.isFinite() && volume >= 0 && high >= maxOf(open, close) && low <= minOf(open, close))
    return TradingCandle(interval.apiValue, time, open, high, low, close, volume, contract.symbol)
}
