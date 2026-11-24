package com.txl.mebot.data.archive

import java.net.URI
import kotlin.math.sqrt

enum class ArchiveEmbeddingProtocol { DASHSCOPE, OPENAI_COMPATIBLE }
enum class ArchiveEmbeddingUse { DOCUMENT, QUERY }
enum class ArchiveSemanticFailure { CONFIGURATION, CREDENTIAL, AUTHORIZATION, BILLING, NETWORK, TIMEOUT, RATE_LIMIT,
    SERVICE, INVALID_RESULT, INPUT_LIMIT, LOCAL_STORAGE, CHANGED, INTERRUPTED }
class ArchiveSemanticException(val failure: ArchiveSemanticFailure) : Exception("档案检索失败：$failure")

data class ArchiveEmbeddingSettings(
    val enabled: Boolean = false,
    val endpoint: String = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
    val model: String = "qwen3.7-text-embedding", val dimensions: Int = 1024,
    val protocol: ArchiveEmbeddingProtocol = ArchiveEmbeddingProtocol.DASHSCOPE,
) {
    fun validated(): ArchiveEmbeddingSettings {
        val url = endpoint.trim().trimEnd('/'); val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || uri.port == 0 || uri.port > 65535 || uri.path.isNullOrBlank() ||
            !Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}").matches(model.trim()) || dimensions !in 1..4096)
            throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION)
        val suffix = if (protocol == ArchiveEmbeddingProtocol.DASHSCOPE) "/services/embeddings/text-embedding/text-embedding" else "/embeddings"
        if (!uri.path.endsWith(suffix)) throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION)
        return copy(endpoint = url, model = model.trim())
    }
    internal fun configuration() = ArchiveEmbeddingConfiguration(endpoint, model, dimensions,
        "${if (protocol == ArchiveEmbeddingProtocol.DASHSCOPE) "dashscope-query-document-v1" else "openai-symmetric-plain-v1"}:projection-v1:utf8-chunks-v1")
    override fun toString() = "ArchiveEmbeddingSettings(enabled=$enabled, model=$model, dimensions=$dimensions, [protected])"
}
data class ArchiveSemanticSettings(val values: ArchiveEmbeddingSettings, val revision: Long, val hasOwnKey: Boolean)
sealed interface ArchiveEmbeddingKeyChange {
    data object Keep : ArchiveEmbeddingKeyChange
    data object UseShared : ArchiveEmbeddingKeyChange
    class Replace(val value: String) : ArchiveEmbeddingKeyChange { override fun toString() = "Replace([protected])" }
}
internal class ArchiveEmbeddingAccess(val settings: ArchiveEmbeddingSettings, val revision: Long, val apiKey: String) {
    override fun toString() = "ArchiveEmbeddingAccess([protected])"
}
data class ArchiveIndexSummary(val total: Int = 0, val ready: Int = 0, val queued: Int = 0, val running: Int = 0,
    val failed: Int = 0, val waiting: Int = 0, val failure: ArchiveSemanticFailure? = null) {
    val missing get() = (total - ready).coerceAtLeast(0)
}
data class ArchiveSearchHit(val recordId: String, val revision: Long, val part: Int, val score: Double)
data class ArchiveSearchSnapshot(val query: String, val fingerprint: String, val hits: List<ArchiveSearchHit>, val configurationRevision: Long = 0) {
    override fun toString() = "ArchiveSearchSnapshot(size=${hits.size}, [protected])"
}
data class ArchiveSearchItem(val record: ArchiveRecord, val snippet: String, val score: Double)
internal fun archiveUnitVector(vector: FloatArray): FloatArray {
    if (vector.isEmpty() || vector.any { !it.isFinite() }) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
    val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
    if (!norm.isFinite() || norm <= 0) throw ArchiveSemanticException(ArchiveSemanticFailure.INVALID_RESULT)
    return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
}
