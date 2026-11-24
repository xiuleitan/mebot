package com.txl.mebot.data.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 只在认证后的私有文件上解码，原件不改写；图片顺序由会话快照提供。 */
internal suspend fun archiveImageData(file: File): String = withContext(Dispatchers.IO) {
    var decoded: Bitmap? = null
    try {
        currentCoroutineContext().ensureActive()
        require(file.length() in 1..20L * 1024 * 1024)
        // 所有支持的 Android 版本共用有界解码与 EXIF 方向处理，避免先解出完整相机大图。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3072) sample *= 2
        var image = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        decoded = image
        val ratio = (2560f / maxOf(image.width, image.height)).coerceAtMost(1f)
        if (ratio < 1f) {
            val scaled = image.scale((image.width * ratio).toInt().coerceAtLeast(1), (image.height * ratio).toInt().coerceAtLeast(1), true)
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
            val opaque = createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(image, 0f, 0f, null) }
            image.recycle(); image = opaque; decoded = image
        }
        var data = ByteArray(0)
        for (quality in listOf(92, 85, 75)) {
            currentCoroutineContext().ensureActive()
            data = ByteArrayOutputStream().use { stream -> check(image.compress(Bitmap.CompressFormat.JPEG, quality, stream)); stream.toByteArray() }
            if (data.size <= 2 * 1024 * 1024) break
            data.fill(0)
        }
        require(data.isNotEmpty() && data.size <= 2 * 1024 * 1024)
        try { "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP) } finally { data.fill(0) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE) }
    catch (_: OutOfMemoryError) { throw ArchiveAnalysisException(ArchiveAnalysisFailure.IMAGE) }
    finally { decoded?.recycle() }
}
