package com.txl.mebot.data.trading.news

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

internal data class TradingNewsDay(val start: Long, val nextStart: Long) {
    operator fun contains(publishedAt: Long) = publishedAt >= start && publishedAt < nextStart
}

internal fun tradingNewsDay(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): TradingNewsDay {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return TradingNewsDay(
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeTradingNewsDay(context: Context): Flow<TradingNewsDay> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(Unit)
    awaitClose { context.unregisterReceiver(receiver) }
}.transformLatest {
    while (true) {
        val now = System.currentTimeMillis()
        val day = tradingNewsDay(now)
        emit(day)
        delay((day.nextStart - now).coerceAtLeast(1L))
    }
}.distinctUntilChanged()
