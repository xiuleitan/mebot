package com.txl.mebot.data.trading.news

import java.time.Instant
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val TradingNewsPushType = "trading_news"
internal const val TradingNewsPageSize = 100
internal const val TradingPriceAlertNewsChannel = "price_alert"
// Older previews only read rows keyed by the server's device ID.
internal const val TradingPriceAlertNewsOwnerPrefix = "local-price-alert:"

internal enum class TradingNewsChannel(val key: String) {
    MAIN("main"),
    TRADFI("tradfi"),
    PRICE_ALERT(TradingPriceAlertNewsChannel),
}

internal data class TradingNewsStory(
    val id: String,
    val channel: TradingNewsChannel,
    val title: String,
    val publishedAt: Long,
    val url: String?,
    val unread: Boolean = false,
    val alertSymbol: String? = null,
) {
    val key: String
        get() = "${channel.key}:$id"

    val displayTitle: String
        get() = title.trimStart { it == '*' || it.isWhitespace() }

    fun shouldNotify(now: Long): Boolean =
        channel == TradingNewsChannel.MAIN && publishedAt in (now - 5 * 60_000L)..(now + 60_000L)
}

internal data class TradingNewsPush(val deviceId: String, val story: TradingNewsStory)

internal data class TradingNewsBatch(val deviceId: String, val stories: List<TradingNewsStory>)

internal fun tradingNewsPush(data: Map<String, String>, now: Long): TradingNewsPush? {
    if (data["type"] != TradingNewsPushType || !validNewsInstance(data["instance_id"])) return null
    val device = data["device_id"]?.takeIf {
        it.length == 36 && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
    } ?: return null
    val story = tradingNewsStory(data["news_id"], data["channel"], data["title"], data["published_at"], data["url"], now)
        ?: return null
    return TradingNewsPush(device, story)
}

internal fun validNewsInstance(id: String?): Boolean =
    id != null && id.length in 1..80 && id.all { it.isLetterOrDigit() || it == '-' }

internal fun tradingNewsStory(
    id: String?,
    channel: String?,
    title: String?,
    publishedAt: String?,
    link: String?,
    now: Long,
): TradingNewsStory? {
    val newsId = id?.takeIf { it.length in 1..128 && it.all { char -> char in '!'..'~' } } ?: return null
    val source = when (channel) {
        TradingNewsChannel.MAIN.key -> TradingNewsChannel.MAIN
        TradingNewsChannel.TRADFI.key -> TradingNewsChannel.TRADFI
        else -> return null
    }
    val headline = title?.trim()?.replace(HeadlineBreaks, " ")
        ?.takeIf { it.length in 1..1_024 && it.none(Char::isISOControl) } ?: return null
    val time = publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?.takeIf { it in 1..(now + 60_000L) } ?: return null
    val url = link?.takeIf { it.length in 1..2_048 && it.none(Char::isISOControl) }?.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
    return TradingNewsStory(newsId, source, headline, time, url?.toString())
}

private val HeadlineBreaks = Regex("[\\r\\n\\t]+")
