package com.txl.mebot.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ScheduleMessageEntity
import com.txl.mebot.data.models.*
import com.txl.mebot.data.schedule.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
class ScheduleTextInterpreterTest {
    private lateinit var database: MebotDatabase
    private lateinit var settings: ModelSettingsRepository
    private val now = ZonedDateTime.parse("2026-10-22T10:00:35+08:00[Asia/Shanghai]")
    private val context = ScheduleAnalysisContext(now, listOf(ScheduleMessageEntity(
        "user-1", "only-this-session", 1, MessageRole.USER, "明天下午4点开会，提前30分钟和10分钟提醒，准点也提醒", now.toInstant().toEpochMilli(),
    )), null, emptyList())

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(application, MebotDatabase::class.java).build()
        settings = ModelSettingsRepository(database, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
    }
    @After fun tearDown() { database.close() }

    @Test fun configuredRequestContainsOnlyTextContextAndValidatesStructuredResponse() = runBlocking {
        val configured = ModelSettings(languageEndpoint = "https://models.example.com/v1/chat/completions")
        settings.save(configured, ApiKeyChange.Replace(KEY), 0)
        var calls = 0
        val draft = ScheduleDraft("关注Predict上Alpha", "留意开盘情况", now.toLocalDate().plusDays(1), LocalTime.of(16, 0), reminderMinutes = listOf(30, 10, 0))
        val interpreter = QwenScheduleTextInterpreter(settings, ModelHttpTransport { endpoint, key, body, timeout ->
            calls++
            assertEquals(configured.languageEndpoint, endpoint)
            assertEquals(KEY, key)
            assertEquals(30_000, timeout)
            assertFalse(body.contains(KEY))
            val request = JSONObject(body)
            assertEquals("qwen3.8-flash", request.getString("model"))
            assertFalse(request.getBoolean("stream"))
            assertFalse(request.getBoolean("enable_thinking"))
            assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
            val messages = request.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertTrue(messages.getJSONObject(0).getString("content").contains("1~30 个 Unicode 字符"))
            val data = JSONObject(messages.getJSONObject(1).getString("content"))
            assertEquals("Asia/Shanghai", data.getString("device_timezone"))
            val attachments = data.getJSONObject("attachment_summary")
            assertEquals(setOf("total_count", "image_count", "file_count"), attachments.keys().asSequence().toSet())
            assertEquals(0, attachments.getInt("total_count"))
            assertEquals(0, attachments.getInt("image_count"))
            assertEquals(0, attachments.getInt("file_count"))
            val history = data.getJSONArray("conversation")
            assertEquals(1, history.length())
            assertEquals(context.messages.single().content, history.getJSONObject(0).getString("text"))
            assertFalse(body.contains("image_url"))
            assertFalse(body.contains("file_url"))
            response(saveJson(draft))
        })
        val result = interpreter.interpret(context) as ScheduleInterpretation.Save
        assertEquals(draft, result.draft)
        assertEquals(1, calls)
    }

    @Test fun dateOnlyDefaultsToDeviceDateAndSupportedRecurrencesKeepAllReminderLeads() {
        val dateOnly = parseScheduleInterpretation(saveJson(ScheduleDraft("采购食材", "土豆、猕猴桃")).toString(), context) as ScheduleInterpretation.Save
        assertEquals(now.toLocalDate(), dateOnly.draft.date)
        assertNull(dateOnly.draft.time)
        assertTrue(requireNotNull(dateOnly.draft.reminderMinutes).isEmpty())
        val rule = RecurrenceRule(RecurrencePattern.WEEKLY, weekDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), endDate = LocalDate.of(2026, 12, 31))
        val draft = ScheduleDraft("喂猫", date = now.toLocalDate(), time = LocalTime.of(18, 0), recurrence = rule, reminderMinutes = listOf(0, 10, 30, 10))
        val result = parseScheduleInterpretation(saveJson(draft).toString(), context) as ScheduleInterpretation.Save
        assertEquals(rule, result.draft.recurrence)
        assertEquals(listOf(30, 10, 0), result.draft.reminderMinutes)
    }

    @Test fun timedDraftsUseLocalDefaultsAndKeepOnTimeWithACustomAdvance() {
        val draft = ScheduleDraft("开会", date = now.toLocalDate(), time = LocalTime.of(16, 0))
        val default = parseScheduleInterpretation(saveJson(draft).toString(), context) as ScheduleInterpretation.Save
        assertEquals(listOf(10, 0), default.draft.reminderMinutes)
        for (minutes in listOf(2, 8, 15)) {
            val request = saveJson(draft.copy(reminderMinutes = listOf(minutes)))
            val custom = parseScheduleInterpretation(request.toString(), context) as ScheduleInterpretation.Save
            assertEquals(listOf(minutes, 0), custom.draft.reminderMinutes)
        }

        val disabled = parseScheduleInterpretation(saveJson(draft.copy(reminderMinutes = emptyList())).toString(), context) as ScheduleInterpretation.Save
        assertEquals(emptyList<Int>(), disabled.draft.reminderMinutes)
        val advanceOnlyRequest = saveJson(draft.copy(reminderMinutes = listOf(8)))
        advanceOnlyRequest.getJSONObject("schedule").put("remind_at_start", false)
        val advanceOnly = parseScheduleInterpretation(advanceOnlyRequest.toString(), context) as ScheduleInterpretation.Save
        assertEquals(listOf(8), advanceOnly.draft.reminderMinutes)
        val titleEdit = editJson(JSONObject().put("title", "新标题"))
        val edited = parseScheduleInterpretation(titleEdit.toString(), context.copy(current = advanceOnly.draft)) as ScheduleInterpretation.Edit
        assertEquals(listOf(8), edited.patch.applyTo(advanceOnly.draft).reminderMinutes)
    }

    @Test fun malformedUnsafeOrCoercedFieldsNeverBecomeAValidTask() {
        val valid = ScheduleDraft("开会", date = now.toLocalDate(), time = LocalTime.of(16, 0))
        val cases = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("schedule").put("title", "😀".repeat(31)) },
            { it.getJSONObject("schedule").put("title", " ") },
            { it.getJSONObject("schedule").put("date", "2026-02-30") },
            { it.getJSONObject("schedule").put("date", 1234) },
            { it.getJSONObject("schedule").put("time", "24:00") },
            { it.getJSONObject("schedule").put("time", "16:00:30") },
            { it.getJSONObject("schedule").put("reminder_minutes", JSONArray().put("10")) },
            { it.getJSONObject("schedule").put("reminder_minutes", JSONArray().put(0.5)) },
            { it.getJSONObject("schedule").put("reminder_minutes", JSONArray().put(-1)) },
            { it.getJSONObject("schedule").put("time", JSONObject.NULL).put("reminder_minutes", JSONArray().put(0)) },
            { it.getJSONObject("schedule").put("sql", "DROP TABLE schedule_tasks") },
            { it.put("answered_question_ids", JSONArray().put("another-sessions-question")) },
            { it.put("decision", "execute_tool") },
        )
        for (change in cases) {
            val candidate = saveJson(valid).also(change)
            val failure = runCatching { parseScheduleInterpretation(candidate.toString(), context) }.exceptionOrNull()
            assertEquals(ScheduleAnalysisFailure.INVALID_RESULT, (failure as ScheduleAnalysisException).failure)
        }
        for (text in listOf("```json\n${saveJson(valid)}\n```", "${saveJson(valid)} trailing", "[]")) {
            assertTrue(runCatching { parseScheduleInterpretation(text, context) }.exceptionOrNull() is ScheduleAnalysisException)
        }
        for (title in listOf("😀".repeat(30), "A".repeat(30), "关注Predict上Alpha")) {
            val longTitle = valid.copy(title = title)
            assertEquals(title, (parseScheduleInterpretation(saveJson(longTitle).toString(), context) as ScheduleInterpretation.Save).draft.title)
        }
    }

    @Test fun missingKeyHttpFailuresAndNetworkErrorsDoNotRetryOrLeakProviderText() = runBlocking {
        var calls = 0
        val missing = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, _, _ -> calls++; error("must not call") })
        assertEquals(ScheduleAnalysisFailure.CONFIGURATION, failureOf { missing.interpret(context) }.failure)
        assertEquals(0, calls)
        settings.save(ModelSettings(), ApiKeyChange.Replace(KEY), 0)
        for ((status, expected) in listOf(401 to ScheduleAnalysisFailure.AUTHORIZATION, 402 to ScheduleAnalysisFailure.BILLING, 503 to ScheduleAnalysisFailure.SERVICE)) {
            calls = 0
            val interpreter = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, _, _ ->
                calls++; ModelHttpResponse(status, "{\"error\":{\"message\":\"$KEY\"}}")
            })
            val failure = failureOf { interpreter.interpret(context) }
            assertEquals(expected, failure.failure)
            assertFalse(failure.toString().contains(KEY))
            assertEquals(1, calls)
        }
        for ((error, expected) in listOf(IOException(KEY) to ScheduleAnalysisFailure.NETWORK, SocketTimeoutException(KEY) to ScheduleAnalysisFailure.TIMEOUT)) {
            val interpreter = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, _, _ -> throw error })
            assertEquals(expected, failureOf { interpreter.interpret(context) }.failure)
        }
    }

    @Test fun truncatedResultIsRejectedAndCancellationPropagatesWithoutRetry() = runBlocking {
        settings.save(ModelSettings(), ApiKeyChange.Replace(KEY), 0)
        val truncated = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, _, _ ->
            response(saveJson(ScheduleDraft("买菜")), finish = "length")
        })
        assertEquals(ScheduleAnalysisFailure.INVALID_RESULT, failureOf { truncated.interpret(context) }.failure)
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val waiting = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, _, _ -> calls++; started.complete(Unit); awaitCancellation() })
        val work = async { waiting.interpret(context) }
        started.await(); work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun editProtocolCarriesOnlyChangedFieldsAndPreservesTheSavedTask() {
        val base = ScheduleDraft("例会", "带电脑", now.toLocalDate().plusDays(1), LocalTime.of(16, 0), reminderMinutes = listOf(30, 10, 0), coverAssetId = "local-cover")
        val editing = context.copy(current = base, selected = base)
        val json = editJson(JSONObject().put("time", "17:00"))
        val change = parseScheduleInterpretation(json.toString(), editing) as ScheduleInterpretation.Edit
        assertEquals(setOf("time"), change.patch.fields)
        val result = change.patch.applyTo(base)
        assertEquals(base.copy(time = LocalTime.of(17, 0)), result)
        val dateOnly = parseScheduleInterpretation(editJson(JSONObject().put("time", JSONObject.NULL)).toString(), editing) as ScheduleInterpretation.Edit
        assertNull(dateOnly.patch.applyTo(base).time)
        assertTrue(requireNotNull(dateOnly.patch.applyTo(base).reminderMinutes).isEmpty())
        assertTrue(runCatching { parseScheduleInterpretation(editJson(JSONObject().put("coverAssetId", "remote")).toString(), editing) }.isFailure)
        assertTrue(runCatching { parseScheduleInterpretation(json.toString(), context) }.isFailure)
        assertTrue(runCatching { parseScheduleInterpretation(saveJson(base).toString(), editing) }.isFailure) // 已有任务不允许重新整项生成。
    }

    @Test fun deleteProtocolUsesScopeAndOriginalDateWhileQueriesNeverReturnTaskData() {
        val delete = deleteJson("once").put("target_date", "2026-10-23")
        val result = parseScheduleInterpretation(delete.toString(), context) as ScheduleInterpretation.Delete
        assertEquals(ScheduleEditScope.ONCE, result.scope)
        assertEquals(LocalDate.of(2026, 10, 23), result.targetDate)
        assertTrue(runCatching { parseScheduleInterpretation(deleteJson("once").put("target_date", "2026-02-30").toString(), context) }.isFailure)
        assertTrue(runCatching { parseScheduleInterpretation(deleteJson("all").put("schedule", JSONObject()).toString(), context) }.isFailure)
        val oldQuery = deleteJson().put("decision", "show")
        assertEquals(UnsupportedScheduleRequest.QUERY, (parseScheduleInterpretation(oldQuery.toString(), context) as ScheduleInterpretation.Reject).reason)
    }

    @Test fun legacyTitleIsPreservedForTimeEditsAndRenamesAllowThirtyCharacters() {
        val old = ScheduleDraft("一二三四五六七八九十", date = now.toLocalDate(), time = LocalTime.of(16, 0))
        val editing = context.copy(current = old, selected = old)
        val change = parseScheduleInterpretation(editJson(JSONObject().put("time", "17:00")).toString(), editing) as ScheduleInterpretation.Edit
        assertEquals(old.title, change.patch.applyTo(old).title)
        assertEquals(LocalTime.of(17, 0), change.patch.applyTo(old).time)
        val invalid = editJson(JSONObject().put("title", "甲".repeat(31)))
        assertTrue(runCatching { parseScheduleInterpretation(invalid.toString(), editing) }.isFailure)
        val valid = editJson(JSONObject().put("title", "新".repeat(30)))
        val renamed = parseScheduleInterpretation(valid.toString(), editing) as ScheduleInterpretation.Edit
        assertEquals("新".repeat(30), renamed.patch.applyTo(old).title)
    }

    @Test fun periodicStopAndFutureBoundaryAreExplicitProtocolValues() {
        val current = ScheduleDraft("喂猫", date = now.toLocalDate(), time = LocalTime.of(18, 0), recurrence = RecurrenceRule(RecurrencePattern.DAILY))
        val periodic = context.copy(current = current, selected = current.copy(date = now.toLocalDate().plusDays(2)))
        val stop = deleteJson("all").put("decision", "stop")
        assertTrue(parseScheduleInterpretation(stop.toString(), periodic) is ScheduleInterpretation.Stop)
        assertTrue(runCatching { parseScheduleInterpretation(stop.toString(), context.copy(current = current.copy(recurrence = null))) }.isFailure)
        for (invalid in listOf(deleteJson("once").put("decision", "stop"), stop.put("target_date", "2026-10-25"))) {
            assertTrue(runCatching { parseScheduleInterpretation(invalid.toString(), periodic) }.isFailure)
        }
        val future = editJson(JSONObject().put("time", "20:00"), "future").put("target_date", "2026-10-25")
        val parsed = parseScheduleInterpretation(future.toString(), periodic) as ScheduleInterpretation.Edit
        assertEquals(LocalDate.of(2026, 10, 25), parsed.targetDate)
        assertEquals(setOf("time"), parsed.patch.fields)
    }

    private suspend fun failureOf(block: suspend () -> Unit): ScheduleAnalysisException {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is ScheduleAnalysisException)
        return failure as ScheduleAnalysisException
    }

    companion object {
        private const val KEY = "sk-local-fake-only"
        internal fun saveJson(draft: ScheduleDraft): JSONObject = JSONObject()
            .put("decision", "save").put("question", JSONObject.NULL).put("reason", JSONObject.NULL)
            .put("answered_question_ids", JSONArray()).put("scope", JSONObject.NULL).put("target_date", JSONObject.NULL).put("schedule", draft.asJson())
        internal fun response(value: JSONObject, finish: String = "stop"): ModelHttpResponse = ModelHttpResponse(200,
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
                .put("message", JSONObject().put("content", value.toString())))).toString())
        internal fun editJson(patch: JSONObject, scope: String? = null): JSONObject = deleteJson(scope).put("decision", "edit").put("schedule", patch)
        internal fun deleteJson(scope: String? = null): JSONObject = JSONObject()
            .put("decision", "delete").put("question", JSONObject.NULL).put("reason", JSONObject.NULL)
            .put("answered_question_ids", JSONArray()).put("scope", scope ?: JSONObject.NULL)
            .put("target_date", JSONObject.NULL).put("schedule", JSONObject.NULL)
    }
}
