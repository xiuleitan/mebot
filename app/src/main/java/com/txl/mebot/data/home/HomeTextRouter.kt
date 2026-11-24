package com.txl.mebot.data.home

import androidx.room.withTransaction
import com.txl.mebot.data.local.HomeRoutingSettingsEntity
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.models.*
import java.net.URI
import kotlinx.coroutines.*
import org.json.JSONObject

class HomeRoutingSettings(private val database: MebotDatabase, private val defaultEndpoint: String = "") {
    fun observe() = database.homeRouting().observeSettings()
    suspend fun read(): HomeRoutingSettingsEntity = database.withTransaction {
        val dao = database.homeRouting()
        dao.insertSettings(HomeRoutingSettingsEntity(endpoint = validateDecisionEndpoint(defaultEndpoint)))
        requireNotNull(dao.settings())
    }
    suspend fun save(endpoint: String, revision: Long): HomeRoutingSettingsEntity {
        val valid = validateDecisionEndpoint(endpoint)
        return database.withTransaction {
            val dao = database.homeRouting(); dao.insertSettings(HomeRoutingSettingsEntity())
            check(dao.saveSettings(valid, revision) == 1) { "路由配置已变化，请重新打开设置" }
            requireNotNull(dao.settings())
        }
    }
}

internal fun validateDecisionEndpoint(text: String): String {
    if (text.isBlank()) return ""
    val value = text.trim().trimEnd('/')
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.path.endsWith("/compatible-mode/v1/systemone") &&
        (uri.port == -1 || uri.port in 1..65535)) { "请填写 HTTPS System One 接口完整地址" }
    return value
}

fun interface HomeTextRouter { suspend fun choose(text: String): HomeBot }
class HomeRouteFailure(val userMessage: String) : Exception(userMessage)

/** 仅发送当前文字和固定选项；不传文件、图片、附件名或历史记录。 */
class BailianHomeTextRouter(private val routing: HomeRoutingSettings, private val models: ModelSettingsRepository,
    private val transport: ModelHttpTransport = HttpsModelTransport()) : HomeTextRouter {
    override suspend fun choose(text: String): HomeBot = withContext(Dispatchers.IO) {
        require(text.isNotBlank())
        try {
            val endpoint = validateDecisionEndpoint(routing.read().endpoint)
            if (endpoint.isEmpty()) throw HomeRouteFailure("请先在模型设置中配置决策模型地址，也可直接选择 Bot")
            val stored = models.load(); val access = models.access(stored.revision)
            val response = withTimeout(8_000) { transport.post(endpoint, access.apiKey, request(text).toString(), 6_000) }
            if (response.status !in 200..299) throw HomeRouteFailure(when (response.status) {
                401, 403 -> "决策模型鉴权未通过，请检查模型设置，也可手动选择 Bot"
                429 -> "决策模型暂时繁忙，可手动选择 Bot"
                else -> "暂时无法判断目标，可手动选择 Bot"
            })
            parse(response.body)
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            throw HomeRouteFailure("判断超时，可手动选择 Bot")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: HomeRouteFailure) { throw failure }
        catch (_: MissingApiKey) { throw HomeRouteFailure("请先在模型设置中保存百炼 API Key，也可手动选择 Bot") }
        catch (_: Exception) { throw HomeRouteFailure("暂时无法判断目标，可手动选择 Bot") }
    }
    internal fun request(text: String): JSONObject {
        val criteria = JSONObject(); HomeBot.entries.forEach { criteria.put(it.id, it.criteria) }
        return JSONObject().put("model", "decision-model-preview").put("state", text)
            .put("questions", JSONObject().put("bot", JSONObject().put("type", "choice")
                .put("instructions", "Choose exactly one bot for the user's intent. Specialized menstrual recording, subscription management and document archiving go to their respective bots. For ordinary actions, classify by WHEN the action is to be done: an explicit execution time means schedule; no execution time means flashnote. A request to remember, check or organize something does not imply a time. 明天记得去买菜 and 今晚整理房间 are schedule; 记得整理房间 and 想做一个工具 are flashnote. 今天想到一个新点子 describes when an idea occurred, not an execution time. 明天带银行卡去银行 is a scheduled action, not archiving. Select the most likely of the five options.")
                .put("criteria", criteria)))
    }
    internal fun parse(body: String): HomeBot {
        require(body.length <= 100_000)
        val answer = JSONObject(body).getJSONObject("answers").getJSONObject("bot")
        require(answer.getString("type") == "choice")
        val choice = HomeBot.fromId(answer.optString("choice"))
        if (answer.has("probabilities")) {
            val distribution = answer.getJSONObject("probabilities")
            val scores = HomeBot.entries.map { bot ->
                val score = (distribution.get(bot.id) as? Number)?.toDouble()
                require(score != null && score.isFinite() && score in 0.0..1.0) { "无效的 Bot 概率" }
                bot to score
            }
            val highest = scores.maxOf { it.second }
            // 并列时保留模型选中的有效 Bot；忽略分布中任何未注册的额外类别。
            return scores.firstOrNull { it.first == choice && it.second == highest }?.first
                ?: scores.first { it.second == highest }.first
        }
        return requireNotNull(choice) { "未知的 Bot ID" }
    }
}
