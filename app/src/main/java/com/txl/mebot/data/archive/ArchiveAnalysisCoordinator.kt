package com.txl.mebot.data.archive

import com.txl.mebot.data.local.MessageRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 准备/落库与网络等待分离，调用方可在等待期间切换会话。所有写回仍验证持久令牌。 */
class ArchiveAnalysisCoordinator(private val records: ArchiveRepository, private val attachments: ArchiveAttachmentRepository,
    private val interpreter: ArchiveInterpreter, private val imageData: suspend (File) -> String = ::archiveImageData) {
    suspend fun begin(sessionId: String): ArchiveAnalysisWork {
        val source = records.context(sessionId)
        val messageId = records.latestUserMessage(sessionId) ?: throw ArchiveAnalysisException(ArchiveAnalysisFailure.INPUT_LIMIT)
        val request = records.beginAnalysis(sessionId, UUID.randomUUID().toString(), source.draft.revision, messageId)
        val updated = records.context(sessionId)
        if (updated.draft.revision != request.draftRevision) throw ArchiveAnalysisException(ArchiveAnalysisFailure.CHANGED)
        return ArchiveAnalysisWork(request, updated, messageId)
    }

    suspend fun interpret(work: ArchiveAnalysisWork): Pair<ArchiveAnalysisContext, ArchiveInterpretation> = requests.withPermit {
        val source = work.source
        if (source.closed || source.record?.revision != source.draft.baseRecordRevision) throw ArchiveAnalysisException(ArchiveAnalysisFailure.CHANGED)
        val latest = source.messages.find { it.id == work.sourceMessageId } ?: throw ArchiveAnalysisException(ArchiveAnalysisFailure.CHANGED)
        if (latest.text.length > 16_000) throw ArchiveAnalysisException(ArchiveAnalysisFailure.INPUT_LIMIT)
        val base = source.draft.content.candidate ?: source.record?.content
        val baseLength = base?.let { it.title.length + it.type.length + it.body.length + it.fields.sumOf { f -> f.name.length + f.value.length } + it.tags.sumOf(String::length) } ?: 0
        if (baseLength > 20_000) throw ArchiveAnalysisException(ArchiveAnalysisFailure.INPUT_LIMIT)
        var length = baseLength + latest.text.length
        val kept = mutableListOf(latest)
        for (message in source.messages.asReversed()) if (message.sequence < latest.sequence && length + message.text.length <= 30_000) {
            kept += message; length += message.text.length
        }
        val messages = kept.sortedBy { it.sequence }
        val links = mergeArchiveLinks(base?.links ?: source.draft.content.analysis.sourceLinks, archiveSourceLinks(latest.text))
        if (links.size > 100 || length + links.sumOf { it.url.length + it.label.length } > 32_000) throw ArchiveAnalysisException(ArchiveAnalysisFailure.INPUT_LIMIT)
        val assets = source.assets.associateBy { it.id }
        val all = messages.filter { it.role == MessageRole.USER }.flatMap { message -> message.assetIds.map { it to message.id } }
            .filter { assets[it.first]?.info?.mimeType?.startsWith("image/") == true }.distinctBy { it.first }
        val required = all.filter { it.second == latest.id }
        if (required.size > 6) throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE_LIMIT)
        val ids = (required + all.asReversed()).distinctBy { it.first }.take(6).map { it.first }.toSet()
        val selected = all.filter { it.first in ids }.sortedBy { (id, _) -> source.assets.indexOfFirst { it.id == id } }
        var encodedLength = 0L
        val images = selected.mapIndexed { index, (id, message) ->
            if (assets.getValue(id).info.byteCount > 20L * 1024 * 1024) throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE)
            val data = attachments.withDecryptedFile(id) { imageData(it) }
            if (!data.startsWith("data:image/jpeg;base64,")) throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE)
            encodedLength += data.length
            if (encodedLength > 11L * 1024 * 1024) throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE_LIMIT)
            ArchiveAnalysisImage(id, message, index + 1, data)
        }
        val context = ArchiveAnalysisContext(source, messages, latest.id, images, links,
            messages.size < source.messages.size || (source.messages.firstOrNull()?.sequence ?: 1) > 1, all.size - images.size)
        context to interpreter.interpret(context)
    }

    suspend fun complete(work: ArchiveAnalysisWork, context: ArchiveAnalysisContext, result: ArchiveInterpretation): Boolean {
        val meta = ArchiveAnalysisMeta(sourceMessageId = work.sourceMessageId, imageCount = context.images.size,
            omittedImages = context.omittedImages, sourceLinks = context.links, requestId = work.request.requestId)
        val footer = (if (context.images.isNotEmpty()) " 本次使用了 ${context.images.size} 张图片。" else "") +
            (if (context.omittedImages > 0) " ${context.omittedImages} 张较早图片未重复发送。" else "") +
            (if (context.historyTrimmed) " 较早对话按需截取。" else "")
        val previous = work.source.draft.content.candidate ?: work.source.record?.content
        val latestText = context.messages.firstOrNull { it.id == work.sourceMessageId }?.text.orEmpty()
        val guarded = if (result is ArchiveInterpretation.Propose && previous != null && previous.title.isNotBlank() &&
            (result.content.title != previous.title && !Regex("标题|名称|改名|名字|更名|叫作|叫做").containsMatchIn(latestText) ||
                result.content.type != previous.type && previous.type.isNotBlank() && !Regex("类型|分类").containsMatchIn(latestText)))
            ArchiveInterpretation.Reject("DIFFERENT_ARCHIVE") else result
        val applied = when (val result = guarded) {
            is ArchiveInterpretation.Propose -> records.completeAnalysis(work.request, result.content, result.conflicts,
                "已整理为草稿，请核对信息后保存。" + (if (result.conflicts.isEmpty()) "" else " 有 ${result.conflicts.size} 项信息需要确认。") + footer,
                meta.copy(status = ArchiveAnalysisStatus.READY))
            is ArchiveInterpretation.Ask -> records.completeAnalysis(work.request, previous?.copy(links = context.links), listOf(result.question), result.question + footer,
                meta.copy(status = ArchiveAnalysisStatus.NEEDS_INPUT), allowIncomplete = true)
            is ArchiveInterpretation.Reject -> {
                val reply = when (result.reason) {
                    "MULTIPLE_ARCHIVES" -> "一次会话只整理一份档案，请先说明本次要登记哪一份，其他资料请新建会话。"
                    "DIFFERENT_ARCHIVE" -> "这段会话已对应一份档案。另一张卡、另一个人或另一份资料请新建会话；若要修改当前标题或分类，请明确说明。"
                    "QUERY" -> "已有资料请在档案列表或详情中查看，这里用于登记和补充当前档案。"
                    else -> "请发送本次要登记的资料说明或图片，一次处理一份档案。"
                }
                records.completeAnalysis(work.request, previous, listOf(reply), reply,
                    meta.copy(status = ArchiveAnalysisStatus.NEEDS_INPUT), allowIncomplete = true)
            }
        }
        if (!applied) fail(work, ArchiveAnalysisFailure.CHANGED)
        return applied
    }
    suspend fun fail(work: ArchiveAnalysisWork, failure: ArchiveAnalysisFailure) =
        records.cancelAnalysis(work.request.sessionId, work.request.requestId, ArchiveAnalysisStatus.FAILED, failure)

    companion object { private val requests = Semaphore(2) }
}

/** 与旧版同样只解析显式 HTTP(S) 片段，不抓网页；重复 URL 的顺序和数量保留。 */
internal fun archiveSourceLinks(text: String): List<ArchiveLink> {
    val found = mutableListOf<Pair<IntRange, ArchiveLink>>()
    fun add(range: IntRange, url: String, label: String = "") {
        if (found.none { range.first <= it.first.last && range.last >= it.first.first }) found += range to ArchiveLink(url, label)
    }
    Regex("!?\\[([^\\]\\n]*)\\]\\((https?://(?:[^\\s<>()]|\\([^()\\s<>]*\\))+?)\\)", RegexOption.IGNORE_CASE)
        .findAll(text).forEach { add(it.range, it.groupValues[2], it.groupValues[1]) }
    Regex("<(https?://[^\\s<>]+)>", RegexOption.IGNORE_CASE).findAll(text).forEach { add(it.range, it.groupValues[1]) }
    Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
        var url = match.value.trimEnd('.', ',', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？', '、')
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
        while (url.endsWith(']') && url.count { it == ']' } > url.count { it == '[' }) url = url.dropLast(1)
        if (url.isNotEmpty()) add(match.range, url)
    }
    return found.sortedBy { it.first.first }.map { it.second }
}
private fun mergeArchiveLinks(base: List<ArchiveLink>, incoming: List<ArchiveLink>): List<ArchiveLink> {
    val result = base.toMutableList()
    val occurrences = mutableMapOf<String, Int>()
    incoming.forEach { link ->
        val count = (occurrences[link.url] ?: 0) + 1; occurrences[link.url] = count
        if (count > result.count { it.url == link.url }) result += link
    }
    return result
}
