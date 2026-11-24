package com.txl.mebot.ui.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private class PhotoMemoryCache(maxKilobytes: Int) : LruCache<String, ImageBitmap>(maxKilobytes) {
    override fun sizeOf(key: String, value: ImageBitmap) = photoKilobytes(value)
}
private val ThumbnailCache = PhotoMemoryCache(12 * 1024)
private val PreviewCache = PhotoMemoryCache(8 * 1024)
private val ThumbnailLoad = Mutex()
private val PreviewLoad = Mutex()
private val CacheGuard = Any()
private var cacheGeneration = 0L

private fun photoKilobytes(photo: ImageBitmap) = (photo.width * photo.height * 4 / 1024).coerceAtLeast(1)
private fun photoCache(dimension: Int) = if (dimension <= 768) ThumbnailCache else PreviewCache
private data class LocalPhotoSource(val coverRelativePath: String?, val image: Int?)
private fun ScheduleEntry.photoSource() = LocalPhotoSource(coverRelativePath, image)
private fun photoKey(context: Context, entry: LocalPhotoSource, dimension: Int): String? =
    (entry.coverRelativePath?.let { "${context.filesDir.absolutePath}/$it" } ?: entry.image?.let { "drawable:$it" })?.let { "$it:$dimension" }

/** 双栏封面按实际像素宽度分档，小图不再默认解码到 1024 像素。 */
internal fun scheduleThumbnailDimension(widthPixels: Int): Int = when {
    widthPixels <= 256 -> 256
    widthPixels <= 512 -> 512
    else -> 768
}

internal fun clearSchedulePhotoCache() = synchronized(CacheGuard) {
    cacheGeneration++
    ThumbnailCache.evictAll()
    PreviewCache.evictAll()
}

/** 详情首帧可复用列表已有的小图，较大图片继续在后台加载。 */
internal fun peekSchedulePhoto(context: Context, entry: ScheduleEntry, maxDimension: Int): ImageBitmap? =
    peekPhoto(context, entry.photoSource(), maxDimension)

private fun peekPhoto(context: Context, entry: LocalPhotoSource, maxDimension: Int): ImageBitmap? {
    val dimension = maxDimension.coerceIn(128, 2048)
    val cacheKey = photoKey(context, entry, dimension) ?: return null
    photoCache(dimension).get(cacheKey)?.let { return it }
    if (dimension > 768) for (size in intArrayOf(768, 512, 320, 256, 128)) {
        ThumbnailCache.get(requireNotNull(photoKey(context, entry, size)))?.let { return it }
    }
    return null
}

/** 全屏大图不能挤掉列表缩略图；同档解码串行，复用周期及相邻页正在加载的封面。 */
internal suspend fun loadCachedSchedulePhoto(context: Context, entry: ScheduleEntry, maxDimension: Int): ImageBitmap? =
    loadCachedPhoto(context, entry.photoSource(), maxDimension)

private suspend fun loadCachedPhoto(context: Context, entry: LocalPhotoSource, maxDimension: Int): ImageBitmap? {
    val dimension = maxDimension.coerceIn(128, 2048)
    val cacheKey = photoKey(context, entry, dimension) ?: return null
    val cache = photoCache(dimension)
    val load = if (dimension <= 768) ThumbnailLoad else PreviewLoad
    return load.withLock {
        val (cached, generation) = synchronized(CacheGuard) { cache.get(cacheKey) to cacheGeneration }
        if (cached != null) return@withLock cached
        val photo = loadPhoto(context, entry, dimension)
        synchronized(CacheGuard) {
            // 删除附件／任务后清缓存，迟到的解码不能重新写回。
            photo?.also {
                if (generation == cacheGeneration && photoKilobytes(it) <= cache.maxSize()) cache.put(cacheKey, it)
            }
        }
    }
}

@Composable
internal fun schedulePhoto(entry: ScheduleEntry, maxDimension: Int = 1024): ImageBitmap? =
    localPhoto(entry.coverRelativePath, entry.image, maxDimension)

@Composable
internal fun localPhoto(relativePath: String?, resource: Int? = null, maxDimension: Int = 1024): ImageBitmap? {
    val entry = LocalPhotoSource(relativePath, resource)
    val context = LocalContext.current.applicationContext
    val dimension = maxDimension.coerceIn(128, 2048)
    val cacheKey = photoKey(context, entry, dimension)
    return key(cacheKey) {
        val exact = cacheKey?.let { photoCache(dimension).get(it) }
        val bitmap by produceState(exact ?: peekPhoto(context, entry, dimension), context) {
            if (exact == null && cacheKey != null) value = loadCachedPhoto(context, entry, dimension)
        }
        bitmap
    }
}

/** 原图只读、按需解码到至多 1024 像素；丢失或损坏的封面不会使日程不可用。 */
internal suspend fun loadSchedulePhoto(context: Context, entry: ScheduleEntry, maxDimension: Int = 1024): ImageBitmap? =
    loadPhoto(context, entry.photoSource(), maxDimension)

private suspend fun loadPhoto(context: Context, entry: LocalPhotoSource, maxDimension: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val path = entry.coverRelativePath
        val file = if (path != null) {
            val root = context.filesDir.canonicalFile
            val target = File(root, path).canonicalFile
            if (!target.path.startsWith(root.path + File.separator) || !target.isFile) return@withContext null
            target
        } else null
        val resource = entry.image
        if (file == null && resource == null) return@withContext null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        if (file != null) BitmapFactory.decodeFile(file.path, options) else BitmapFactory.decodeResource(context.resources, requireNotNull(resource), options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        var sample = 1
        while (options.outWidth / sample > maxDimension.coerceIn(128, 2048) || options.outHeight / sample > maxDimension.coerceIn(128, 2048)) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bitmap = if (file != null) BitmapFactory.decodeFile(file.path, options) else BitmapFactory.decodeResource(context.resources, requireNotNull(resource), options)
        val orientation = file?.let { runCatching { ExifInterface(it.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull() }
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
        bitmap?.let {
            val oriented = if (matrix.isIdentity) it else Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
            if (oriented !== it) it.recycle()
            oriented.prepareToDraw()
            oriented.asImageBitmap()
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { null }
}
