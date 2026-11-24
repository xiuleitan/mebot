package com.txl.mebot.data.archive

import com.txl.mebot.data.models.HttpsModelTransport
import com.txl.mebot.data.models.ModelHttpTransport
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

fun interface ArchiveEmbeddingClient {
    suspend fun embed(settings: ArchiveEmbeddingSettings, key: String, text: String, use: ArchiveEmbeddingUse): FloatArray
}

/** 每次只编码一份已确认投影/一个查询；传输可取消，不重定向、不在此层重试。 */
class QwenArchiveEmbeddingClient(private val transport: ModelHttpTransport = HttpsModelTransport()) : ArchiveEmbeddingClient {
    override suspend fun embed(settings: ArchiveEmbeddingSettings, key: String, text: String, use: ArchiveEmbeddingUse): FloatArray = withContext(Dispatchers.IO) {
        try {
            val config = settings.validated()
            if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 7000) throw ArchiveSemanticException(ArchiveSemanticFailure.INPUT_LIMIT)
            val body = JSONObject().put("model", config.model)
            if (config.protocol == ArchiveEmbeddingProtocol.DASHSCOPE) {
                body.put("input", JSONObject().put("texts", JSONArray().put(text)))
                    .put("parameters", JSONObject().put("dimension", config.dimensions).put("output_type", "dense")
                        .put("text_type", if (use == ArchiveEmbeddingUse.QUERY) "query" else "document"))
            } else body.put("input", text).put("dimensions", config.dimensions).put("encoding_format", "float")
            val response = withTimeout(45_000) { transport.post(config.endpoint, key, body.toString(), 35_000) }
            if (response.status !in 200..299) {
                val code = runCatching { JSONObject(response.body).let { it.optJSONObject("error")?.optString("code") ?: it.optString("code") }.lowercase() }.getOrDefault("")
                if ("arrearage" in code || "insufficient_quota" in code || "balance" in code) throw ArchiveSemanticException(ArchiveSemanticFailure.BILLING)
                throw ArchiveSemanticException(when (response.status) {
                401, 403 -> ArchiveSemanticFailure.AUTHORIZATION
                402 -> ArchiveSemanticFailure.BILLING
                408, 504 -> ArchiveSemanticFailure.TIMEOUT
                429 -> ArchiveSemanticFailure.RATE_LIMIT
                400, 404, 422 -> ArchiveSemanticFailure.CONFIGURATION
                in 500..599 -> ArchiveSemanticFailure.SERVICE
                else -> ArchiveSemanticFailure.INVALID_RESULT
                })
            }
            val json = JSONObject(response.body)
            val declaredModel = json.optString("model")
            if (declaredModel.isNotEmpty() && declaredModel != config.model) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
            val array = if (config.protocol == ArchiveEmbeddingProtocol.DASHSCOPE) json.getJSONObject("output").getJSONArray("embeddings") else json.getJSONArray("data")
            if (array.length() != 1) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
            val item = array.getJSONObject(0)
            val index = item.opt("text_index") ?: item.opt("index")
            if (index !is Number || index.toDouble() != 0.0) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
            val values = item.getJSONArray("embedding")
            if (values.length() != config.dimensions) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
            archiveUnitVector(FloatArray(values.length()) { position ->
                val value = values.get(position)
                if (value !is Number) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
                value.toFloat()
            })
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            throw ArchiveSemanticException(ArchiveSemanticFailure.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ArchiveSemanticException) { throw error }
        catch (_: SocketTimeoutException) { throw ArchiveSemanticException(ArchiveSemanticFailure.TIMEOUT) }
        catch (_: IOException) { throw ArchiveSemanticException(ArchiveSemanticFailure.NETWORK) }
        catch (_: Exception) { throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT) }
    }
}
