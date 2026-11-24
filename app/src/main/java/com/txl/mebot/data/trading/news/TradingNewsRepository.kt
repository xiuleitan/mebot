package com.txl.mebot.data.trading.news

import androidx.room.withTransaction
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.TradingNewsRecord
import com.txl.mebot.data.trading.alerts.newsRecord
import com.txl.mebot.data.trading.alerts.notification
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class TradingNewsRepository(
    private val database: MebotDatabase,
    private val recentNews: suspend () -> TradingNewsBatch?,
) {
    private val dao get() = database.tradingNews()
    private val alerts get() = database.tradingAlerts()

    val configuredDevice get() = alerts.observeDeviceId().distinctUntilChanged()

    fun latest(day: TradingNewsDay) = combine(
        dao.observeLatest(TradingNewsChannel.PRICE_ALERT.key, day.start, day.nextStart),
        dao.observeLatest(TradingNewsChannel.MAIN.key, day.start, day.nextStart),
        dao.observeLatest(TradingNewsChannel.TRADFI.key, day.start, day.nextStart),
    ) { alert, main, tradfi -> listOfNotNull(alert, main, tradfi).map { it.story() } }.distinctUntilChanged()

    fun history(day: TradingNewsDay) = dao.observeHistory(TradingNewsPageSize, day.start, day.nextStart)
        .map { records -> records.map { it.record.story(unread = it.record.unread, alertSymbol = it.alertSymbol) } }
        .distinctUntilChanged()

    fun hasUnread(day: TradingNewsDay) = dao.observeHasUnread(TradingNewsPageSize, day.start, day.nextStart).distinctUntilChanged()

    suspend fun markOpened(day: TradingNewsDay = tradingNewsDay()): Set<String> = database.withTransaction {
        val deviceId = alerts.connection()?.deviceId ?: return@withTransaction emptySet()
        val unread = dao.historyForDevice(deviceId, TradingNewsPageSize, day.start, day.nextStart)
            .filter { it.unread }
            .mapTo(mutableSetOf()) { it.story().key }
        if (unread.isNotEmpty()) dao.markRead(deviceId)
        unread
    }

    suspend fun refreshPriceAlerts(day: TradingNewsDay) = database.withTransaction {
        dao.deleteExpiredPriceAlerts(day.start)
        val deviceId = alerts.connection()?.deviceId ?: return@withTransaction
        val missing = mutableListOf<TradingNewsRecord>()
        for ((event, previous) in dao.priceEventsNeedingSummary(deviceId, day.start, day.nextStart, TradingNewsPageSize)) {
            val rule = alerts.ruleForEvent(deviceId, event.instanceId, event.symbol, event.ruleId, event.kind)
            val notice = event.notification(rule, message = null, target = null, previousHeadline = previous)
            val record = event.newsRecord(notice, unread = false)
            if (previous == null) missing += record
            else dao.updatePriceAlertHeadline(record.deviceId, record.newsId, record.title)
        }
        if (missing.isNotEmpty()) dao.save(missing)
    }

    suspend fun accept(push: TradingNewsPush, now: Long): TradingNewsStory? = database.withTransaction {
        if (alerts.connection()?.deviceId != push.deviceId) return@withTransaction null
        val story = push.story
        dao.save(listOf(story.record(push.deviceId)))
        if (!story.shouldNotify(now) || dao.claimNotice(push.deviceId, story.channel.key, story.id) == 0) null
        else story
    }

    suspend fun refresh() {
        val batch = recentNews() ?: return
        if (batch.stories.isEmpty()) return
        database.withTransaction {
            if (alerts.connection()?.deviceId == batch.deviceId) {
                dao.save(batch.stories.map { it.record(batch.deviceId) })
            }
        }
    }

    private fun TradingNewsStory.record(deviceId: String) = TradingNewsRecord(
        deviceId = deviceId, channel = channel.key, newsId = id, title = title, publishedAt = publishedAt, url = url,
        unread = true,
    )

    private fun TradingNewsRecord.story(unread: Boolean = false, alertSymbol: String? = null) = TradingNewsStory(
        newsId, TradingNewsChannel.entries.first { it.key == channel }, title, publishedAt, url, unread, alertSymbol,
    )
}
