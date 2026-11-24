package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.MessageRole
import com.txl.mebot.data.local.ScheduleAttachmentSummary
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.models.*
import com.txl.mebot.data.schedule.*
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.asEntry
import com.txl.mebot.ui.schedule.loadSchedulePhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

/** 开发机验证真实 SQLite、原图复制、封面和模型边界，不操作手机或调用付费模型。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleAttachmentStorageTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var schedules: ScheduleRepository
    private lateinit var conversations: ScheduleConversationRepository
    private lateinit var attachments: ScheduleAttachmentRepository
    private lateinit var session: String
    private val day = LocalDate.now()

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        context.deleteDatabase(MebotDatabase.FILE_NAME)
        db = MebotDatabase.create(context)
        connect()
        session = conversations.openCreation().sessionId
    }
    private fun connect() {
        media = LocalMediaStore(context, db)
        schedules = ScheduleRepository(db)
        conversations = ScheduleConversationRepository(db, schedules)
        attachments = ScheduleAttachmentRepository(context, db, media)
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }

    private fun image(color: Int) = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
        output.toByteArray()
    }
    private suspend fun add(bytes: ByteArray, name: String, mime: String = "application/octet-stream", to: String = session) =
        media.importDocument({ ByteArrayInputStream(bytes) }, name, mime, onImported = { attachments.attach(to, it, name) })

    @Test fun selectedContentUriIsCopiedAndSurvivesSourceRemovalAndDatabaseReopen() = runBlocking {
        val bytes = image(Color.RED)
        val source = File(context.filesDir, "media/originals/picker-source/photo.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source, "用户图片.png")
        val asset = attachments.importUri(session, uri, true)
        val stored = requireNotNull(media.fileFor(asset.id))
        assertNotEquals(source.canonicalPath, stored.canonicalPath)
        assertTrue(stored.readBytes().contentEquals(bytes))
        source.delete()
        db.close(); db = MebotDatabase.create(context); connect()
        assertEquals(session, conversations.openCreation().sessionId)
        val list = attachments.observe(session).first()
        assertEquals("用户图片.png", list.single().link.displayName)
        assertTrue(list.single().link.isCover)
        assertTrue(requireNotNull(media.fileFor(asset.id)).readBytes().contentEquals(bytes))
        val task = schedules.createTask(session, "create", ScheduleDraft("带图日程", date = day))
        assertEquals(asset.id, task.coverAssetId)
        val card = schedules.observeDisplayDate(day).first().single().asEntry()
        assertEquals(1, card.attachmentCount)
        assertNotNull(loadSchedulePhoto(context, card))
    }

    @Test fun firstImageWinsAfterFilesAndExplicitCoverChoiceSurvivesAdditionalSelections() = runBlocking {
        val pdf = add("%PDF-1.4\nlocal document".toByteArray(), "资料.pdf", "application/pdf")
        val first = add(image(Color.RED), "一.png", "image/png")
        val second = add(image(Color.BLUE), "二.png", "image/png")
        assertEquals(listOf(pdf.id, first.id, second.id), attachments.observe(session).first().map { it.asset.id })
        assertEquals(first.id, attachments.observe(session).first().single { it.link.isCover }.asset.id)
        attachments.chooseCover(session, second.id)
        val third = add(image(Color.GREEN), "三.png", "image/png")
        assertNotEquals(second.id, third.id)
        assertEquals(second.id, attachments.observe(session).first().single { it.link.isCover }.asset.id)
        val task = schedules.createTask(session, "create", ScheduleDraft("采购", date = day))
        assertEquals(second.id, task.coverAssetId)
        assertEquals(4, schedules.observeDisplayDate(day).first().single().attachmentCount)
        assertTrue(runCatching { attachments.chooseCover(session, pdf.id) }.isFailure)
    }

    @Test fun coverChangeIsSharedByAllRecurringOccurrencesIncludingHistoryAndCompletion() = runBlocking {
        val one = add(image(Color.RED), "一.png", "image/png")
        val two = add(image(Color.BLUE), "二.png", "image/png")
        val task = schedules.createTask(session, "create", ScheduleDraft("喂猫", "猫粮不变", day, LocalTime.of(18, 0), RecurrenceRule(RecurrencePattern.DAILY), listOf(30, 0)))
        schedules.materializeRange(day, day.plusDays(3))
        val card = schedules.observeDate(day.plusDays(1)).first().single()
        schedules.setCompleted(session, card.id, true, "done", card.revision)
        val before = schedules.history(day, day.plusDays(3)).map { it.copy(coverAssetId = null) }
        attachments.chooseCover(session, two.id)
        assertEquals(before, schedules.history(day, day.plusDays(3)).map { it.copy(coverAssetId = null) })
        assertTrue(schedules.history(day, day.plusDays(3)).all { it.coverAssetId == two.id })
        schedules.materializeRange(day.plusDays(20), day.plusDays(20))
        assertEquals(two.id, schedules.observeDate(day.plusDays(20)).first().single().coverAssetId)
        assertEquals(task.revision, schedules.taskForSession(session)?.revision)
        assertEquals(listOf(30, 0), schedules.reminderMinutesForSession(session))
        assertNotNull(media.fileFor(one.id))
    }

    @Test fun repeatSelectionDeduplicatesContentAndUsesPerSessionDisplayNames() = runBlocking {
        val bytes = "some local text".toByteArray()
        val first = add(bytes, "第一份.txt", "text/plain")
        val second = add(bytes, "重复选择.txt", "text/plain")
        assertEquals(first.id, second.id)
        assertEquals(1, attachments.observe(session).first().size)
        val other = conversations.clearCreation().sessionId
        // 清空后旧文件等待清理；重新导入同一内容安全复用完整文件。
        val next = add(bytes, "另一个名字.txt", "text/plain", other)
        assertEquals("另一个名字.txt", attachments.observe(other).first().single().link.displayName)
        media.cleanPendingDeletions()
        assertTrue(requireNotNull(media.fileFor(next.id)).isFile)
    }

    @Test fun removingCoverChoosesNextImageAndDoesNotDeleteAnotherTasksSharedFile() = runBlocking {
        val bytes = image(Color.RED)
        val first = add(bytes, "一.png", "image/png")
        val second = add(image(Color.BLUE), "二.png", "image/png")
        schedules.createTask(session, "create", ScheduleDraft("采购", date = day))
        val other = schedules.openSession().id
        add(bytes, "共享图.png", "image/png", other)
        schedules.createTask(other, "other", ScheduleDraft("另一项", date = day))
        val shared = requireNotNull(media.fileFor(first.id))
        attachments.remove(session, first.id)
        media.cleanPendingDeletions()
        assertTrue(shared.isFile)
        assertEquals(second.id, schedules.taskForSession(session)?.coverAssetId)
        attachments.remove(session, second.id)
        media.cleanPendingDeletions()
        assertNull(schedules.taskForSession(session)?.coverAssetId)
        assertEquals(0, schedules.observeDisplayDate(day).first().first { it.sessionId == session }.attachmentCount)
        assertEquals(first.id, schedules.taskForSession(other)?.coverAssetId)
    }

    @Test fun deletingTaskRemovesAttachmentRowsAndUniqueFilesWhileSharedDataRemains() = runBlocking {
        val asset = add("unique file".toByteArray(), "唯一.txt", "text/plain")
        val file = requireNotNull(media.fileFor(asset.id))
        val task = schedules.createTask(session, "create", ScheduleDraft("采购", date = day))
        schedules.deleteTask(session, "delete", task.revision)
        assertTrue(attachments.observe(session).first().isEmpty())
        assertNull(db.mediaDao().asset(asset.id))
        media.cleanPendingDeletions()
        assertFalse(file.exists())
    }

    @Test fun attachmentOnlyDraftIsRestoredAndClearRemovesItsUnreferencedCopy() = runBlocking {
        val asset = add("draft file".toByteArray(), "待办.txt", "text/plain")
        val file = requireNotNull(media.fileFor(asset.id))
        schedules.openSession() // 较新但无内容的空会话不能抢走已有附件草稿。
        assertEquals(session, conversations.openCreation().sessionId)
        assertTrue(conversations.observeMessages(session, 10).first().isEmpty())
        val fresh = conversations.clearCreation()
        assertNotEquals(session, fresh.sessionId)
        media.cleanPendingDeletions()
        assertFalse(file.exists())
        assertNull(db.mediaDao().asset(asset.id))
    }

    @Test fun failedAssociationRollsBackMetadataAndQueuesOnlyTheUnlinkedFileForCleanup() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_attach BEFORE INSERT ON schedule_local_attachments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { add("test file".toByteArray(), "失败.txt", "text/plain") }.isFailure)
        assertTrue(attachments.observe(session).first().isEmpty())
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); it.getInt(0) })
        assertTrue(db.mediaDao().cleanupPaths(10).isNotEmpty())
        media.cleanPendingDeletions()
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_attach")
        assertNotNull(add("test file".toByteArray(), "成功.txt", "text/plain"))
    }

    @Test fun imagesAreValidatedAndOversizedOrInterruptedCopiesNeverBecomeAttachments() = runBlocking {
        assertTrue(runCatching { add("not an image".toByteArray(), "坏图.png", "image/png") }.isFailure)
        val tooLarge = object : InputStream() {
            var left = 50L * 1024 * 1024 + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val count = minOf(left, len.toLong()).toInt(); left -= count
                buffer.fill(0, off, off + count)
                return count
            }
        }
        assertTrue(runCatching { media.importDocument({ tooLarge }, "超大.png", "image/png", true) { attachments.attach(session, it) } }.isFailure)
        assertTrue(attachments.observe(session).first().isEmpty())
        assertTrue(File(context.filesDir, "media/staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun modelReceivesOnlyAttachmentCountsAndCanCreateAReferentialTaskWithItsLocalCover() = runBlocking {
        val image = add(image(Color.RED), "private-image-name.png", "image/png")
        add("PRIVATE_FILE_CONTENT".toByteArray(), "private-file-name.txt", "text/plain")
        val settings = ModelSettingsRepository(db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 3 }, "AES") })
        settings.save(ModelSettings(), ApiKeyChange.Replace("sk-host-test"), 0)
        val interpreter = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, body, _ ->
            assertFalse(body.contains("private-image-name")); assertFalse(body.contains("private-file-name"))
            assertFalse(body.contains("PRIVATE_FILE_CONTENT")); assertFalse(body.contains("image_url")); assertFalse(body.contains("file_url"))
            assertFalse(body.contains("base64")); assertFalse(body.contains("media/originals"))
            val request = JSONObject(body)
            val input = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            val summary = input.getJSONObject("attachment_summary")
            assertEquals(setOf("total_count", "image_count", "file_count"), summary.keys().asSequence().toSet())
            assertEquals(2, summary.getInt("total_count"))
            assertEquals(1, summary.getInt("image_count"))
            assertEquals(1, summary.getInt("file_count"))
            assertEquals("明天把这两个东西收了", input.getJSONArray("conversation").getJSONObject(0).getString("text"))
            assertFalse(input.has("attachments")); assertFalse(input.has("files"))
            ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.saveJson(ScheduleDraft("收取两个东西", "把这两个东西收了（参照本任务附件）", day.plusDays(1))))
        })
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "user", "明天把这两个东西收了", draft.revision)
        val result = ScheduleCreationCoordinator(db, schedules, conversations, interpreter).process(session, "user")
        assertEquals(day.plusDays(1), result.savedDate)
        assertEquals(image.id, schedules.taskForSession(session)?.coverAssetId)
        val card = schedules.observeDisplayDate(day.plusDays(1)).first().single()
        assertEquals("收取两个东西", card.occurrence.title)
        assertNull(card.occurrence.time)
        assertEquals(2, card.attachmentCount)
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
        assertTrue(schedules.pendingReminders(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun genericImageActivityCreatesOneDateOnlyCardWithBothLocalImages() = runBlocking {
        val first = add(image(Color.RED), "private-first.png", "image/png")
        add(image(Color.BLUE), "private-second.png", "image/png")
        val settings = ModelSettingsRepository(db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 3 }, "AES") })
        settings.save(ModelSettings(), ApiKeyChange.Replace("sk-host-test"), 0)
        var calls = 0
        val interpreter = QwenScheduleTextInterpreter(settings, ModelHttpTransport { _, _, body, _ ->
            calls++
            val input = JSONObject(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("user_reference_only", input.getString("attachment_usage"))
            assertEquals(2, input.getJSONObject("attachment_summary").getInt("image_count"))
            assertEquals("今天晚上要做这两个图片的事", input.getJSONArray("conversation").getJSONObject(0).getString("text"))
            assertFalse(body.contains("private-first")); assertFalse(body.contains("private-second"))
            ScheduleTextInterpreterTest.response(ScheduleTextInterpreterTest.saveJson(
                ScheduleDraft("处理图片事项", "今天晚上要做这两张图片里的事（参照本任务图片）", day)))
        })
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "image-activity", "今天晚上要做这两个图片的事", draft.revision)
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, interpreter)
        coordinator.process(session, "image-activity")
        coordinator.process(session, "image-activity")
        assertEquals(1, calls)
        val card = schedules.observeDisplayDate(day).first().single()
        assertEquals("处理图片事项", card.occurrence.title)
        assertNull(card.occurrence.time)
        assertEquals(2, card.attachmentCount)
        assertEquals(first.id, schedules.taskForSession(session)?.coverAssetId)
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
        assertTrue(schedules.pendingReminders(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun imageContentQuestionIsRejectedAndAutomaticallyRetriedWithoutDuplicatingAnything() = runBlocking {
        add(image(Color.RED), "first.png", "image/png")
        add(image(Color.BLUE), "second.png", "image/png")
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "generic-images", "今天晚上要做这两个图片的事", draft.revision)
        var calls = 0
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
            calls++
            if (calls == 1) ScheduleInterpretation.Ask("请问这两张图片里具体是什么事情？（例如：打印文件、填写表格）")
            else ScheduleInterpretation.Save(ScheduleDraft("处理图片事项", "今天晚上完成图片里的事", day))
        })
        val result = coordinator.process(session, "generic-images")
        assertEquals(2, calls)
        assertNotNull(result.createdOccurrenceId)
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
        assertEquals(2, attachments.observe(session).first().size)
        coordinator.process(session, "generic-images")
        assertEquals(2, calls)
        assertEquals(1, schedules.observeDate(day).first().size)
        assertEquals(2, schedules.recentMessages(session).size)
    }

    @Test fun necessaryActionTimeAndExplicitImageReadingQuestionsRemainAllowed() = runBlocking {
        for ((index, pair) in listOf(
            "明天这两张图片" to "你要对图片做什么？",
            "明晚提醒我做这两张图片里的事" to "明晚几点提醒你？",
            "识别图片里的内容，提取要做的事情并创建日程" to "我无法读取图片内容，请提供要做事项的必要文字。",
        ).withIndex()) {
            val target = if (index == 0) session else schedules.openSession().id
            add(image(Color.RED), "reference.png", "image/png", target)
            val draft = conversations.openConversation(target)
            conversations.sendUserMessage(target, "necessary-$index", pair.first, draft.revision)
            ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
                ScheduleInterpretation.Ask(pair.second)
            }).process(target, "necessary-$index")
            assertEquals(pair.second, conversations.observePendingQuestions(target).first().single().question)
            assertNull(schedules.taskForSession(target))
        }
    }

    @Test fun savingGenericActivityRetiresOldImageContentQuestionEvenIfModelOmitsItsId() = runBlocking {
        add(image(Color.RED), "first.png", "image/png")
        add(image(Color.BLUE), "second.png", "image/png")
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "first-request", "今天晚上要做这两个图片的事", draft.revision)
        conversations.recordQuestion(session, "old-content-question", "请问这两张图片里具体是什么事情？")
        val restored = conversations.openConversation(session)
        conversations.sendUserMessage(session, "record-only", "你主要记录这两个图片的事儿就可以了", restored.revision)
        ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
            ScheduleInterpretation.Save(ScheduleDraft("处理图片事项", "今天晚上要做图片里的事", day))
        }).process(session, "record-only")
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
        assertEquals(1, schedules.observeDate(day).first().size)
        assertEquals(2, attachments.observe(session).first().size)
    }

    @Test fun attachmentSummaryIsSessionScopedAndReflectsLocalChangesAcrossReopen() = runBlocking {
        assertEquals(ScheduleAttachmentSummary(), db.scheduleMediaDao().attachmentSummary(session))
        val first = add(image(Color.RED), "private-one.png", "image/png")
        val second = add(image(Color.BLUE), "private-two.png", "image/png")
        add("PRIVATE_FILE_CONTENT".toByteArray(), "private.txt", "text/plain")
        val other = schedules.openSession().id
        add(image(Color.GREEN), "other-session.png", "image/png", other)
        assertEquals(ScheduleAttachmentSummary(3, 2, 1), db.scheduleMediaDao().attachmentSummary(session))
        assertEquals(ScheduleAttachmentSummary(1, 1, 0), db.scheduleMediaDao().attachmentSummary(other))
        attachments.chooseCover(session, second.id)
        assertEquals(ScheduleAttachmentSummary(3, 2, 1), db.scheduleMediaDao().attachmentSummary(session))
        attachments.remove(session, first.id)
        assertEquals(ScheduleAttachmentSummary(2, 1, 1), db.scheduleMediaDao().attachmentSummary(session))
        db.close(); db = MebotDatabase.create(context); connect()
        assertEquals(ScheduleAttachmentSummary(2, 1, 1), db.scheduleMediaDao().attachmentSummary(session))
        assertEquals(ScheduleAttachmentSummary(), db.scheduleMediaDao().attachmentSummary("not-a-session"))
    }

    @Test fun oldRequestsForFileDescriptionsDoNotPreventCreatingTheReferentialTask() = runBlocking {
        add(image(Color.RED), "private.png", "image/png")
        add("PRIVATE_CONTENT".toByteArray(), "private.txt", "text/plain")
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "old-user", "明天把这两个东西收了", draft.revision)
        conversations.recordQuestion(session, "old-question", "这两个东西是什么？请描述图片和文件内容。")
        db.close(); db = MebotDatabase.create(context); connect()
        val restored = conversations.openConversation(session)
        conversations.sendUserMessage(session, "user-again", "明天把这两个东西收了", restored.revision)
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
            assertEquals(ScheduleAttachmentSummary(2, 1, 1), it.attachments)
            assertEquals("old-question", it.questions.single().id)
            ScheduleInterpretation.Save(ScheduleDraft("收取两个东西", "参照本任务附件", day.plusDays(1)), answeredQuestionIds = setOf("old-question"))
        })
        coordinator.process(session, "user-again")
        assertEquals("收取两个东西", schedules.taskForSession(session)?.title)
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
        assertEquals(2, attachments.observe(session).first().size)
        assertEquals(1, schedules.observeDate(day.plusDays(1)).first().size)
    }

    @Test fun retryAndFollowupUseCurrentAttachmentCountsWithoutReadingMediaContents() = runBlocking {
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "user", "明天收取这两个东西", draft.revision)
        var call = 0
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
            when (++call) {
                1 -> {
                    assertEquals(ScheduleAttachmentSummary(), it.attachments)
                    throw ScheduleAnalysisException(ScheduleAnalysisFailure.NETWORK)
                }
                2 -> {
                    assertEquals(ScheduleAttachmentSummary(2, 1, 1), it.attachments)
                    ScheduleInterpretation.Save(ScheduleDraft("收取两个东西", date = day.plusDays(1)))
                }
                else -> {
                    assertEquals(ScheduleAttachmentSummary(1, 1, 0), it.attachments)
                    ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", date = day.plusDays(2)), setOf("date")))
                }
            }
        })
        assertTrue(runCatching { coordinator.process(session, "user") }.exceptionOrNull() is ScheduleAnalysisException)
        add(image(Color.RED), "private.png", "image/png")
        val file = add("PRIVATE_CONTENT".toByteArray(), "private.txt", "text/plain")
        coordinator.process(session, "user")
        val task = requireNotNull(schedules.taskForSession(session))
        attachments.remove(session, file.id)
        val followup = conversations.openConversation(session)
        conversations.sendUserMessage(session, "edit", "改成后天", followup.revision)
        coordinator.process(session, "edit")
        assertEquals(3, call)
        assertEquals(task.id, schedules.taskForSession(session)?.id)
        assertEquals("收取两个东西", schedules.taskForSession(session)?.title)
        assertEquals(day.plusDays(2), schedules.taskForSession(session)?.date)
        assertEquals(1, schedules.observeDate(day.plusDays(2)).first().size)
    }

    @Test fun coverSelectionWhileModelIsRunningCannotBeOverwrittenByItsLateResult() = runBlocking {
        val first = add(image(Color.RED), "一.png", "image/png")
        val second = add(image(Color.BLUE), "二.png", "image/png")
        schedules.createTask(session, "create", ScheduleDraft("采购", "买蔬菜", day))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter {
            assertEquals(first.id, it.current?.coverAssetId)
            entered.complete(Unit); release.await()
            ScheduleInterpretation.Edit(SchedulePatch(ScheduleDraft("忽略", detail = "买蔬菜和水果"), setOf("detail")))
        })
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "edit", "再加水果", draft.revision)
        val work = async { coordinator.process(session, "edit") }
        entered.await(); attachments.chooseCover(session, second.id); release.complete(Unit); work.await()
        assertEquals(second.id, schedules.taskForSession(session)?.coverAssetId)
        assertEquals(second.id, schedules.observeDate(day).first().single().coverAssetId)
        assertEquals("买蔬菜和水果", schedules.taskForSession(session)?.detail)
    }

    @Test fun jpegExifOrientationIsRespectedWithoutChangingTheOriginalBytes() = runBlocking {
        val source = File(context.filesDir, "photo.jpg")
        ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED); compress(Bitmap.CompressFormat.JPEG, 90, output); recycle() }
            source.writeBytes(output.toByteArray())
        }
        ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val bytes = source.readBytes()
        val asset = add(bytes, "相机.jpg", "image/jpeg")
        val photo = requireNotNull(loadSchedulePhoto(context, ScheduleEntry("photo", "相机", day, detail = "", coverRelativePath = asset.relativePath)))
        assertEquals(16, photo.width); assertEquals(24, photo.height)
        assertTrue(requireNotNull(media.fileFor(asset.id)).readBytes().contentEquals(bytes))
        source.delete()
        Unit
    }

    @Test fun sendSnapshotsOnlyPendingMediaInOrderAndRetryLeavesLaterSelectionsUntouched() = runBlocking {
        val imageOne = add(image(Color.RED), "one.png", "image/png")
        val document = add("private document".toByteArray(), "notes.txt", "text/plain")
        val imageTwo = add(image(Color.BLUE), "two.png", "image/png")
        val draft = conversations.openConversation(session)
        val afterSend = conversations.sendUserMessage(session, "first-send", "明天处理这几个附件", draft.revision)
        val first = db.scheduleMediaDao().observeMessageAttachments(session, 40).first()
        assertEquals(listOf(imageOne.id, document.id, imageTwo.id), first.map { it.asset.id })
        assertEquals(listOf("one.png", "notes.txt", "two.png"), first.map { it.link.displayName })
        assertTrue(attachments.observe(session).first().none { it.link.pendingSend || it.link.draftOnly })
        val later = add("later selection".toByteArray(), "later.txt", "text/plain")
        conversations.sendUserMessage(session, "first-send", "明天处理这几个附件", draft.revision)
        assertTrue(attachments.observe(session).first().single { it.asset.id == later.id }.link.pendingSend)
        val second = conversations.sendUserMessage(session, "second-send", "再补充这份文件", afterSend.revision)
        conversations.sendUserMessage(session, "plain-followup", "改成后天", second.revision)
        db.close(); db = MebotDatabase.create(context); connect()
        val history = db.scheduleMediaDao().observeMessageAttachments(session, 40).first().groupBy { it.link.messageId }
        assertEquals(listOf(imageOne.id, document.id, imageTwo.id), history.getValue("first-send").map { it.asset.id })
        assertEquals(listOf(later.id), history.getValue("second-send").map { it.asset.id })
        assertNull(history["plain-followup"])
        assertTrue(db.scheduleMediaDao().observeMessageAttachments(session, 1).first().isEmpty())
        assertTrue(requireNotNull(media.fileFor(document.id)).readText() == "private document")
    }

    @Test fun failedMediaMessageCommitRollsBackInputAndPendingSelectionsTogether() = runBlocking {
        val asset = add(image(Color.RED), "one.png", "image/png")
        val initial = conversations.openConversation(session)
        val draft = conversations.saveInput(session, "明天处理图片", initial.revision)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_send_media BEFORE INSERT ON schedule_attachments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { conversations.sendUserMessage(session, "retry-send", draft.inputText, draft.revision) }.isFailure)
        assertEquals(draft, conversations.openConversation(session))
        assertTrue(schedules.recentMessages(session).isEmpty())
        assertTrue(attachments.observe(session).first().single().link.pendingSend)
        assertTrue(requireNotNull(media.fileFor(asset.id)).exists())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_send_media")
        conversations.sendUserMessage(session, "retry-send", draft.inputText, draft.revision)
        assertFalse(attachments.observe(session).first().single().link.pendingSend)
        assertEquals(1, db.scheduleMediaDao().observeMessageAttachments(session, 40).first().size)
    }

    @Test fun cancellingReselectedMediaDoesNotRemoveSentHistoryOrTheTaskCover() = runBlocking {
        val asset = add(image(Color.RED), "one.png", "image/png")
        val draft = conversations.openConversation(session)
        conversations.sendUserMessage(session, "sent", "处理图片", draft.revision)
        schedules.createTask(session, "create", ScheduleDraft("处理图片", date = day))
        attachments.attach(session, asset)
        assertTrue(attachments.observe(session).first().single().link.pendingSend)
        attachments.discardPending(session, asset.id)
        assertEquals(asset.id, schedules.taskForSession(session)?.coverAssetId)
        assertFalse(attachments.observe(session).first().single().link.pendingSend)
        val extra = add(image(Color.BLUE), "discard.png", "image/png")
        val extraFile = requireNotNull(media.fileFor(extra.id))
        attachments.discardPending(session, extra.id); media.cleanPendingDeletions()
        assertFalse(extraFile.exists())
        attachments.remove(session, asset.id); media.cleanPendingDeletions()
        assertTrue(requireNotNull(media.fileFor(asset.id)).exists())
        assertEquals(asset.id, db.scheduleMediaDao().observeMessageAttachments(session, 40).first().single().asset.id)
        val task = requireNotNull(schedules.taskForSession(session))
        schedules.deleteTask(session, "delete", task.revision); media.cleanPendingDeletions()
        assertNull(media.fileFor(asset.id))
    }

    @Test fun imageOnlyMessageIsRecordedThenTextCreatesOneTaskWithoutResendingImages() = runBlocking {
        add(image(Color.RED), "one.png", "image/png")
        add(image(Color.BLUE), "two.png", "image/png")
        val draft = conversations.openConversation(session)
        val next = conversations.sendUserMessage(session, "only-images", "", draft.revision)
        var calls = 0
        val coordinator = ScheduleCreationCoordinator(db, schedules, conversations, ScheduleTextInterpreter { input ->
            calls++
            assertEquals(ScheduleAttachmentSummary(2, 2, 0), input.messageAttachments["only-images"])
            if (calls == 1) {
                assertEquals("", input.messages.first().content)
                ScheduleInterpretation.Ask("想为这些图片安排什么事情？")
            } else {
                assertNull(input.messageAttachments["with-text"])
                ScheduleInterpretation.Save(ScheduleDraft("处理图片事项", date = day), answeredQuestionIds = input.questions.map { it.id }.toSet())
            }
        })
        coordinator.process(session, "only-images")
        assertNull(schedules.taskForSession(session))
        conversations.sendUserMessage(session, "with-text", "今天晚上做这两个图片的事", next.revision)
        coordinator.process(session, "with-text")
        assertEquals(1, schedules.observeDate(day).first().size)
        assertEquals(2, db.scheduleMediaDao().observeMessageAttachments(session, 40).first().size)
        assertTrue(conversations.observePendingQuestions(session).first().isEmpty())
    }

    @Test fun schemaSixMigrationKeepsDraftPhotosAndDoesNotPrefillSavedTaskPhotos() = runBlocking {
        val original = File(context.filesDir, "media/originals/legacy.png").apply { parentFile!!.mkdirs(); writeBytes(image(Color.RED)) }
        db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/6.json"
        val schema = JSONObject((File(path).takeIf { it.exists() } ?: File("app/$path")).readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(MebotDatabase.FILE_NAME), null).use { old ->
            val tables = schema.getJSONArray("entities")
            for (index in 0 until tables.length()) {
                val table = tables.getJSONObject(index); val name = table.getString("tableName")
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                table.optJSONArray("indices")?.let { indices -> for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name)) }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            old.execSQL("INSERT INTO schedule_sessions VALUES ('saved',100,200),('draft',100,201)")
            old.execSQL("INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('photo','IMPORTED','media/originals/legacy.png','旧图.png', 'image/png',50,24,16,100)")
            old.execSQL("INSERT INTO schedule_local_attachments VALUES ('saved','photo','旧图.png',1,100,1),('draft','photo','草稿图.png',1,100,1)")
            old.execSQL("INSERT INTO schedule_tasks VALUES ('task','saved','开会','备注',${day.toEpochDay()},NULL,'photo',1,100,200,NULL)")
            old.execSQL("INSERT INTO schedule_task_versions VALUES ('task',1,${day.toEpochDay()},'开会','备注',${day.toEpochDay()},NULL,'photo',100)")
            old.execSQL("INSERT INTO schedule_conversation_drafts VALUES ('draft','待发送文字',3,201)")
            old.execSQL("INSERT INTO model_settings VALUES (1,'qwen3.8-flash','https://example.com','z-image-turbo','https://example.com',768,X'010203',X'040506',2,100)")
            old.version = 6
        }
        db = MebotDatabase.create(context); connect()
        assertFalse(attachments.observe("saved").first().single().link.pendingSend)
        assertFalse(attachments.observe("saved").first().single().link.draftOnly)
        assertTrue(attachments.observe("draft").first().single().link.pendingSend)
        assertEquals("待发送文字", conversations.openCreation().inputText)
        assertEquals("photo", schedules.taskForSession("saved")?.coverAssetId)
        assertTrue(original.exists())
        db.openHelper.readableDatabase.query("SELECT keyCiphertext FROM model_settings WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getBlob(0).contentEquals(byteArrayOf(1, 2, 3)))
        }
    }

    @Test fun actualSchemaFourUpgradesWithOldAttachmentTaskDraftAndEncryptedKeyIntact() = runBlocking {
        db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME)
        val path = "schemas/com.txl.mebot.data.local.MebotDatabase/4.json"
        val schemaFile = File(path).takeIf { it.exists() } ?: File("app/$path")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val file = context.getDatabasePath(MebotDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val tables = schema.getJSONArray("entities")
            for (index in 0 until tables.length()) {
                val table = tables.getJSONObject(index); val name = table.getString("tableName")
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                table.optJSONArray("indices")?.let { indices -> for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name)) }
            }
            schema.getJSONArray("setupQueries").let { queries -> for (i in 0 until queries.length()) old.execSQL(queries.getString(i)) }
            old.execSQL("INSERT INTO schedule_sessions VALUES ('legacy',100,200)")
            old.execSQL("INSERT INTO schedule_messages VALUES ('msg','legacy',1,'USER','开会',100,NULL)")
            old.execSQL("INSERT INTO media_assets (id,source,relativePath,originalName,mimeType,byteCount,width,height,createdAt) VALUES ('photo','IMPORTED','media/originals/photo.png','旧图.png','image/png',50,10,10,100)")
            old.execSQL("INSERT INTO schedule_attachments VALUES ('legacy','photo','msg',100)")
            old.execSQL("INSERT INTO schedule_tasks VALUES ('task','legacy','开会','备注',${day.toEpochDay()},NULL,'photo',1,100,200,NULL)")
            old.execSQL("INSERT INTO schedule_task_versions VALUES ('task',1,${day.toEpochDay()},'开会','备注',${day.toEpochDay()},NULL,'photo',100)")
            old.execSQL("INSERT INTO schedule_conversation_drafts VALUES ('legacy','未发草稿',7,200)")
            old.execSQL("INSERT INTO model_settings VALUES (1,'qwen3.8-flash','https://example.com','z-image-turbo','https://example.com',768,X'010203',X'040506',2,100)")
            old.version = 4
        }
        db = MebotDatabase.create(context); connect()
        val files = attachments.observe("legacy").first()
        assertEquals("旧图.png", files.single().link.displayName)
        assertTrue(files.single().link.isCover)
        assertEquals("photo", schedules.taskForSession("legacy")?.coverAssetId)
        assertEquals("未发草稿", conversations.openConversation("legacy").inputText)
        assertEquals("开会", schedules.recentMessages("legacy").single().content)
        db.openHelper.readableDatabase.query("SELECT keyCiphertext,keyIv FROM model_settings WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getBlob(0).contentEquals(byteArrayOf(1, 2, 3))); assertTrue(it.getBlob(1).contentEquals(byteArrayOf(4, 5, 6)))
        }
    }
}
