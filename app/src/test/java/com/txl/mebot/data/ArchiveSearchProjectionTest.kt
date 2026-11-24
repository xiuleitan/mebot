package com.txl.mebot.data

import com.txl.mebot.data.archive.*
import com.txl.mebot.ui.archive.archiveChangedSections
import org.junit.*
import org.junit.Assert.*

class ArchiveSearchProjectionTest {
    @Test fun confirmedObjectMeaningIsPreservedWhileSecretsRawInputOriginalUrlsAndAssetIdsAreExcluded() {
        val content = ArchiveContent("中国银行借记卡", "银行卡", listOf("个人资料"), listOf(ArchiveField("发卡行", "中国银行"),
            ArchiveField("号码", "6222000011112222333"), ArchiveField("密码", "do-not-index-secret")),
            "已确认的用途\n口令 abc-secret\n参考 https://example.com/guide?token=private#secret", rawInput = "UNSENT-RAW-CHAT",
            links = listOf(ArchiveLink("https://example.com/guide?secret=123", "使用说明")), assetIds = listOf("private-asset-id"))
        val text = archiveSearchParts(content).single()
        for (value in listOf("中国银行借记卡", "银行卡", "发卡行：中国银行", "已确认的用途", "example.com/guide", "使用说明")) assertTrue(value, text.contains(value))
        for (value in listOf("do-not-index-secret", "abc-secret", "6222000011112222333", "UNSENT-RAW-CHAT", "private-asset-id", "?token", "?secret", "#secret")) assertFalse(value, text.contains(value))
    }
    @Test fun longUnicodeTextIsSplitWithinByteBudgetWithoutLosingTextOrObjectContext() {
        val body = "长期资料🙂段落\n".repeat(1500)
        val parts = archiveSearchParts(ArchiveContent("长期档案", "资料", body = body))
        assertTrue(parts.size > 1); assertTrue(parts.size <= 100)
        assertTrue(parts.all { it.startsWith("标题：长期档案\n类型：资料\n") && it.toByteArray(Charsets.UTF_8).size <= 7000 })
        assertEquals(body, parts.mapIndexed { i, part -> part.substringAfter("片段 ${i + 1}\n") }.joinToString(""))
    }
    @Test fun excessiveProjectionFailsExplicitlyRatherThanPublishingAPartialIndex() {
        val content = ArchiveContent("超长档案", fields = List(100) { ArchiveField("字段 $it", "字".repeat(3000)) })
        assertEquals(ArchiveSemanticFailure.INPUT_LIMIT, (runCatching { archiveSearchParts(content) }.exceptionOrNull() as ArchiveSemanticException).failure)
    }
    @Test fun confirmationSummaryShowsWhichSectionsChangeWithoutCopyingSecretValues() {
        val before = ArchiveContent("原标题", fields = listOf(ArchiveField("号码", "00123")), body = "原说明")
        assertEquals(emptyList<String>(), archiveChangedSections(before, before))
        assertEquals(listOf("标题", "正文", "具体信息"), archiveChangedSections(before, before.copy(title = "新标题", body = "新说明", fields = listOf(ArchiveField("号码", "00456")))))
    }
}
