package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.local.*
import com.txl.mebot.data.models.*
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QwenDayimaInterpreterTest {
    private lateinit var db: MebotDatabase
    private lateinit var settings: ModelSettingsRepository
    private val now = ZonedDateTime.parse("2026-10-25T12:00:00+08:00[Asia/Shanghai]")
    private val context = DayimaAiContext(now, listOf(DayimaChatMessageEntity("m1", "this-chat-only", 1, DayimaMessageRole.USER, "今天来了", 1)),
        listOf(DayimaChatTarget("local-record-id-not-for-provider", LocalDate.of(2026, 9, 27), 1)))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), MebotDatabase::class.java).build()
        settings = ModelSettingsRepository(db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
    }
    @After fun cleanup() { db.close() }

    @Test fun usesSharedQwenSettingsAndDoesNotPutCredentialsOrDatabaseIdsInBody() = runBlocking {
        settings.save(ModelSettings(), ApiKeyChange.Replace("fake-dayima-key-for-tests"), 0)
        val interpreter = QwenDayimaInterpreter(settings, ModelHttpTransport { endpoint, key, body, timeout ->
            assertEquals(ModelSettings().languageEndpoint, endpoint)
            assertEquals("fake-dayima-key-for-tests", key)
            assertEquals(35_000, timeout)
            assertFalse(body.contains(key)); assertFalse(body.contains("local-record-id-not-for-provider"))
            val request = JSONObject(body)
            assertEquals("qwen3.8-flash", request.getString("model"))
            assertFalse(request.getBoolean("stream")); assertFalse(request.getBoolean("enable_thinking"))
            assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
            val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(now.toString(), input.getString("sent_at"))
            assertEquals(1, input.getJSONArray("messages").length())
            response("""{"decision":"write","operations":[{"kind":"record","date":"2026-10-25","note":null}]}""")
        })
        val result = interpreter.interpret(context) as DayimaAiDecision.Write
        assertEquals(DayimaChatMutation.Record(now.toLocalDate(), null), result.mutations.single())
    }

    @Test fun invalidStructuredRepliesAreRejectedRatherThanPartiallyInterpreted() {
        val invalid = listOf(
            """{"decision":"write","operations":[{"kind":"record","date":"2026-02-30","note":null}]}""",
            """{"decision":"write","operations":[{"kind":"edit","target":{"id":"invented"},"date":"2026-10-24"}]}""",
            """{"decision":"write","operations":[{"kind":"reminders","prepare":"true"}]}""",
            """{"decision":"answer","text":"你好","sql":"DELETE FROM records"}""",
            """{"decision":"query","query":{"kind":"COMPARE","from":"2026-01-01","through":"2026-02-01"}}""",
            """{"decision":"write","operations":[]}""",
            """{"decision":"write","operations":[{"kind":"images","target":{"id":"invented"},"assetId":"other-chat"}]}""",
            "```json\n{\"decision\":\"answer\",\"text\":\"hello\"}\n```",
            """{"decision":"answer","text":"你好"} trailing"""
        )
        for (text in invalid) try { parseDayimaDecision(text); fail(text) }
        catch (error: DayimaChatException) { assertEquals(DayimaChatFailure.INVALID_RESULT, error.failure) }
    }

    @Test fun localPhotosAreRepresentedOnlyByTheirCountAndCannotBecomeProviderImageOrFilePayloads() = runBlocking {
        settings.save(ModelSettings(), ApiKeyChange.Replace("fake-dayima-key-for-tests"), 0)
        val interpreter = QwenDayimaInterpreter(settings, ModelHttpTransport { _, _, body, _ ->
            val input = JSONObject(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(setOf("sent_at", "history_trimmed", "attached_local_images", "messages", "last_targets", "requested_local_data"), input.keys().asSequence().toSet())
            assertEquals(2, input.getInt("attached_local_images"))
            for (forbidden in listOf("image_url", "data:image", "content://", "media/originals", "relativePath", "displayName", "base64")) assertFalse(body.contains(forbidden))
            response("""{"decision":"write","operations":[{"kind":"images","target":{"date":"2026-10-25","last":false}}]}""")
        })
        val decision = interpreter.interpret(context.copy(pendingImageCount = 2)) as DayimaAiDecision.Write
        assertEquals(DayimaChatMutation.Images(DayimaTargetRef(now.toLocalDate())), decision.mutations.single())
    }

    @Test fun missingKeyAndHttpFailuresHaveSafeSpecificErrors() = runBlocking {
        var called = false
        val missing = QwenDayimaInterpreter(settings, ModelHttpTransport { _, _, _, _ -> called = true; response("{}") })
        failure(DayimaChatFailure.CONFIGURATION) { missing.interpret(context) }
        assertFalse(called)
        settings.save(ModelSettings(), ApiKeyChange.Replace("fake-dayima-key-for-tests"), 0)
        val denied = QwenDayimaInterpreter(settings, ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(401, """{"error":{"code":"InvalidApiKey","message":"hidden-details"}}""") })
        failure(DayimaChatFailure.AUTHORIZATION) { denied.interpret(context) }
        val network = QwenDayimaInterpreter(settings, ModelHttpTransport { _, _, _, _ -> throw IOException("hidden-details") })
        failure(DayimaChatFailure.NETWORK) { network.interpret(context) }
        val truncated = QwenDayimaInterpreter(settings, ModelHttpTransport { _, _, _, _ -> response("""{"decision":"answer","text":"未完成"}""", "length") })
        failure(DayimaChatFailure.INVALID_RESULT) { truncated.interpret(context) }
    }

    @Test fun cancellationIsPropagatedAndNeverRewrittenAsSuccessOrRetryableNetworkFailure() = runBlocking {
        settings.save(ModelSettings(), ApiKeyChange.Replace("fake-dayima-key-for-tests"), 0)
        val interpreter = QwenDayimaInterpreter(settings, ModelHttpTransport { _, _, _, _ -> throw CancellationException("cancelled") })
        try { interpreter.interpret(context); fail() } catch (_: CancellationException) { }
    }

    private suspend fun failure(expected: DayimaChatFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") } catch (error: DayimaChatException) {
            assertEquals(expected, error.failure); assertFalse(error.message.orEmpty().contains("hidden-details"))
        }
    }
    private fun response(content: String, finish: String = "stop") = ModelHttpResponse(200, JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString())
}
