package com.txl.mebot.data.models

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class ModelRole { LANGUAGE, IMAGE }
enum class ConnectionResultKind {
    SUCCESS, INVALID_KEY, NO_PERMISSION, SERVICE_NOT_ENABLED, BILLING, MODEL_UNAVAILABLE,
    ENDPOINT, PARAMETERS, RATE_LIMIT, SERVICE_ERROR, DNS, NETWORK, TIMEOUT, TLS, BAD_RESPONSE, CANCELLED,
}
data class ModelConnectionResult(val kind: ConnectionResultKind, val httpStatus: Int? = null, val elapsedMillis: Long = 0)

class ModelHttpResponse(val status: Int, val body: String) {
    override fun toString() = "ModelHttpResponse(status=$status, body=[redacted])"
}

fun interface ModelHttpTransport {
    suspend fun post(endpoint: String, apiKey: String, body: String, readTimeoutMillis: Int): ModelHttpResponse
}

/** 不跟随重定向，不重试收费请求；取消等待会断开本机连接。 */
class HttpsModelTransport(
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : ModelHttpTransport {
    override suspend fun post(endpoint: String, apiKey: String, body: String, readTimeoutMillis: Int): ModelHttpResponse =
        suspendCancellableCoroutine { continuation ->
            Dispatchers.IO.dispatch(continuation.context) {
                var connection: HttpsURLConnection? = null
                try {
                    if (!continuation.isActive) return@dispatch
                    val url = URL(endpoint)
                    require(url.protocol == "https" && url.userInfo == null)
                    val request = connect(url)
                    connection = request
                    continuation.invokeOnCancellation {
                        // 取消可能由主线程触发，连接清理仍在 IO 线程完成。
                        Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { request.disconnect() } }
                    }
                    if (!continuation.isActive) return@dispatch
                    request.requestMethod = "POST"
                    request.instanceFollowRedirects = false
                    request.connectTimeout = 10_000
                    request.readTimeout = readTimeoutMillis
                    request.useCaches = false
                    request.doOutput = true
                    request.setRequestProperty("Authorization", "Bearer $apiKey")
                    request.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    request.setRequestProperty("Accept", "application/json")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    request.setFixedLengthStreamingMode(bytes.size)
                    request.outputStream.use { it.write(bytes) }
                    val status = request.responseCode
                    val response = if (status in 300..399) "" else {
                        val stream = if (status >= 400) request.errorStream else request.inputStream
                        stream?.use(::readBounded).orEmpty()
                    }
                    continuation.resume(ModelHttpResponse(status, response))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { runCatching { connection?.disconnect() } }
            }
        }

    private fun readBounded(stream: InputStream): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (result.size() + count > 128 * 1024) throw InvalidModelResponse()
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }
}

private class InvalidModelResponse : IOException("模型响应格式异常")

class ModelConnectionChecker(private val transport: ModelHttpTransport = HttpsModelTransport()) {
    suspend fun check(access: ModelAccess, role: ModelRole): ModelConnectionResult = withContext(Dispatchers.IO) {
        val settings = access.settings.validated()
        val endpoint = if (role == ModelRole.LANGUAGE) settings.languageEndpoint else settings.imageEndpoint
        val body = if (role == ModelRole.LANGUAGE) languageProbe(settings) else imageProbe(settings)
        val started = System.nanoTime()
        var status: Int? = null
        val kind = try {
            val timeout = if (role == ModelRole.IMAGE) 60_000 else 30_000
            val reply = withTimeout(timeout.toLong() + 10_000) { transport.post(endpoint, access.apiKey, body, timeout) }
            status = reply.status
            interpret(reply, role)
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            ConnectionResultKind.TIMEOUT
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnknownHostException) { ConnectionResultKind.DNS
        } catch (_: SocketTimeoutException) { ConnectionResultKind.TIMEOUT
        } catch (_: SSLException) { ConnectionResultKind.TLS
        } catch (_: ConnectException) { ConnectionResultKind.NETWORK
        } catch (_: InvalidModelResponse) { ConnectionResultKind.BAD_RESPONSE
        } catch (_: IOException) { ConnectionResultKind.NETWORK
        } catch (_: Exception) { ConnectionResultKind.BAD_RESPONSE }
        ModelConnectionResult(kind, status, (System.nanoTime() - started) / 1_000_000)
    }

    private fun languageProbe(settings: ModelSettings): String = JSONObject()
        .put("model", settings.languageModel)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "连接检查，请只回复 OK。")))
        .put("stream", false).put("enable_thinking", false).put("max_tokens", 16).toString()

    private fun imageProbe(settings: ModelSettings): String = JSONObject()
        .put("model", settings.imageModel)
        .put("input", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("text", "浅色背景上的简洁绿色圆形，无文字，用于检查图片生成连接。"))))))
        .put("parameters", JSONObject().put("size", "${settings.imageSize}*${settings.imageSize}").put("prompt_extend", false)).toString()

    internal fun interpret(reply: ModelHttpResponse, role: ModelRole): ConnectionResultKind {
        val json = try { JSONObject(reply.body) } catch (_: Exception) { null }
        val error = json?.optJSONObject("error")
        val code = (error?.optString("code") ?: json?.optString("code")).orEmpty().lowercase()
        // 仅用于分类；服务端原文可能回显凭据，绝不展示或记录 message / body。
        val message = (error?.optString("message") ?: json?.optString("message")).orEmpty().lowercase()
        if (reply.status !in 200..299 || error != null || code.isNotEmpty()) {
            return when {
                reply.status == 401 || "invalid_api_key" in code || "invalidapikey" in code || "unauthorized" in code -> ConnectionResultKind.INVALID_KEY
                reply.status == 402 || "arrearage" in code || "insufficient_quota" in code || "balance" in code -> ConnectionResultKind.BILLING
                "unpurchased" in code || "notactivated" in code -> ConnectionResultKind.SERVICE_NOT_ENABLED
                "model" in code && ("notfound" in code || "not_found" in code || "invalid" in code) ||
                    "model" in message && ("not exist" in message || "not found" in message) -> ConnectionResultKind.MODEL_UNAVAILABLE
                reply.status == 403 || "accessdenied" in code -> ConnectionResultKind.NO_PERMISSION
                reply.status == 429 || "throttl" in code || "ratelimit" in code -> ConnectionResultKind.RATE_LIMIT
                reply.status in 300..399 || reply.status in setOf(404, 405) -> ConnectionResultKind.ENDPOINT
                reply.status >= 500 -> ConnectionResultKind.SERVICE_ERROR
                else -> ConnectionResultKind.PARAMETERS
            }
        }
        if (json == null) return ConnectionResultKind.BAD_RESPONSE
        return when (role) {
            ModelRole.LANGUAGE -> {
                val messageObject = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                if (messageObject?.optString("content").isNullOrBlank()) ConnectionResultKind.BAD_RESPONSE else ConnectionResultKind.SUCCESS
            }
            ModelRole.IMAGE -> {
                val content = json.optJSONObject("output")?.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optJSONArray("content")
                val hasImage = content != null && (0 until content.length()).any { index ->
                    val image = content.optJSONObject(index)?.optString("image").orEmpty()
                    val uri = try { URI(image) } catch (_: Exception) { null }
                    uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
                }
                if (hasImage) ConnectionResultKind.SUCCESS else ConnectionResultKind.BAD_RESPONSE
            }
        }
    }
}
