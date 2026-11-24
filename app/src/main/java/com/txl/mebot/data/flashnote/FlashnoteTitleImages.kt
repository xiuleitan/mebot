package com.txl.mebot.data.flashnote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.txl.mebot.data.local.FlashnoteAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*

/** 顺序读取本条记录的图片，只压缩内存副本；不改写原图，不上传文件附件或历史记录。 */
internal suspend fun flashnoteTitleImages(root: File, attachments: List<FlashnoteAttachment>): List<FlashnoteTitleImage> = withContext(Dispatchers.IO) {
    val images = attachments.filter { it.isImage }
    if (images.size > 8) throw FlashnoteTitleException(FlashnoteTitleFailure.LIMIT)
    var encodedSize = 0
    images.map { item ->
        currentCoroutineContext().ensureActive()
        val data = try {
            val base = root.canonicalFile
            val file = File(base, requireNotNull(item.asset.relativePath)).canonicalFile
            require(file.path.startsWith(base.path + File.separator) && file.isFile)
            flashnoteTitleImageData(file)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: FlashnoteTitleException) { throw failure }
        catch (_: Exception) { throw FlashnoteTitleException(FlashnoteTitleFailure.IMAGE) }
        encodedSize += data.length
        if (encodedSize > 6 * 1024 * 1024) throw FlashnoteTitleException(FlashnoteTitleFailure.LIMIT)
        FlashnoteTitleImage(data)
    }
}

private suspend fun flashnoteTitleImageData(file: File): String {
    var decoded: Bitmap? = null
    try {
        require(file.length() in 1..32L * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        var image = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        decoded = image
        val ratio = (1536f / maxOf(image.width, image.height)).coerceAtMost(1f)
        if (ratio < 1f) {
            val scaled = Bitmap.createScaledBitmap(image, (image.width * ratio).toInt().coerceAtLeast(1),
                (image.height * ratio).toInt().coerceAtLeast(1), true)
            if (scaled !== image) image.recycle()
            image = scaled; decoded = image
        }
        val exif = ExifInterface(file)
        val transform = Matrix().apply { if (exif.isFlipped) postScale(-1f, 1f); postRotate(exif.rotationDegrees.toFloat()) }
        if (!transform.isIdentity) {
            val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height, transform, true)
            if (rotated !== image) image.recycle()
            image = rotated; decoded = image
        }
        if (image.hasAlpha()) {
            val opaque = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(image, 0f, 0f, null) }
            image.recycle(); image = opaque; decoded = image
        }
        for (quality in listOf(85, 75, 60)) {
            currentCoroutineContext().ensureActive()
            val data = ByteArrayOutputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.JPEG, quality, output)); output.toByteArray()
            }
            try {
                if (data.size <= 1024 * 1024) return "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP)
            } finally { data.fill(0) }
        }
        throw FlashnoteTitleException(FlashnoteTitleFailure.LIMIT)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: FlashnoteTitleException) { throw failure }
    catch (_: Exception) { throw FlashnoteTitleException(FlashnoteTitleFailure.IMAGE) }
    catch (_: OutOfMemoryError) { throw FlashnoteTitleException(FlashnoteTitleFailure.IMAGE) }
    finally { decoded?.recycle() }
}
