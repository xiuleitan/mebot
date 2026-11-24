package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.dayima.*
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayimaImagesTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var attachments: DayimaAttachmentRepository
    private lateinit var records: DayimaRepository
    private lateinit var chats: DayimaConversationRepository
    private lateinit var engine: DayimaChatCoordinator
    private val today = LocalDate.of(2026, 10, 28)
    private val clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC)
    private var calls = 0
    private var handler: suspend (DayimaAiContext) -> DayimaAiDecision = { DayimaAiDecision.Answer("测试回复") }

    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME); connect() }
    private fun connect() {
        db = MebotDatabase.create(context); media = LocalMediaStore(context, db, clock)
        attachments = DayimaAttachmentRepository(context, db, media, clock)
        records = DayimaRepository(db, clock) { ZoneOffset.UTC }; chats = DayimaConversationRepository(db, clock) { ZoneOffset.UTC }
        engine = DayimaChatCoordinator(db, records, chats, DayimaTextInterpreter { calls++; handler(it) }, clock) { ZoneOffset.UTC }
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }
    private fun image(color: Int = Color.BLUE) = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
        output.toByteArray()
    }
    private suspend fun add(id: String, color: Int = Color.BLUE) = attachments.importImage(chats.snapshot(id)!!.draft,
        { ByteArrayInputStream(image(color)) }, "本地照片.png")
    private suspend fun send(id: String, text: String): DayimaChatMessageEntity {
        val draft = chats.saveInput(id, text, chats.snapshot(id)!!.draft.revision)
        return chats.send(id, text, UUID.randomUUID().toString(), draft.revision)
    }
    private fun recordDecision(date: LocalDate = today) = DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(date, null)))

    @Test fun localOriginalAndUnsentDraftSurviveReopenSourceDeletionAndCacheClearing() = runBlocking {
        val chat = chats.open(new = true)
        val source = File(context.cacheDir, "dayima-source.png").apply { writeBytes(image()) }; val bytes = source.readBytes()
        val saved = attachments.importImage(chat.draft, { source.inputStream() }, source.name)
        val input = chats.saveInput(chat.session.id, "还没发送的输入", saved.draft.revision)
        source.delete(); media.clearDerivedCache(); db.close(); connect()
        val restored = chats.open(chat.session.id)
        assertEquals(input, restored.draft); assertEquals(saved.assetId, restored.images.single().asset.id)
        assertArrayEquals(bytes, media.fileFor(saved.assetId)!!.readBytes()); assertTrue(records.periods().isEmpty()); assertEquals(0, calls)
    }

    @Test fun sendFailureRollsBackMessageAndKeepsTextAndImagesForSameRequestRetry() = runBlocking {
        val chat = chats.open(new = true); val imported = add(chat.session.id)
        val draft = chats.saveInput(chat.session.id, "今天开始", imported.draft.revision)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_dayima_send BEFORE INSERT ON dayima_message_media BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { chats.send(chat.session.id, draft.inputText, "request", draft.revision) }.isFailure)
        assertNull(db.dayimaDao().message("request")); assertEquals(draft, chats.snapshot(chat.session.id)!!.draft)
        assertEquals(imported.assetId, chats.snapshot(chat.session.id)!!.images.single().asset.id)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_dayima_send")
        val sent = chats.send(chat.session.id, draft.inputText, "request", draft.revision)
        assertEquals(sent, chats.send(chat.session.id, draft.inputText, "request", draft.revision))
        assertTrue(chats.snapshot(chat.session.id)!!.images.isEmpty())
        assertEquals(1, chats.snapshot(chat.session.id)!!.messageImages.getValue("request").size)
    }

    @Test fun startAndImagesCommitTogetherAndRetryOrSameDayPhotoDoNotDuplicateTheDateOrLink() = runBlocking {
        val chat = chats.open(new = true); val id = chat.session.id; add(id)
        handler = { assertEquals(1, it.pendingImageCount); recordDecision() }
        val first = send(id, "今天来了"); engine.process(id, first.id); engine.process(id, first.id)
        val period = records.periods().single()
        assertEquals(1, calls); assertEquals(1, records.periodDetail(period.id)!!.images.size)
        add(id); val second = send(id, "今天已经开始了，补充这张照片"); engine.process(id, second.id)
        assertEquals(1, records.periods().size); assertEquals(1, records.periodDetail(period.id)!!.images.size)
        assertTrue(chats.snapshot(id)!!.images.isEmpty())
    }

    @Test fun imageOnlyAsksLocallyAndDateFollowupBindsTheOriginalMessageImage() = runBlocking {
        val period = (records.execute(DayimaChange.RecordPeriod(today.minusDays(27))) as DayimaWriteResult.PeriodSaved).period
        val chat = chats.open(new = true); val imported = add(chat.session.id)
        val first = send(chat.session.id, ""); engine.process(chat.session.id, first.id)
        assertEquals(0, calls); assertEquals(DayimaOperationState.WAITING_FOR_INPUT, chats.snapshot(chat.session.id)!!.operation!!.state)
        assertTrue(records.periodDetail(period.id)!!.images.isEmpty())
        handler = { assertEquals(1, it.pendingImageCount); DayimaAiDecision.Write(listOf(DayimaChatMutation.Images(DayimaTargetRef(period.startDate)))) }
        val followup = send(chat.session.id, "放在上次那条开始记录"); engine.process(chat.session.id, followup.id)
        assertEquals(listOf(imported.assetId), records.periodDetail(period.id)!!.images.map { it.asset.id })
        assertEquals(1, records.periods().size)
    }

    @Test fun imageBindingFailureRollsBackBusinessRecordAndOperationThenRetriesOnce() = runBlocking {
        val chat = chats.open(new = true); add(chat.session.id); val request = send(chat.session.id, "今天来了")
        handler = { recordDecision() }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_dayima_link BEFORE INSERT ON dayima_period_media BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { engine.process(chat.session.id, request.id) }.isFailure)
        assertTrue(records.periods().isEmpty()); assertNotNull(chats.snapshot(chat.session.id)!!.messageImages[request.id])
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_dayima_link")
        engine.process(chat.session.id, request.id); engine.process(chat.session.id, request.id)
        assertEquals(1, records.periods().size); assertEquals(1, records.periodDetail(records.periods().single().id)!!.images.size)
    }

    @Test fun multipleRecordTargetsAskAndRollBackAllMutationsUntilAUniqueTargetIsGiven() = runBlocking {
        val chat = chats.open(new = true); add(chat.session.id)
        handler = { DayimaAiDecision.Write(listOf(DayimaChatMutation.Record(today.minusDays(28), null), DayimaChatMutation.Record(today, null))) }
        val request = send(chat.session.id, "补记两次开始，并保存照片"); engine.process(chat.session.id, request.id)
        assertTrue(records.periods().isEmpty()); assertEquals(DayimaOperationState.WAITING_FOR_INPUT, chats.snapshot(chat.session.id)!!.operation!!.state)
    }

    @Test fun photoCaptionAloneCannotCreateAStartEvenWhenTheModelSuggestsOne() = runBlocking {
        val chat = chats.open(new = true); add(chat.session.id); handler = { recordDecision() }
        val caption = send(chat.session.id, "今天的照片"); engine.process(chat.session.id, caption.id)
        assertTrue(records.periods().isEmpty()); assertEquals(DayimaOperationState.WAITING_FOR_INPUT, chats.snapshot(chat.session.id)!!.operation!!.state)
        val confirmed = send(chat.session.id, "是今天实际开始了"); engine.process(chat.session.id, confirmed.id)
        assertEquals(1, records.periods().size); assertEquals(1, records.periodDetail(records.periods().single().id)!!.images.size)
    }

    @Test fun draftMessageAndPeriodReferencesEachProtectTheSharedAssetAndDeletingChatKeepsRecordImages() = runBlocking {
        val chat = chats.open(new = true); val imported = add(chat.session.id)
        assertEquals(0, db.mediaDao().deleteIfUnused(imported.assetId))
        handler = { recordDecision() }; val message = send(chat.session.id, "今天来了")
        assertEquals(0, db.mediaDao().deleteIfUnused(imported.assetId)); engine.process(chat.session.id, message.id)
        val period = records.periods().single(); chats.delete(chat.session.id); media.cleanPendingDeletions()
        assertNotNull(media.fileFor(imported.assetId)); assertEquals(0, db.mediaDao().deleteIfUnused(imported.assetId))
        assertEquals(imported.assetId, records.periodDetail(period.id)!!.images.single().asset.id)
        db.openHelper.readableDatabase.query("SELECT sourceMessageId FROM dayima_period_media").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
        records.execute(DayimaChange.DeletePeriod(period.id, period.revision)); media.cleanPendingDeletions()
        assertNull(media.fileFor(imported.assetId)); assertNull(db.mediaDao().asset(imported.assetId))
    }

    @Test fun deletingRecordKeepsChatAndAnotherBotsMediaThenLastReferenceAllowsCleanup() = runBlocking {
        val chat = chats.open(new = true); val imported = add(chat.session.id)
        val notes = FlashnoteRepository(db); val other = notes.attach(notes.openCreation(), db.mediaDao().asset(imported.assetId)!!, "共享图片")
        handler = { recordDecision() }; val request = send(chat.session.id, "今天开始"); engine.process(chat.session.id, request.id)
        val period = records.periods().single(); records.execute(DayimaChange.DeletePeriod(period.id, period.revision))
        assertNotNull(chats.snapshot(chat.session.id)!!.messageImages[request.id]); chats.delete(chat.session.id); media.cleanPendingDeletions()
        assertNotNull(media.fileFor(imported.assetId)); notes.discardDraft(other.key, other.revision); media.cleanPendingDeletions()
        assertNull(media.fileFor(imported.assetId))
    }

    @Test fun newChatCannotConsumeAnotherChatsImagesAndForgedSourceIsRejected() = runBlocking {
        val first = chats.open(new = true); val imported = add(first.session.id)
        val second = chats.open(new = true)
        handler = { assertEquals(0, it.pendingImageCount); DayimaAiDecision.Write(listOf(DayimaChatMutation.Images(DayimaTargetRef(today)))) }
        val request = send(second.session.id, "把照片添加到今天的记录"); engine.process(second.session.id, request.id)
        assertTrue(records.periods().isEmpty()); assertNotNull(media.fileFor(imported.assetId))
        chats.open(first.session.id); val original = send(first.session.id, "图片消息")
        chats.open(second.session.id); val current = send(second.session.id, "今天开始")
        val op = db.dayimaDao().operation(current.id)!!
        db.dayimaDao().updateOperation(op.requestMessageId, op.state, op.action, op.question,
            JSONObject(op.resultJson!!).put("media_sources", JSONArray().put(original.id)).toString(), null, null, clock.millis(), op.revision)
        assertTrue(runCatching { engine.process(second.session.id, current.id) }.isFailure); assertTrue(records.periods().isEmpty())
    }

    @Test fun changedTargetOrDeletedChatRejectsLateImageResults() = runBlocking {
        val period = (records.execute(DayimaChange.RecordPeriod(today.minusDays(27))) as DayimaWriteResult.PeriodSaved).period
        val chat = chats.open(new = true); add(chat.session.id)
        handler = { records.execute(DayimaChange.EditPeriod(period.id, period.revision, period.startDate.plusDays(1))); DayimaAiDecision.Write(listOf(DayimaChatMutation.Images(DayimaTargetRef(period.startDate)))) }
        val request = send(chat.session.id, "保存到上次记录")
        assertTrue(runCatching { engine.process(chat.session.id, request.id) }.isFailure); assertTrue(records.periodDetail(period.id)!!.images.isEmpty())
        add(chat.session.id, Color.RED); handler = { chats.delete(chat.session.id); recordDecision() }
        val late = send(chat.session.id, "今天来了")
        assertTrue(runCatching { engine.process(chat.session.id, late.id) }.isFailure); assertEquals(1, records.periods().size)
    }

    @Test fun invalidOversizedAndStaleImportsDoNotChangeInputOrLeavePartialReferences() = runBlocking {
        val chat = chats.open(new = true); val draft = chats.saveInput(chat.session.id, "必须保留的输入", chat.draft.revision)
        assertTrue(runCatching { attachments.importImage(draft, { ByteArrayInputStream("not an image".toByteArray()) }) }.isFailure)
        val large = object : InputStream() {
            var remaining = 50L * 1024 * 1024 + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(length.toLong(), remaining).toInt(); bytes.fill(0, offset, offset + n); remaining -= n; return n
            }
        }
        assertTrue(runCatching { attachments.importImage(draft, { large }) }.isFailure)
        assertTrue(runCatching { attachments.importImage(chat.draft, { ByteArrayInputStream(image()) }) }.isFailure)
        assertEquals(draft, chats.snapshot(chat.session.id)!!.draft); assertTrue(chats.snapshot(chat.session.id)!!.images.isEmpty())
        assertTrue(db.openHelper.readableDatabase.query("SELECT id FROM media_assets").use { !it.moveToFirst() })
    }

    @Test fun cancellingPhotoAssociationDoesNotCreateAStartOrAttachOnTheNextRequest() = runBlocking {
        val chat = chats.open(new = true); add(chat.session.id); val first = send(chat.session.id, ""); engine.process(chat.session.id, first.id)
        val cancel = send(chat.session.id, "取消保存这些照片"); engine.process(chat.session.id, cancel.id)
        handler = { assertEquals(0, it.pendingImageCount); recordDecision() }
        val next = send(chat.session.id, "今天来了"); engine.process(chat.session.id, next.id)
        assertTrue(records.periodDetail(records.periods().single().id)!!.images.isEmpty())
    }
}
