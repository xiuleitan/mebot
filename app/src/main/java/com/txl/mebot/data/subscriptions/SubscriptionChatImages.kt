package com.txl.mebot.data.subscriptions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 模型只接收当前会话选中的压缩副本；持久原图不改动，也不创建远程图片 URL。 */
internal suspend fun subscriptionImageData(file: File): String = withContext(Dispatchers.IO) {
    var decoded: Bitmap? = null
    try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 3072) sample *= 2
        decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val ratio = (2560f / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        if (ratio < 1) {
            val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
            if (scaled !== decoded) decoded.recycle()
            decoded = scaled
        }
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply { when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        } }
        if (!transform.isIdentity) {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
            if (rotated !== decoded) decoded.recycle()
            decoded = rotated
        }
        if (decoded.hasAlpha()) {
            val opaque = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            decoded.recycle(); decoded = opaque
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(decoded.compress(Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray()
        }
        require(bytes.size <= 4 * 1024 * 1024)
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { throw SubscriptionAnalysisException(SubscriptionAnalysisFailure.IMAGE) }
    catch (_: OutOfMemoryError) { throw SubscriptionAnalysisException(SubscriptionAnalysisFailure.IMAGE) }
    finally { decoded?.recycle() }
}
