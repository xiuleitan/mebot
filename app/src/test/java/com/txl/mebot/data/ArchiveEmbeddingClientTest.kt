package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.models.*
import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArchiveEmbeddingClientTest {
    private val config = ArchiveEmbeddingSettings(enabled = true, dimensions = 3)
    private fun reply(native: Boolean, vector: JSONArray = JSONArray(listOf(3, 4, 0)), index: Int = 0): ModelHttpResponse {
        val item = JSONObject().put(if (native) "text_index" else "index", index).put("embedding", vector)
        return ModelHttpResponse(200, if (native) JSONObject().put("output", JSONObject().put("embeddings", JSONArray().put(item))).toString()
            else JSONObject().put("model", config.model).put("data", JSONArray().put(item)).toString())
    }
    @Test fun nativeHttpUsesCorrectDimensionAndDistinctDocumentQueryParametersWithoutEmbeddingKeyInBody() = runBlocking {
        val seen = mutableListOf<String>()
        val client = QwenArchiveEmbeddingClient(ModelHttpTransport { endpoint, key, body, timeout ->
            assertEquals(config.endpoint, endpoint); assertEquals("fixture-key", key); assertEquals(35_000, timeout)
            assertFalse(body.contains("fixture-key"))
            val json = JSONObject(body); assertEquals(config.model, json.getString("model"))
            assertEquals("我的中国银行卡", json.getJSONObject("input").getJSONArray("texts").getString(0))
            val params = json.getJSONObject("parameters"); assertEquals(3, params.getInt("dimension")); assertFalse(params.has("dimensions"))
            seen += params.getString("text_type"); reply(true)
        })
        for (use in listOf(ArchiveEmbeddingUse.DOCUMENT, ArchiveEmbeddingUse.QUERY)) {
            val vector = client.embed(config, "fixture-key", "我的中国银行卡", use)
            assertArrayEquals(floatArrayOf(.6f, .8f, 0f), vector, .00001f)
        }
        assertEquals(listOf("document", "query"), seen)
    }
    @Test fun compatibleProtocolUsesItsOwnFieldNamesAndDoesNotSendDashscopeParameters() = runBlocking {
        val compatible = config.copy(endpoint = "https://models.example.com/v1/embeddings", protocol = ArchiveEmbeddingProtocol.OPENAI_COMPATIBLE)
        val client = QwenArchiveEmbeddingClient(ModelHttpTransport { _, _, body, _ ->
            val json = JSONObject(body)
            assertEquals("身份证", json.getString("input")); assertEquals(3, json.getInt("dimensions")); assertEquals("float", json.getString("encoding_format"))
            assertFalse(json.has("parameters")); assertFalse(json.has("text_type")); reply(false)
        })
        assertEquals(3, client.embed(compatible, "fixture-key", "身份证", ArchiveEmbeddingUse.QUERY).size)
    }
    @Test fun invalidVectorsWrongDimensionsWrongIndicesAndUnexpectedModelAreRejected() = runBlocking {
        val invalid = listOf(reply(true, JSONArray(listOf(0, 0, 0))), reply(true, JSONArray(listOf(1, 0))),
            reply(true, JSONArray(listOf("1", 0, 0))), reply(true, index = 1),
            ModelHttpResponse(200, """{"output":{"embeddings":[]}}"""), ModelHttpResponse(200, """{"output":{"embeddings":[{"text_index":0,"embedding":[1e100,0,0]}]}}"""))
        for (response in invalid) {
            val client = QwenArchiveEmbeddingClient(ModelHttpTransport { _, _, _, _ -> response })
            assertEquals(ArchiveSemanticFailure.INVALID_RESULT, (runCatching { client.embed(config, "fixture-key", "资料", ArchiveEmbeddingUse.DOCUMENT) }.exceptionOrNull() as ArchiveSemanticException).failure)
        }
    }
    @Test fun configurationLimitsAndNetworkErrorsAreSafeAndDoNotRetryAutomatically() = runBlocking {
        var calls = 0
        val client = QwenArchiveEmbeddingClient(ModelHttpTransport { _, _, _, _ -> calls++; throw IOException("private account info") })
        assertEquals(ArchiveSemanticFailure.CONFIGURATION, failure { client.embed(config.copy(endpoint = "http://example.com/embeddings"), "key", "资料", ArchiveEmbeddingUse.DOCUMENT) })
        assertEquals(ArchiveSemanticFailure.INPUT_LIMIT, failure { client.embed(config, "key", "字".repeat(2500), ArchiveEmbeddingUse.DOCUMENT) })
        assertEquals(0, calls)
        assertEquals(ArchiveSemanticFailure.NETWORK, failure { client.embed(config, "key", "资料", ArchiveEmbeddingUse.QUERY) }); assertEquals(1, calls)
        val denied = QwenArchiveEmbeddingClient(ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(401, "sensitive provider body") })
        val error = runCatching { denied.embed(config, "key", "资料", ArchiveEmbeddingUse.QUERY) }.exceptionOrNull()!!
        assertFalse(error.toString().contains("sensitive")); assertEquals(ArchiveSemanticFailure.AUTHORIZATION, (error as ArchiveSemanticException).failure)
        val billing = QwenArchiveEmbeddingClient(ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(403, """{"code":"Arrearage","message":"private details"}""") })
        assertEquals(ArchiveSemanticFailure.BILLING, failure { billing.embed(config, "key", "资料", ArchiveEmbeddingUse.QUERY) })
    }
    @Test fun cancellationPropagatesToTransportAndNeverRetries() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var cancelled = false; var calls = 0
        val client = QwenArchiveEmbeddingClient(ModelHttpTransport { _, _, _, _ -> calls++; entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } })
        val job = launch { client.embed(config, "key", "资料", ArchiveEmbeddingUse.QUERY) }
        entered.await(); job.cancelAndJoin(); assertTrue(cancelled); assertEquals(1, calls)
    }
    private suspend fun failure(block: suspend () -> Unit) = (runCatching { block() }.exceptionOrNull() as ArchiveSemanticException).failure
}
