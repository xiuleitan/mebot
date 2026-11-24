package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.subscriptions.SubscriptionEditor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
class SubscriptionImagesTest {
    private lateinit var context: Context
    private lateinit var db: SubscriptionStorageTestDatabase
    private lateinit var records: SubscriptionRepository
    private lateinit var media: LocalMediaStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE)
        db = SubscriptionStorageTestDatabase.create(context); connect()
    }
    private fun connect() { records = SubscriptionRepository(db.storage()); media = LocalMediaStore(context, db) }
    @After fun teardown() {
        db.close(); context.deleteDatabase(SubscriptionStorageTestDatabase.TEST_FILE)
        File(context.filesDir, "media").deleteRecursively(); File(context.cacheDir, "media_previews").deleteRecursively()
    }
    private suspend fun image(color: Int): String {
        val bytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
            }; output.toByteArray()
        }
        return media.importImage({ ByteArrayInputStream(bytes) }).id
    }
    private fun values(images: List<String>? = null, cover: String? = null) = SubscriptionValues("图片订阅", 600, "CNY", SubscriptionCycleUnit.NONE, 0,
        coverAssetId = cover, imageAssetIds = images)
    private suspend fun replace(item: SubscriptionEntity, ids: List<String>, cover: String? = null, request: String = UUID.randomUUID().toString()) =
        records.update(request, item.id, item.revision, SubscriptionValues.from(item).copy(imageAssetIds = ids, coverAssetId = cover))

    @Test fun firstSelectionIsDefaultCoverOrderAndDedupSurviveReopeningAndCacheClearing() = runBlocking {
        val a = image(Color.RED); val b = image(Color.GREEN); val c = image(Color.BLUE)
        val created = records.create("create", values(listOf(b, a, b, c)))
        assertEquals(b, created.coverAssetId); assertEquals(listOf(b, a, c), records.imageIds(created.id))
        media.clearDerivedCache(); db.close(); db = SubscriptionStorageTestDatabase.create(context); connect()
        assertEquals(created, records.get(created.id)); assertEquals(listOf(b, a, c), records.imageIds(created.id))
        listOf(a, b, c).forEach { assertNotNull(media.fileFor(it)) }
    }
    @Test fun addingChangingCoverAndNonImageUpdatesKeepOriginalOrderAndCompleteHistory() = runBlocking {
        val a = image(Color.RED); val b = image(Color.GREEN); val c = image(Color.BLUE)
        var item = records.create("create", values(listOf(a, b)))
        item = replace(item, listOf(a, b, c), a)
        assertEquals(a, item.coverAssetId)
        item = replace(item, listOf(a, b, c), c)
        assertEquals(c, item.coverAssetId); assertEquals(listOf(a, b, c), records.imageIds(item.id))
        item = records.update("text", item.id, item.revision, SubscriptionValues.from(item).copy(notes = "只改备注"))
        assertEquals(listOf(a, b, c), records.imageIds(item.id)); assertEquals(c, item.coverAssetId)
        val history = records.history(item.id)
        assertEquals(4, history.size)
        assertEquals(3, JSONObject(history[1].afterJson).getJSONArray("imageAssetIds").length())
        assertEquals(a, JSONObject(history[1].beforeJson!!).getString("coverAssetId"))
    }
    @Test fun removingCoverFallsBackToFirstRemainingAndRemovingLastClearsGalleryAndCover() = runBlocking {
        val a = image(Color.RED); val b = image(Color.GREEN); val c = image(Color.BLUE)
        var item = records.create("create", values(listOf(a, b, c), b))
        item = replace(item, listOf(a, c))
        assertEquals(a, item.coverAssetId)
        item = replace(item, listOf(c))
        assertEquals(c, item.coverAssetId)
        item = replace(item, emptyList())
        assertNull(item.coverAssetId); assertTrue(records.imageIds(item.id).isEmpty())
        assertEquals(4, records.history(item.id).size)
    }
    @Test fun failedImageLinkWriteRollsBackCoverRevisionReceiptAndHistoryAndSameRequestCanRetry() = runBlocking {
        val a = image(Color.RED); val b = image(Color.GREEN)
        val item = records.create("create", values(listOf(a)))
        val update = SubscriptionValues.from(item).copy(imageAssetIds = listOf(a, b), coverAssetId = b)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_gallery BEFORE INSERT ON subscription_images
            BEGIN SELECT RAISE(ABORT,'fixture failure'); END""")
        assertTrue(runCatching { records.update("retry", item.id, 1, update) }.isFailure)
        assertEquals(item, records.get(item.id)); assertEquals(listOf(a), records.imageIds(item.id))
        assertNull(db.subscriptions().operation("retry")); assertEquals(1, records.history(item.id).size)
        assertNotNull(media.fileFor(b))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_gallery")
        val changed = records.update("retry", item.id, 1, update)
        assertEquals(changed, records.update("retry", item.id, 1, update))
        assertEquals(b, changed.coverAssetId); assertEquals(listOf(a, b), records.imageIds(item.id)); assertEquals(2, records.history(item.id).size)
    }
    @Test fun invalidAndStaleGalleryChangesCannotDiscardExistingImages() = runBlocking {
        val a = image(Color.RED); val b = image(Color.GREEN)
        val item = records.create("create", values(listOf(a, b)))
        assertTrue(runCatching { replace(item, listOf(a, "missing")) }.isFailure)
        assertTrue(runCatching { replace(item, listOf(a), b) }.isFailure)
        val changed = records.update("text", item.id, 1, SubscriptionValues.from(item).copy(notes = "新版"))
        assertTrue(runCatching { replace(item, listOf(b)) }.exceptionOrNull() is SubscriptionConflict)
        assertEquals(changed, records.get(item.id)); assertEquals(listOf(a, b), records.imageIds(item.id))
        assertEquals(2, records.history(item.id).size)
    }
    @Test fun cleanupProtectsNonCoverGalleryImagesAndOtherBotsWhileReleasingUnreferencedDeletedImage() = runBlocking {
        val a = image(Color.RED); val shared = image(Color.GREEN); val deleted = image(Color.BLUE)
        val item = records.create("create", values(listOf(a, shared, deleted)))
        assertEquals(0, db.mediaDao().deleteIfUnused(shared)); assertEquals(0, db.mediaDao().deleteIfUnused(deleted))
        db.flashnoteDao().insertNote(FlashnoteEntity("flash", content = "其他Bot", createdAt = 1, updatedAt = 1))
        db.flashnoteMediaDao().insertNote(FlashnoteAttachmentEntity("flash", shared, "共用原图", 0))
        val oldFile = media.fileFor(deleted)!!
        replace(item, listOf(a))
        media.cleanPendingDeletions()
        assertNotNull(media.fileFor(shared)); assertNull(db.mediaDao().asset(deleted)); assertFalse(oldFile.exists())
        assertNotNull(media.fileFor(a))
    }
    @Test fun conversationKeepsSentImageAfterRemovalFromSubscriptionGallery() = runBlocking {
        val chats = SubscriptionConversationRepository(db.storage())
        val attachments = SubscriptionAttachmentRepository(context, db.storage(), media)
        val session = chats.newConversation(); val a = image(Color.RED); val b = image(Color.GREEN)
        attachments.attach(session.id, a, "第一张"); attachments.attach(session.id, b, "第二张")
        val draft = chats.saveDraft(session.id, "登记", "{}", 0)
        chats.sendUserMessage(session.id, "message", "登记", listOf(a, b), draft.revision)
        val item = records.create("create", values(listOf(a, b)), session.id)
        replace(item, listOf(a)); media.cleanPendingDeletions()
        assertEquals(listOf(a), records.imageIds(item.id))
        assertEquals(listOf(a, b), db.subscriptionConversations().messageMedia("message").map { it.assetId })
        assertNotNull(media.fileFor(b))
    }
    @Test fun oldValuesPayloadStillRetriesAndOldEditorDraftCanBeDecodedWithNewImagesOptional() = runBlocking {
        val a = image(Color.RED); val legacy = values(cover = a)
        val created = records.create("legacy", legacy)
        assertEquals(created, records.create("legacy", SubscriptionValues.decode(legacy.json())))
        assertEquals(listOf(a), records.imageIds(created.id))
        val editor = SubscriptionEditor("conversation", coverAssetId = a)
        val old = editor.json(); old.remove("imageAssetIds")
        assertNull(SubscriptionEditor.decode(old).imageAssetIds)
        val modern = editor.copy(imageAssetIds = listOf(a, "another"))
        assertEquals(modern, SubscriptionEditor.decode(modern.json()))
    }
}
