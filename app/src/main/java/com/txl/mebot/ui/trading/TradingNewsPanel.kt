package com.txl.mebot.ui.trading

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.txl.mebot.R
import com.txl.mebot.data.trading.news.TradingNewsChannel
import com.txl.mebot.data.trading.news.TradingNewsStory
import com.txl.mebot.ui.common.hapticAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NewsMinute = DateTimeFormatter.ofPattern("HH:mm")
private val NewsMonthDay = DateTimeFormatter.ofPattern("MM-dd")
private val NewsFullDate = DateTimeFormatter.ofPattern("uuuu-MM-dd")
private val NewsHeadlineMinHeight = 38.dp
private val MainNewsTimeBackground = Color(0xFFFFC45C)

private fun newsCalendar(): Pair<ZoneId, LocalDate> {
    val zone = ZoneId.systemDefault()
    return zone to LocalDate.now(zone)
}

@Composable
internal fun TradingNewsPreview(latest: List<TradingNewsStory>, pushStatus: TradingPushStatus, hasUnread: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val unreadDescription = if (hasUnread) stringResource(R.string.trading_news_unread) else null
    Box(Modifier.semantics { unreadDescription?.let { stateDescription = it } }) {
        Column(
            Modifier.heightIn(min = 48.dp).fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = if (latest.isEmpty()) Alignment.Center else Alignment.CenterStart,
            ) {
                if (latest.isEmpty()) {
                    Text(stringResource(R.string.trading_news), style = fonts.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (story in latest) {
                            Column {
                                Text(stringResource(story.channel.nameResource), style = fonts.labelSmall, color = colors.primary)
                                Text(
                                    story.displayTitle, style = fonts.bodySmall, color = colors.onSurface,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            val (label, color) = when (pushStatus) {
                TradingPushStatus.CHECKING -> R.string.trading_push_checking to colors.onSurfaceVariant
                TradingPushStatus.CONNECTED -> R.string.trading_push_connected to TradingUp
                TradingPushStatus.OFFLINE -> R.string.trading_push_offline to TradingDown
                TradingPushStatus.UNCONFIRMED -> R.string.trading_push_unconfirmed to TradingDown
                TradingPushStatus.NOT_CONFIGURED -> R.string.trading_push_not_configured to colors.onSurfaceVariant
            }
            Text(
                stringResource(label), modifier = Modifier.align(Alignment.End), style = fonts.labelSmall,
                color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasUnread) {
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 3.dp)
                    .size(5.dp).background(MainNewsTimeBackground, CircleShape),
            )
        }
    }
}

@Composable
internal fun TradingNewsDrawerContent(
    stories: List<TradingNewsStory>,
    openedUnread: Set<String> = emptySet(),
    onOpenContract: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val context = LocalContext.current
    var calendar by remember { mutableStateOf(newsCalendar()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                calendar = newsCalendar()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val (zone, today) = calendar
    val minute = remember(zone) { NewsMinute.withZone(zone) }
    val days = remember(stories, zone) {
        stories.groupBy { Instant.ofEpochMilli(it.publishedAt).atZone(zone).toLocalDate() }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.trading_news),
                modifier = Modifier.semantics { heading() },
                style = fonts.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = hapticAction(onClose)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(18.dp))
            }
        }
        if (stories.isEmpty()) {
            Text(stringResource(R.string.trading_news_empty), style = fonts.bodySmall, color = colors.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((day, headlines) in days) {
                    item(key = day.toEpochDay()) { TradingNewsDateDivider(day, today) }
                    items(headlines, key = { it.key }) { story ->
                        TradingNewsHeadline(
                            story, minute.format(Instant.ofEpochMilli(story.publishedAt)),
                            unread = story.unread || story.key in openedUnread,
                            onOpenContract = onOpenContract,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TradingNewsDateDivider(day: LocalDate, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    val date = day.format(if (day.year == today.year) NewsMonthDay else NewsFullDate)
    val label = if (day == today) stringResource(R.string.trading_news_today_date, date) else date
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
        Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
    }
}

@Composable
private fun TradingNewsHeadline(story: TradingNewsStory, time: String, unread: Boolean, onOpenContract: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val url = story.url
    val alertSymbol = story.alertSymbol
    val mainNews = story.channel == TradingNewsChannel.MAIN
    val channel = stringResource(story.channel.nameResource)
    val timeDescription = if (unread) stringResource(R.string.trading_news_unread_channel, channel) else channel
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides NewsHeadlineMinHeight) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = hapticAction {
                if (alertSymbol != null) {
                    onOpenContract(alertSymbol)
                } else if (url != null) {
                    try { uriHandler.openUri(url) }
                    catch (_: IllegalArgumentException) {
                        Toast.makeText(context, R.string.trading_news_link_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = alertSymbol != null || url != null,
            shape = RoundedCornerShape(6.dp),
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
        ) {
            Box(
                Modifier.heightIn(min = NewsHeadlineMinHeight).padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.alignByBaseline()) {
                        Text(
                            time,
                            modifier = Modifier.semantics { stateDescription = timeDescription }
                                .background(if (mainNews) MainNewsTimeBackground else Color.Transparent, RoundedCornerShape(4.dp))
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            style = fonts.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = if (mainNews) TradingBackground else colors.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (unread) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(6.dp)
                                    .background(MainNewsTimeBackground, CircleShape)
                                    .border(1.dp, colors.surfaceContainer, CircleShape),
                            )
                        }
                    }
                    Text(story.displayTitle, Modifier.weight(1f).alignByBaseline(), style = fonts.bodySmall)
                }
            }
        }
    }
}

private val TradingNewsChannel.nameResource: Int
    get() = when (this) {
        TradingNewsChannel.MAIN -> R.string.trading_news_main
        TradingNewsChannel.TRADFI -> R.string.trading_news_tradfi
        TradingNewsChannel.PRICE_ALERT -> R.string.trading_news_price_alert
    }
