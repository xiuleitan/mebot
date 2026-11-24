package com.txl.mebot.data

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeDispatchCoordinatorTest {
    private lateinit var app: Application
    private lateinit var container: AppContainer
    private lateinit var dispatcher: HomeDispatchCoordinator
    private lateinit var archive: ArchiveRepository
    private lateinit var archiveFiles: ArchiveAttachmentRepository
    private val db get() = container.database
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        container = AppContainer(app)
        val storage = ArchiveStorage(db, db.archives(), ArchiveCipher { SecretKeySpec(ByteArray(32) { 6 }, "AES") })
        archive = ArchiveRepository(storage); archiveFiles = ArchiveAttachmentRepository(app, storage)
        dispatcher = HomeDispatchCoordinator(app, container, archive, archiveFiles)
    }
    @After fun close() {
        container.stopMaintenance(); db.close(); app.deleteDatabase(MebotDatabase.FILE_NAME)
        File(app.filesDir, "media").deleteRecursively(); File(app.filesDir, "archive").deleteRecursively()
    }
    private fun image() = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }; out.toByteArray()
    }
    private suspend fun input(text: String, files: Boolean = false): HomeSendSnapshot {
        val old = container.homeComposer.read()
        val draft = container.homeComposer.saveInput(text, old.revision)
        container.media.importImage({ ByteArrayInputStream(image()) }, originalName = "图.png") { container.homeComposer.attach(it, "图.png") }
        if (files) container.media.importDocument({ ByteArrayInputStream("资料内容".toByteArray()) }, "资料.txt", "text/plain") { container.homeComposer.attach(it, "资料.txt") }
        return HomeSendSnapshot(text, draft.revision, container.homeComposer.attachments())
    }
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
    @Test fun everyBotReceivesOneIndependentTaskAndRepeatedSubmissionNeverDuplicatesIt() = runBlocking {
        val tables = mapOf(HomeBot.SCHEDULE to "schedule_messages", HomeBot.FLASHNOTE to "flashnotes", HomeBot.DAYIMA to "dayima_chat_messages",
            HomeBot.SUBSCRIPTIONS to "subscription_messages", HomeBot.ARCHIVE to "archive_messages")
        for (bot in HomeBot.entries) {
            val source = input("@${bot.label} 本次 ${bot.label} 任务", files = bot == HomeBot.ARCHIVE)
            val ticket = dispatcher.dispatch(bot, source)
            assertTrue(ticket.handedOff); assertEquals(bot.id, ticket.botId)
            assertEquals(ticket, dispatcher.dispatch(bot, source))
            assertEquals(bot.label, 1, count(tables.getValue(bot)))
            assertEquals("", container.homeComposer.read().inputText); assertTrue(container.homeComposer.attachments().isEmpty())
            val body = HomeBotMention.parse(source.text).body
            if (bot == HomeBot.FLASHNOTE) {
                assertTrue(ticket.dismissed); assertEquals(body, container.flashnotes.note(ticket.targetId)!!.content)
            } else {
                if (bot == HomeBot.ARCHIVE) assertEquals(body, archive.context(ticket.targetId).messages.single().text)
                else db.openHelper.readableDatabase.query("SELECT content FROM `${tables.getValue(bot)}`").use {
                    assertTrue(it.moveToFirst()); assertEquals(body, it.getString(0))
                }
                assertEquals(ticket, dispatcher.active()); dispatcher.dismiss(ticket.id)
            }
            assertNull(dispatcher.active())
        }
    }
    @Test fun invalidOrMismatchedMentionCannotDispatchOrLoseItsDraftAndAttachments() = runBlocking {
        for (text in listOf("@不存在 明天买菜", "@闪念 明天买菜")) {
            val source = input(text)
            assertTrue(runCatching { dispatcher.dispatch(HomeBot.SCHEDULE, source) }.isFailure)
            assertEquals(text, container.homeComposer.read().inputText)
            assertEquals(source.attachments, container.homeComposer.attachments())
            assertEquals(0, count("home_dispatches")); assertEquals(0, count("schedule_messages"))
        }
    }
    @Test fun existingBotDraftsArePreservedWhenHomeCreatesItsOwnTask() = runBlocking {
        val schedule = container.conversations.openCreation().let { container.conversations.saveInput(it.sessionId, "日程中未发的文字", it.revision) }
        val note = container.flashnotes.openCreation().let { container.flashnotes.saveInput(it.key, "", "闪念中未保存的文字", it.revision) }
        val sub = container.subscriptionConversations.newConversation()
        val subDraft = container.subscriptionConversations.saveDraft(sub.id, "订阅中未发的文字", "{}", 0)
        val dayima = container.dayimaConversations.open(new = true).draft.let { container.dayimaConversations.saveInput(it.sessionId, "经期中未发的文字", it.revision) }
        for (bot in HomeBot.entries.filter { it != HomeBot.ARCHIVE }) {
            dispatcher.dispatch(bot, input("从首页发给 ${bot.label}"))
            assertEquals(schedule, db.conversationDao().draft(schedule.sessionId))
            assertEquals(note, container.flashnotes.draft(note.key))
            assertEquals(subDraft, db.subscriptionConversations().draft(sub.id))
            assertEquals(dayima, db.dayimaDao().draft(dayima.sessionId))
        }
    }
    @Test fun failedHomeClearRollsBackBotMessageAndAttachmentsThenRetryUsesTheSameTicket() = runBlocking {
        val source = input("明天买菜")
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_handoff BEFORE UPDATE ON home_composer_drafts
            WHEN NEW.inputText='' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { dispatcher.dispatch(HomeBot.SCHEDULE, source) }.isFailure)
        assertEquals(0, count("schedule_messages")); assertEquals(0, count("schedule_sessions"))
        assertEquals(source.text, container.homeComposer.read().inputText)
        assertEquals(source.attachments, container.homeComposer.attachments())
        val pendingId = db.openHelper.readableDatabase.query("SELECT id FROM home_dispatches").use { it.moveToFirst(); it.getString(0) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_handoff")
        val delivered = dispatcher.dispatch(HomeBot.SCHEDULE, source)
        assertEquals(pendingId, delivered.id); assertEquals(1, count("schedule_messages"))
    }
    @Test fun regularScheduleCreationCannotDiscardAnUnfinishedHomeConversation() = runBlocking {
        val regular = container.conversations.openCreation().let { container.conversations.saveInput(it.sessionId, "原有的日程草稿", it.revision) }
        val ticket = dispatcher.dispatch(HomeBot.SCHEDULE, input("明天提醒我开会"))
        assertEquals(regular.sessionId, container.conversations.openCreation().sessionId)
        assertEquals(regular.inputText, db.conversationDao().draft(regular.sessionId)!!.inputText)
        container.conversations.clearCreation()
        assertNotNull(db.scheduleDao().session(ticket.targetId))
        assertEquals(1, db.conversationDao().contextMessages(ticket.targetId, Long.MAX_VALUE, 10).size)
        assertEquals(1, db.scheduleMediaDao().attachments(ticket.targetId).size)
    }
    @Test fun unsupportedTargetAttachmentsLeaveOriginalInputUntouched() = runBlocking {
        val source = input("记录订阅", files = true)
        for (bot in listOf(HomeBot.SUBSCRIPTIONS, HomeBot.DAYIMA)) assertTrue(runCatching { dispatcher.dispatch(bot, source) }.isFailure)
        assertEquals(source.text, container.homeComposer.read().inputText)
        assertEquals(source.attachments, container.homeComposer.attachments()); assertEquals(0, count("home_dispatches"))
        dispatcher.dispatch(HomeBot.FLASHNOTE, source)
        assertEquals(2, container.flashnotes.noteAttachments(db.openHelper.readableDatabase.query("SELECT targetId FROM home_dispatches").use { it.moveToFirst(); it.getString(0) }).size)
    }
    @Test fun archiveKeepsEncryptedCopiesReadableAfterSourceMediaIsReleased() = runBlocking {
        val source = input("保存资料", files = true)
        val expected = source.attachments.map { container.media.fileFor(it.asset.id)!!.readBytes() }
        val ticket = dispatcher.dispatch(HomeBot.ARCHIVE, source)
        val current = archive.context(ticket.targetId)
        assertEquals(2, current.assets.size)
        assertEquals(source.text, current.messages.single().text)
        for ((index, asset) in current.assets.withIndex()) {
            archiveFiles.withDecryptedFile(asset.id) { assertArrayEquals(expected[index], it.readBytes()) }
            assertNull(db.mediaDao().asset(source.attachments[index].asset.id))
        }
        container.media.cleanPendingDeletions()
        for ((index, asset) in current.assets.withIndex()) archiveFiles.withDecryptedFile(asset.id) { assertArrayEquals(expected[index], it.readBytes()) }
    }
}
