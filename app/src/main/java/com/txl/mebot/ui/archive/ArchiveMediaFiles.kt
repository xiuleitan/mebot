package com.txl.mebot.ui.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.txl.mebot.data.archive.ArchiveAttachmentRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class ArchiveMediaFiles(private val context: Context, private val attachments: ArchiveAttachmentRepository,
    private val enqueue: (androidx.work.OneTimeWorkRequest) -> Unit = { request -> WorkManager.getInstance(context).enqueue(request).result.get() }) {
    private val exports = ArchiveExportCache(context)
    suspend fun import(sessionId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.take(1_000) ?: "附件"
        runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) }.getOrNull()?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) name = cursor.getString(column)?.take(1_000) ?: name
        }
        val reported = runCatching { resolver.getType(uri) }.getOrNull()?.takeUnless { it == "application/octet-stream" }
        val mime = reported ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        attachments.import(sessionId, name, mime) { requireNotNull(resolver.openInputStream(uri)) }
    }
    suspend fun prepareCamera(): Pair<String, Uri> = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "archive_ui_camera").apply { check(isDirectory || mkdirs()) }
        val file = File.createTempFile("capture-", ".jpg", directory)
        file.path to cameraUri(file.path)
    }
    fun cameraUri(path: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", cameraFile(path))
    suspend fun deleteCamera(path: String) = withContext(Dispatchers.IO) { cameraFile(path).delete(); Unit }
    private fun cameraFile(path: String) = File(path).also {
        require(it.canonicalFile.parentFile == File(context.cacheDir, "archive_ui_camera").canonicalFile)
    }

    suspend fun image(id: String, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!attachments.metadata(id).mimeType.startsWith("image/")) return@withContext null
        attachments.withDecryptedFile(id) { file ->
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val edge = maxOf(info.size.width, info.size.height)
                    if (edge > maxEdge) decoder.setTargetSize((info.size.width.toLong() * maxEdge / edge).toInt().coerceAtLeast(1),
                        (info.size.height.toLong() * maxEdge / edge).toInt().coerceAtLeast(1))
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > maxEdge) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withDecryptedFile null
                val exif = runCatching { ExifInterface(file) }.getOrNull()
                val matrix = Matrix().apply { if (exif?.isFlipped == true) postScale(-1f, 1f); postRotate(exif?.rotationDegrees?.toFloat() ?: 0f) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
            }
        }
    }

    /** 只有“打开文件”显式动作才生成共享副本；原件始终保持认证加密。 */
    suspend fun exportForViewing(id: String): Pair<Uri, String> {
        var publishedToken: String? = null
        try { return withContext(Dispatchers.IO) {
        val info = attachments.metadata(id)
        exports.locked {
            clean(); clearInterruptedStaging()
            check(staging.isDirectory || staging.mkdirs())
            val token = UUID.randomUUID().toString()
            publishedToken = token
            val temporary = File(staging, token).apply { check(mkdirs()) }
            val directory = destination(token)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(info.mimeType)?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) } ?: "bin"
            val name = "${UUID.randomUUID()}.$extension"
            try {
                val output = File(temporary, name)
                val coroutine = currentCoroutineContext()
                attachments.withDecryptedFile(id) { plain -> FileOutputStream(output).use { stream ->
                    plain.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        try { while (true) {
                            coroutine.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            stream.write(buffer, 0, size)
                        } } finally { buffer.fill(0) }
                    }; stream.flush(); stream.fd.sync()
                } }
                clean(reserveBytes = output.length(), reserveCount = 1)
                val request = OneTimeWorkRequestBuilder<ArchiveExportCleanupWorker>()
                    .setInputData(workDataOf("directory" to token)).setInitialDelay(1, TimeUnit.HOURS).build()
                enqueue(request)
                coroutine.ensureActive()
                check(temporary.renameTo(directory))
                coroutine.ensureActive()
                FileProvider.getUriForFile(context, "${context.packageName}.attachments", File(directory, name)) to info.mimeType
            } catch (error: Exception) { directory.deleteRecursively(); throw error }
            finally { temporary.deleteRecursively() }
        }
        } } catch (error: Exception) {
            publishedToken?.let { token -> withContext(NonCancellable + Dispatchers.IO) { exports.locked { remove(token) } } }
            throw error
        }
    }
    suspend fun cleanExpiredExports() = withContext(Dispatchers.IO) {
        exports.locked { clean(); clearInterruptedStaging() }
    }
}

/** WorkManager 跨页面/进程回收派生明文副本，不访问业务原件。 */
class ArchiveExportCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString("directory") ?: return@withContext Result.failure()
        if (!name.matches(Regex("[a-f0-9-]{36}"))) return@withContext Result.failure()
        try { ArchiveExportCache(applicationContext).locked { remove(name) }; Result.success() }
        catch (_: Exception) { Result.retry() }
    }
}
