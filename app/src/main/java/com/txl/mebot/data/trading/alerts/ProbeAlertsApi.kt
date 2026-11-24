package com.txl.mebot.data.trading.alerts

import android.content.Context
import com.txl.mebot.BuildConfig
import com.txl.mebot.data.local.TradingPriceAlert
import com.txl.mebot.data.local.TradingPriceAlertEvent
import com.txl.mebot.data.trading.normalizeUsdtSymbol
import com.txl.mebot.data.trading.news.TradingNewsPageSize
import com.txl.mebot.data.trading.news.TradingNewsStory
import com.txl.mebot.data.trading.news.tradingNewsStory
import com.txl.mebot.data.trading.news.validNewsInstance
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal enum class TradingAlertKind(val value: String, val resource: String, val label: String) {
    TARGET("target", "alerts", "目标价"),
    WINDOW("window", "updowns", "窗口涨跌"),
    LADDER("ladder", "updowns", "阶梯价位"),
}

internal enum class TradingAlertMode(val kind: TradingAlertKind, val label: String = kind.label) {
    TARGET(TradingAlertKind.TARGET),
    WINDOW(TradingAlertKind.WINDOW),
    OPENING_OPPORTUNITY(TradingAlertKind.TARGET, "开单机会"),
}

internal data class TradingAlertDraft(
    val symbol: String, val mode: TradingAlertMode, val threshold: String,
    val minutes: Int? = null, val unit: String? = null,
)

internal class ProbeAlertException(
    val detail: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
) : Exception(detail)

internal fun probeEndpoint(input: String): HttpUrl? {
    val url = input.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null) return null
    val path = url.encodedPath.trimEnd('/').ifEmpty { "/api/v1" }
    if (!path.endsWith("/api/v1")) return null
    return url.newBuilder().encodedPath("$path/").build()
}

private val PositiveDecimal = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

internal fun alertDecimal(input: String): String? {
    val value = input.trim()
    if (value.length !in 1..48 || !PositiveDecimal.matches(value)) return null
    return value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString()
}

internal fun tradingAlertEvent(data: Map<String, String>, now: Long): TradingPriceAlertEvent? {
    if (data["type"] != "trading_price_alert") return null
    val deviceId = data["device_id"]?.takeIf { it.length == 36 && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        ?: return null
    val instance = data["instance_id"]?.takeIf { it.length in 1..80 && it.all { char -> char.isLetterOrDigit() || char == '-' } }
        ?: return null
    val eventId = data["event_id"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val ruleId = data["rule_id"]?.takeIf { it.length in 1..80 && it.all { char -> char.isLetterOrDigit() || char == '-' } }
        ?: return null
    val kind = TradingAlertKind.entries.firstOrNull { it.value == data["kind"] } ?: return null
    val symbol = data["symbol"]?.takeIf { it.length in 5..64 && normalizeUsdtSymbol(it) == it } ?: return null
    val price = data["mark_price"]?.let(::alertDecimal) ?: return null
    val triggered = runCatching { Instant.parse(data["triggered_at"]).toEpochMilli() }.getOrNull() ?: return null
    if (triggered !in now - 5 * 60_000L..now + 60_000L) return null
    return TradingPriceAlertEvent(deviceId, instance, eventId, ruleId, kind.value, symbol, price, triggered)
}

internal class ProbeAlertsApi(
    private val endpoint: HttpUrl,
    private val token: String,
    private val client: OkHttpClient,
) {
    suspend fun instanceId(): String = request("GET", route("health"))
        .getString("instance_id").also { require(it.length in 1..80) }

    suspend fun register(deviceId: String, fcmToken: String, packageName: String): Boolean {
        return registerDevice(route("devices", deviceId), fcmToken, packageName)
    }

    suspend fun registerNews(deviceId: String, fcmToken: String, packageName: String): Boolean =
        registerDevice(route("news", "devices", deviceId), fcmToken, packageName)

    suspend fun requestPushCheck(deviceId: String, checkId: String) {
        val body = JSONObject().put("device_id", deviceId).put("check_id", checkId)
        check(request("POST", route("news", "push-check"), body).getBoolean("accepted"))
    }

    private suspend fun registerDevice(url: HttpUrl, fcmToken: String, packageName: String): Boolean {
        val body = JSONObject().put("fcm_token", fcmToken).put("package_name", packageName)
        val response = request("PUT", url, body)
        check(response.getBoolean("registered"))
        return response.getBoolean("push_available")
    }

    suspend fun news(now: Long): List<TradingNewsStory> {
        val url = route("news").newBuilder().addQueryParameter("limit", TradingNewsPageSize.toString()).build()
        val response = request("GET", url)
        check(validNewsInstance(response.optString("instance_id")))
        val items = response.getJSONArray("items")
        return buildList {
            for (index in 0 until minOf(items.length(), TradingNewsPageSize)) {
                val item = items.optJSONObject(index) ?: continue
                tradingNewsStory(
                    item.optString("news_id"), item.optString("channel"), item.optString("title"),
                    item.optString("published_at"), item.optString("url"), now,
                )?.let(::add)
            }
        }
    }

    suspend fun create(rule: TradingPriceAlert): ProbeCreatedAlert {
        val kind = TradingAlertKind.entries.first { it.value == rule.kind }
        val payload = JSONObject().put("symbol", rule.symbol).put("device_id", rule.deviceId)
        if (kind == TradingAlertKind.TARGET) payload.put("target_price", rule.threshold)
        else {
            payload.put("mode", kind.value).put("threshold", rule.threshold)
            if (kind == TradingAlertKind.WINDOW) payload.put("window_minutes", rule.minutes).put("unit", rule.unit)
        }
        val reply = request("POST", route(kind.resource), payload, rule.requestId.substringAfterLast(':'))
        if (kind != TradingAlertKind.TARGET) return ProbeCreatedAlert(reply.ruleId(), null)
        val item = reply.getJSONArray("items").getJSONObject(0)
        return when (item.getString("status")) {
            "at_target" -> ProbeCreatedAlert(null, null)
            "created", "duplicate" -> {
                val created = item.getJSONObject("rule")
                ProbeCreatedAlert(
                    created.ruleId(), created.getString("direction").also { require(it in setOf("up", "down")) },
                    duplicate = item.getString("status") == "duplicate",
                )
            }
            else -> throw ProbeAlertException("提醒服务器返回了未知状态")
        }
    }

    suspend fun cancel(rule: TradingPriceAlert) {
        val kind = TradingAlertKind.entries.first { it.value == rule.kind }
        val url = route(kind.resource, requireNotNull(rule.serverId)).newBuilder()
            .addQueryParameter("device_id", rule.deviceId).build()
        request("DELETE", url)
    }

    suspend fun activeIds(resource: String, deviceId: String): Set<String> {
        val url = route(resource).newBuilder().addQueryParameter("device_id", deviceId).build()
        val items = request("GET", url).getJSONArray("items")
        return (0 until items.length()).mapTo(mutableSetOf()) { items.getJSONObject(it).ruleId() }
    }

    private fun route(vararg segments: String): HttpUrl = endpoint.newBuilder().apply {
        segments.forEach { addPathSegment(it) }
    }.build()

    private suspend fun request(
        method: String, url: HttpUrl, body: JSONObject? = null, requestId: String? = null,
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .apply { requestId?.let { header("Idempotency-Key", it) } }
            .method(method, body?.toString()?.toRequestBody(JsonType)).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(ProbeAlertException("连接提醒服务器失败，请检查地址、网络和 HTTPS 证书", true))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val source = it.body?.source()
                        if (source?.request(ResponseLimit + 1L) == true) throw ProbeAlertException("提醒服务器响应过大")
                        val bodyText = source?.readUtf8().orEmpty()
                        if (!it.isSuccessful) throw when (it.code) {
                            401, 403 -> ProbeAlertException("提醒服务器拒绝访问，请检查访问令牌")
                            404 -> ProbeAlertException("提醒服务器找不到该接口或规则", httpStatus = 404)
                            409 -> ProbeAlertException("提醒状态已变化，请刷新后重试", httpStatus = 409)
                            400, 422 -> ProbeAlertException("提醒服务器未接受条件，请检查合约和输入", httpStatus = it.code)
                            else -> ProbeAlertException("提醒服务器暂时无法处理请求（${it.code}）", it.code >= 500)
                        }
                        if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText)
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun JSONObject.ruleId(): String =
        getString("id").also { require(it.length in 1..80 && it.all { char -> char.isLetterOrDigit() || char == '-' }) }

    private companion object {
        val JsonType = "application/json; charset=utf-8".toMediaType()
        const val ResponseLimit = 256_000L
    }
}

internal data class ProbeCreatedAlert(val id: String?, val direction: String?, val duplicate: Boolean = false)

internal class ProbeAlertClients(private val context: Context) {
    private val regular = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build()

    private val development by lazy {
        if (!BuildConfig.DEBUG) return@lazy null
        val certificate = try {
            context.assets.open("probe-local-ca.crt").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } catch (_: IOException) { return@lazy null }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("probe-local", certificate)
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        regular.newBuilder().sslSocketFactory(ssl.socketFactory, manager).build()
    }

    fun client(url: HttpUrl): OkHttpClient {
        val parts = url.host.split('.').mapNotNull(String::toIntOrNull)
        val local = url.host == "localhost" || parts.size == 4 &&
            (parts[0] == 127 || parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31)
        return if (local) development ?: regular else regular
    }
}
