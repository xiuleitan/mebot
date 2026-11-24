package com.txl.mebot.data.archive

import java.net.URI

/** 仅取用户确认的正式内容，不读取原始聊天、文件名或待确认草稿。 */
internal fun archiveSearchParts(content: ArchiveContent): List<String> {
    fun safe(value: String): String = redactArchiveProjection(value)
    val header = buildString {
        append("标题：").append(safe(content.title)).append('\n')
        if (content.type.isNotBlank()) append("类型：").append(safe(content.type)).append('\n')
        if (content.tags.isNotEmpty()) append("标签：").append(safe(content.tags.joinToString("、"))).append('\n')
    }.take(600)
    val text = buildString {
        content.fields.filterNot { secretField.containsMatchIn(it.name) }.forEach { append(safe(it.name)).append('：').append(safe(it.value)).append('\n') }
        append(safe(content.body))
        content.links.forEach { link ->
            append('\n').append(safe(link.label))
            val uri = runCatching { URI(link.url) }.getOrNull()
            if (!uri?.host.isNullOrBlank()) append(' ').append(uri!!.host).append(safe(uri.path.orEmpty()))
        }
    }
    if ((header + text).toByteArray(Charsets.UTF_8).size > 600_000) throw ArchiveSemanticException(ArchiveSemanticFailure.INPUT_LIMIT)
    if ((header + text).toByteArray(Charsets.UTF_8).size <= 7000) return listOf(header + text)
    val headerBytes = header.toByteArray(Charsets.UTF_8).size
    val limit = 6900 - headerBytes
    val pieces = mutableListOf<String>(); var piece = StringBuilder(); var bytes = 0; var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index); val length = Character.charCount(point)
        val size = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
        if (bytes + size > limit) { pieces += header + "片段 ${pieces.size + 1}\n" + piece; piece = StringBuilder(); bytes = 0 }
        piece.appendCodePoint(point); bytes += size; index += length
    }
    if (piece.isNotEmpty()) pieces += header + "片段 ${pieces.size + 1}\n" + piece
    if (pieces.size > 100) throw ArchiveSemanticException(ArchiveSemanticFailure.INPUT_LIMIT)
    return pieces
}

private val secretField = Regex("密码|口令|密钥|私钥|验证码|password|passwd|secret|api.?key|token|\\bpin\\b", RegexOption.IGNORE_CASE)
private val urlPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private fun redactArchiveProjection(value: String): String = value.lineSequence().map { line ->
    if (secretField.containsMatchIn(line)) "[凭据内容不参与索引]"
    else urlPattern.replace(line) { match -> runCatching { URI(match.value).let { it.host.orEmpty() + it.path.orEmpty() } }.getOrDefault("[链接]") }
        .replace(Regex("(?:\\d[ -]?){12,}\\d[Xx]?|\\d{7,}[Xx]?"), "[号码]")
}.joinToString("\n")
