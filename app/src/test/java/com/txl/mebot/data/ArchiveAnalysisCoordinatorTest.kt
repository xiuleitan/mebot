package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.archive.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveAnalysisCoordinatorTest {
    private lateinit var f: ArchiveAnalysisFixture
    @Before fun setup() = runBlocking { f = ArchiveAnalysisFixture(); f.records.createSession("a"); Unit }
    @After fun teardown() { f.close() }
    private fun proposal(context: ArchiveAnalysisContext, title: String = "我的身份证", conflicts: List<String> = emptyList()) =
        ArchiveInterpretation.Propose(ArchiveContent(title, "证件", fields = listOf(ArchiveField("姓名", "示例姓名")), links = context.links,
            assetIds = context.source.assets.filterNot { it.pending }.map { it.id }), conflicts)
    private suspend fun complete(coordinator: ArchiveAnalysisCoordinator): ArchiveAnalysisWork {
        val work = coordinator.begin("a"); val (context, result) = coordinator.interpret(work)
        assertTrue(coordinator.complete(work, context, result)); return work
    }

    @Test fun twoImagesAreDecryptedInOrderAndOnlyTheSelectedConversationIsSent() = runBlocking {
        val front = f.image("a", "正面.jpg"); val back = f.image("a", "背面.jpg")
        f.send("a", "这是我的身份证正反面", listOf(front, back))
        f.records.createSession("other"); val unrelated = f.image("other", "其他人.jpg"); f.send("other", "另一个人的秘密", listOf(unrelated))
        var calls = 0
        val coordinator = f.coordinator(ArchiveInterpreter { context ->
            calls++; assertEquals("a", context.source.sessionId)
            assertEquals(listOf(front, back), context.images.map { it.assetId }); assertEquals(listOf(1, 2), context.images.map { it.number })
            assertTrue(context.images[0].dataUrl.endsWith(android.util.Base64.encodeToString("正面.jpg".toByteArray(), android.util.Base64.NO_WRAP)))
            assertFalse(context.messages.any { it.text.contains("秘密") }); proposal(context)
        })
        complete(coordinator)
        assertEquals(1, calls); assertTrue(f.records.records().isEmpty())
        val draft = f.records.draft("a"); assertEquals(ArchiveAnalysisStatus.READY, draft.content.analysis.status)
        assertEquals(2, draft.content.analysis.imageCount); assertEquals(listOf(front, back), draft.content.candidate!!.assetIds)
        assertTrue(f.records.context("other").draft.content.candidate == null)
        assertTrue(File(f.app.cacheDir, "archive_previews").listFiles().orEmpty().isEmpty())
    }

    @Test fun blurryAndConflictingFieldsBlockSavingUntilExplicitlyReviewed() = runBlocking {
        f.send("a", "登记我的身份证")
        val coordinator = f.coordinator(ArchiveInterpreter { proposal(it, conflicts = listOf("号码第六位模糊", "地址与文字不一致")) })
        complete(coordinator)
        val draft = f.records.draft("a")
        assertTrue(runCatching { f.records.confirm("a", "save-blocked", draft.revision) }.isFailure)
        assertTrue(f.records.records().isEmpty())
        val reviewed = f.records.saveDraft("a", draft.revision, draft.content.copy(conflicts = emptyList(), candidate = draft.content.candidate!!.copy(fields = listOf(ArchiveField("证件号", "001234X")))))
        val first = f.records.confirm("a", "save-ok", reviewed.revision)
        assertEquals(first, f.records.confirm("a", "save-ok", reviewed.revision))
        assertEquals("001234X", f.records.records().single().content.fields.single().value)
        f.send("a", "补充签发机关")
        complete(coordinator)
        val next = f.records.draft("a")
        val clean = f.records.saveDraft("a", next.revision, next.content.copy(conflicts = emptyList()))
        val updated = f.records.confirm("a", "update", clean.revision)
        assertEquals(first.recordId, updated.recordId); assertEquals(1, f.records.records().size)
    }

    @Test fun questionsRetainOriginalDuplicateLinksAcrossRepliesAndRetries() = runBlocking {
        val url = "https://example.com/path?a=1&a=2#frag"
        f.send("a", "[$url]($url) <$url> https://example.com/second")
        var phase = 0
        val coordinator = f.coordinator(ArchiveInterpreter { context ->
            assertEquals(listOf(url, url, "https://example.com/second"), context.links.map { it.url })
            if (phase++ < 2) ArchiveInterpretation.Ask("本次要登记哪份档案？") else proposal(context)
        })
        complete(coordinator); complete(coordinator) // 重试不追加用户消息、不倍增链接。
        assertEquals(1, f.records.context("a").messages.count { it.role == com.txl.mebot.data.local.MessageRole.USER })
        f.send("a", "本次登记身份证")
        complete(coordinator)
        assertEquals(listOf(url, url, "https://example.com/second"), f.records.draft("a").content.candidate!!.links.map { it.url })
        assertTrue(f.records.records().isEmpty())
    }

    @Test fun anotherArchiveCannotSilentlyReplaceCurrentDraftAndExplicitRenameIsAllowed() = runBlocking {
        f.send("a", "登记我的身份证")
        complete(f.coordinator(ArchiveInterpreter { proposal(it) }))
        f.send("a", "再登记另一张银行卡")
        complete(f.coordinator(ArchiveInterpreter { proposal(it, "我的银行卡") }))
        var draft = f.records.draft("a")
        assertEquals("我的身份证", draft.content.candidate!!.title)
        assertEquals(ArchiveAnalysisStatus.NEEDS_INPUT, draft.content.analysis.status)
        assertTrue(draft.content.conflicts.single().contains("新建会话"))
        f.send("a", "把当前档案标题改名为身份证资料")
        complete(f.coordinator(ArchiveInterpreter { proposal(it, "身份证资料") }))
        draft = f.records.draft("a")
        assertEquals("身份证资料", draft.content.candidate!!.title); assertTrue(draft.content.conflicts.isEmpty())
    }

    @Test fun editingOrNewRequestInvalidatesLateResultWithoutOverwritingNewInputOrToken() = runBlocking {
        f.send("a", "旧资料")
        val coordinator = f.coordinator(ArchiveInterpreter { proposal(it) })
        val old = coordinator.begin("a"); val (oldContext, oldResult) = coordinator.interpret(old)
        val draft = f.records.draft("a")
        f.records.saveDraft("a", draft.revision, draft.content.copy(input = "新的尚未发送输入"))
        assertFalse(coordinator.complete(old, oldContext, oldResult))
        assertEquals("新的尚未发送输入", f.records.draft("a").content.input)
        f.send("a", "最新消息")
        val fresh = coordinator.begin("a")
        assertFalse(coordinator.complete(old, oldContext, oldResult))
        assertEquals(fresh.request.requestId, f.records.draft("a").activeRequestId)
        val (ctx, result) = coordinator.interpret(fresh)
        assertTrue(coordinator.complete(fresh, ctx, result)); assertEquals(1, f.records.context("a").messages.count { it.role == com.txl.mebot.data.local.MessageRole.ASSISTANT })
    }

    @Test fun deletedOrConcurrentlyUpdatedRecordCannotBeRevivedByModel() = runBlocking {
        val seed = f.records.saveDraft("a", 0, ArchiveDraftContent(candidate = ArchiveContent("我的身份证", "证件")))
        val saved = f.records.confirm("a", "initial", seed.revision)
        f.send("a", "补充内容")
        val coordinator = f.coordinator(ArchiveInterpreter { proposal(it) })
        val work = coordinator.begin("a"); val (ctx, result) = coordinator.interpret(work)
        f.records.createSession("editor", saved.recordId)
        val edit = f.records.saveDraft("editor", 0, ArchiveDraftContent(candidate = ArchiveContent("我的身份证", "证件", body = "其他会话更新")))
        f.records.confirm("editor", "external-save", edit.revision)
        assertFalse(coordinator.complete(work, ctx, result))
        assertEquals("其他会话更新", f.records.record(saved.recordId)!!.content.body)
        f.records.delete("editor", "delete", 2)
        assertFalse(coordinator.complete(work, ctx, result)); assertNull(f.records.record(saved.recordId))
        assertTrue(f.records.context("a").closed)
    }

    @Test fun unreadableImageAbortsBeforeModelAndKeepsEncryptedOriginalAndMessage() = runBlocking {
        val image = f.image("a", "bad.jpg"); f.send("a", "识别这张图", listOf(image)); var calls = 0
        var temporary: File? = null
        val coordinator = f.coordinator(ArchiveInterpreter { calls++; proposal(it) }) { file -> temporary = file; throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE) }
        val work = coordinator.begin("a")
        val error = runCatching { coordinator.interpret(work) }.exceptionOrNull() as ArchiveAnalysisException
        coordinator.fail(work, error.failure)
        assertEquals(0, calls); assertFalse(temporary!!.exists())
        assertTrue(File(f.app.filesDir, "archive/originals/$image.bin").exists())
        assertEquals(1, f.records.context("a").messages.size)
        assertEquals(ArchiveAnalysisFailure.IMAGE, f.records.draft("a").content.analysis.failure)
    }

    @Test fun tooManyCurrentImagesAreNotSilentlyDroppedAndOlderImagesAreExplicitlyCounted() = runBlocking {
        val images = (1..7).map { f.image("a", "image-$it.jpg") }
        f.send("a", "七张新图", images); var calls = 0
        val coordinator = f.coordinator(ArchiveInterpreter { calls++; proposal(it) })
        val tooMany = coordinator.begin("a")
        assertEquals(ArchiveAnalysisFailure.IMAGE_LIMIT, (runCatching { coordinator.interpret(tooMany) }.exceptionOrNull() as ArchiveAnalysisException).failure)
        assertEquals(0, calls); coordinator.fail(tooMany, ArchiveAnalysisFailure.IMAGE_LIMIT)
        f.send("a", "先根据已有图片整理")
        val work = coordinator.begin("a"); val (ctx, result) = coordinator.interpret(work)
        assertEquals(6, ctx.images.size); assertEquals(1, ctx.omittedImages)
        assertTrue(coordinator.complete(work, ctx, result))
        assertTrue(f.records.context("a").messages.last().text.contains("1 张较早图片未重复发送"))
    }

    @Test fun textOnlyFlowSkipsImagePreparationAndOldDraftJsonIsBackwardCompatible() = runBlocking {
        f.send("a", "家庭网络，名称 001，密码稍后填写")
        val coordinator = f.coordinator(ArchiveInterpreter { assertTrue(it.images.isEmpty()); proposal(it, "家庭网络") }) { error("不应解码图片") }
        complete(coordinator)
        val legacy = JSONObject("""{"version":1,"input":"旧草稿","candidate":null,"conflicts":[]}""")
        assertEquals(ArchiveAnalysisStatus.NONE, ArchiveCodec.draft(legacy).analysis.status)
        val content = f.records.draft("a").content
        assertEquals(content, ArchiveCodec.draft(ArchiveCodec.draft(content)))
    }

    @Test fun markdownAutolinksAndPunctuationPreserveOriginalUrlBytesAndDuplicates() {
        val urls = archiveSourceLinks("[说明](https://example.com/a_(b)?x=01#f) <https://example.com/z> https://example.com/z， https://example.com/end).")
        assertEquals(listOf("https://example.com/a_(b)?x=01#f", "https://example.com/z", "https://example.com/z", "https://example.com/end"), urls.map { it.url })
        assertEquals("说明", urls.first().label)
    }
}
