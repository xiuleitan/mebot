package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.subscriptions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionAttachmentStorageTest {
    private lateinit var context: Context
    private lateinit var database: SubscriptionStorageTestDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var attachments: SubscriptionAttachmentRepository
    private lateinit var conversations: SubscriptionConversationRepository
    private lateinit var subscriptions: SubscriptionRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE)
        database = SubscriptionStorageTestDatabase.create(context)
        connect()
    }
    private fun connect() {
        media = LocalMediaStore(context, database)
        attachments = SubscriptionAttachmentRepository(context, database.storage(), media)
        conversations = SubscriptionConversationRepository(database.storage())
        subscriptions = SubscriptionRepository(database.storage())
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE)
        File(context.filesDir, "media").deleteRecursively()
        File(context.cacheDir, "media_previews").deleteRecursively()
    }
    private fun image(color: Int = Color.BLUE) = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
        }
        output.toByteArray()
    }
    private suspend fun add(id: String, bytes: ByteArray = image()) = attachments.importImage(id, { ByteArrayInputStream(bytes) }, "订阅订单.png")
    private fun values(cover: String?) = SubscriptionValues("云盘", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
        anchorDate = LocalDate.of(2026, 10, 31), nextBillingDate = LocalDate.of(2026, 10, 31), coverAssetId = cover)

    @Test fun screenshotIsCopiedToPersistentStorageAndSurvivesReopenAndCacheClearing() = runBlocking {
        val session = conversations.newConversation()
        val source = File(context.cacheDir, "temporary-subscription.png").apply { writeBytes(image()) }
        val originalBytes = source.readBytes()
        val saved = attachments.importImage(session.id, { source.inputStream() }, source.name)
        source.delete()
        assertTrue(saved.relativePath!!.startsWith("media/originals/"))
        assertArrayEquals(originalBytes, media.fileFor(saved.id)!!.readBytes())
        File(context.cacheDir, "media_previews").apply { mkdirs(); resolve("thumbnail").writeText("derived") }
        media.clearDerivedCache()
        database.close()
        database = SubscriptionStorageTestDatabase.create(context)
        connect()
        assertArrayEquals(originalBytes, media.fileFor(saved.id)!!.readBytes())
        val link = attachments.observe(session.id).first().single()
        assertEquals(saved.id, link.asset.id)
        assertEquals("temporary-subscription.png", link.link.displayName)
        assertTrue(link.link.pendingSend)
    }

    @Test fun identicalScreenshotsShareOneFileWhileTheirConversationLinksStayIndependent() = runBlocking {
        val a = conversations.newConversation()
        val b = conversations.newConversation()
        val bytes = image()
        val first = add(a.id, bytes)
        val second = add(b.id, bytes)
        assertEquals(first.id, second.id)
        assertEquals(1, attachments.observe(a.id).first().size)
        assertEquals(1, attachments.observe(b.id).first().size)
        attachments.discardPending(a.id, first.id)
        media.cleanPendingDeletions()
        assertTrue(attachments.observe(a.id).first().isEmpty())
        assertNotNull(media.fileFor(second.id))
        assertNotNull(database.mediaDao().asset(second.id))
        assertEquals(second.id, attachments.observe(b.id).first().single().asset.id)
    }

    @Test fun manualCoverImportDoesNotBecomeAnUnrequestedChatUpload() = runBlocking {
        val session = conversations.newConversation()
        val asset = attachments.importImage(session.id, { ByteArrayInputStream(image()) }, "封面.png", asPending = false)
        assertFalse(attachments.observe(session.id).first().single().link.pendingSend)
        attachments.attach(session.id, asset.id, "用户后来明确选择发送")
        assertTrue(attachments.observe(session.id).first().single().link.pendingSend)
    }

    @Test fun sendingStoresImageReferencesAndRetryDoesNotConsumeNewPendingInput() = runBlocking {
        val session = conversations.newConversation()
        val first = add(session.id)
        val draft = conversations.saveDraft(session.id, "从这张图提取", "{}", 0)
        val message = conversations.sendUserMessage(session.id, "user", draft.inputText, listOf(first.id), draft.revision)
        val later = add(session.id, image(Color.RED))
        val newer = conversations.saveDraft(session.id, "下一句话", "{}", draft.revision + 1)
        assertEquals(message, conversations.sendUserMessage(session.id, "user", draft.inputText, listOf(first.id), draft.revision))
        assertEquals(newer, database.subscriptionConversations().draft(session.id))
        assertEquals(listOf(first.id), database.subscriptionConversations().messageMedia(message.id).map { it.assetId })
        assertTrue(attachments.observe(session.id).first().first { it.asset.id == later.id }.link.pendingSend)
        attachments.attach(session.id, first.id, "重新选择")
        attachments.discardPending(session.id, first.id)
        media.cleanPendingDeletions()
        assertNotNull(media.fileFor(first.id))
        assertEquals(2, attachments.observe(session.id).first().size)
    }

    @Test fun anotherConversationsImageCannotBeSentOrBecomeTheCover() = runBlocking {
        val a = conversations.newConversation()
        val b = conversations.newConversation()
        val asset = add(a.id)
        val draft = conversations.saveDraft(b.id, "不应该成功", "{}", 0)
        assertTrue(runCatching { conversations.sendUserMessage(b.id, "wrong", draft.inputText, listOf(asset.id), draft.revision) }.isFailure)
        assertTrue(runCatching { subscriptions.create("wrong-cover", values(asset.id), b.id) }.isFailure)
        assertEquals(draft, database.subscriptionConversations().draft(b.id))
        assertNull(database.subscriptionConversations().message("wrong"))
        assertTrue(database.subscriptions().all().isEmpty())
    }

    @Test fun attachmentWriteFailureRollsBackMessageAndKeepsUnsentInputAndImage() = runBlocking {
        val session = conversations.newConversation()
        val asset = add(session.id)
        val draft = conversations.saveDraft(session.id, "保存失败时不能丢掉", "{}", 0)
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_message_media
            BEFORE INSERT ON subscription_message_media BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { conversations.sendUserMessage(session.id, "user", draft.inputText, listOf(asset.id), draft.revision) }.isFailure)
        assertNull(database.subscriptionConversations().message("user"))
        assertEquals(draft, database.subscriptionConversations().draft(session.id))
        assertTrue(attachments.observe(session.id).first().single().link.pendingSend)
        assertNotNull(media.fileFor(asset.id))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_message_media")
        assertNotNull(conversations.sendUserMessage(session.id, "user", draft.inputText, listOf(asset.id), draft.revision))
    }

    @Test fun rawMessageAttachmentCannotCrossTheConversationForeignKey() = runBlocking {
        val a = conversations.newConversation()
        val b = conversations.newConversation()
        val asset = add(a.id)
        val reply = conversations.appendAssistantMessage(b.id, "reply", "另一个会话")
        assertTrue(runCatching { database.subscriptionConversations().insertMessageMedia(
            listOf(SubscriptionMessageMediaEntity(reply.id, b.id, asset.id, "错绑", 0))) }.isFailure)
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun historicalCoverStaysReferencedAfterCurrentCoverChangesAndDraftIsCleared() = runBlocking {
        val session = conversations.newConversation()
        val asset = add(session.id)
        val created = subscriptions.create("create", values(asset.id), session.id)
        subscriptions.update("update", created.id, 1, values(null), session.id)
        attachments.discardPending(session.id, asset.id)
        media.cleanPendingDeletions()
        assertTrue(attachments.observe(session.id).first().isEmpty())
        assertTrue(database.subscriptions().referencesMedia(asset.id))
        assertNotNull(media.fileFor(asset.id))
        assertEquals(asset.id, subscriptions.history(created.id).last().afterCoverAssetId)
        assertEquals(0, database.mediaDao().deleteIfUnused(asset.id))
        // 公共清理保护和历史外键共同保留原图。
        assertTrue(runCatching { database.openHelper.writableDatabase.execSQL("DELETE FROM media_assets WHERE id = ?", arrayOf(asset.id)) }.isFailure)
    }

    @Test fun discardingPendingScreenshotCannotDeleteAnotherBotsAttachment() = runBlocking {
        val session = conversations.newConversation()
        val asset = add(session.id)
        database.flashnoteDao().insertNote(FlashnoteEntity("flash", content = "其他 Bot 的原始内容", createdAt = 1, updatedAt = 1))
        database.flashnoteMediaDao().insertNote(FlashnoteAttachmentEntity("flash", asset.id, "共享原图", 0))
        attachments.discardPending(session.id, asset.id)
        media.cleanPendingDeletions()
        assertNotNull(database.mediaDao().asset(asset.id))
        assertNotNull(media.fileFor(asset.id))
    }

    @Test fun soleUnsentImageCanBeReleasedAndCleanupDoesNotTouchUnrelatedOriginals() = runBlocking {
        val a = conversations.newConversation()
        val b = conversations.newConversation()
        val removed = add(a.id)
        val retained = add(b.id, image(Color.GREEN))
        val oldPath = requireNotNull(media.fileFor(removed.id))
        attachments.discardPending(a.id, removed.id)
        assertNull(database.mediaDao().asset(removed.id))
        assertTrue(oldPath.exists()) // 文件删除队列在事务提交后执行。
        media.cleanPendingDeletions()
        assertFalse(oldPath.exists())
        assertNotNull(media.fileFor(retained.id))
    }

    @Test fun invalidImageOrFailedAssociationDoesNotLeaveHalfSavedAttachment() = runBlocking {
        val session = conversations.newConversation()
        assertTrue(runCatching { add(session.id, "not an image".toByteArray()) }.isFailure)
        assertTrue(attachments.observe(session.id).first().isEmpty())
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_subscription_attachment
            BEFORE INSERT ON subscription_conversation_media BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { add(session.id) }.isFailure)
        assertTrue(attachments.observe(session.id).first().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_subscription_attachment")
        media.cleanPendingDeletions()
        assertTrue(File(context.filesDir, "media/originals").walkTopDown().none { it.isFile })
        assertTrue(File(context.filesDir, "media/staging").walkTopDown().none { it.isFile })
        assertNotNull(add(session.id))
    }
}
