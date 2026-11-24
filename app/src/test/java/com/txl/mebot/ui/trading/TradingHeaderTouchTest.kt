package com.txl.mebot.ui.trading

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.trading.MarketQuote
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.TradingPosition
import com.txl.mebot.data.trading.TradingPositionDetails
import com.txl.mebot.data.trading.TradingPositionKey
import com.txl.mebot.data.trading.TradingPositionPlan
import com.txl.mebot.data.trading.TradingTrend
import com.txl.mebot.data.trading.TradingTrendDirection
import com.txl.mebot.data.trading.news.TradingNewsChannel
import com.txl.mebot.data.trading.news.TradingNewsStory
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TradingHeaderTouchTest {
    @Test
    fun theWholeQuoteRowOpensContractsAndTheTrailingButtonReturnsHome() {
        val contract = TradingContract("INTCUSDT", "INTC", 2)
        val quote = MarketQuote(54.01, 1.68)
        var contractOpens = 0
        var homeReturns = 0
        withTradingContent(content = {
            TradingHeader(
                state = TradingState(contract = contract, quote = quote),
                onReturnHome = { homeReturns++ },
                onOpenContracts = { contractOpens++ },
            )
        }) { activity, nodes ->
            val icon = nodes.text("I").boundsInWindow
            val symbol = nodes.text(contract.symbol).boundsInWindow
            val price = nodes.text(formatPrice(quote.lastPrice)).boundsInWindow
            val change = formatTradingChange(quote.changePercent24h)
            val changeDescription = activity.getString(R.string.trading_change_24h, change)
            val action = activity.getString(R.string.trading_choose_contract)
            val row = nodes.first { it.config.getOrNull(SemanticsActions.OnClick)?.label == action }.boundsInWindow
            val home = nodes.described(activity.getString(R.string.return_home)).boundsInWindow
            val decor = activity.window.decorView

            assertTrue(nodes.none { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(changeDescription) == true ||
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == change } == true
            })
            assertTrue(icon.right < symbol.left)
            assertEquals(icon.center.y, symbol.center.y, 1f)
            assertTrue(symbol.right < price.left)
            assertEquals(row.right - 10f * activity.resources.displayMetrics.density, price.right, 1f)
            assertTrue(row.right <= home.left)
            assertTrue(nodes.none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(activity.getString(R.string.open_sidebar)) == true
            })
            val points = listOf(
                icon.center,
                symbol.center,
                price.center,
                Offset((symbol.right + price.left) / 2, row.center.y),
                Offset(row.right - 2f, row.top + 2f),
            )
            for ((index, point) in points.withIndex()) {
                tap(decor, point)
                assertEquals("contract tap at $point", index + 1, contractOpens)
                assertEquals(0, homeReturns)
            }

            tap(decor, home.center)
            assertEquals(points.size, contractOpens)
            assertEquals(1, homeReturns)
        }
    }

    @Test
    fun watchlistCardShowsTheAssetPriceAndChangeOnOneLine() {
        val contract = TradingContract("1000PEPEUSDT", "1000PEPE", 7)
        val quote = MarketQuote(0.0035421, -12.35)
        var selections = 0
        withTradingContent(content = {
            Box(Modifier.width(274.dp)) {
                TradingContractCard(contract, quote, active = false, hasPosition = true) { selections++ }
            }
        }) { activity, nodes ->
            val asset = nodes.text("1000PEPE").boundsInWindow
            val price = nodes.text(formatPrice(quote.lastPrice, contract.priceDecimals)).boundsInWindow
            val change = nodes.described(
                activity.getString(R.string.trading_change_24h, formatTradingChange(quote.changePercent24h)),
            ).boundsInWindow
            assertTrue(nodes.any { node ->
                node.config.getOrNull(SemanticsProperties.StateDescription) ==
                    activity.getString(R.string.trading_watchlist_has_position)
            })
            assertTrue(nodes.none { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { "USDT" in it.text } == true
            })
            val indicator = nodes.single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "watchlist_position_indicator"
            }.boundsInWindow
            assertTrue(asset.right < indicator.left)
            assertTrue(indicator.right < price.left)
            assertTrue(price.right < change.left)
            assertEquals(asset.center.y, price.center.y, 1f)
            assertEquals(price.center.y, change.center.y, 1f)
            val card = nodes.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(34f * activity.resources.displayMetrics.density, card.boundsInWindow.height, 1f)
            tap(activity.window.decorView, price.center)
            assertEquals(1, selections)
        }
    }

    @Test
    fun onlyPositionsHaveTheAccentBorderAndIndicator() {
        val contracts = TradingContract.defaults.take(3)
        withTradingContent(content = {
            Column(Modifier.width(300.dp)) {
                for ((index, item) in contracts.withIndex()) {
                    TradingContractCard(
                        item, null, active = index == 0, hasPosition = index == 2,
                        categoryBackground = WatchlistCategoryColor.SLATE.background,
                    ) {}
                }
            }
        }) { activity, nodes ->
            assertEquals(1, nodes.count {
                it.config.getOrNull(SemanticsProperties.TestTag) == "watchlist_position_indicator"
            })
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                fun border(asset: String): Int {
                    val card = generateSequence(nodes.text(asset)) { it.parent }
                        .first { it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInWindow
                    val inset = 0.5f * activity.resources.displayMetrics.density
                    return bitmap.getPixel((card.left + inset).toInt(), card.center.y.toInt())
                }
                assertEquals(TradingColors.outlineVariant.toArgb(), border(contracts[0].asset))
                assertEquals(border(contracts[1].asset), border(contracts[0].asset))
                assertEquals(0xFFFFC45C.toInt(), border(contracts[2].asset))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun watchlistIconsUseInitialsForBuiltInAndAddedContracts() {
        val contracts = TradingContract.defaults + listOf(
            TradingContract("SOLUSDT", "SOL", 2),
            TradingContract("1000PEPEUSDT", "1000PEPE", 7),
            TradingContract("币安人生USDT", "币安人生", 4),
        )
        withTradingContent(content = {
            Row {
                contracts.forEach { ContractIcon(it, customIconPath = null) }
            }
        }) { _, nodes ->
            listOf("B", "E", "H", "S", "P", "币").forEach { initial ->
                assertTrue("Visible initial $initial", nodes.text(initial).boundsInWindow.height > 0)
            }
        }
    }

    @Test
    fun theWatchlistMovesAfterALongPressAndCommitsOnlyOnRelease() {
        val starting = TradingContract.defaults
        val order = mutableStateOf(starting)
        var saves = 0
        withTradingContent(content = {
            TradingWatchlistList(
                contracts = order.value,
                state = rememberLazyListState(),
                sorting = true,
                onReorder = { order.value = it; saves++ },
                modifier = Modifier.size(270.dp, 180.dp),
            ) { item, modifier ->
                TradingContractCard(item, null, active = false, modifier = modifier, selectable = false) {}
            }
        }) { activity, nodes ->
            val first = nodes.text(starting.first().asset).boundsInWindow.center
            val last = nodes.text(starting.last().asset).boundsInWindow.center
            val view = activity.window.decorView
            fun dragAndFinish(finish: Int) {
                val down = SystemClock.uptimeMillis()
                dispatchTouch(view, down, MotionEvent.ACTION_DOWN, first)
                repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                for (step in 1..8) {
                    val point = first + (last - first) * (step / 8f)
                    dispatchTouch(view, down, MotionEvent.ACTION_MOVE, point)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                }
                assertEquals(0, saves)
                dispatchTouch(view, down, finish, last)
            }
            dragAndFinish(MotionEvent.ACTION_CANCEL)
            assertEquals(starting, order.value)
            assertEquals(0, saves)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            dragAndFinish(MotionEvent.ACTION_UP)
            assertEquals(1, saves)
            assertEquals(starting.drop(1) + starting.first(), order.value)
        }
    }

    @Test
    fun theRailAndEveryCardUseTheCategoryColorEvenWhenSelected() {
        val contracts = TradingContract.defaults.take(3)
        val categories = mapOf(
            contracts[0].symbol to "主流", contracts[1].symbol to "主流", contracts[2].symbol to "其它",
        )
        val categoryColors = mapOf("主流" to WatchlistCategoryColor.TEAL, "其它" to WatchlistCategoryColor.PURPLE)
        withTradingContent(content = {
            TradingWatchlistList(
                contracts = contracts,
                categories = categories,
                categoryColors = categoryColors,
                state = rememberLazyListState(),
                sorting = false,
                onReorder = {},
                modifier = Modifier.size(290.dp, 160.dp),
            ) { item, modifier ->
                TradingContractCard(
                    item, null, active = item == contracts.first(), modifier = modifier,
                    hasPosition = item == contracts.first(),
                    categoryBackground = categoryColors.getValue(categories.getValue(item.symbol)).background,
                ) {}
            }
        }) { activity, nodes ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val inset = 4f * activity.resources.displayMetrics.density
                for ((name, color) in categoryColors) {
                    val label = nodes.described(name).boundsInWindow
                    assertEquals(color.background.toArgb(), bitmap.getPixel((label.left + inset).toInt(), label.center.y.toInt()))
                }
                for (item in contracts) {
                    val card = generateSequence(nodes.text(item.asset)) { it.parent }
                        .first { it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInWindow
                    val color = categoryColors.getValue(categories.getValue(item.symbol))
                    assertEquals(color.background.toArgb(), bitmap.getPixel(card.center.x.toInt(), card.center.y.toInt()))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun categoryColorPickerMarksTheCurrentColorAndAcceptsAnother() {
        var picked: WatchlistCategoryColor? = null
        withTradingContent(content = {
            TradingWatchlistColorPicker(WatchlistCategoryColor.BLUE, enabled = true) { picked = it }
        }) { activity, nodes ->
            val current = nodes.described(activity.getString(WatchlistCategoryColor.BLUE.label))
            assertEquals(true, current.config.getOrNull(SemanticsProperties.Selected))
            val other = nodes.described(activity.getString(WatchlistCategoryColor.GOLD.label))
            tap(activity.window.decorView, other.boundsInWindow.center)
            assertEquals(WatchlistCategoryColor.GOLD, picked)
        }
    }

    @Test
    fun categorizedCardsShareALeftLabelAndDragOnlyWithinTheirCategory() {
        val starting = TradingContract.defaults
        val order = mutableStateOf(starting)
        val categories = mapOf(starting[0].symbol to "主流", starting[1].symbol to "主流")
        withTradingContent(content = {
            TradingWatchlistList(
                contracts = order.value,
                categories = categories,
                state = rememberLazyListState(),
                sorting = true,
                onReorder = { order.value = it },
                modifier = Modifier.size(270.dp, 180.dp),
            ) { item, modifier ->
                TradingContractCard(item, null, active = false, modifier = modifier, selectable = false) {}
            }
        }) { activity, nodes ->
            val rail = nodes.described("主流").boundsInWindow
            val typeText = nodes.text("主\n流")
            val first = nodes.text(starting[0].asset).boundsInWindow.center
            val second = nodes.text(starting[1].asset).boundsInWindow.center
            val ungrouped = nodes.text(starting[2].asset).boundsInWindow.center
            assertTrue(rail.right < first.x && rail.top < first.y && rail.bottom > second.y)
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(typeText.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            val typeLayout = layouts.single()
            assertEquals(2, typeLayout.lineCount)
            assertEquals(11.sp, typeLayout.layoutInput.style.fontSize)
            assertEquals(FontWeight.Bold, typeLayout.layoutInput.style.fontWeight)
            assertTrue(ungrouped.x < first.x)

            val view = activity.window.decorView
            val down = SystemClock.uptimeMillis()
            dispatchTouch(view, down, MotionEvent.ACTION_DOWN, first)
            repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            for (step in 1..8) {
                val point = first + (ungrouped - first) * (step / 8f)
                dispatchTouch(view, down, MotionEvent.ACTION_MOVE, point)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            assertEquals(starting, order.value)
            dispatchTouch(view, down, MotionEvent.ACTION_UP, ungrouped)
            assertEquals(listOf(starting[1], starting[0], starting[2]), order.value)
        }
    }

    @Test
    fun chartActionsShareTheTextButtonHeightAndKeepTheAlarmClickable() {
        var alertOpens = 0
        val trend = TradingTrend(TradingTrendDirection.SIDEWAYS, "", "")
        for (fontScale in listOf(1f, 1.5f)) {
            withTradingContent(content = {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    TradingChartActions(
                        trend = Result.success(trend), onlyCurrentPosition = true, hasActiveAlerts = true,
                        onShowTrend = {}, onToggleFillScope = {}, onShowPriceAlerts = { alertOpens++ },
                    )
                }
            }) { activity, nodes ->
                fun buttonFor(node: SemanticsNode) = generateSequence(node) { it.parent }
                    .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                val trendLabel = activity.getString(
                    R.string.trading_trend_button, activity.getString(R.string.trading_trend_sideways),
                )
                val trendBounds = buttonFor(nodes.text(trendLabel)).boundsInWindow
                val scopeBounds = buttonFor(nodes.text(activity.getString(R.string.trading_fill_scope_current))).boundsInWindow
                val alarm = buttonFor(nodes.described("价格提醒"))
                val alarmBounds = alarm.boundsInWindow
                assertEquals(trendBounds.top, scopeBounds.top, 1f)
                assertEquals(trendBounds.bottom, scopeBounds.bottom, 1f)
                assertEquals(scopeBounds.top, alarmBounds.top, 1f)
                assertEquals(scopeBounds.bottom, alarmBounds.bottom, 1f)
                assertEquals(32f * activity.resources.displayMetrics.density, alarmBounds.width, 1f)
                assertEquals("有正在监控的价格提醒", alarm.config.getOrNull(SemanticsProperties.StateDescription))
                tap(activity.window.decorView, alarmBounds.center)
            }
        }
        assertEquals(2, alertOpens)
    }

    @Test
    fun candleDetailsCoverTheChartButtonsAndTheirTouchTargets() {
        var trendClicks = 0
        val details = mutableStateOf<TradingCandleDetails?>(
            TradingCandleDetails(1_795_318_400_000L, 43.33, 43.50, 43.29, 43.39, onLeft = true),
        )
        withTradingContent(content = {
            Box(Modifier.size(340.dp, 250.dp)) {
                Box(Modifier.padding(start = 7.dp, top = 20.dp).size(120.dp, 52.dp).clickable { trendClicks++ }) {
                    Text("趋势：空")
                }
                TradingCandleDetailsLayer(details, TradingInterval.H1, priceDecimals = 2)
            }
        }) { activity, nodes ->
            for (label in listOf("开", "高", "低", "收")) assertTrue(nodes.text(label).boundsInWindow.height > 0)
            assertTrue(nodes.text(formatPrice(43.39, 2)).boundsInWindow.width > 0)
            val point = nodes.text("开").boundsInWindow.center
            val button = nodes.first { it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInWindow
            assertTrue(button.contains(point))
            tap(activity.window.decorView, point)
            assertEquals(0, trendClicks)
        }
    }

    @Test
    fun livePositionBadgesHaveMatchingHeightsAndFonts() {
        val contract = TradingContract("TQQQUSDT", "TQQQ", 2)
        val position = TradingPosition("LONG", "BOTH", 1L, "550", "49.59", "49.23", "-198")
        val key = TradingPositionKey("account", "open", contract.symbol)
        val details = TradingPositionDetails(position, key, key, openingFill = null, trendAtOpen = TradingTrendDirection.BEARISH)
        withTradingContent(content = {
            TradingPositionCard(contract, details, TradingPositionPlan(), markPrice = null) {}
        }) { activity, nodes ->
            val direction = nodes.text(activity.getString(R.string.trading_position_long))
            val trend = nodes.text(activity.getString(R.string.trading_trend_button, activity.getString(R.string.trading_trend_bearish)))
            val day = nodes.text(activity.getString(R.string.trading_position_day_unrecorded))
            fun fontOf(node: SemanticsNode) = mutableListOf<TextLayoutResult>().let { layouts ->
                assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                layouts.single().layoutInput.style.fontSize
            }
            for (neighbor in listOf(trend, day)) {
                assertEquals(neighbor.boundsInWindow.top, direction.boundsInWindow.top, 1f)
                assertEquals(neighbor.boundsInWindow.bottom, direction.boundsInWindow.bottom, 1f)
                assertEquals(fontOf(neighbor), fontOf(direction))
            }
            assertTrue(nodes.described(activity.getString(R.string.trading_position_direction_mismatch)).boundsInWindow.height > 0)
        }
    }

    @Test
    fun activePriceAlertShowsThePriceAndCancelsFromTheCross() {
        val rule = TradingPriceAlert(
            requestId = "live-rule", deviceId = "device", instanceId = "server", symbol = "TQQQUSDT",
            kind = "target", threshold = "49", minutes = null, unit = null, createdAt = 1L,
            serverId = "7", direction = "down", status = "active",
        )
        var cancels = 0
        withTradingContent(content = {
            TradingAlertRuleRow(rule, enabled = true, onRetry = {}, onCancel = { cancels++ })
        }) { activity, nodes ->
            val price = "目标价 49"
            val title = nodes.text(price).boundsInWindow
            val cross = nodes.described(activity.getString(R.string.trading_alert_cancel_rule, price))
            assertTrue(cross.boundsInWindow.center.x > title.center.x)
            val labels = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertEquals(listOf(price), labels)
            tap(activity.window.decorView, cross.boundsInWindow.center)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun emptyTradingNoteAreaOpensTheEditor() {
        var opens = 0
        var newsOpens = 0
        withTradingContent(content = {
            TradingNoteAndNews(
                note = "", latestNews = emptyList(), pushStatus = TradingPushStatus.OFFLINE,
                onOpenNews = { newsOpens++ }, onEdit = { opens++ },
            )
        }) { activity, nodes ->
            val emptyNote = nodes.text(activity.getString(R.string.trading_note_empty))
            val card = nodes.single {
                it.config.getOrNull(SemanticsActions.OnClick) != null && it.boundsInWindow.contains(emptyNote.boundsInWindow.center)
            }.boundsInWindow
            assertTrue(card.contains(emptyNote.boundsInWindow.center))
            assertTrue(card.height >= 48f * activity.resources.displayMetrics.density)
            tap(activity.window.decorView, Offset(card.right - 3f, card.center.y))
            assertEquals(1, opens)
            tap(activity.window.decorView, nodes.text(activity.getString(R.string.trading_push_offline)).boundsInWindow.center)
            assertEquals(1, opens)
            assertEquals(1, newsOpens)
        }
    }

    @Test
    fun notePreviewNumbersNonblankLinesWithoutChangingPricesOrDuplicatingNumbers() {
        val note = "观察原油\n\n7、等待确认\r\n1.25% 时再看"
        withTradingContent(content = {
            TradingNoteAndNews(note, emptyList(), TradingPushStatus.CONNECTED, onOpenNews = {}, onEdit = {})
        }) { _, nodes ->
            assertTrue(nodes.text("1. 观察原油\n2. 等待确认\n3. 1.25% 时再看").boundsInWindow.height > 0)
        }
    }

    @Test
    fun theNoteCardMatchesTallerNewsAndKeepsItsTopPadding() {
        val note = "观察\n等待\n回踩\n收盘"
        val main = TradingNewsStory("main", TradingNewsChannel.MAIN, "主频道新闻内容".repeat(8), 1L, null)
        val finance = TradingNewsStory("finance", TradingNewsChannel.TRADFI, "财经新闻内容".repeat(8), 2L, null)
        val alert = TradingNewsStory("alert", TradingNewsChannel.PRICE_ALERT, "BTCUSDT 目标价已到达 · 标记价 65100 USDT", 3L, null)
        withTradingContent(content = {
            Box(Modifier.width(360.dp)) {
                TradingNoteAndNews(
                    note, listOf(alert, main, finance), TradingPushStatus.CONNECTED, onOpenNews = {}, onEdit = {},
                )
            }
        }) { activity, nodes ->
            val content = nodes.text("1. 观察\n2. 等待\n3. 回踩\n4. 收盘").boundsInWindow
            assertTrue(nodes.text(alert.title).boundsInWindow.bottom < nodes.text(main.title).boundsInWindow.top)
            val fcm = nodes.text(activity.getString(R.string.trading_push_connected)).boundsInWindow
            fun cardAt(point: Offset) = nodes.single {
                it.config.getOrNull(SemanticsActions.OnClick) != null && it.boundsInWindow.contains(point)
            }.boundsInWindow
            val noteCard = cardAt(content.center)
            val newsCard = cardAt(fcm.center)
            val padding = 6f * activity.resources.displayMetrics.density
            assertEquals(newsCard.top, noteCard.top, 1f)
            assertEquals(newsCard.bottom, noteCard.bottom, 1f)
            assertEquals(noteCard.top + padding, content.top, 1f)
            assertTrue(content.bottom <= noteCard.bottom - padding + 1f)
        }
    }

    @Test
    fun savedTradingNoteWrapsOnTheLeftOfBothLatestNewsChannels() {
        val content = "观察原油走势\n等待下一根 K 线确认。".repeat(40)
        val main = TradingNewsStory("main-1", TradingNewsChannel.MAIN, "*主频道快讯测试", 1L, null)
        val tradfi = TradingNewsStory("tradfi-1", TradingNewsChannel.TRADFI, "财经频道快讯测试", 2L, null)
        var opens = 0
        var newsOpens = 0
        withTradingContent(content = {
            Box(Modifier.width(360.dp)) {
                TradingNoteAndNews(
                    content, listOf(main, tradfi), TradingPushStatus.CONNECTED,
                    onOpenNews = { newsOpens++ }, onEdit = { opens++ },
                )
            }
        }) { activity, nodes ->
            val note = nodes.text(numberedTradingNote(content))
            val news = nodes.text("主频道快讯测试")
            val finance = nodes.text(tradfi.title)
            val pushStatus = nodes.text(activity.getString(R.string.trading_push_connected))
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(note.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            val layout = layouts.single()
            assertTrue(note.boundsInWindow.right < news.boundsInWindow.left)
            assertTrue(note.boundsInWindow.right < finance.boundsInWindow.left)
            assertTrue(news.boundsInWindow.bottom < finance.boundsInWindow.top)
            assertTrue(finance.boundsInWindow.bottom < pushStatus.boundsInWindow.top)
            assertTrue(finance.boundsInWindow.left < pushStatus.boundsInWindow.left)
            assertTrue(layout.lineCount > 7)
            assertEquals(layout.getLineBottom(6), note.boundsInWindow.height, 2f)
            tap(activity.window.decorView, note.boundsInWindow.center)
            assertEquals(1, opens)
            tap(activity.window.decorView, finance.boundsInWindow.center)
            assertEquals(1, newsOpens)
            assertEquals(1, opens)
        }
    }

    @Test
    @OptIn(InternalComposeUiApi::class)
    fun directSwipesScrollTheNoteInsideTheTradingListAndATapOpensTheEditor() = WindowRecomposerPolicy.withFactory({ view ->
        view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance()),
        )
    }) {
        val content = (1..40).joinToString("\n") { "观察第 ${it} 项" }
        val page = LazyListState()
        var edits = 0
        withTradingContent(content = {
            LazyColumn(state = page, modifier = Modifier.fillMaxSize()) {
                item {
                    TradingNoteAndNews(content, emptyList(), TradingPushStatus.CONNECTED, onOpenNews = {}, onEdit = { edits++ })
                }
                item { Spacer(Modifier.height(1000.dp)) }
            }
        }) { activity, nodes ->
            val note = nodes.text(numberedTradingNote(content))
            val frame = note.boundsInWindow
            val start = Offset(frame.center.x, frame.top + frame.height * 0.8f)
            val end = Offset(frame.center.x, frame.top + frame.height * 0.2f)
            val card = generateSequence(note) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val noteScroll = descendants(card).mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }.single()
            val view = activity.window.decorView

            drag(view, start, end)
            val afterSwipeUp = noteScroll.value()
            assertTrue(afterSwipeUp > 0f)
            assertEquals(0, page.firstVisibleItemIndex)
            assertEquals(0, page.firstVisibleItemScrollOffset)
            assertEquals(0, edits)

            drag(view, end, start)
            assertTrue(noteScroll.value() < afterSwipeUp)
            assertEquals(0, page.firstVisibleItemScrollOffset)
            assertEquals(0, edits)

            tap(view, frame.center)
            assertEquals(1, edits)
        }
    }

    @Test
    fun unreadNewsMarksTheUpperLeftOfThePreview() {
        withTradingContent(content = {
            Box(Modifier.size(160.dp, 100.dp)) {
                TradingNewsPreview(emptyList(), TradingPushStatus.CONNECTED, hasUnread = true)
            }
        }) { activity, nodes ->
            val preview = nodes.single {
                it.config.getOrNull(SemanticsProperties.StateDescription) == activity.getString(R.string.trading_news_unread)
            }.boundsInWindow
            val inset = 5.5f * activity.resources.displayMetrics.density
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertEquals(0xFFFFC45C.toInt(), bitmap.getPixel((preview.left + inset).toInt(), (preview.top + inset).toInt()))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun newsDrawerGroupsLocalDatesAndPlacesMinutesBesideHeadlines() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val newestHeadline = "最新消息 A*B"
        val newest = TradingNewsStory(
            "main-1", TradingNewsChannel.MAIN, "  ** $newestHeadline",
            today.atTime(3, 1, 45).atZone(zone).toInstant().toEpochMilli(), "https://example.invalid/main",
        )
        val earlier = TradingNewsStory(
            "tradfi-1", TradingNewsChannel.TRADFI, "当天稍早消息",
            today.atTime(0, 34, 16).atZone(zone).toInstant().toEpochMilli(), "https://example.invalid/brief",
        )
        val longNews = TradingNewsStory(
            "main-2", TradingNewsChannel.MAIN, "这是一条需要多行排版的方程式新闻。".repeat(5),
            today.atTime(0, 12, 6).atZone(zone).toInstant().toEpochMilli(), "https://example.invalid/long",
        )
        val older = TradingNewsStory(
            "tradfi-2", TradingNewsChannel.TRADFI, "前一天的消息",
            yesterday.atTime(23, 54, 16).atZone(zone).toInstant().toEpochMilli(), null,
        )
        var closes = 0
        withTradingContent(content = {
            val drawer = rememberDrawerState(DrawerValue.Open)
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(drawerState = drawer, modifier = Modifier.width(300.dp)) {
                        TradingNewsDrawerContent(
                            listOf(newest, earlier, longNews, older), openedUnread = setOf(newest.key),
                            onOpenContract = {},
                            onClose = { closes++ },
                        )
                    }
                },
            ) { Box(Modifier.fillMaxSize()) }
        }) { activity, nodes ->
            val title = nodes.text(activity.getString(R.string.trading_news))
            assertTrue(title.boundsInWindow.width > 0)
            assertTrue(title.boundsInWindow.left < 16f * activity.resources.displayMetrics.density)
            val todayLabel = activity.getString(R.string.trading_news_today_date, today.format(DateTimeFormatter.ofPattern("MM-dd")))
            val yesterdayLabel = yesterday.format(DateTimeFormatter.ofPattern(if (yesterday.year == today.year) "MM-dd" else "uuuu-MM-dd"))
            val todayBounds = nodes.text(todayLabel).boundsInWindow
            val newestBounds = nodes.text(newestHeadline).boundsInWindow
            val earlierBounds = nodes.text(earlier.title).boundsInWindow
            val yesterdayBounds = nodes.text(yesterdayLabel).boundsInWindow
            assertTrue(title.boundsInWindow.bottom < todayBounds.top)
            assertTrue(todayBounds.bottom <= newestBounds.top)
            assertTrue(newestBounds.bottom < earlierBounds.top)
            val longBounds = nodes.text(longNews.title).boundsInWindow
            assertTrue(earlierBounds.bottom < longBounds.top)
            assertTrue(longBounds.bottom < yesterdayBounds.top)
            assertTrue(yesterdayBounds.bottom <= nodes.text(older.title).boundsInWindow.top)
            val mainTime = nodes.text("03:01")
            val financeTime = nodes.text("00:34")
            val timeBounds = mainTime.boundsInWindow
            assertTrue(timeBounds.right < newestBounds.left)
            assertTrue(timeBounds.top < newestBounds.bottom && timeBounds.bottom > newestBounds.top)
            assertTrue(nodes.text("23:54").boundsInWindow.right < nodes.text(older.title).boundsInWindow.left)
            assertEquals(activity.getString(R.string.trading_news_unread_channel, activity.getString(R.string.trading_news_main)),
                mainTime.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(activity.getString(R.string.trading_news_tradfi), financeTime.config.getOrNull(SemanticsProperties.StateDescription))

            fun cardBounds(headline: String) = generateSequence(nodes.text(headline)) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }.boundsInWindow

            val firstCard = cardBounds(newestHeadline)
            val secondCard = cardBounds(earlier.title)
            val longCard = cardBounds(longNews.title)
            val cardGap = 4f * activity.resources.displayMetrics.density
            assertTrue(longCard.height > secondCard.height)
            assertEquals(cardGap, secondCard.top - firstCard.bottom, 1f)
            assertEquals(cardGap, longCard.top - secondCard.bottom, 1f)

            val mainTimeLayouts = mutableListOf<TextLayoutResult>()
            assertTrue(mainTime.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(mainTimeLayouts) == true)
            assertEquals(TradingBackground, mainTimeLayouts.single().layoutInput.style.color)
            val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertEquals(1, texts.count { it == todayLabel })
            assertTrue(texts.none { it.startsWith("方程式") || it.contains("03:01:45") })
            nodes.described(activity.getString(R.string.close_panel)).performClick()
            assertEquals(1, closes)
        }
    }

    @Test
    fun tappingAnAlertCardOpensItsContractAndTappingNewsOpensItsLink() {
        val time = System.currentTimeMillis()
        val alert = TradingNewsStory(
            "instance:1", TradingNewsChannel.PRICE_ALERT, "TQQQ 目标价 47.63 已达到", time, null,
            alertSymbol = "TQQQUSDT",
        )
        val article = TradingNewsStory("news", TradingNewsChannel.MAIN, "最新消息", time - 1000, "https://example.invalid/news")
        val contracts = mutableListOf<String>()
        val links = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { links += uri }
        }
        withTradingContent(content = {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                TradingNewsDrawerContent(listOf(alert, article), onOpenContract = { contracts += it }, onClose = {})
            }
        }) { activity, nodes ->
            tap(activity.window.decorView, nodes.text(alert.title).boundsInWindow.center)
            tap(activity.window.decorView, nodes.text(article.title).boundsInWindow.center)
            assertEquals(listOf("TQQQUSDT"), contracts)
            assertEquals(listOf(article.url), links)
        }
    }

    private fun withTradingContent(
        content: @Composable () -> Unit,
        verify: (ComponentActivity, List<SemanticsNode>) -> Unit,
    ) {
        ShadowChoreographer.setPaused(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val host = ComposeView(activity).apply {
                setContent { MaterialTheme(colorScheme = TradingColors, typography = TradingTypography) { content() } }
            }
            activity.setContentView(host)
            controller.visible()
            repeat(24) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                host.getChildAt(0)?.let { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) }
            }
            val view = checkNotNull(host.getChildAt(0))
            val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
            verify(activity, descendants(owner.unmergedRootSemanticsNode).toList())
        } finally {
            controller.pause().stop().destroy()
            ShadowChoreographer.setPaused(false)
        }
    }

    private fun List<SemanticsNode>.text(value: String) = first { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    }

    private fun List<SemanticsNode>.described(label: String) = first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun SemanticsNode.performClick() {
        val click = generateSequence(this) { it.parent }
            .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(click())
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }

    private fun tap(view: View, point: Offset) {
        val time = SystemClock.uptimeMillis()
        dispatchTouch(view, time, MotionEvent.ACTION_DOWN, point)
        dispatchTouch(view, time, MotionEvent.ACTION_UP, point)
    }

    private fun drag(view: View, start: Offset, end: Offset) {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(16))
        val time = SystemClock.uptimeMillis()
        dispatchTouch(view, time, MotionEvent.ACTION_DOWN, start)
        for (step in 1..4) {
            looper.idleFor(Duration.ofMillis(16))
            dispatchTouch(view, time, MotionEvent.ACTION_MOVE, start + (end - start) * (step / 4f))
        }
        looper.idleFor(Duration.ofMillis(130))
        dispatchTouch(view, time, MotionEvent.ACTION_UP, end)
        looper.idleFor(Duration.ofMillis(150))
    }

    private fun dispatchTouch(view: View, down: Long, action: Int, point: Offset) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
