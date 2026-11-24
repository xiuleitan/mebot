package com.txl.mebot.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.models.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeTextRouterTest {
    private lateinit var app: Application
    private lateinit var db: MebotDatabase
    private lateinit var settings: HomeRoutingSettings
    private lateinit var models: ModelSettingsRepository
    private val endpoint = "https://ws-test.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone"
    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(app); settings = HomeRoutingSettings(db)
        models = ModelSettingsRepository(db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 9 }, "AES") })
        settings.save(endpoint, 0); models.save(ModelSettings(), ApiKeyChange.Replace("test-key"), 0)
        Unit
    }
    @After fun close() { db.close(); app.deleteDatabase(MebotDatabase.FILE_NAME) }
    private fun answer(id: String) = """{"answers":{"bot":{"type":"choice","choice":"$id","confidence":0.91}}}"""
    @Test fun requestCarriesOnlyCurrentTextAndStableBotOptionsOverTheSystemOneProtocol() = runBlocking {
        var requests = 0
        val router = BailianHomeTextRouter(settings, models, ModelHttpTransport { url, key, body, _ ->
            requests++; assertEquals(endpoint, url); assertEquals("test-key", key)
            val json = JSONObject(body)
            assertEquals("decision-model-preview", json.getString("model"))
            assertEquals("明天要买菜", json.getString("state")); assertFalse(json.has("messages"))
            assertFalse(body.contains("image_url")); assertFalse(body.contains("test-key"))
            val question = json.getJSONObject("questions").getJSONObject("bot")
            assertEquals("choice", question.getString("type"))
            val options = question.getJSONObject("criteria")
            assertEquals(HomeBot.entries.map { it.id }.toSet(), options.keys().asSequence().toSet())
            HomeBot.entries.forEach { assertEquals(it.criteria, options.getString(it.id)) }
            ModelHttpResponse(200, answer("schedule"))
        })
        assertEquals(HomeBot.SCHEDULE, router.choose("明天要买菜")); assertEquals(1, requests)
    }
    @Test fun validSingleChoicesMapToAllBotsAndUnknownResponsesCannotBecomeRoutes() = runBlocking {
        for (bot in HomeBot.entries) {
            val router = BailianHomeTextRouter(settings, models, ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(200, answer(bot.id)) })
            assertEquals(bot, router.choose("测试任务"))
            assertTrue(runCatching { router.parse(answer("other")) }.isFailure)
            assertTrue(runCatching { router.parse(answer("invented-bot")) }.isFailure)
            assertTrue(runCatching { router.parse("""{"answers":{"bot":{"type":"score","choice":"schedule"}}}""") }.isFailure)
        }
    }
    @Test fun highestExistingBotProbabilityWinsEvenWhenChoiceNamesAnotherClass() {
        val router = BailianHomeTextRouter(settings, models)
        for (winner in HomeBot.entries) {
            val scores = JSONObject()
            HomeBot.entries.forEach { scores.put(it.id, if (it == winner) 0.6 else 0.1) }
            scores.put("other", 0.99)
            val body = JSONObject(answer("other"))
            body.getJSONObject("answers").getJSONObject("bot").put("probabilities", scores)
            assertEquals(winner, router.parse(body.toString()))
        }
    }
    @Test fun lowConfidenceStillSelectsTheBestBotAndTiesKeepAValidModelChoice() {
        val router = BailianHomeTextRouter(settings, models)
        val body = JSONObject(answer("archive"))
        val item = body.getJSONObject("answers").getJSONObject("bot")
        val scores = JSONObject()
        HomeBot.entries.forEach { scores.put(it.id, if (it == HomeBot.SCHEDULE) 0.21 else if (it == HomeBot.ARCHIVE) 0.19 else 0.2) }
        item.put("confidence", 0.01).put("probabilities", scores)
        assertEquals(HomeBot.SCHEDULE, router.parse(body.toString()))
        HomeBot.entries.forEach { scores.put(it.id, 0.2) }
        assertEquals(HomeBot.ARCHIVE, router.parse(body.toString()))
        item.put("choice", "unknown")
        assertEquals(HomeBot.SCHEDULE, router.parse(body.toString()))
    }
    @Test fun malformedDistributionsAreReportedAsResponseFailuresWithoutCreatingAFakeCategory() {
        val router = BailianHomeTextRouter(settings, models)
        for (invalid in listOf(-0.1, 1.1, "not-a-number", JSONObject.NULL)) {
            val scores = JSONObject(); HomeBot.entries.forEach { scores.put(it.id, 0.2) }
            scores.put("schedule", invalid)
            val body = JSONObject(answer("schedule"))
            body.getJSONObject("answers").getJSONObject("bot").put("probabilities", scores)
            assertTrue(runCatching { router.parse(body.toString()) }.isFailure)
        }
    }
    @Test fun configurationAndNetworkFailureAreExplicitAndNeverInventALocalBot() = runBlocking {
        var requests = 0
        val transport = ModelHttpTransport { _, _, _, _ -> requests++; ModelHttpResponse(401, "redacted") }
        val router = BailianHomeTextRouter(settings, models, transport)
        val error = runCatching { router.choose("明天买菜") }.exceptionOrNull()
        assertTrue(error is HomeRouteFailure); assertTrue(error!!.message!!.contains("鉴权")); assertEquals(1, requests)
        val current = settings.read(); settings.save("", current.revision)
        assertTrue(runCatching { router.choose("明天买菜") }.exceptionOrNull() is HomeRouteFailure)
        assertEquals(1, requests)
        for (bad in listOf("http://host/compatible-mode/v1/systemone", "https://key@host/compatible-mode/v1/systemone", "$endpoint?key=bad", "https://host/v1/chat/completions"))
            assertTrue(runCatching { validateDecisionEndpoint(bad) }.isFailure)
    }
}
