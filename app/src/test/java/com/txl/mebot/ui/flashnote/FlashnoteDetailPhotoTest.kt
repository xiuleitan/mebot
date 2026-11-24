package com.txl.mebot.ui.flashnote

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlashnoteDetailPhotoTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "flashnote-detail-photo-test").apply { mkdirs() }
    }
    @After fun teardown() { root.deleteRecursively() }

    private fun photo(name: String, width: Int, height: Int): File {
        val file = File(root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { check(bitmap.compress(
            if (name.endsWith(".jpg")) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    @Test fun squareDetailKeepsScreenshotShortSideAtOriginalResolution() = runBlocking {
        val file = photo("screenshot.png", 1080, 2400)
        val original = file.readBytes()
        val result = requireNotNull(loadFlashnoteDetailPhoto(context, file.relativeTo(context.filesDir).path,
            IntSize(960, 960), crop = true))
        assertEquals(1080, result.width)
        assertEquals(2400, result.height)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun cropAndFitUseTheirActualDisplayBoundsInsteadOfOneLongEdgeLimit() {
        assertEquals(1, flashnoteDetailPhotoSample(2400, 1080, IntSize(960, 960), crop = true))
        assertEquals(2, flashnoteDetailPhotoSample(2400, 1080, IntSize(1080, 2000), crop = false))
        assertEquals(1, flashnoteDetailPhotoSample(1080, 2400, IntSize(1080, 2000), crop = false))
        // 采样到 1/4 时短边仅 750 像素，不能再放大到 800 像素的方形预览。
        assertEquals(2, flashnoteDetailPhotoSample(4000, 3000, IntSize(800, 800), crop = true))
        assertEquals(1, flashnoteDetailPhotoSample(100, 200, IntSize(960, 960), crop = true))
    }

    @Test fun veryLargeAndExtremelyLongImagesStayWithinDecodeMemoryLimits() {
        for ((width, height) in listOf(12000 to 9000, 1080 to 100000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = flashnoteDetailPhotoSample(width, height, IntSize(4096, 4096), crop = true)
            val decodedWidth = (width.toLong() + sample - 1) / sample
            val decodedHeight = (height.toLong() + sample - 1) / sample
            assertTrue(decodedWidth * decodedHeight <= 8_000_000)
            assertTrue(decodedWidth <= 8192 && decodedHeight <= 8192)
            assertEquals(0, sample and (sample - 1))
        }
    }

    @Test fun originalExifOrientationIsAppliedWithoutRewritingTheFile() = runBlocking {
        val file = photo("rotated.jpg", 2400, 1080)
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val original = file.readBytes()
        val result = requireNotNull(loadFlashnoteDetailPhoto(context, file.relativeTo(context.filesDir).path,
            IntSize(1080, 2000), crop = false))
        assertEquals(1080, result.width)
        assertEquals(2400, result.height)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun missingBrokenOrEscapingFilesDoNotBreakDetailsOrReadOutsideAppFiles() = runBlocking {
        val target = IntSize(960, 960)
        assertNull(loadFlashnoteDetailPhoto(context, null, target, crop = true))
        assertNull(loadFlashnoteDetailPhoto(context, "missing.png", target, crop = true))
        val broken = File(root, "broken.png").apply { writeText("invalid image") }
        assertNull(loadFlashnoteDetailPhoto(context, broken.relativeTo(context.filesDir).path, target, crop = true))
        val outside = File(context.cacheDir, "flashnote-detail-outside.png")
        try {
            photo("inside.png", 32, 32).copyTo(outside, overwrite = true)
            assertNull(loadFlashnoteDetailPhoto(context, outside.relativeTo(context.filesDir).path, target, crop = true))
            assertTrue(outside.isFile)
        } finally { outside.delete() }
        assertEquals("invalid image", broken.readText())
    }
}
