package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.models.*
import com.txl.mebot.data.subscriptions.*
import java.time.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
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
class SubscriptionInterpreterTest {
    private lateinit var db: MebotDatabase
    private lateinit var settings: ModelSettingsRepository
    private val now = ZonedDateTime.parse("2026-10-27T16:00:00+08:00[Asia/Shanghai]")
    private val context = SubscriptionAnalysisContext(now, listOf(SubscriptionMessageEntity("message", "this-chat", 1, MessageRole.USER, "帮我登记云盘会员", now.toInstant().toEpochMilli())), null,
        listOf(SubscriptionAnalysisImage("local-asset", "message", "data:image/jpeg;base64,ZmFrZQ==")), false)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), MebotDatabase::class.java).build()
        settings = ModelSettingsRepository(db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
    }
    @After fun teardown() { db.close() }
    private fun proposal() = JSONObject("""{"decision":"propose","question":null,"reason":null,"subscription":{
        "title":"云盘会员","amount":"25.00","currency":"CNY","cycle_unit":"MONTH","cycle_interval":1,
        "anchor_date":"2026-10-31","month_end":true,"next_billing_date":"2026-10-31","valid_until":null,"auto_renew":true,
        "account":null,"payment_method":"微信支付","notes":"备份照片"}}""")
    @Test fun sendsConfiguredMultimodalRequestAndNeverEmbedsCredentialsOrLocalPaths() = runBlocking {
        val key = "test-only-subscription-key"
        settings.save(ModelSettings(languageEndpoint = "https://models.example.com/v1/chat/completions"), ApiKeyChange.Replace(key), 0)
        val interpreter = QwenSubscriptionInterpreter(settings, ModelHttpTransport { endpoint, credential, body, timeout ->
            assertEquals("https://models.example.com/v1/chat/completions", endpoint); assertEquals(key, credential); assertEquals(45_000, timeout)
            val request = JSONObject(body)
            assertEquals("qwen3.8-flash", request.getString("model"))
            assertFalse(request.getBoolean("stream")); assertFalse(request.getBoolean("enable_thinking"))
            val content = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertTrue(content.getJSONObject(0).getString("text").contains("帮我登记云盘会员"))
            assertTrue(content.getJSONObject(0).getString("text").contains("2026-10-27"))
            assertEquals("data:image/jpeg;base64,ZmFrZQ==", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertFalse(body.contains(key)); assertFalse(body.contains("local-asset")); assertFalse(body.contains("filesDir"))
            ModelHttpResponse(200, JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", proposal().toString())))).toString())
        })
        val values = (interpreter.interpret(context) as SubscriptionInterpretation.Propose).values
        assertEquals(2500L, values.amountMinor)
        assertEquals(LocalDate.of(2026, 10, 31), values.nextBillingDate)
        assertTrue(values.anchorAtMonthEnd)
        assertNull(values.coverAssetId)
    }
    @Test fun malformedOrUnsafeOutputsCannotBecomeSubscriptionValues() {
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("tool_calls", "delete everything") },
            { it.getJSONObject("subscription").put("id", "someone-else") },
            { it.getJSONObject("subscription").put("amount", 25) },
            { it.getJSONObject("subscription").put("amount", "-25") },
            { it.getJSONObject("subscription").put("amount", "25.123") },
            { it.getJSONObject("subscription").put("amount", "1e10") },
            { it.getJSONObject("subscription").put("amount", "999999999999999999999999999999") },
            { it.getJSONObject("subscription").put("auto_renew", "true") },
            { it.getJSONObject("subscription").put("cycle_interval", "1") },
            { it.getJSONObject("subscription").put("cycle_interval", 1.5) },
            { it.getJSONObject("subscription").put("next_billing_date", "2026-02-30") },
            { it.getJSONObject("subscription").put("currency", "UNKNOWN") },
            { it.getJSONObject("subscription").put("coverAssetId", "remote-other-image") },
            { it.getJSONObject("subscription").put("status", "DELETED") },
        )
        for (corrupt in corruptions) {
            val json = proposal(); corrupt(json)
            assertTrue(json.toString(), runCatching { parseSubscriptionInterpretation(json.toString(), context) }.exceptionOrNull() is SubscriptionAnalysisException)
        }
        assertTrue(runCatching { parseSubscriptionInterpretation(proposal().toString() + " extra", context) }.isFailure)
    }
    @Test fun clearPastAnchorAdvancesNextPlanWithoutInventingAnyActualPayment() {
        val json = proposal().apply { getJSONObject("subscription").put("anchor_date", "2026-01-31").put("next_billing_date", "2026-02-28") }
        val values = (parseSubscriptionInterpretation(json.toString(), context) as SubscriptionInterpretation.Propose).values
        assertEquals(LocalDate.of(2026, 1, 31), values.anchorDate)
        assertEquals(LocalDate.of(2026, 10, 31), values.nextBillingDate)
    }
    @Test fun validQuestionsAndSingleSubscriptionRejectionsAreTypedAndBounded() {
        val ask = JSONObject().put("decision", "ask").put("question", "请确认金额、周期和是否自动续费？").put("reason", JSONObject.NULL).put("subscription", JSONObject.NULL)
        assertTrue(parseSubscriptionInterpretation(ask.toString(), context) is SubscriptionInterpretation.Ask)
        ask.put("question", "已创建订阅")
        assertTrue(runCatching { parseSubscriptionInterpretation(ask.toString(), context) }.isFailure)
        val reject = JSONObject().put("decision", "reject").put("question", JSONObject.NULL).put("reason", "MULTIPLE_SUBSCRIPTIONS").put("subscription", JSONObject.NULL)
        assertEquals("MULTIPLE_SUBSCRIPTIONS", (parseSubscriptionInterpretation(reject.toString(), context) as SubscriptionInterpretation.Reject).reason)
    }
    @Test fun missingConfigurationDoesNotMakeANetworkRequestAndErrorsDoNotExposeProviderResponse() = runBlocking {
        var calls = 0
        val interpreter = QwenSubscriptionInterpreter(settings, ModelHttpTransport { _, _, _, _ -> calls++; ModelHttpResponse(401, "secret-error-body") })
        assertEquals(SubscriptionAnalysisFailure.CONFIGURATION, (runCatching { interpreter.interpret(context) }.exceptionOrNull() as SubscriptionAnalysisException).failure)
        assertEquals(0, calls)
        settings.save(ModelSettings(), ApiKeyChange.Replace("test-key"), 0)
        val error = runCatching { interpreter.interpret(context) }.exceptionOrNull() as SubscriptionAnalysisException
        assertEquals(SubscriptionAnalysisFailure.AUTHORIZATION, error.failure)
        assertFalse(error.toString().contains("secret-error-body"))
        assertFalse(context.images.single().toString().contains("ZmFrZQ"))
    }
}
