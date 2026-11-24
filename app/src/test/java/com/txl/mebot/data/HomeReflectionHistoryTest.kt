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
class HomeReflectionHistoryTest {
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

    @Test fun finishedTextIsDeduplicatedWithoutSavingEveryAutosavedKeystroke() = runBlocking {
        text("昨天用过的话")
        repo.write("开始编辑", HomeReflectionMode.TEXT, rememberBefore = true)
        repeat(60) { repo.write("连续输入 $it", HomeReflectionMode.TEXT) }
        assertEquals(listOf("昨天用过的话"), repo.historyPage(100).map { it.content.text })
        repo.rememberCurrent(); repo.rememberCurrent()
        assertEquals(setOf("昨天用过的话", "连续输入 59"), repo.historyPage(100).map { it.content.text }.toSet())
        val original = repo.historyPage(100).first { it.content.text == "昨天用过的话" }
        val tomorrow = HomeReflectionRepository(context, db, media, Clock.offset(clock, Duration.ofDays(1)))
        repeat(3) { tomorrow.useHistory(original.content.id) }
        val history = tomorrow.historyPage(100)
        assertEquals(2, history.size)
        assertEquals(original.content.id, history.first().content.id)
        assertEquals(original.content.firstUsedAt, history.first().content.firstUsedAt)
        assertEquals(clock.millis() + Duration.ofDays(1).toMillis(), history.first().content.lastUsedAt)
    }
    @Test fun replacedAndRemovedPicturesRemainDurableAndRestorableAfterReopen() = runBlocking {
        val redSource = image(Color.RED)
        val red = repo.importImage(Uri.fromFile(redSource)).content.imageAssetId!!
        redSource.delete()
        val blue = repo.importImage(Uri.fromFile(image(Color.BLUE))).content.imageAssetId!!
        repo.removeImage(); repo.cleanImages()
        assertTrue(media.fileFor(red)!!.isFile); assertTrue(media.fileFor(blue)!!.isFile)
        assertEquals(0, db.mediaDao().deleteIfUnused(red))
        db.close(); open()
        val old = repo.historyPage(100).single { it.content.imageAssetId == red }
        val restored = repo.useHistory(old.content.id)
        assertEquals(red, restored.content.imageAssetId)
        assertEquals(HomeReflectionMode.IMAGE, restored.content.mode)
        assertNotNull(old.imageRelativePath)
        assertTrue(media.fileFor(red)!!.isFile)
    }
    @Test fun restoringTextOrImageKeepsTheOtherModeContentAndTheReplacedImage() = runBlocking {
        text("以前的文字")
        val textId = repo.historyPage(10).single().content.id
        val red = repo.importImage(Uri.fromFile(image(Color.RED))).content.imageAssetId!!
        val redId = repo.historyPage(10).single { it.content.imageAssetId == red }.content.id
        text("现在的文字")
        val blue = repo.importImage(Uri.fromFile(image(Color.BLUE))).content.imageAssetId!!
        val restoredText = repo.useHistory(textId)
        assertEquals("以前的文字", restoredText.content.text); assertEquals(blue, restoredText.content.imageAssetId)
        val restoredImage = repo.useHistory(redId)
        assertEquals(red, restoredImage.content.imageAssetId); assertEquals("以前的文字", restoredImage.content.text)
        repo.cleanImages(); assertTrue(media.fileFor(blue)!!.isFile)
        assertEquals(4, repo.historyPage(10).size)
    }
    @Test fun failedRestoreRollsBackCurrentContentNewHistoryAndUsageTimeTogetherThenRetriesOnce() = runBlocking {
        text("历史文字")
        val history = repo.historyPage(10)
        val before = repo.write("还没归档的新文字", HomeReflectionMode.TEXT)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_restore BEFORE UPDATE ON home_reflection
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        assertTrue(runCatching { repo.useHistory(history.single().content.id) }.isFailure)
        assertEquals(before, repo.read()); assertEquals(history, repo.historyPage(10))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restore")
        assertEquals("历史文字", repo.useHistory(history.single().content.id).content.text)
        assertEquals(2, repo.historyPage(10).size)
    }
    @Test fun historyPagesCoverAllRecordsWithoutOverlapAndReuseDoesNotCreateADuplicate() = runBlocking {
        repeat(85) { i -> HomeReflectionRepository(context, db, media, Clock.offset(clock, Duration.ofMinutes(i.toLong())))
            .apply { write("感悟 $i", HomeReflectionMode.TEXT); rememberCurrent() } }
        val all = repo.historyPage(40) + repo.historyPage(40, 40) + repo.historyPage(40, 80)
        assertEquals(85, all.size); assertEquals(85, all.map { it.content.id }.toSet().size)
        assertEquals("感悟 84", all.first().content.text); assertEquals("感悟 0", all.last().content.text)
        assertEquals(emptyList<HomeReflectionHistoryRecord>(), repo.historyPage(40, 85))
        repo.useHistory(all.last().content.id)
        assertEquals(85, repo.historyPage(100).size)
    }
    @Test fun emptyContentAndUnknownHistoryCannotCreateBlankRecordsOrEraseCurrentContent() = runBlocking {
        repo.rememberCurrent(); repo.write("  \n  ", HomeReflectionMode.TEXT, rememberBefore = true); repo.rememberCurrent()
        assertTrue(repo.historyPage(10).isEmpty())
        text("保留当前内容")
        val before = repo.read()
        assertTrue(runCatching { repo.useHistory("missing") }.isFailure)
        assertEquals(before, repo.read()); assertEquals(1, repo.historyPage(10).size)
    }
    @Test fun switchingModeWithUnflushedTextKeepsTheFinishedTextInHistory() = runBlocking {
        text("原来的文字")
        val asset = repo.importImage(Uri.fromFile(image(Color.RED))).content.imageAssetId!!
        repo.write("原来的文字", HomeReflectionMode.TEXT)
        repo.write("切换图片之前写完的话", HomeReflectionMode.IMAGE)
        assertTrue(repo.historyPage(10).any { it.content.text == "切换图片之前写完的话" })
        assertEquals(asset, repo.read().content.imageAssetId)
    }
}
