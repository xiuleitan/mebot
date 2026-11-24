package com.txl.mebot.data.models

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

class GeneratedCoverLink(val url: String, val model: String) {
    override fun toString() = "GeneratedCoverLink(model=$model, url=[redacted])"
}
class ModelImageException(val kind: ConnectionResultKind) : IOException("封面生成失败：$kind")
fun interface CoverImageGenerator { suspend fun generate(prompt: String): GeneratedCoverLink }
fun interface CoverImageDownloader { suspend fun download(url: String): ByteArray }

/** 仅发送本地构造的装饰提示词；没有附件、历史、用户图片或额外的提示词改写请求。 */
class ZImageCoverGenerator(
    private val settings: ModelSettingsRepository,
    private val transport: ModelHttpTransport = HttpsModelTransport(),
) : CoverImageGenerator {
    override suspend fun generate(prompt: String): GeneratedCoverLink = withContext(Dispatchers.IO) {
        try {
            val stored = settings.load()
            val access = settings.access(stored.revision)
            val configuration = access.settings.validated()
            val body = JSONObject().put("model", configuration.imageModel)
                .put("input", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("text", prompt))))))
                .put("parameters", JSONObject().put("size", "768*768").put("prompt_extend", false)).toString()
            val response = withTimeout(80_000) { transport.post(configuration.imageEndpoint, access.apiKey, body, 60_000) }
            val kind = ModelConnectionChecker().interpret(response, ModelRole.IMAGE)
            if (kind != ConnectionResultKind.SUCCESS) throw ModelImageException(kind)
            val choice = JSONObject(response.body).getJSONObject("output").getJSONArray("choices").getJSONObject(0)
            if (choice.optString("finish_reason", "stop") != "stop") throw ModelImageException(ConnectionResultKind.BAD_RESPONSE)
            val content = choice.getJSONObject("message").getJSONArray("content")
            val urls = (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("image")?.takeIf(String::isNotBlank) }
            if (urls.size != 1 || !isGeneratedImageUrl(urls.single())) throw ModelImageException(ConnectionResultKind.BAD_RESPONSE)
            GeneratedCoverLink(urls.single(), configuration.imageModel)
        } catch (failure: ModelImageException) { throw failure
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            throw ModelImageException(ConnectionResultKind.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: MissingApiKey) { throw ModelImageException(ConnectionResultKind.PARAMETERS)
        } catch (_: CredentialUnavailable) { throw ModelImageException(ConnectionResultKind.PARAMETERS)
        } catch (_: ModelSettingsConflict) { throw ModelImageException(ConnectionResultKind.PARAMETERS)
        } catch (_: ModelConfigurationError) { throw ModelImageException(ConnectionResultKind.PARAMETERS)
        } catch (_: SocketTimeoutException) { throw ModelImageException(ConnectionResultKind.TIMEOUT)
        } catch (_: SSLException) { throw ModelImageException(ConnectionResultKind.TLS)
        } catch (_: IOException) { throw ModelImageException(ConnectionResultKind.NETWORK)
        } catch (_: Exception) { throw ModelImageException(ConnectionResultKind.BAD_RESPONSE) }
    }
}

internal fun isGeneratedImageUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
        uri.port != 0 && uri.port <= 65535
}.getOrDefault(false)

/** 下载使用独立 GET，不携带百炼凭据、不跟随重定向，限制读取大小并在取消时断开连接。 */
class HttpsCoverImageDownloader(
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : CoverImageDownloader {
    override suspend fun download(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
        Dispatchers.IO.dispatch(continuation.context) {
            var connection: HttpsURLConnection? = null
            try {
                require(isGeneratedImageUrl(url))
                if (!continuation.isActive) return@dispatch
                val request = connect(URL(url))
                connection = request
                continuation.invokeOnCancellation {
                    Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { request.disconnect() } }
                }
                if (!continuation.isActive) return@dispatch
                request.requestMethod = "GET"
                request.instanceFollowRedirects = false
                request.connectTimeout = 10_000
                request.readTimeout = 20_000
                request.useCaches = false
                request.setRequestProperty("Accept", "image/*")
                if (request.responseCode !in 200..299 || request.contentLengthLong > MAX_BYTES) throw IOException("封面下载失败")
                val bytes = request.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (!continuation.isActive) throw CancellationException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_BYTES) throw IOException("封面图片过大")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                continuation.resume(bytes)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally { runCatching { connection?.disconnect() } }
        }
    }

    private companion object { const val MAX_BYTES = 12 * 1024 * 1024 }
}
