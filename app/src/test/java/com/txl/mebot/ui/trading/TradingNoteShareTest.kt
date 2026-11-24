package com.txl.mebot.ui.trading

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.ArchiveImageDownloadsTest.SavedImagesProvider
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingInterval
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TradingNoteShareTest {
    private lateinit var context: Context
    private lateinit var provider: SavedImagesProvider
    private val clipboard get() = checkNotNull(context.getSystemService(ClipboardManager::class.java))
    private val png get() = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aVI0AAAAASUVORK5CYII=",
        Base64.DEFAULT,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = Robolectric.buildContentProvider(SavedImagesProvider::class.java).create("media").get()
    }

    @After
    fun tearDown() {
        provider.directory.deleteRecursively()
    }

    @Test
    fun publishesThePngInMebotAlbumAndCopiesTheExactDraft() = runBlocking<Unit> {
        val draft = "  观察反弹\n等待确认 📓 "
        val name = tradingNoteExportName(TradingContract.ETH, TradingInterval.H4)
        assertTrue(name.startsWith("mebot_ETHUSDT_4h_") && name.endsWith(".png"))
        provider.expected = png

        assertEquals(TradingNoteShareResult.DONE, TradingNoteShare(context).finish(TradingNoteExport(name, png, draft)))
        val entry = provider.rows.getValue(1L)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1)
        assertTrue(provider.pendingWhenOpened)
        assertEquals(1, provider.publications)
        assertEquals("Pictures/mebot", entry.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("image/png", entry.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(0, entry.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
        assertArrayEquals(png, provider.file(uri).readBytes())
        assertEquals(draft, clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        assertEquals(TradingNoteShareResult.IMAGE_ONLY, TradingNoteShare(context).finish(TradingNoteExport("chart.png", png, "")))
        assertEquals(2, provider.publications)
        assertEquals(draft, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun removesTheNewImageIfGalleryPublicationFails() = runBlocking<Unit> {
        clipboard.setPrimaryClip(ClipData.newPlainText("previous", "已有剪贴板文字"))
        provider.failPublish = true
        assertEquals(TradingNoteShareResult.SAVE_FAILED, TradingNoteShare(context).finish(TradingNoteExport("chart.png", png, "草稿")))
        assertEquals(1, provider.deletions)
        assertTrue(provider.rows.isEmpty())
        assertFalse(provider.directory.listFiles().orEmpty().isNotEmpty())
        assertEquals("已有剪贴板文字", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    @Config(sdk = [28])
    fun writesTheSelectedDocumentAndCopiesTheDraftOnOlderAndroid() = runBlocking<Unit> {
        val document = provider.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues())
        val draft = "下次复盘这个形态\n先记录"
        val status = TradingNoteShare(context).finish(TradingNoteExport("chart.png", png, draft), document)
        assertEquals(TradingNoteShareResult.DONE, status)
        assertEquals(1, provider.insertions)
        assertArrayEquals(png, provider.file(document).readBytes())
        assertEquals(draft, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
