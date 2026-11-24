package com.txl.mebot.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.txl.mebot.data.trading.alerts.LegacyPriceAlertDetailSeparator
import com.txl.mebot.data.trading.news.TradingPriceAlertNewsChannel
import com.txl.mebot.data.trading.news.TradingPriceAlertNewsOwnerPrefix
import kotlinx.coroutines.flow.Flow

private const val ActiveDevice = "(SELECT deviceId FROM trading_alert_connection WHERE id = 1)"
private const val ActiveDeviceNews = "deviceId IN ($ActiveDevice, '$TradingPriceAlertNewsOwnerPrefix' || $ActiveDevice)"
private const val NewsForDevice = "deviceId IN (:deviceId, '$TradingPriceAlertNewsOwnerPrefix' || :deviceId)"
private const val CurrentNews = "(channel != '$TradingPriceAlertNewsChannel' OR (publishedAt >= :dayStart AND publishedAt < :dayEnd))"

@Entity(
    tableName = "trading_news",
    primaryKeys = ["deviceId", "channel", "newsId"],
    indices = [Index(value = ["deviceId", "publishedAt"])],
)
internal data class TradingNewsRecord(
    val deviceId: String,
    val channel: String,
    val newsId: String,
    val title: String,
    val publishedAt: Long,
    val url: String?,
    val noticeClaimed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val unread: Boolean = false,
)

internal data class TradingNewsWithContract(@Embedded val record: TradingNewsRecord, val alertSymbol: String?)

internal data class TradingPriceEventForNews(@Embedded val event: TradingPriceAlertEvent, val previousHeadline: String?)

@Dao
internal interface TradingNewsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun save(news: List<TradingNewsRecord>)

    @Query("""
        UPDATE trading_news SET noticeClaimed = 1
        WHERE deviceId = :deviceId AND channel = :channel AND newsId = :newsId AND noticeClaimed = 0
    """)
    suspend fun claimNotice(deviceId: String, channel: String, newsId: String): Int

    @Query("""
        SELECT * FROM trading_news
        WHERE $ActiveDeviceNews AND channel = :channel
        AND $CurrentNews
        ORDER BY publishedAt DESC, newsId DESC LIMIT 1
    """)
    fun observeLatest(channel: String, dayStart: Long, dayEnd: Long): Flow<TradingNewsRecord?>

    @Query("""
        SELECT news.*, CASE WHEN news.channel = '$TradingPriceAlertNewsChannel' THEN (
            SELECT event.symbol FROM trading_price_alert_events AS event
            WHERE event.deviceId = $ActiveDevice
            AND event.instanceId = substr(news.newsId, 1, instr(news.newsId, ':') - 1)
            AND event.eventId = CAST(substr(news.newsId, instr(news.newsId, ':') + 1) AS INTEGER)
        ) END AS alertSymbol
        FROM trading_news AS news
        WHERE $ActiveDeviceNews
        AND $CurrentNews
        ORDER BY publishedAt DESC, channel, newsId DESC LIMIT :limit
    """)
    fun observeHistory(limit: Int, dayStart: Long, dayEnd: Long): Flow<List<TradingNewsWithContract>>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM (
                SELECT unread FROM trading_news
                WHERE $ActiveDeviceNews
                AND $CurrentNews
                ORDER BY publishedAt DESC, channel, newsId DESC LIMIT :limit
            ) WHERE unread = 1
        )
    """)
    fun observeHasUnread(limit: Int, dayStart: Long, dayEnd: Long): Flow<Boolean>

    @Query("""
        SELECT * FROM trading_news WHERE $NewsForDevice AND $CurrentNews
        ORDER BY publishedAt DESC, channel, newsId DESC LIMIT :limit
    """)
    suspend fun historyForDevice(deviceId: String, limit: Int, dayStart: Long, dayEnd: Long): List<TradingNewsRecord>

    @Query("DELETE FROM trading_news WHERE channel = '$TradingPriceAlertNewsChannel' AND publishedAt < :dayStart")
    suspend fun deleteExpiredPriceAlerts(dayStart: Long)

    @Query("""
        SELECT event.*, news.title AS previousHeadline FROM trading_price_alert_events AS event
        LEFT JOIN trading_news AS news
        ON news.deviceId = ('$TradingPriceAlertNewsOwnerPrefix' || event.deviceId)
        AND news.channel = '$TradingPriceAlertNewsChannel'
        AND news.newsId = (event.instanceId || ':' || event.eventId)
        WHERE event.deviceId = :deviceId AND event.triggeredAt >= :dayStart AND event.triggeredAt < :dayEnd
        AND (news.newsId IS NULL OR instr(news.title, '$LegacyPriceAlertDetailSeparator') > 0)
        ORDER BY event.triggeredAt DESC, event.eventId DESC LIMIT :limit
    """)
    suspend fun priceEventsNeedingSummary(deviceId: String, dayStart: Long, dayEnd: Long, limit: Int): List<TradingPriceEventForNews>

    @Query("""
        UPDATE trading_news SET title = :title
        WHERE deviceId = :deviceId AND channel = '$TradingPriceAlertNewsChannel' AND newsId = :newsId
    """)
    suspend fun updatePriceAlertHeadline(deviceId: String, newsId: String, title: String)

    @Query("UPDATE trading_news SET unread = 0 WHERE $NewsForDevice AND unread = 1")
    suspend fun markRead(deviceId: String)
}
