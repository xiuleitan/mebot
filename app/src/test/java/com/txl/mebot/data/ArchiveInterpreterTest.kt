package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.models.*
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveInterpreterTest {
    private lateinit var fixture: ArchiveAnalysisFixture
    private lateinit var settings: ModelSettingsRepository
    private lateinit var context: ArchiveAnalysisContext
    @Before fun setup() = runBlocking {
        fixture = ArchiveAnalysisFixture()
        settings = ModelSettingsRepository(fixture.db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        fixture.records.createSession("local-this-chat")
        val front = fixture.image("local-this-chat", "front.jpg")
        val back = fixture.image("local-this-chat", "back.jpg")
        fixture.send("local-this-chat", "我的身份证，正反面合并", listOf(front, back))
        val source = fixture.records.context("local-this-chat")
        context = ArchiveAnalysisContext(source, source.messages, source.messages.single().id,
            listOf(ArchiveAnalysisImage(front, source.messages.single().id, 1, "data:image/jpeg;base64,ZnJvbnQ="),
                ArchiveAnalysisImage(back, source.messages.single().id, 2, "data:image/jpeg;base64,YmFjaw==")), emptyList(), false, 0)
    }
    @After fun teardown() { fixture.close() }
    private fun proposal() = JSONObject("""{"decision":"propose","question":null,"reason":null,"conflicts":[],"archive":{
        "title":"我的身份证","type":"证件","tags":["身份证"],"fields":[{"name":"证件号","value":"001234X"}],"body":"正反面资料","link_labels":[]}}""")
    private fun response(json: JSONObject = proposal(), finish: String = "stop") = ModelHttpResponse(200, JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", json.toString())))).toString())
    private suspend fun configure() { settings.save(ModelSettings(languageEndpoint = "https://model.example.com/v1/chat/completions"), ApiKeyChange.Replace("fixture-key"), 0) }

    @Test fun sendsTextAndBothActualImagesWithSharedDefaultModelWithoutLocalIdentifiers() = runBlocking {
        configure()
        val interpreter = QwenArchiveInterpreter(settings, ModelHttpTransport { endpoint, key, body, timeout ->
            assertEquals("https://model.example.com/v1/chat/completions", endpoint); assertEquals("fixture-key", key); assertEquals(45_000, timeout)
            val request = JSONObject(body)
            assertEquals("qwen3.8-flash", request.getString("model")); assertFalse(request.getBoolean("stream")); assertFalse(request.getBoolean("enable_thinking"))
            assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
            val content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals("text", content.getJSONObject(0).getString("type")); assertTrue(content.getJSONObject(0).getString("text").contains("正反面合并"))
            assertEquals(3, content.length())
            assertEquals(context.images[0].dataUrl, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals(context.images[1].dataUrl, content.getJSONObject(2).getJSONObject("image_url").getString("url"))
            for (privateValue in listOf("fixture-key", context.source.sessionId, context.sourceMessageId) + context.source.assets.map { it.id }) assertFalse(privateValue, body.contains(privateValue))
            assertFalse(body.contains("filesDir")); response()
        })
        val parsed = interpreter.interpret(context) as ArchiveInterpretation.Propose
        assertEquals("001234X", parsed.content.fields.single().value)
        assertEquals(context.source.assets.map { it.id }, parsed.content.assetIds)
        assertEquals(context.source.assets.first().id, parsed.content.coverAssetId)
        assertTrue(fixture.records.records().isEmpty())
    }

    @Test fun labelsCannotRewriteReorderOrDeduplicateOriginalUrls() {
        val urls = listOf("https://example.com/?x=1&x=2#part", "https://example.com/?x=1&x=2#part", "https://example.com/?x=3")
        val linked = context.copy(links = urls.map { ArchiveLink(it) })
        val json = proposal().apply { getJSONObject("archive").put("link_labels", JSONArray(listOf("正面", "背面", "参考"))) }
        val result = parseArchiveInterpretation(json.toString(), linked) as ArchiveInterpretation.Propose
        assertEquals(urls, result.content.links.map { it.url }); assertEquals(listOf("正面", "背面", "参考"), result.content.links.map { it.label })
        json.getJSONObject("archive").put("link_labels", JSONArray(listOf("遗漏")))
        assertInvalid(json.toString(), linked)
    }

    @Test fun rejectsUnknownKeysIdsWrongTypesOversizedFieldsAndTrailingData() {
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("tool_calls", "delete") }, { it.getJSONObject("archive").put("id", "other-record") },
            { it.getJSONObject("archive").put("assetIds", JSONArray(listOf("other-photo"))) },
            { it.getJSONObject("archive").put("links", JSONArray(listOf("https://evil.example"))) },
            { it.getJSONObject("archive").put("title", "") }, { it.getJSONObject("archive").put("title", "长".repeat(301)) },
            { it.getJSONObject("archive").getJSONArray("fields").getJSONObject(0).put("value", 1234) },
            { it.getJSONObject("archive").getJSONArray("fields").getJSONObject(0).put("value", "x".repeat(20_001)) },
            { it.getJSONObject("archive").getJSONArray("fields").getJSONObject(0).put("name", "") },
            { it.getJSONObject("archive").put("tags", JSONArray(listOf(7))) },
            { it.put("conflicts", JSONArray(listOf(""))) }, { it.put("question", "未按协议置空") },
        )
        for (corrupt in corruptions) { val json = proposal(); corrupt(json); assertInvalid(json.toString()) }
        assertInvalid(proposal().toString() + " trailing"); assertInvalid("```json\n${proposal()}\n```")
    }

    @Test fun questionsAndConflictsAreRetainedAndFalseExecutionClaimsAreRejected() {
        val proposal = proposal().put("conflicts", JSONArray(listOf("证件号第六位模糊，请核对", "文字地址与图片地址不同")))
        assertEquals(2, (parseArchiveInterpretation(proposal.toString(), context) as ArchiveInterpretation.Propose).conflicts.size)
        val ask = JSONObject("""{"decision":"ask","question":"这些图片是同一个人的证件吗？","reason":null,"conflicts":[],"archive":null}""")
        assertTrue(parseArchiveInterpretation(ask.toString(), context) is ArchiveInterpretation.Ask)
        ask.put("question", "已经保存身份证"); assertInvalid(ask.toString())
        val reject = JSONObject("""{"decision":"reject","question":null,"reason":"MULTIPLE_ARCHIVES","conflicts":[],"archive":null}""")
        assertEquals("MULTIPLE_ARCHIVES", (parseArchiveInterpretation(reject.toString(), context) as ArchiveInterpretation.Reject).reason)
        reject.put("reason", "DELETE"); assertInvalid(reject.toString())
    }

    @Test fun configurationAndProviderFailuresAreSafeAndNeverPretendToSave() = runBlocking {
        var calls = 0
        val noConfig = QwenArchiveInterpreter(settings, ModelHttpTransport { _, _, _, _ -> calls++; response() })
        assertEquals(ArchiveAnalysisFailure.CONFIGURATION, failure { noConfig.interpret(context) }); assertEquals(0, calls)
        configure()
        for ((status, expected) in listOf(401 to ArchiveAnalysisFailure.AUTHORIZATION, 429 to ArchiveAnalysisFailure.RATE_LIMIT, 500 to ArchiveAnalysisFailure.SERVICE)) {
            val interpreter = QwenArchiveInterpreter(settings, ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(status, "private provider key/account/error") })
            val error = runCatching { interpreter.interpret(context) }.exceptionOrNull() as ArchiveAnalysisException
            assertEquals(expected, error.failure); assertFalse(error.toString().contains("private"))
        }
        val broken = QwenArchiveInterpreter(settings, ModelHttpTransport { _, _, _, _ -> throw IOException("private endpoint") })
        assertEquals(ArchiveAnalysisFailure.NETWORK, failure { broken.interpret(context) })
        val truncated = QwenArchiveInterpreter(settings, ModelHttpTransport { _, _, _, _ -> response(finish = "length") })
        assertEquals(ArchiveAnalysisFailure.INVALID_RESULT, failure { truncated.interpret(context) })
        assertTrue(fixture.records.records().isEmpty()); assertFalse(context.images.first().toString().contains("ZnJvbnQ"))
    }

    @Test fun cancellingInterpretationCancelsTransportWithoutRetry() = runBlocking {
        configure(); val entered = CompletableDeferred<Unit>(); var calls = 0; var cancelled = false
        val interpreter = QwenArchiveInterpreter(settings, ModelHttpTransport { _, _, _, _ ->
            calls++; entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        })
        val job = launch { interpreter.interpret(context) }; entered.await(); job.cancelAndJoin()
        assertTrue(cancelled); assertEquals(1, calls)
    }

    private fun assertInvalid(text: String, source: ArchiveAnalysisContext = context) {
        assertEquals(ArchiveAnalysisFailure.INVALID_RESULT, (runCatching { parseArchiveInterpretation(text, source) }.exceptionOrNull() as? ArchiveAnalysisException)?.failure)
    }
    private suspend fun failure(block: suspend () -> Unit) = (runCatching { block() }.exceptionOrNull() as ArchiveAnalysisException).failure
}
