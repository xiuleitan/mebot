package com.txl.mebot.data.archive

enum class ArchiveAnalysisStatus { NONE, RUNNING, READY, NEEDS_INPUT, FAILED, CANCELLED, INTERRUPTED }
enum class ArchiveAnalysisFailure { CONFIGURATION, CREDENTIAL, AUTHORIZATION, BILLING, NETWORK, TIMEOUT, RATE_LIMIT,
    SERVICE, INVALID_RESULT, LOCAL_SAVE, CHANGED, INTERRUPTED, IMAGE, IMAGE_LIMIT, INPUT_LIMIT }
class ArchiveAnalysisException(val failure: ArchiveAnalysisFailure) : Exception("档案整理失败：$failure")
data class ArchiveAnalysisMeta(
    val status: ArchiveAnalysisStatus = ArchiveAnalysisStatus.NONE, val sourceMessageId: String? = null,
    val failure: ArchiveAnalysisFailure? = null, val imageCount: Int = 0, val omittedImages: Int = 0,
    val sourceLinks: List<ArchiveLink> = emptyList(),
    val requestId: String? = null,
)
class ArchiveAnalysisImage(val assetId: String, val messageId: String, val number: Int, val dataUrl: String) {
    override fun toString() = "ArchiveAnalysisImage(number=$number, [protected])"
}
data class ArchiveAnalysisContext(val source: ArchiveContext, val messages: List<ArchiveChatMessage>,
    val sourceMessageId: String, val images: List<ArchiveAnalysisImage>, val links: List<ArchiveLink>,
    val historyTrimmed: Boolean, val omittedImages: Int)
sealed interface ArchiveInterpretation {
    data class Propose(val content: ArchiveContent, val conflicts: List<String>) : ArchiveInterpretation
    data class Ask(val question: String) : ArchiveInterpretation { override fun toString() = "Ask([protected])" }
    data class Reject(val reason: String) : ArchiveInterpretation
}
fun interface ArchiveInterpreter { suspend fun interpret(context: ArchiveAnalysisContext): ArchiveInterpretation }
data class ArchiveAnalysisWork(val request: ArchiveAnalysisRequest, val source: ArchiveContext, val sourceMessageId: String)
