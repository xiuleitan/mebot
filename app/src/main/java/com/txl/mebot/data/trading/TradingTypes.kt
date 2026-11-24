package com.txl.mebot.data.trading

import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingFill
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal const val BTC_CONTRACT = "BTCUSDT"
internal const val TRADING_QUOTE_ASSET = "USDT"
internal const val CANDLE_PAGE = 240
internal const val CANDLE_CACHE_LIMIT = CANDLE_PAGE * 16
internal const val POSITION_HISTORY_PAGE = 24
internal const val TREND_REASON_LIMIT = 1_000
internal const val TRADE_PAGE = 1_000
internal const val TRADE_WINDOW_MS = 7L * 24 * 60 * 60 * 1_000
internal const val WATCHLIST_CATEGORY_LIMIT = 24

internal data class TradingContract(val symbol: String, val asset: String, val priceDecimals: Int) {
    val streamSymbol = symbol.lowercase(Locale.ROOT)

    companion object {
        val BTC = TradingContract(BTC_CONTRACT, "BTC", 2)
        val ETH = TradingContract("ETHUSDT", "ETH", 2)
        val HYPE = TradingContract("HYPEUSDT", "HYPE", 3)
        val defaults = listOf(BTC, ETH, HYPE)
    }
}

internal data class TradingWatchlistEntry(val contract: TradingContract, val category: String? = null)

internal fun normalizeUsdtSymbol(input: String): String? {
    val parts = input.trim().uppercase(Locale.ROOT).split('/', '-', limit = 3)
    val asset = when {
        parts.size == 1 -> parts[0].removeSuffix(TRADING_QUOTE_ASSET).trimEnd()
        parts.size == 2 && parts[1].trim() == TRADING_QUOTE_ASSET -> parts[0].trimEnd()
        else -> return null
    }
    return if (asset.isNotEmpty() && asset.all(Char::isLetterOrDigit)) asset + TRADING_QUOTE_ASSET else null
}

internal enum class TradingTrendDirection { BULLISH, BEARISH, SIDEWAYS }

internal data class TradingTrend(
    val direction: TradingTrendDirection,
    val bullishReason: String,
    val bearishReason: String,
    val legacyReason: String = "",
)

internal enum class TradingInterval(val apiValue: String, val label: String, val chartSpan: String, val multiplier: Int) {
    M1("1m", "1分", "minute", 1),
    M5("5m", "5分", "minute", 5),
    M15("15m", "15分", "minute", 15),
    H1("1h", "1小时", "hour", 1),
    H4("4h", "4小时", "hour", 4),
    D1("1d", "日线", "day", 1),
    D3("3d", "3日", "day", 3),
    W1("1w", "周线", "week", 1),
    MONTH1("1M", "月线", "month", 1),
}

internal data class TradingPosition(
    val side: String,
    val positionSide: String,
    val updatedAt: Long,
    val quantity: String,
    val entryPrice: String,
    val markPrice: String,
    val unrealizedPnl: String,
)

internal data class TradingPositionSnapshot<T>(val positions: List<T>, val openSymbols: Set<String>)

internal data class TradingBalancePoint(val time: Long, val balance: BigDecimal)

internal sealed interface MarketUpdate

internal data class MarketTick(val candle: TradingCandle, val closed: Boolean) : MarketUpdate

internal data class MarketQuote(val lastPrice: Double, val changePercent24h: Double) : MarketUpdate

internal data class MarketMarkPrice(val price: BigDecimal) : MarketUpdate

internal data class TradingHistory(val bars: List<TradingCandle>, val hasEarlier: Boolean)

internal data class TradingHistorySummary(val netPnl: BigDecimal, val winRatePercent: Double?)

internal enum class DrawingTool(val overlay: String, val label: String) {
    HORIZONTAL("horizontalStraightLine", "水平线"),
    SEGMENT("segment", "趋势线"),
}

internal data class TradingPoint(val time: Long, val price: Double)

internal data class TradingLineMove(val id: String, val endpoint: Int, val time: Long?, val price: Double)

internal class TradingCredentials(val key: String, val secret: String) {
    fun isValid(): Boolean = listOf(key, secret).all { value ->
        value.length in 16..128 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }
}

internal enum class TradingProblem(val message: String) {
    NETWORK("币安连接失败，请检查网络后重试"),
    UNSUPPORTED_LOCATION("当前网络无法访问币安合约服务"),
    ACCESS("币安拒绝访问，请检查密钥、U 本位合约账户和 IP 限制"),
    WRITE_PERMISSION("此密钥有交易或资金操作权限，请使用只开启「读取」的密钥"),
    RATE_LIMIT("币安请求较频繁，请稍后重试"),
    RESPONSE("币安返回的数据无法读取"),
    STORAGE("无法读取或保存本地记录，请重试"),
    INPUT("请输入完整的系统生成 API Key 和 Secret Key"),
    ACCOUNT_REQUIRED("请先在交易设置连接币安账户"),
    CONTRACT_INPUT("请输入币种代码或 USDT 合约名"),
    CONTRACT_UNAVAILABLE("找不到可交易的 USDT 永续合约"),
    CONTRACT_ALREADY_ADDED("该合约已在列表中"),
}

internal class TradingException(val problem: TradingProblem) : IllegalStateException(problem.message)

internal fun oldestTradeTime(serverTime: Long): Long {
    val calendarLimit = Instant.ofEpochMilli(serverTime).atZone(ZoneOffset.UTC).minusMonths(3).toInstant().toEpochMilli()
    val dayLimit = serverTime - 90L * 24 * 60 * 60 * 1_000
    return maxOf(calendarLimit, dayLimit) + 60_000
}

internal fun TradingFill.actionLabel(): String = when (positionSide to side) {
    "LONG" to "BUY" -> "买入 · 多仓"
    "LONG" to "SELL" -> "卖出 · 多仓"
    "SHORT" to "SELL" -> "卖出 · 空仓"
    "SHORT" to "BUY" -> "买入 · 空仓"
    else -> if (side == "BUY") "买入成交" else "卖出成交"
}
