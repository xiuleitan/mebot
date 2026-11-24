package com.txl.mebot.ui.flashnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val DetailPhotoLoad = Mutex()
private const val DetailPhotoPixelLimit = 8_000_000L
private const val DetailPhotoEdgeLimit = 8192

/** 只在详情可见时读取原图，不复用缩略图的长边限制，也不挤占列表图片缓存。 */
@Composable
internal fun flashnoteDetailPhoto(relativePath: String?, targetSize: IntSize, crop: Boolean): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val photo by produceState<ImageBitmap?>(null, context, relativePath, targetSize, crop) {
        value = loadFlashnoteDetailPhoto(context, relativePath, targetSize, crop)
    }
    return photo
}

/** 裁切预览按短边保留足够像素；全屏适配按实际显示范围采样。大图仍受内存上限约束。 */
internal fun flashnoteDetailPhotoSample(width: Int, height: Int, targetSize: IntSize, crop: Boolean): Int {
    require(width > 0 && height > 0)
    val targetWidth = targetSize.width.coerceIn(1, 4096)
    val targetHeight = targetSize.height.coerceIn(1, 4096)
    var sample = 1
    while (sample < (1 shl 30)) {
        val next = sample * 2
        val enoughWidth = width / next >= targetWidth
        val enoughHeight = height / next >= targetHeight
        if (if (crop) enoughWidth && enoughHeight else enoughWidth || enoughHeight) sample = next else break
    }
    fun decodedEdge(edge: Int) = (edge.toLong() + sample - 1) / sample
    while (decodedEdge(width) * decodedEdge(height) > DetailPhotoPixelLimit ||
        decodedEdge(width) > DetailPhotoEdgeLimit || decodedEdge(height) > DetailPhotoEdgeLimit) {
        sample *= 2
    }
    return sample
}

internal suspend fun loadFlashnoteDetailPhoto(
    context: Context, relativePath: String?, targetSize: IntSize, crop: Boolean,
): ImageBitmap? = withContext(Dispatchers.IO) {
    if (relativePath == null || targetSize.width <= 0 || targetSize.height <= 0) return@withContext null
    // 多图预加载串行解码，避免同时分配数张高清位图。离开详情后不保留全局大图缓存。
    DetailPhotoLoad.withLock {
        try {
            val root = context.filesDir.canonicalFile
            val file = File(root, relativePath).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return@withLock null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withLock null
            val orientation = runCatching {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val swapAxes = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
            val width = if (swapAxes) options.outHeight else options.outWidth
            val height = if (swapAxes) options.outWidth else options.outHeight
            options.inSampleSize = flashnoteDetailPhotoSample(width, height, targetSize, crop)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return@withLock null
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val oriented = if (matrix.isIdentity) bitmap else
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            oriented.prepareToDraw()
            oriented.asImageBitmap()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null
        } catch (_: OutOfMemoryError) { null }
    }
}
