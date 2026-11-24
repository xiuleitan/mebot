package com.txl.mebot.data.archive

import com.txl.mebot.data.local.MessageRole
import org.json.JSONArray
import org.json.JSONObject

data class ArchiveField(val name: String, val value: String)
data class ArchiveLink(val url: String, val label: String = "")

/** 对外只暴露解密后的领域值，不把标题、号码、正文或文件名放进公开元数据。 */
data class ArchiveContent(
    val title: String, val type: String = "", val tags: List<String> = emptyList(),
    val fields: List<ArchiveField> = emptyList(), val body: String = "", val rawInput: String = "",
    val links: List<ArchiveLink> = emptyList(), val assetIds: List<String> = emptyList(), val coverAssetId: String? = null,
) {
    override fun toString() = "ArchiveContent([protected])"
    internal fun validated(allowBlankTitle: Boolean = false): ArchiveContent {
        require((allowBlankTitle || title.isNotBlank()) && title.length <= 300) { "请填写档案标题" }
        require(type.length <= 100 && tags.size <= 100 && tags.all { it.length <= 100 })
        require(fields.size <= 200 && fields.all { it.name.length <= 200 && it.value.length <= 20_000 })
        require(body.length <= 200_000 && rawInput.length <= 200_000)
        require(links.size <= 100 && links.all { it.url.length <= 4_000 && it.label.length <= 300 })
        require(assetIds.size <= 100 && assetIds.distinct().size == assetIds.size && assetIds.all { it.isNotBlank() })
        require(coverAssetId == null || coverAssetId in assetIds) { "封面必须来自本档案附件" }
        return copy(title = title.trim(), tags = tags.map(String::trim).filter(String::isNotEmpty).distinct())
    }
}

data class ArchiveDraftContent(val input: String = "", val candidate: ArchiveContent? = null, val conflicts: List<String> = emptyList(),
    val analysis: ArchiveAnalysisMeta = ArchiveAnalysisMeta()) {
    override fun toString() = "ArchiveDraftContent([protected])"
}
data class ArchiveRecord(val id: String, val content: ArchiveContent, val revision: Long, val createdAt: Long, val updatedAt: Long)
data class ArchiveDraft(val sessionId: String, val content: ArchiveDraftContent, val revision: Long, val baseRecordRevision: Long?, val activeRequestId: String? = null)
data class ArchiveChatMessage(val id: String, val role: MessageRole, val text: String, val assetIds: List<String>, val sequence: Long) {
    override fun toString() = "ArchiveChatMessage(id=$id, role=$role, [protected])"
}
data class ArchiveLocalAsset(val id: String, val info: ArchiveAssetInfo, val pending: Boolean)
data class ArchiveSessionSummary(val id: String, val recordId: String?, val title: String, val preview: String, val closed: Boolean)
data class ArchiveContext(val sessionId: String, val record: ArchiveRecord?, val draft: ArchiveDraft, val messages: List<ArchiveChatMessage>,
    val assets: List<ArchiveLocalAsset> = emptyList(), val closed: Boolean = false)
data class ArchiveSaveReceipt(val operationId: String, val recordId: String, val revision: Long)
data class ArchiveAnalysisRequest(val sessionId: String, val requestId: String, val draftRevision: Long, val recordRevision: Long?)
data class ArchiveAssetInfo(val name: String, val mimeType: String, val byteCount: Long, val sha256: String) {
    override fun toString() = "ArchiveAssetInfo([protected], byteCount=$byteCount)"
}
class ArchiveConflict : IllegalStateException("档案或草稿已变化，请重新读取后再操作")

internal object ArchiveCodec {
    fun content(value: ArchiveContent) = JSONObject().put("version", 1).put("title", value.title).put("type", value.type)
        .put("tags", JSONArray(value.tags)).put("fields", JSONArray(value.fields.map { JSONObject().put("name", it.name).put("value", it.value) }))
        .put("body", value.body).put("rawInput", value.rawInput)
        .put("links", JSONArray(value.links.map { JSONObject().put("url", it.url).put("label", it.label) }))
        .put("assetIds", JSONArray(value.assetIds)).put("coverAssetId", value.coverAssetId ?: JSONObject.NULL)
    fun content(json: JSONObject): ArchiveContent {
        require(json.getInt("version") == 1) { "不支持的档案格式，原数据已保留" }
        return ArchiveContent(json.getString("title"), json.getString("type"), strings(json.getJSONArray("tags")),
            objects(json.getJSONArray("fields")).map { ArchiveField(it.getString("name"), it.getString("value")) },
            json.getString("body"), json.getString("rawInput"), objects(json.getJSONArray("links")).map { ArchiveLink(it.getString("url"), it.getString("label")) },
            strings(json.getJSONArray("assetIds")), if (json.isNull("coverAssetId")) null else json.getString("coverAssetId"))
    }
    fun draft(value: ArchiveDraftContent) = JSONObject().put("version", 1).put("input", value.input)
        .put("candidate", value.candidate?.let(::content) ?: JSONObject.NULL).put("conflicts", JSONArray(value.conflicts))
        .put("analysis", JSONObject().put("status", value.analysis.status.name).put("sourceMessageId", value.analysis.sourceMessageId ?: JSONObject.NULL)
            .put("failure", value.analysis.failure?.name ?: JSONObject.NULL).put("imageCount", value.analysis.imageCount).put("omittedImages", value.analysis.omittedImages)
            .put("sourceLinks", JSONArray(value.analysis.sourceLinks.map { JSONObject().put("url", it.url).put("label", it.label) }))
            .put("requestId", value.analysis.requestId ?: JSONObject.NULL))
    fun draft(json: JSONObject): ArchiveDraftContent {
        require(json.getInt("version") == 1) { "不支持的草稿格式，原数据已保留" }
        val meta = json.optJSONObject("analysis")
        val analysis = if (meta == null) ArchiveAnalysisMeta() else ArchiveAnalysisMeta(ArchiveAnalysisStatus.valueOf(meta.getString("status")),
            if (meta.isNull("sourceMessageId")) null else meta.getString("sourceMessageId"),
            if (meta.isNull("failure")) null else ArchiveAnalysisFailure.valueOf(meta.getString("failure")), meta.optInt("imageCount"), meta.optInt("omittedImages"),
            meta.optJSONArray("sourceLinks")?.let { array -> objects(array).map { ArchiveLink(it.getString("url"), it.getString("label")) } }.orEmpty(),
            if (meta.isNull("requestId")) null else meta.getString("requestId"))
        return ArchiveDraftContent(json.getString("input"), if (json.isNull("candidate")) null else content(json.getJSONObject("candidate")), strings(json.getJSONArray("conflicts")), analysis)
    }
    fun asset(value: ArchiveAssetInfo) = JSONObject().put("version", 1).put("name", value.name).put("mimeType", value.mimeType).put("byteCount", value.byteCount).put("sha256", value.sha256)
    fun asset(json: JSONObject): ArchiveAssetInfo {
        require(json.getInt("version") == 1)
        return ArchiveAssetInfo(json.getString("name"), json.getString("mimeType"), json.getLong("byteCount"), json.getString("sha256"))
    }
    private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }
    private fun objects(array: JSONArray) = List(array.length()) { array.getJSONObject(it) }
}
