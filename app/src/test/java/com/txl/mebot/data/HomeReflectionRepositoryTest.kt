package com.txl.mebot.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.home.HomeReflectionRepository
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReflectionRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var repo: HomeReflectionRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-31T08:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME); open()
    }
    private fun open() { db = MebotDatabase.create(context); media = LocalMediaStore(context, db, clock); repo = HomeReflectionRepository(context, db, media, clock) }
    @After fun close() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }
    private fun image(color: Int): File = File(context.cacheDir, "reflection-$color.png").apply {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun textPersistsAcrossReopenAndDaysAndIdenticalSaveDoesNotDuplicateOrRevise() = runBlocking {
        assertEquals(HomeReflectionRecord(), repo.read())
        val saved = repo.write("知行合一\n每天做好一件小事", HomeReflectionMode.TEXT)
        assertEquals(1L, saved.content.revision)
        assertEquals(saved, repo.write(saved.content.text, HomeReflectionMode.TEXT))
        db.close(); open()
        assertEquals(saved, repo.read())
        val tomorrow = HomeReflectionRepository(context, db, media, Clock.offset(clock, java.time.Duration.ofDays(2)))
        assertEquals(saved, tomorrow.read())
    }
    @Test fun importedImageIsCopiedAndBothModesKeepTheirOwnContent() = runBlocking {
        repo.write("保留文字", HomeReflectionMode.TEXT)
        val source = image(Color.RED)
        val saved = repo.importImage(Uri.fromFile(source))
        val id = saved.content.imageAssetId!!
        assertEquals(HomeReflectionMode.IMAGE, saved.content.mode)
        assertEquals("保留文字", saved.content.text)
        source.delete()
        assertTrue(media.fileFor(id)!!.isFile)
        val text = repo.write("更新文字", HomeReflectionMode.TEXT)
        assertEquals(id, text.content.imageAssetId)
        assertEquals(0, db.mediaDao().deleteIfUnused(id))
        assertEquals(id, repo.write(text.content.text, HomeReflectionMode.IMAGE).content.imageAssetId)
        db.close(); open()
        assertEquals(HomeReflectionMode.IMAGE, repo.read().content.mode)
        assertTrue(media.fileFor(id)!!.isFile)
    }
    @Test fun choosingImageWithoutPickingOneDoesNotReplacePublishedTextWithABlankCard() = runBlocking {
        repo.write("原来的寄语", HomeReflectionMode.TEXT)
        val saved = repo.write("原来的寄语", HomeReflectionMode.IMAGE)
        assertEquals(HomeReflectionMode.TEXT, saved.content.mode)
        assertEquals("原来的寄语", saved.content.text)
    }
    @Test fun replacingAndRemovingPreservesHistoryAndOtherBotsImages() = runBlocking {
        val first = repo.importImage(Uri.fromFile(image(Color.RED))).content.imageAssetId!!
        val original = media.fileFor(first)!!
        db.flashnoteDao().insertNote(FlashnoteEntity("shared", content = "其他Bot的原图", createdAt = 1, updatedAt = 1))
        db.flashnoteMediaDao().insertNote(FlashnoteAttachmentEntity("shared", first, "共用图片", 0))
        val second = repo.importImage(Uri.fromFile(image(Color.BLUE))).content.imageAssetId!!
        val historical = media.fileFor(second)!!
        val cleared = repo.removeImage()
        assertNull(cleared.content.imageAssetId)
        assertEquals(HomeReflectionMode.TEXT, cleared.content.mode)
        repo.cleanImages()
        assertTrue(original.isFile); assertNotNull(db.mediaDao().asset(first))
        assertTrue(historical.isFile); assertNotNull(db.mediaDao().asset(second))
        assertEquals(setOf(first, second), repo.historyPage(10).mapNotNull { it.content.imageAssetId }.toSet())
    }
    @Test fun imageMetadataAndCardRollbackTogetherOnWriteFailureThenRetrySavesOnce() = runBlocking {
        val before = repo.importImage(Uri.fromFile(image(Color.RED)))
        val file = media.fileFor(before.content.imageAssetId!!)!!
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_reflection BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        val source = image(Color.BLUE)
        assertTrue(runCatching { repo.importImage(Uri.fromFile(source)) }.isFailure)
        assertEquals(before, repo.read()); assertTrue(file.isFile)
        assertEquals(1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); it.getInt(0) })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_reflection")
        val after = repo.importImage(Uri.fromFile(source))
        assertEquals(before.content.revision + 1, after.content.revision)
        assertNotEquals(before.content.imageAssetId, after.content.imageAssetId)
        repo.cleanImages()
        assertTrue(media.fileFor(after.content.imageAssetId!!)!!.isFile)
    }
    @Test fun latePickerAndInvalidImageNeverReplacePublishedContent() = runBlocking {
        val before = repo.importImage(Uri.fromFile(image(Color.RED)))
        assertTrue(runCatching { repo.importImage(Uri.fromFile(image(Color.BLUE))) { false } }.isFailure)
        val invalid = File(context.cacheDir, "not-an-image.bin").apply { writeText("not an image") }
        assertTrue(runCatching { repo.importImage(Uri.fromFile(invalid)) }.isFailure)
        assertEquals(before, repo.read())
        repo.cleanImages()
        assertTrue(media.fileFor(before.content.imageAssetId!!)!!.isFile)
    }
    @Test fun tooLongTextAndDatabaseFailureCannotPartiallyOverwriteSavedContent() = runBlocking {
        val before = repo.write("原来的提醒", HomeReflectionMode.TEXT)
        assertTrue(runCatching { repo.write("字".repeat(HomeReflectionRepository.MAX_TEXT + 1), HomeReflectionMode.TEXT) }.isFailure)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_text BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { repo.write("失败的新输入", HomeReflectionMode.IMAGE) }.isFailure)
        assertEquals(before, repo.read())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_text")
        assertEquals("重试成功", repo.write("重试成功", HomeReflectionMode.TEXT).content.text)
    }
}
