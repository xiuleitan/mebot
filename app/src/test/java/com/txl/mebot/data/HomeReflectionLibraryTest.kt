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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
class HomeReflectionLibraryTest {
    private lateinit var context: Context
    private lateinit var db: MebotDatabase
    private lateinit var media: LocalMediaStore
    private lateinit var repo: HomeReflectionRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-31T08:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(MebotDatabase.FILE_NAME); open() }
    private fun open() { db = MebotDatabase.create(context); media = LocalMediaStore(context, db, clock); repo = HomeReflectionRepository(context, db, media, clock) }
    @After fun close() { db.close(); context.deleteDatabase(MebotDatabase.FILE_NAME); File(context.filesDir, "media").deleteRecursively() }
    private suspend fun text(value: String) { repo.write(value, HomeReflectionMode.TEXT, rememberBefore = true); repo.rememberCurrent() }
    private fun image(color: Int): File = File(context.cacheDir, "reflection-history-$color.png").apply {
        val bitmap = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun editingHistoryDoesNotSwitchCurrentAndEditingCurrentReplacesItsRecord() = runBlocking {
        text("过去的文字"); text("正在展示的文字")
        val old = repo.historyPage(10).single { it.content.text == "过去的文字" }.content
        repo.editText(old, "修改过的历史")
        assertEquals("正在展示的文字", repo.read().content.text)
        assertEquals(setOf("修改过的历史", "正在展示的文字"), repo.historyPage(10).map { it.content.text }.toSet())
        val current = repo.historyPage(10).single { it.content.text == "正在展示的文字" }.content
        repo.editText(current, "新的当前文字")
        assertEquals("新的当前文字", repo.read().content.text)
        assertEquals(2, repo.historyPage(10).size)
        val edited = repo.historyPage(10).single { it.content.text == "修改过的历史" }.content
        repo.editText(edited, "新的当前文字")
        assertEquals(1, repo.historyPage(10).size)
        db.close(); open(); assertEquals("新的当前文字", repo.read().content.text)
    }
    @Test fun deletingCurrentClearsHomeButPreservesTheOtherModeAndNeverRecreatesDeletedText() = runBlocking {
        val image = repo.importImage(Uri.fromFile(image(Color.RED))).content.imageAssetId!!
        text("删除当前文字")
        val current = repo.historyPage(10).single { it.content.text == "删除当前文字" }.content
        repo.deleteEntry(current)
        assertEquals("", repo.read().content.text); assertNull(repo.read().content.imageAssetId)
        repo.rememberCurrent(); repo.cleanImages()
        assertEquals(listOf(image), repo.historyPage(10).map { it.content.imageAssetId })
        assertTrue(media.fileFor(image)!!.isFile)
        db.close(); open(); repo.rememberCurrent()
        assertEquals(1, repo.historyPage(10).size); assertEquals("", repo.read().content.text)
    }
    @Test fun deletingHistoryOnlyCleansUnsharedOriginalsAndKeepsOtherBotsAttachments() = runBlocking {
        val red = repo.importImage(Uri.fromFile(image(Color.RED))).content.imageAssetId!!
        val redFile = media.fileFor(red)!!
        val blue = repo.importImage(Uri.fromFile(image(Color.BLUE))).content.imageAssetId!!
        val blueFile = media.fileFor(blue)!!
        db.flashnoteDao().insertNote(FlashnoteEntity("shared", content = "共用图片", createdAt = 1, updatedAt = 1))
        db.flashnoteMediaDao().insertNote(FlashnoteAttachmentEntity("shared", blue, "共用图片", 0))
        repo.addText("当前文字")
        repo.deleteEntry(repo.historyPage(10).single { it.content.imageAssetId == red }.content)
        repo.deleteEntry(repo.historyPage(10).single { it.content.imageAssetId == blue }.content)
        repo.cleanImages()
        assertEquals("当前文字", repo.read().content.text)
        assertFalse(redFile.exists()); assertNull(db.mediaDao().asset(red))
        assertTrue(blueFile.exists()); assertNotNull(db.mediaDao().asset(blue))
        assertEquals(1, repo.historyPage(10).size)
    }
    @Test fun replacingHistoricalPhotoKeepsCurrentTextAndPersistsTheNewPhoto() = runBlocking {
        val red = repo.importImage(Uri.fromFile(image(Color.RED))).content.imageAssetId!!
        val redFile = media.fileFor(red)!!
        val old = repo.historyPage(10).single().content
        repo.addText("首页仍然显示文字")
        val green = image(Color.GREEN)
        repo.editImage(old, Uri.fromFile(green))
        green.delete(); repo.cleanImages()
        assertEquals(HomeReflectionMode.TEXT, repo.read().content.mode)
        assertEquals("首页仍然显示文字", repo.read().content.text)
        val newImage = repo.historyPage(10).single { it.content.mode == HomeReflectionMode.IMAGE }.content
        assertNotEquals(red, newImage.imageAssetId); assertFalse(redFile.exists())
        assertTrue(media.fileFor(newImage.imageAssetId!!)!!.isFile)
        repo.useHistory(newImage.id)
        db.close(); open()
        assertEquals(newImage.imageAssetId, repo.read().content.imageAssetId)
    }
    @Test fun failedImageEditRollsBackMetadataHistoryCurrentAndCleanupThenRetriesWithoutDuplicates() = runBlocking {
        repo.importImage(Uri.fromFile(image(Color.RED)))
        val original = repo.historyPage(10).single().content
        val before = repo.read(); val history = repo.historyPage(10)
        val file = media.fileFor(original.imageAssetId!!)!!
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_edit_image BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { repo.editImage(original, Uri.fromFile(image(Color.BLUE))) }.isFailure)
        assertEquals(before, repo.read()); assertEquals(history, repo.historyPage(10)); assertTrue(file.isFile)
        assertEquals(1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_assets").use { it.moveToFirst(); it.getInt(0) })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit_image")
        repo.editImage(original, Uri.fromFile(image(Color.BLUE))); repo.cleanImages()
        assertEquals(1, repo.historyPage(10).size); assertFalse(file.exists())
        assertTrue(media.fileFor(repo.read().content.imageAssetId!!)!!.isFile)
    }
    @Test fun failedDeleteRollsBackAndStaleEditsCannotResurrectADeletedEntry() = runBlocking {
        repo.addText("原内容"); val original = repo.historyPage(10).single().content
        val before = repo.read()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_delete_current BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { repo.deleteEntry(original) }.isFailure)
        assertEquals(before, repo.read()); assertEquals(original, repo.historyPage(10).single().content)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete_current")
        repo.deleteEntry(original)
        assertTrue(runCatching { repo.editText(original, "迟到的编辑") }.isFailure)
        assertTrue(runCatching { repo.deleteEntry(original) }.isFailure)
        assertTrue(repo.historyPage(10).isEmpty()); assertEquals("", repo.read().content.text)
    }
    @Test fun deletingTheHiddenTextAlsoClearsItsRetainedValueSoItCannotComeBackLater() = runBlocking {
        text("隐藏的旧文字"); val old = repo.historyPage(10).single().content
        repo.importImage(Uri.fromFile(image(Color.RED)))
        repo.deleteEntry(old)
        assertEquals(HomeReflectionMode.IMAGE, repo.read().content.mode)
        assertEquals("", repo.read().content.text)
        repo.removeImage(); repo.rememberCurrent()
        assertTrue(repo.historyPage(10).none { it.content.mode == HomeReflectionMode.TEXT })
    }
}
