package com.txl.mebot.data.schedule

import com.txl.mebot.data.local.ScheduleAttachmentSummary
import com.txl.mebot.data.local.ScheduleMessageEntity
import com.txl.mebot.data.local.SchedulePendingQuestionEntity
import com.txl.mebot.data.models.ConnectionResultKind
import com.txl.mebot.data.models.CredentialUnavailable
import com.txl.mebot.data.models.HttpsModelTransport
import com.txl.mebot.data.models.MissingApiKey
import com.txl.mebot.data.models.ModelConfigurationError
import com.txl.mebot.data.models.ModelConnectionChecker
import com.txl.mebot.data.models.ModelHttpTransport
import com.txl.mebot.data.models.ModelRole
import com.txl.mebot.data.models.ModelSettingsConflict
import com.txl.mebot.data.models.ModelSettingsRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class ScheduleAnalysisFailure {
    CONFIGURATION, CREDENTIAL, AUTHORIZATION, BILLING, NETWORK, TIMEOUT, RATE_LIMIT, SERVICE,
    INVALID_RESULT, LOCAL_SAVE, CHANGED, INTERRUPTED,
}

class ScheduleAnalysisException(val failure: ScheduleAnalysisFailure) : Exception("日程解析失败：$failure")

data class ScheduleAnalysisContext(
    val now: ZonedDateTime,
    val messages: List<ScheduleMessageEntity>,
    val current: ScheduleDraft?,
    val questions: List<SchedulePendingQuestionEntity>,
    val historyTrimmed: Boolean = false,
    val selected: ScheduleDraft? = null,
    val selectedOriginalDate: LocalDate? = null,
    val attachments: ScheduleAttachmentSummary = ScheduleAttachmentSummary(),
    val messageAttachments: Map<String, ScheduleAttachmentSummary> = emptyMap(),
)

enum class ScheduleEditScope { UNSPECIFIED, FUTURE, ONCE, ALL }
enum class UnsupportedScheduleRequest { SECONDS, MULTIPLE_TASKS, QUERY, UNRELATED }

sealed interface ScheduleInterpretation {
    val answeredQuestionIds: Set<String>
    data class Save(
        val draft: ScheduleDraft, val scope: ScheduleEditScope = ScheduleEditScope.UNSPECIFIED,
        override val answeredQuestionIds: Set<String> = emptySet(),
    ) : ScheduleInterpretation
    data class Ask(val question: String, override val answeredQuestionIds: Set<String> = emptySet()) : ScheduleInterpretation
    data class Edit(val patch: SchedulePatch, val scope: ScheduleEditScope = ScheduleEditScope.UNSPECIFIED,
        val targetDate: LocalDate? = null, override val answeredQuestionIds: Set<String> = emptySet()) : ScheduleInterpretation
    data class Delete(val scope: ScheduleEditScope = ScheduleEditScope.UNSPECIFIED, val targetDate: LocalDate? = null,
        override val answeredQuestionIds: Set<String> = emptySet()) : ScheduleInterpretation
    data class Stop(override val answeredQuestionIds: Set<String> = emptySet()) : ScheduleInterpretation
    data class Reject(val reason: UnsupportedScheduleRequest, override val answeredQuestionIds: Set<String> = emptySet()) : ScheduleInterpretation
}

fun interface ScheduleTextInterpreter {
    suspend fun interpret(context: ScheduleAnalysisContext): ScheduleInterpretation
}

/** 传当前会话文字及附件数量摘要；模型不能读取附件、直接写库或执行工具。 */
class QwenScheduleTextInterpreter(
    private val settings: ModelSettingsRepository,
    private val transport: ModelHttpTransport = HttpsModelTransport(),
) : ScheduleTextInterpreter {
    override suspend fun interpret(context: ScheduleAnalysisContext): ScheduleInterpretation = withContext(Dispatchers.IO) {
        try {
            val stored = settings.load()
            val access = settings.access(stored.revision)
            val body = JSONObject().put("model", access.settings.languageModel)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", ScheduleExtractionPrompt))
                    .put(JSONObject().put("role", "user").put("content", context.asJson().toString())))
                .put("stream", false).put("enable_thinking", false)
                .put("response_format", JSONObject().put("type", "json_object"))
            val response = withTimeout(40_000) {
                transport.post(access.settings.languageEndpoint, access.apiKey, body.toString(), 30_000)
            }
            val kind = ModelConnectionChecker(transport).interpret(response, ModelRole.LANGUAGE)
            if (kind != ConnectionResultKind.SUCCESS) throw ScheduleAnalysisException(kind.asScheduleFailure())
            val choice = JSONObject(response.body).getJSONArray("choices").getJSONObject(0)
            if (choice.optString("finish_reason") != "stop") invalidResult()
            val content = choice.getJSONObject("message").get("content") as? String ?: invalidResult()
            parseScheduleInterpretation(content, context)
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            throw ScheduleAnalysisException(ScheduleAnalysisFailure.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ScheduleAnalysisException) { throw failure
        } catch (_: MissingApiKey) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.CONFIGURATION)
        } catch (_: ModelConfigurationError) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.CONFIGURATION)
        } catch (_: ModelSettingsConflict) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.CONFIGURATION)
        } catch (_: CredentialUnavailable) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.CREDENTIAL)
        } catch (_: SocketTimeoutException) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.TIMEOUT)
        } catch (_: IOException) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.NETWORK)
        } catch (_: Exception) { throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT) }
    }
}

private fun ConnectionResultKind.asScheduleFailure(): ScheduleAnalysisFailure = when (this) {
    ConnectionResultKind.INVALID_KEY, ConnectionResultKind.NO_PERMISSION, ConnectionResultKind.SERVICE_NOT_ENABLED -> ScheduleAnalysisFailure.AUTHORIZATION
    ConnectionResultKind.BILLING -> ScheduleAnalysisFailure.BILLING
    ConnectionResultKind.DNS, ConnectionResultKind.NETWORK, ConnectionResultKind.TLS -> ScheduleAnalysisFailure.NETWORK
    ConnectionResultKind.TIMEOUT -> ScheduleAnalysisFailure.TIMEOUT
    ConnectionResultKind.RATE_LIMIT -> ScheduleAnalysisFailure.RATE_LIMIT
    ConnectionResultKind.MODEL_UNAVAILABLE, ConnectionResultKind.ENDPOINT, ConnectionResultKind.PARAMETERS -> ScheduleAnalysisFailure.CONFIGURATION
    ConnectionResultKind.BAD_RESPONSE -> ScheduleAnalysisFailure.INVALID_RESULT
    else -> ScheduleAnalysisFailure.SERVICE
}

internal fun parseScheduleInterpretation(text: String, context: ScheduleAnalysisContext): ScheduleInterpretation {
    try {
        if (text.length > 24_000) invalidResult()
        val reader = JSONTokener(text)
        val root = reader.nextValue() as? JSONObject ?: invalidResult()
        if (reader.nextClean() != '\u0000') invalidResult()
        root.onlyKeys("decision", "question", "reason", "answered_question_ids", "schedule", "scope", "target_date")
        val answers = root.getJSONArray("answered_question_ids").strings().toSet()
        if (!context.questions.map { it.id }.containsAll(answers)) invalidResult()
        val scope = when (root.nullableString("scope")) {
            null -> ScheduleEditScope.UNSPECIFIED
            "future" -> ScheduleEditScope.FUTURE
            "once" -> ScheduleEditScope.ONCE
            "all" -> ScheduleEditScope.ALL
            else -> invalidResult()
        }
        val targetDate = root.nullableString("target_date")?.let(::strictDate)
        return when (root.string("decision")) {
            "save" -> {
                if (!root.isNull("question") || !root.isNull("reason")) invalidResult()
                if (context.current != null || scope != ScheduleEditScope.UNSPECIFIED || targetDate != null) invalidResult()
                val schedule = root.getJSONObject("schedule")
                val remindAtStart = schedule.takeOnTimePreference()
                ScheduleInterpretation.Save(schedule.asDraft(context.now.toLocalDate(), remindAtStart = remindAtStart), scope, answers)
            }
            "edit" -> {
                if (!root.isNull("question") || !root.isNull("reason") || context.current == null) invalidResult()
                val patch = root.getJSONObject("schedule")
                val remindAtStart = patch.takeOnTimePreference()
                val fields = patch.keys().asSequence().toSet()
                if (fields.isEmpty() || !EditableScheduleFields.containsAll(fields)) invalidResult()
                val base = (if (scope == ScheduleEditScope.ONCE) context.selected else null) ?: context.current
                val merged = base.asJson()
                for (field in fields) merged.put(field, patch.get(field))
                if ("time" in fields && "reminder_minutes" !in fields) {
                    if (patch.isNull("time")) merged.put("reminder_minutes", JSONArray())
                    else if (base.time == null) merged.put("reminder_minutes", JSONObject.NULL)
                }
                val draft = merged.asDraft(context.now.toLocalDate(), base.title,
                    remindAtStart = remindAtStart.takeIf { "reminder_minutes" in fields })
                ScheduleInterpretation.Edit(SchedulePatch(draft, fields), scope, targetDate, answers)
            }
            "delete" -> {
                if (!root.isNull("question") || !root.isNull("reason") || !root.isNull("schedule")) invalidResult()
                ScheduleInterpretation.Delete(scope, targetDate, answers)
            }
            "stop" -> {
                if (context.current?.recurrence == null || !root.isNull("question") || !root.isNull("reason") ||
                    !root.isNull("schedule") || scope != ScheduleEditScope.ALL || targetDate != null) invalidResult()
                ScheduleInterpretation.Stop(answers)
            }
            "ask" -> {
                if (!root.isNull("schedule") || !root.isNull("reason") || !root.isNull("scope") || targetDate != null) invalidResult()
                val question = root.string("question").trim()
                if (question.isBlank() || question.length > 600) invalidResult()
                ScheduleInterpretation.Ask(question, answers)
            }
            "show" -> {
                if (!root.isNull("schedule") || !root.isNull("question") || !root.isNull("reason") || !root.isNull("scope") || targetDate != null) invalidResult()
                ScheduleInterpretation.Reject(UnsupportedScheduleRequest.QUERY, answers)
            }
            "reject" -> {
                if (!root.isNull("schedule") || !root.isNull("question") || !root.isNull("scope") || targetDate != null) invalidResult()
                ScheduleInterpretation.Reject(UnsupportedScheduleRequest.valueOf(root.string("reason")), answers)
            }
            else -> invalidResult()
        }
    } catch (failure: ScheduleAnalysisException) { throw failure
    } catch (_: Exception) { invalidResult() }
}

private fun JSONObject.takeOnTimePreference(): Boolean {
    if (!has("remind_at_start")) return true
    if (isNull("reminder_minutes")) invalidResult()
    return remove("remind_at_start") as? Boolean ?: invalidResult()
}

// null 的 remindAtStart 表示沿用已保存的提醒配置。只有用户指定的新配置才补准点提醒。
private fun JSONObject.asDraft(today: LocalDate, existingTitle: String? = null, remindAtStart: Boolean? = null): ScheduleDraft {
    onlyKeys("title", "detail", "date", "time", "recurrence", "reminder_minutes")
    val date = nullableString("date")?.let {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)) invalidResult()
        LocalDate.parse(it).also { value -> if (value.year !in 1..9999) invalidResult() }
    } ?: today
    val time = nullableString("time")?.let {
        if (!Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(it)) invalidResult()
        LocalTime.parse(it)
    }
    val recurrence = if (isNull("recurrence")) null else getJSONObject("recurrence").run {
        onlyKeys("pattern", "interval_days", "week_days", "month_day", "end_date", "enabled")
        val pattern = RecurrencePattern.valueOf(string("pattern"))
        val interval = integer("interval_days").also { if (it !in 1..3650) invalidResult() }
        val week = getJSONArray("week_days").integers().also { if (it.any { day -> day !in 1..7 } || it.size > 7) invalidResult() }
        val month = if (isNull("month_day")) null else integer("month_day")
        val end = nullableString("end_date")?.let {
            if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)) invalidResult()
            LocalDate.parse(it).also { value -> if (value.year !in 1..9999) invalidResult() }
        }
        val enabled = get("enabled") as? Boolean ?: invalidResult()
        RecurrenceRule(pattern, interval, week.map(DayOfWeek::of).toSet(), month, end, enabled)
    }
    val minutes = if (isNull("reminder_minutes")) null else getJSONArray("reminder_minutes").integers().also {
        if ((remindAtStart != null && it.size > 10) || it.any { minute -> minute !in 0..525_600 }) invalidResult()
    }
    if (time == null && !minutes.isNullOrEmpty()) invalidResult()
    val reminders = when {
        minutes.isNullOrEmpty() || remindAtStart == null -> minutes
        remindAtStart -> minutes + 0
        else -> minutes.filterNot { it == 0 }
    }
    val detail = string("detail").also { if (it.length > 12_000) invalidResult() }
    return ScheduleDraft(string("title"), detail, date, time, recurrence, reminders).resolved(today, existingTitle)
}

private fun strictDate(value: String): LocalDate {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) invalidResult()
    return LocalDate.parse(value).also { if (it.year !in 1..9999) invalidResult() }
}

internal fun ScheduleDraft.asJson(): JSONObject = JSONObject()
    .put("title", title).put("detail", detail).put("date", date?.toString() ?: JSONObject.NULL)
    .put("time", time?.let { "%02d:%02d".format(java.util.Locale.ROOT, it.hour, it.minute) } ?: JSONObject.NULL)
    .put("recurrence", recurrence?.let { rule -> JSONObject()
        .put("pattern", rule.pattern.name).put("interval_days", rule.intervalDays)
        .put("week_days", JSONArray(rule.weekDays.map { it.value }.sorted()))
        .put("month_day", rule.monthDay ?: JSONObject.NULL).put("end_date", rule.endDate?.toString() ?: JSONObject.NULL)
        .put("enabled", rule.enabled)
    } ?: JSONObject.NULL)
    .put("reminder_minutes", reminderMinutes?.let(::JSONArray) ?: JSONObject.NULL)

private fun ScheduleAnalysisContext.asJson(): JSONObject = JSONObject()
    .put("device_now", now.toString()).put("device_timezone", now.zone.id)
    .put("current_schedule", current?.asJson() ?: JSONObject.NULL)
    .put("selected_occurrence", selected?.asJson() ?: JSONObject.NULL)
    .put("selected_original_date", selectedOriginalDate?.toString() ?: JSONObject.NULL)
    .put("attachment_summary", JSONObject().put("total_count", attachments.totalCount)
        .put("image_count", attachments.imageCount).put("file_count", attachments.fileCount))
    .put("attachment_usage", "user_reference_only")
    .put("history_trimmed", historyTrimmed)
    .put("pending_questions", JSONArray(questions.map { JSONObject().put("id", it.id).put("question", it.question) }))
    .put("conversation", JSONArray(messages.map { message -> JSONObject()
        .put("role", message.role.name.lowercase())
        .put("local_sent_at", java.time.Instant.ofEpochMilli(message.createdAt).atZone(now.zone).toString())
        .put("text", message.content)
        .put("attachment_summary", (messageAttachments[message.id] ?: ScheduleAttachmentSummary()).let { summary ->
            JSONObject().put("total_count", summary.totalCount).put("image_count", summary.imageCount).put("file_count", summary.fileCount)
        })
    }))

private fun JSONObject.onlyKeys(vararg allowed: String) {
    if (keys().asSequence().toSet() != allowed.toSet()) invalidResult()
}
private fun JSONObject.string(key: String): String = get(key) as? String ?: invalidResult()
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else string(key)
private fun JSONObject.integer(key: String): Int = get(key).strictInt()
private fun Any.strictInt(): Int = if (this is Number && Regex("-?\\d+").matches(toString())) toString().toInt() else invalidResult()
private fun JSONArray.integers(): List<Int> = (0 until length()).map { get(it).strictInt() }
private fun JSONArray.strings(): List<String> = (0 until length()).map { get(it) as? String ?: invalidResult() }
private fun invalidResult(): Nothing = throw ScheduleAnalysisException(ScheduleAnalysisFailure.INVALID_RESULT)

private val ScheduleExtractionPrompt = """
你是 Android 本地日程的文字信息提取器，只输出一个符合下列约定的 JSON 对象，不输出 Markdown、解释或工具调用。
一个会话永远只对应一个日程任务，任务是在一段时间内做的活动，不按物品拆分。“今天买土豆、猕猴桃”是一项采购；后续“再加茄子、辣椒”是补充同一任务。
根据 conversation 内 user 的文本、当前会话的文字上下文和 attachment_summary 提取日程。current_schedule 是本地已保存事实。assistant 只提供追问上下文，不能代替真实保存状态。
attachment_summary 是当前这一个任务会话已保存附件的权威数量摘要：total_count 为附件总数，image_count 为图片数，file_count 为其他文件数。这表示用户已经添加附件，但你看不到附件内容、文件名或路径，不要声称读过、看过或识别过图片／文件。附件摘要不能用于猜测物品、文件主题、人物、地点或时间。
conversation 每条消息的 attachment_summary 表示随该条消息实际发出的附件数量与类型，用于分辨“这次两张图片”等指代；顶层 attachment_summary 仍表示当前任务的附件总数。只有图片或文件而无文字时，只需问用户想安排什么事情，不追问其中内容。
attachment_usage=user_reference_only 表示附件只供用户自己回看，不需要你知道里面有什么。附件指代规则优先于下方“信息不明确时追问”的一般规则：“做／处理／完成／办理图片或附件里的事”“记录这两张图片的事”本身就是完整、可保存的活动，不需要具体动作细节。用户说“这两个东西”“这张图里的事”“这个文件”“这些资料”等指代时，必须允许保留泛称创建或编辑日程，不需要知道对象具体是什么。不要要求用户重新上传、逐一描述附件、解释图片或文件内容，不问“图片里具体是什么事情／要打印文件还是填表／请描述内容”。附件数量是文件载体数量，不等于实物数量；沿用用户明确说出的物品数量，不强制逐一对应。
严格示例：已添加两张图片，用户说“今天晚上要做这两个图片的事”，直接 save，标题“处理图片事项”，detail 保留“今天晚上要做这两张图片里的事（参照本任务图片）”，date 为该发言当地日期，time=null，recurrence=null，reminder_minutes=[]。“晚上”只是完成时段，用户没有要求提醒，不追问几点，不编造具体时刻。用户继续说“你主要记录这两个图片的事儿就可以了”，应立即创建／保留同一任务，不再追问内容；已有同类 pending_questions 作废。若用户明确说“明晚八点提醒我做这两张图片的事”，则提取明天20:00，reminder_minutes=null，仍不追问图片内容。
例如 attachment_summary={"total_count":2,"image_count":1,"file_count":1}，用户说“明天把这两个东西收了”：直接 save，标题可为“收取两个东西”，detail 保留“把这两个东西收了（参照本任务附件）”，日期为该用户发言的明天，time=null、recurrence=null、reminder_minutes=[]，不要询问是哪两个东西、图片是什么、文件里有什么。“明天打印这个文件”可直接记录“打印文件”；“下周一去拿这些资料”可直接记录“领取资料”。同一会话中继续说“改成后天”仅编辑日期，保留泛称、数量、动作及其他字段。
只在缺少要做的动作、必须的提醒时刻等真正影响执行的信息时追问。例如只有附件或“明天这两个”时问要做什么；“明天提醒我取这两个东西”没有提醒时刻时，只问明天几点提醒，不追问东西是什么。没有附件时不要声称已添加附件；用户文字本身能记录行动时仍可保留泛称。用户明确要求从图片或文件中读取课表、内容、具体时间等才能完成需求时，说明无法读取并请其提供必要文字，不能凭数量猜测。
device_now 和 device_timezone 是设备当前时间、时区。明天、下周等相对时间以相应用户发言 local_sent_at 为准；“5分钟后”等相对时刻也以该发言时间计算，最终只精确到分钟。没有日期时用当天，date 返回 null；没有时间时 time=null，不编造 00:00 或闹钟。“今天下午买菜”可保存仅日期；“明天下午提醒我开会”有提醒意图但无明确时刻，应追问几点。仅有“周五”而日期指向有歧义、只有“4点”不能判断上午下午、时间已过去且仍要提醒、连泛称活动都没有说明时追问；“做附件里的事／处理图片事项”等泛称就是活动，不属于缺少活动。不猜测附件内容。
新建或修改标题时必须生成 1~$ScheduleTitleLimit 个 Unicode 字符的简短标题，完整购物物品、地点、备注写 detail，不丢掉补充内容。无需追问用户给标题。已有任务仅改时间、日期或详情时，不要为了缩短旧标题而擅自改名。
新建有具体时间的日程，采用默认提醒时返回 reminder_minutes=null，本地登记提前 $DefaultScheduleLeadMinutes 分钟和准点两次提醒。“明天21:30提醒我开会”采用默认提醒。“明天21:30开会，提前8分钟提醒”返回 reminder_minutes=[8]，用指定的提前量替换默认提前量，本地会保留准点。“只在准点提醒”返回 [0]。仅日期或明确关闭全部提醒返回 []。用户明确说“只提前8分钟提醒”或“提前8分钟，不要准点”时返回 reminder_minutes=[8] 并加 remind_at_start=false。仅取消准点时，返回应保留的提前量和 remind_at_start=false。
detail 是详情弹窗标题下的任务说明：根据用户文本整理为简洁、完整的行动描述，保留对象、数量、地点、原因、准备事项及备注，不受标题字数限制，较多事项可分行。去掉“帮我记录”等对话套话，不写“已创建”等执行回复，不添加用户没有提供的事实，不猜测附件内容。后续补充合并到原说明；用户更正对象、名称或其他事实时，返回替换旧表述后的完整 detail，保留其余有效信息。仅修改标题或时间时仍只返回对应字段，不借机重写详情。
例如“15分钟后提醒我洗衣服，并提前2分钟提醒我”，日程时间取该条消息发出后15分钟，reminder_minutes=[2]。
秒级提醒（如30秒后、30s后、带秒的时刻）返回 reject/SECONDS，请用户改成分钟级，不擅自向上取整。一个会话内明确提出两个不同时间的独立活动时返回 reject/MULTIPLE_TASKS，提示分别新建；同次采购的多个物品不能如此处理。
周期可用 DAILY（每天）、WEEKLY（week_days，周一=1…周日=7）、MONTHLY（month_day）、INTERVAL（interval_days）。工作日明确为周一到周五，不表示法定调休。每月31日遇到无31日的月份会跳过。如用户要求节假日规则、农历、每月最后一天等本版本不支持的规则，请追问可支持的替代，不擅自转换。未给周期时 recurrence=null，不推断长期周期。
已有 current_schedule 时，补充或修改必须返回 edit，schedule 中仅包含本轮明确要修改的字段，不返回无关字段、不重新完整生成原任务。例如“改成下午五点”只返回 {"time":"17:00"}；已有下午四点，“改成五点”沿用下午语境。已有采购详情“土豆、猕猴桃”，继续“茄子、酱油、辣椒”仅返回合并后的完整 detail，不改变标题、日期、时间、周期、提前量。明确删除某些物品仅编辑 detail，不得返回 delete。日期修改不改变时间；明确取消具体时刻才将 time=null。未提出修改的字段一律不要出现在 schedule 中。
selected_occurrence 是用户从详情进入会话时的那张卡片。周期编辑必须先问清范围：只修改本次/指定某天用 scope="once"，本次及以后或从某天起用 scope="future"；没有明确范围时 ask，不替用户选。future 默认从 selected_original_date（单次改期前的原发生日）开始，含该次；用户明确“从今天起/从明天起/从某天起”时，target_date 必须写对应 YYYY-MM-DD，不能把范围起点写成 schedule.date。“整项周期都改”用 future 且 target_date 写设备今天，过去及已完成记录保留。once 的 target_date 指被编辑的原发生日或所选卡片日期，未指日期用 null；改期后的新日期才写 schedule.date。新增或修改周期规则只能 future，不用于 once。修改时间或补充备注不应重设周期起始日期，保留 INTERVAL 的原始间隔相位。若明显要另建无关任务，应引导点击“新建日程”。
明确“删除这个日程/任务/删掉它”返回 delete，普通任务直接删除，不要求确认。周期删除须明确“本次/某天”scope="once"，或“整个周期/全部”scope="all"；仅说删除而范围不明确就问清范围。删除仅指定部分采购物品、关闭提醒、完成任务、否定或询问删除都不是删除任务。“停止整个周期/暂停重复/以后不再重复”返回 stop，scope="all"，保留过去与已完成记录、会话和封面，停止今天及之后未完成日程。stop 不等于 delete，不能互相替代；只关闭提醒返回 edit 的 reminder_minutes=[]；仅取消本次用 delete/once。否定或询问停止不能执行 stop。现版本不处理聊天查询，返回 reject/QUERY 并引导查看详情。其他无关输入返回 reject/UNRELATED。
过去 assistant 若曾因附件指代追问“是什么东西／请描述图片或文件内容”，这类追问不能阻止创建：只要本轮文字和 attachment_summary 已足够记录行动，就直接创建或修改，把已不再需要的这类 pending_questions ID 记为作废。
answered_question_ids 只填写 pending_questions 中本轮已明确回答、由用户明确改换要求而作废，或按上述附件指代规则已不再需要的内容追问 ID；不要编造 ID，不因普通回复而无条件解决所有问题。缺失的动作、提醒时刻、周期范围等必要问题仍需保留。
JSON 必须恰好具有以下 7 个键：
{"decision":"save|edit|delete|stop|ask|reject","question":null,"reason":null,"answered_question_ids":[],"schedule":null,"scope":null,"target_date":null}
save 只用于新建，question、reason、scope、target_date=null，schedule 为完整对象：
{"title":"采购食材","detail":"土豆、猕猴桃","date":null,"time":null,"recurrence":null,"reminder_minutes":[]}
date 为 null 或 YYYY-MM-DD，time 为 null 或严格 HH:mm。recurrence 非 null 时必须恰好有：
{"pattern":"DAILY|WEEKLY|MONTHLY|INTERVAL","interval_days":1,"week_days":[],"month_day":null,"end_date":null,"enabled":true}
不适用的周期字段保留默认值：非 INTERVAL 的 interval_days=1；非 WEEKLY 的 week_days=[]；非 MONTHLY 的 month_day=null。end_date 为 null 或 YYYY-MM-DD 且不早于开始日期。enabled 默认 true，仅明确要求暂停才改为 false。reminder_minutes 为 null（采用默认）或最多10个整数分钟值，范围0~525600。仅明确要求取消准点时，可在 save 或 edit 的 schedule 中附加布尔字段 remind_at_start=false，并同时提供 reminder_minutes 数组。
edit: question、reason=null，scope=null|"once"|"future"，schedule 为仅包含修改字段的对象，字段值格式与新建一致。
delete: question、reason、schedule=null，scope=null|"once"|"all"。
stop: question、reason、schedule、target_date=null，scope="all"，仅用于已有周期任务，禁止用于普通任务。
ask: question 为一条简洁明确的中文追问，其余 schedule、reason、scope、target_date=null。
reject: reason 只能为 SECONDS、MULTIPLE_TASKS、QUERY、UNRELATED，其余 question、schedule、scope、target_date=null。
不要声称已创建、已修改、已响铃；本地代码在验证、持久化之后生成执行回复。
""".trimIndent()
