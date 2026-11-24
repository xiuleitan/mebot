package com.txl.mebot.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.flashnote.*
import com.txl.mebot.data.local.*
import java.io.File
import java.io.RandomAccessFile
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
class FlashnoteTitleImagesTest {
    private lateinit var root: File
    @Before fun setup() {
        root = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "flashnote-title-images-test").apply { mkdirs() }
    }
    @After fun teardown() { root.deleteRecursively() }
    private fun photo(name: String, color: Int, width: Int = 24, height: Int = 16): FlashnoteAttachment {
        val file = File(root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { check(bitmap.compress(if (name.endsWith(".jpg")) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return attachment(name, width, height)
    }
    private fun attachment(name: String, width: Int = 24, height: Int = 16, mime: String = "image/png") = FlashnoteAttachment(
        MediaAssetEntity(name, MediaSource.IMPORTED, relativePath = name, originalName = name, mimeType = mime,
            byteCount = 1, width = width, height = height, createdAt = 0), name, 0)
    private fun decoded(image: FlashnoteTitleImage): Bitmap {
        assertTrue(image.dataUrl.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(image.dataUrl.substringAfter(','), Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test fun allSelectedPicturesKeepOrderAndOriginalFilesAreByteForByteUnchanged() = runBlocking {
        val first = photo("first.png", Color.RED, 3072, 1024)
        val second = photo("second.png", Color.BLUE)
        val original = listOf(first, second).associate { it.asset.id to File(root, it.asset.relativePath!!).readBytes() }
        val result = flashnoteTitleImages(root, listOf(first, attachment("unrelated.pdf", 0, 0, "application/pdf"), second))
        assertEquals(2, result.size)
        val red = decoded(result[0]); val blue = decoded(result[1])
        assertTrue(red.width <= 1536 && red.height <= 1536)
        assertTrue(Color.red(red.getPixel(1, 1)) > 220)
        assertTrue(Color.blue(blue.getPixel(1, 1)) > 220)
        red.recycle(); blue.recycle()
        listOf(first, second).forEach { assertArrayEquals(original[it.asset.id], File(root, it.asset.relativePath!!).readBytes()) }
        assertFalse(result.first().dataUrl.contains("first.png"))
    }

    @Test fun transparentPicturesUseAWhiteCopyWithoutChangingTheOriginalAlpha() = runBlocking {
        val item = photo("transparent.png", Color.TRANSPARENT)
        val before = File(root, "transparent.png").readBytes()
        val image = decoded(flashnoteTitleImages(root, listOf(item)).single())
        assertTrue(Color.red(image.getPixel(1, 1)) > 240 && Color.green(image.getPixel(1, 1)) > 240)
        image.recycle()
        assertArrayEquals(before, File(root, "transparent.png").readBytes())
    }

    @Test fun exifRotationIsAppliedOnlyToTheModelCopy() = runBlocking {
        val item = photo("rotated.jpg", Color.GREEN, 40, 20)
        ExifInterface(File(root, "rotated.jpg")).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        val before = File(root, "rotated.jpg").readBytes()
        val image = decoded(flashnoteTitleImages(root, listOf(item)).single())
        assertEquals(20, image.width); assertEquals(40, image.height)
        image.recycle()
        assertArrayEquals(before, File(root, "rotated.jpg").readBytes())
    }

    @Test fun invalidMissingOversizedOrEscapingFilesNeverBecomeModelImages() = runBlocking {
        File(root, "broken.png").writeText("not an image")
        for (name in listOf("missing.png", "broken.png", "../outside.png")) {
            val failure = runCatching { flashnoteTitleImages(root, listOf(attachment(name))) }.exceptionOrNull()
            assertTrue(name, failure is FlashnoteTitleException)
            assertEquals(FlashnoteTitleFailure.IMAGE, (failure as FlashnoteTitleException).failure)
        }
        val escapedName = "../flashnote-title-outside-${java.util.UUID.randomUUID()}.png"
        val escaped = photo(escapedName, Color.RED)
        val escapedFile = File(root, escapedName)
        val escapedBytes = escapedFile.readBytes()
        try {
            assertEquals(FlashnoteTitleFailure.IMAGE, (runCatching { flashnoteTitleImages(root, listOf(escaped)) }
                .exceptionOrNull() as FlashnoteTitleException).failure)
            assertArrayEquals(escapedBytes, escapedFile.readBytes())
        } finally { escapedFile.delete() }
        RandomAccessFile(File(root, "too-large.png"), "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        assertEquals(FlashnoteTitleFailure.IMAGE, (runCatching { flashnoteTitleImages(root, listOf(attachment("too-large.png"))) }
            .exceptionOrNull() as FlashnoteTitleException).failure)
        assertEquals(FlashnoteTitleFailure.LIMIT, (runCatching { flashnoteTitleImages(root, List(9) { attachment("missing-$it.png") }) }
            .exceptionOrNull() as FlashnoteTitleException).failure)
        assertEquals("not an image", File(root, "broken.png").readText())
    }
}
