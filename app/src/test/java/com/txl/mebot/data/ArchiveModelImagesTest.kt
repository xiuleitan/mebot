package com.txl.mebot.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.archive.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveModelImagesTest {
    private lateinit var file: File
    @Before fun setup() { file = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "archive-model-image-fixture") }
    @After fun teardown() { file.delete() }
    private fun pixels(width: Int, height: Int, color: Int, jpeg: Boolean = false) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { assertTrue(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    private suspend fun decoded(): Bitmap {
        val data = archiveImageData(file); assertTrue(data.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(data.substringAfter(','), Base64.NO_WRAP)
        assertTrue(bytes.size <= 2 * 1024 * 1024); assertEquals(0xff, bytes[0].toInt() and 0xff); assertEquals(0xd8, bytes[1].toInt() and 0xff)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    @Test fun fullImageIsEncodedWithBoundedResolutionAndOriginalIsUnchanged() = runBlocking {
        pixels(5000, 1800, Color.BLUE); val original = file.readBytes()
        val bitmap = decoded()
        assertTrue(bitmap.width in 2400..2560); assertTrue(bitmap.height in 850..922)
        assertTrue(Color.blue(bitmap.getPixel(100, 100)) > 220); bitmap.recycle()
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun transparentImagesAreFlattenedOntoWhiteRatherThanUnreadableBlack() = runBlocking {
        pixels(40, 40, Color.TRANSPARENT)
        val bitmap = decoded(); val pixel = bitmap.getPixel(20, 20)
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240); bitmap.recycle()
    }
    @Test @Config(sdk = [27, 35]) fun exifOrientationIsAppliedAcrossAndroidVersions() = runBlocking {
        pixels(120, 60, Color.GREEN, jpeg = true)
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val original = file.readBytes(); val bitmap = decoded()
        assertEquals(60, bitmap.width); assertEquals(120, bitmap.height); bitmap.recycle()
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun corruptEmptyAndOversizedFilesFailWithoutReturningAPlaceholder() = runBlocking {
        file.writeText("not a real image")
        assertEquals(ArchiveAnalysisFailure.IMAGE, (runCatching { archiveImageData(file) }.exceptionOrNull() as ArchiveAnalysisException).failure)
        file.writeBytes(ByteArray(0))
        assertEquals(ArchiveAnalysisFailure.IMAGE, (runCatching { archiveImageData(file) }.exceptionOrNull() as ArchiveAnalysisException).failure)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
        assertEquals(ArchiveAnalysisFailure.IMAGE, (runCatching { archiveImageData(file) }.exceptionOrNull() as ArchiveAnalysisException).failure)
    }
}
