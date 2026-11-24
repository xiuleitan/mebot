package com.txl.mebot.ui.trading

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.txl.mebot.data.local.TradingCandle
import com.txl.mebot.data.local.TradingFill
import com.txl.mebot.data.local.TradingLine
import com.txl.mebot.data.trading.DrawingTool
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingHistory
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.TradingLineMove
import com.txl.mebot.data.trading.TradingPoint
import java.io.ByteArrayInputStream
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class TradingChartStopLoss(val price: String, val side: String)

@Composable
internal fun TradingChart(
    contract: TradingContract,
    interval: TradingInterval,
    bars: List<TradingCandle>,
    tool: DrawingTool?,
    fills: List<TradingFill>,
    onFill: (List<Long>) -> Unit,
    onLine: (DrawingTool, List<TradingPoint>) -> Unit,
    onOlder: suspend (TradingContract, TradingInterval, Long) -> TradingHistory,
    modifier: Modifier = Modifier,
    focusTime: Long? = null,
    tick: TradingCandle? = null,
    lines: List<TradingLine> = emptyList(),
    onMoveLine: (TradingContract, TradingLineMove, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    referencePrice: Double? = null,
    stopLosses: List<TradingChartStopLoss> = emptyList(),
    initialBarSpace: Float? = null,
    onBarSpaceChanged: (Float) -> Unit = {},
    foreground: (@Composable BoxScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val palette = remember(scheme, fontScale) {
        ChartPalette(
            background = cssColor(TradingChartBackground.toArgb()),
            text = cssColor(scheme.onSurface.toArgb()),
            ink = cssColor(scheme.onSurfaceVariant.toArgb()),
            grid = cssColor(scheme.outlineVariant.toArgb()),
            accent = cssColor(scheme.primary.toArgb()),
            up = cssColor(TradingUp.toArgb()),
            down = cssColor(TradingDown.toArgb()),
            fontScale = fontScale,
        )
    }
    val scope = rememberCoroutineScope()
    val details = remember(contract, interval) { mutableStateOf<TradingCandleDetails?>(null) }
    Box(modifier) {
        AndroidView(
            factory = { context -> TradingChartHost(context, TradingChartView(context, scope)) },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.chart.release() },
            update = { host ->
                val view = host.chart
                view.onFill = onFill
                view.onLine = onLine
                view.onMoveLine = onMoveLine
                view.onOlder = onOlder
                view.onBarSpaceChanged = onBarSpaceChanged
                view.onCandleDetails = { details.value = it }
                view.render(ChartContent(
                    contract = contract, interval = interval, bars = bars, tick = tick,
                    fills = fills, lines = lines, tool = tool, palette = palette,
                    focusTime = focusTime, referencePrice = referencePrice,
                    stopLosses = stopLosses,
                    initialBarSpace = initialBarSpace,
                    customCrosshair = foreground != null,
                ))
            },
        )
        if (foreground != null) {
            foreground(this)
            TradingCandleDetailsLayer(details, interval, contract.priceDecimals)
        }
    }
}

internal class TradingChartHost<T : View>(context: Context, val chart: T) : FrameLayout(context) {
    private var ownsTouch = false

    init {
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            // AndroidView passes the request to Compose before the outer list starts a drag.
            ownsTouch = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return try {
            super.dispatchTouchEvent(event) || ownsTouch
        } finally {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endTouch()
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept || !ownsTouch) super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onDetachedFromWindow() {
        endTouch()
        super.onDetachedFromWindow()
    }

    private fun endTouch() {
        if (!ownsTouch) return
        ownsTouch = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}

private fun cssColor(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

private data class ChartPalette(
    val background: String,
    val text: String,
    val ink: String,
    val grid: String,
    val accent: String,
    val up: String,
    val down: String,
    val fontScale: Float,
)

private data class ChartContent(
    val contract: TradingContract,
    val interval: TradingInterval,
    val bars: List<TradingCandle>,
    val tick: TradingCandle?,
    val fills: List<TradingFill>,
    val lines: List<TradingLine>,
    val tool: DrawingTool?,
    val palette: ChartPalette,
    val focusTime: Long?,
    val referencePrice: Double?,
    val stopLosses: List<TradingChartStopLoss>,
    val initialBarSpace: Float?,
    val customCrosshair: Boolean,
)

@SuppressLint("SetJavaScriptEnabled")
private class TradingChartView(context: Context, private val scope: CoroutineScope) : WebView(context) {
    var onFill: (List<Long>) -> Unit = {}
    var onLine: (DrawingTool, List<TradingPoint>) -> Unit = { _, _ -> }
    var onMoveLine: (TradingContract, TradingLineMove, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) }
    var onBarSpaceChanged: (Float) -> Unit = {}
    var onCandleDetails: (TradingCandleDetails?) -> Unit = {}
    var onOlder: suspend (TradingContract, TradingInterval, Long) -> TradingHistory =
        { _, _, _ -> TradingHistory(emptyList(), false) }

    private var ready = false
    private var released = false
    private var pending: ChartContent? = null
    private var displayed: ChartContent? = null
    private var windowRevision = 0L
    private var historyJob: Job? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isNestedScrollingEnabled = false
        settings.apply {
            javaScriptEnabled = true
            // The chart palette already scales the Canvas and HTML labels together.
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                val file = url.lastPathSegment
                val mime = Files[file]
                if (url.scheme == "https" && url.host == Host && url.path == "/trading/" + file &&
                    request.method == "GET" && mime != null
                ) {
                    return WebResourceResponse(mime, "UTF-8", context.assets.open("trading/" + file))
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
        addJavascriptInterface(HostBridge(), "TradingHost")
        loadUrl("https://" + Host + "/trading/index.html")
    }

    fun render(content: ChartContent) {
        pending = content
        if (!ready || released) return
        val old = displayed
        val windowChanged = old == null || old.contract != content.contract ||
            old.interval != content.interval || old.bars !== content.bars
        val changes = JSONObject()
        if (windowChanged) {
            historyJob?.cancel()
            windowRevision++
            val period = JSONObject().put("id", content.interval.apiValue)
                .put("type", content.interval.chartSpan).put("span", content.interval.multiplier)
            changes.put("window", JSONObject()
                .put("revision", windowRevision)
                .put("symbol", content.contract.symbol)
                .put("pricePrecision", content.contract.priceDecimals)
                .put("bars", barsJson(content.bars))
                .put("period", period))
        }
        if (content.focusTime != null && (windowChanged || old?.focusTime != content.focusTime)) {
            changes.put("focusTime", content.focusTime)
        }
        if (old?.customCrosshair != content.customCrosshair) changes.put("customCrosshair", content.customCrosshair)
        if (windowChanged || old?.referencePrice != content.referencePrice) {
            changes.put("referencePrice", content.referencePrice ?: JSONObject.NULL)
        }
        if (windowChanged || old?.stopLosses != content.stopLosses) {
            changes.put("stopLosses", JSONArray().apply {
                content.stopLosses.forEach {
                    put(JSONObject().put("price", it.price).put("side", it.side))
                }
            })
        }
        if (windowChanged || old?.tick != content.tick) content.tick?.let { changes.put("tick", it.chartJson()) }
        if (old?.fills !== content.fills) changes.put("fills", JSONArray().apply {
            content.fills.forEach {
                put(JSONObject().put("id", it.tradeId.toString()).put("time", it.time).put("side", it.side)
                    .put("price", it.price.toDouble()))
            }
        })
        if (old?.lines !== content.lines) changes.put("lines", JSONArray().apply {
            content.lines.forEach {
                put(JSONObject().put("id", it.id).put("kind", it.kind).put("time", it.time)
                    .put("price", it.price).put("endTime", it.endTime).put("endPrice", it.endPrice))
            }
        })
        if (windowChanged || old?.tool != content.tool) changes.put("tool", content.tool?.overlay ?: JSONObject.NULL)
        if (old?.palette != content.palette) changes.put("palette", JSONObject()
            .put("background", content.palette.background)
            .put("text", content.palette.text)
            .put("ink", content.palette.ink)
            .put("grid", content.palette.grid)
            .put("accent", content.palette.accent)
            .put("up", content.palette.up)
            .put("down", content.palette.down)
            .put("fontScale", content.palette.fontScale.toDouble()))
        if (old == null) {
            changes.put("timezone", TimeZone.getDefault().id)
            content.initialBarSpace?.let { changes.put("initialBarSpace", it.toDouble()) }
        }
        if (changes.length() > 0) evaluateJavascript("window.tradeChart.update(" + changes + ")", null)
        displayed = content
    }

    private fun onEvent(event: JSONObject) {
        val type = event.optString("type")
        if (type != "ready" && type != "lineMove" && type != "barSpace" &&
            event.optLong("revision", -1L) != windowRevision
        ) return
        when (type) {
            "ready" -> {
                ready = true
                pending?.let(::render)
            }
            "barSpace" -> onBarSpaceChanged(event.optDouble("value").toFloat())
            "fill" -> {
                val ids = event.getJSONArray("tradeIds")
                onFill(List(ids.length()) { ids.getString(it).toLong() })
            }
            "line" -> {
                val tool = DrawingTool.entries.firstOrNull { it.overlay == event.getString("kind") } ?: return
                val items = event.getJSONArray("points")
                val points = List(items.length()) { index ->
                    val point = items.getJSONObject(index)
                    TradingPoint(point.getLong("time"), point.getDouble("price"))
                }
                onLine(tool, points)
            }
            "lineMove" -> {
                val request = event.getLong("request")
                val current = displayed ?: return
                val move = TradingLineMove(
                    id = event.getString("id"),
                    endpoint = event.getInt("endpoint"),
                    time = if (event.isNull("time")) null else event.getLong("time"),
                    price = event.getDouble("price"),
                )
                val complete: (Boolean) -> Unit = { saved ->
                    if (!released) evaluateJavascript("window.tradeChart.lineMoveSaved($request,$saved)", null)
                }
                // A candle refresh can change the window revision while the save event is in transit.
                if (event.optString("symbol") == current.contract.symbol) onMoveLine(current.contract, move, complete)
                else complete(false)
            }
            "candleDetails" -> onCandleDetails(event.optJSONObject("details")?.let { values ->
                TradingCandleDetails(
                    time = values.getLong("time"),
                    open = values.getDouble("open"),
                    high = values.getDouble("high"),
                    low = values.getDouble("low"),
                    close = values.getDouble("close"),
                    onLeft = values.getBoolean("onLeft"),
                )
            })
            "older" -> {
                val id = event.getLong("id")
                val before = event.getLong("before")
                val current = displayed ?: return
                val interval = TradingInterval.entries.firstOrNull { it.apiValue == event.optString("interval") } ?: return
                if (before <= 0 || interval != current.interval) return
                val revision = windowRevision
                historyJob?.cancel()
                historyJob = scope.launch {
                    val history = onOlder(current.contract, interval, before)
                    if (!released && revision == windowRevision) {
                        evaluateJavascript(
                            "window.tradeChart.history(" + id + "," + barsJson(history.bars) + "," + history.hasEarlier + ")",
                            null,
                        )
                    }
                }
            }
        }
    }

    fun release() {
        released = true
        historyJob?.cancel()
        removeJavascriptInterface("TradingHost")
        stopLoading()
        destroy()
    }

    inner class HostBridge {
        @JavascriptInterface
        fun event(message: String) {
            if (message.length > 64_000) return
            post { if (!released) runCatching { onEvent(JSONObject(message)) } }
        }
    }

    private companion object {
        const val Host = "trading.mebot.invalid"
        val Files = mapOf(
            "index.html" to "text/html",
            "chart.css" to "text/css",
            "chart.js" to "application/javascript",
            "klinecharts-10.1.1.js" to "application/javascript",
        )
    }
}

private fun barsJson(bars: List<TradingCandle>) = JSONArray().apply { bars.forEach { put(it.chartJson()) } }

private fun TradingCandle.chartJson(): JSONObject = JSONObject()
    .put("timestamp", time)
    .put("open", open)
    .put("high", high)
    .put("low", low)
    .put("close", close)
    .put("volume", volume)
