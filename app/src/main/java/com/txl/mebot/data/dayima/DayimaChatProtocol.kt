package com.txl.mebot.data.dayima

import com.txl.mebot.data.local.DayimaChatMessageEntity
import com.txl.mebot.data.local.DayimaOperationAction
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.json.JSONObject

enum class DayimaChatFailure { CONFIGURATION, CREDENTIAL, AUTHORIZATION, BILLING, NETWORK, TIMEOUT, RATE_LIMIT, SERVICE, INVALID_RESULT, LOCAL_SAVE, CHANGED, INTERRUPTED }
class DayimaChatException(val failure: DayimaChatFailure) : Exception("大姨妈聊天处理失败：$failure")
class DayimaChatChanged(reason: String = "聊天或记录已变化") : IllegalStateException(reason)
class DayimaClarification(val question: String) : Exception(question)

data class DayimaChatTarget(val id: String, val date: LocalDate, val revision: Long)
data class DayimaAiContext(
    val now: ZonedDateTime,
    val messages: List<DayimaChatMessageEntity>,
    val lastTargets: List<DayimaChatTarget> = emptyList(),
    val reads: List<JSONObject> = emptyList(),
    val historyTrimmed: Boolean = false,
    val pendingImageCount: Int = 0,
)

enum class DayimaQueryKind { LATEST, HISTORY, STATISTICS, COMPARE, NOTES, REMINDERS }
data class DayimaChatQuery(
    val kind: DayimaQueryKind,
    val range: DayimaDateRange? = null,
    val secondRange: DayimaDateRange? = null,
    val includeAge: Boolean = false,
)

/** 日期／last 引用由本地解析为真实记录和版本，模型不生成数据库标识。 */
data class DayimaTargetRef(val date: LocalDate? = null, val last: Boolean = false)
sealed interface DayimaChatMutation {
    data class Record(val date: LocalDate, val note: String?) : DayimaChatMutation
    data class Edit(val target: DayimaTargetRef, val date: LocalDate) : DayimaChatMutation
    data class Delete(val target: DayimaTargetRef) : DayimaChatMutation
    data class Note(val date: LocalDate, val text: String, val target: DayimaTargetRef? = null) : DayimaChatMutation
    data class Images(val target: DayimaTargetRef) : DayimaChatMutation
    data class Birth(val date: LocalDate?) : DayimaChatMutation
    data class Reminders(val prepare: Boolean?, val missing: Boolean?, val time: LocalTime?, val daysBefore: Int?) : DayimaChatMutation
}

sealed interface DayimaAiDecision {
    data class Ask(val question: String, val hint: DayimaOperationAction?) : DayimaAiDecision
    data class Answer(val text: String) : DayimaAiDecision
    data class Query(val query: DayimaChatQuery) : DayimaAiDecision
    data class Lookup(val query: DayimaChatQuery) : DayimaAiDecision
    data class Write(val mutations: List<DayimaChatMutation>) : DayimaAiDecision
}

fun interface DayimaTextInterpreter { suspend fun interpret(context: DayimaAiContext): DayimaAiDecision }

internal fun parseDayimaDecision(text: String): DayimaAiDecision {
    try {
        require(text.length <= 24_000)
        val reader = org.json.JSONTokener(text)
        val root = reader.nextValue() as? JSONObject ?: error("object required")
        require(reader.nextClean() == '\u0000')
        return when (root.strictString("decision")) {
            "ask" -> {
                root.only("decision", "question", "hint")
                DayimaAiDecision.Ask(root.strictString("question").bounded(600), root.nullString("hint")?.let(DayimaOperationAction::valueOf))
            }
            "answer" -> { root.only("decision", "text"); DayimaAiDecision.Answer(root.strictString("text").bounded(6000)) }
            "query", "lookup" -> {
                root.only("decision", "query")
                val query = root.getJSONObject("query").query()
                if (root.strictString("decision") == "query") DayimaAiDecision.Query(query) else DayimaAiDecision.Lookup(query)
            }
            "write" -> {
                root.only("decision", "operations")
                val operations = root.getJSONArray("operations")
                require(operations.length() in 1..12)
                DayimaAiDecision.Write((0 until operations.length()).map { operations.getJSONObject(it).mutation() })
            }
            else -> error("decision")
        }
    } catch (_: Exception) { throw DayimaChatException(DayimaChatFailure.INVALID_RESULT) }
}

private fun JSONObject.query(): DayimaChatQuery {
    only("kind", "from", "through", "second_from", "second_through", "include_age")
    val kind = DayimaQueryKind.valueOf(strictString("kind"))
    fun range(from: String, through: String): DayimaDateRange? {
        val a = nullString(from); val b = nullString(through)
        require((a == null) == (b == null))
        return a?.let { DayimaDateRange(strictDayimaDate(it), strictDayimaDate(requireNotNull(b))) }
    }
    val first = range("from", "through"); val second = range("second_from", "second_through")
    require(kind !in setOf(DayimaQueryKind.LATEST, DayimaQueryKind.REMINDERS) || first == null)
    require(kind == DayimaQueryKind.COMPARE || second == null)
    require(kind != DayimaQueryKind.COMPARE || first != null && second != null)
    return DayimaChatQuery(kind, first, second, optionalBoolean("include_age") ?: false)
}

private fun JSONObject.mutation(): DayimaChatMutation = when (strictString("kind")) {
    "record" -> { only("kind", "date", "note"); DayimaChatMutation.Record(strictDayimaDate(strictString("date")), nullString("note")?.bounded(4000)) }
    "edit" -> { only("kind", "target", "date"); DayimaChatMutation.Edit(getJSONObject("target").target(), strictDayimaDate(strictString("date"))) }
    "delete" -> { only("kind", "target"); DayimaChatMutation.Delete(getJSONObject("target").target()) }
    "note" -> { only("kind", "date", "text", "target"); DayimaChatMutation.Note(strictDayimaDate(strictString("date")), strictString("text").bounded(4000),
        if (isNull("target")) null else getJSONObject("target").target()) }
    "images" -> { only("kind", "target"); DayimaChatMutation.Images(getJSONObject("target").target()) }
    "birth" -> { only("kind", "date"); DayimaChatMutation.Birth(nullString("date")?.let(::strictDayimaDate)) }
    "reminders" -> {
        only("kind", "prepare", "missing", "time", "days_before")
        val time = nullString("time")?.let { require(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(it)); LocalTime.parse(it) }
        val days = if (isNull("days_before")) null else (get("days_before") as? Int ?: error("integer required")).also { require(it in 0..30) }
        val prepare = optionalBoolean("prepare"); val missing = optionalBoolean("missing")
        require(prepare != null || missing != null || time != null || days != null)
        DayimaChatMutation.Reminders(prepare, missing, time, days)
    }
    else -> error("mutation")
}

private fun JSONObject.target(): DayimaTargetRef {
    only("date", "last")
    val date = nullString("date")?.let(::strictDayimaDate)
    val last = optionalBoolean("last") ?: false
    require((date != null) != last)
    return DayimaTargetRef(date, last)
}
internal fun strictDayimaDate(value: String): LocalDate {
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
    return LocalDate.parse(value).also { require(it.year in 1..9999) }
}
internal fun JSONObject.only(vararg keys: String) { require(keys.toSet().containsAll(keys().asSequence().toSet())) }
internal fun JSONObject.strictString(key: String): String = get(key) as? String ?: error("string required")
internal fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else strictString(key)
internal fun JSONObject.optionalBoolean(key: String): Boolean? = if (isNull(key)) null else get(key) as? Boolean ?: error("boolean required")
private fun String.bounded(max: Int): String = trim().also { require(it.isNotEmpty() && it.length <= max) }
