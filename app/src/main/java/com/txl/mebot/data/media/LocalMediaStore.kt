package com.txl.mebot.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.data.local.MediaSource
import com.txl.mebot.data.local.MebotDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val MediaFiles = Mutex()

enum class BuiltinCover(val key: String) {
    MEETING("meeting"), MEDICAL("medical"), REVIEW("review"),
    FITNESS("fitness"), DINNER("dinner"), GROCERIES("groceries"),
}

/** 正式图片与缓存分开；删除任务后仅清理不再被任何记录引用的应用副本。 */
class LocalMediaStore(
    context: Context,
    private val database: MebotDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val filesRoot = context.applicationContext.filesDir
    private val cacheRoot = File(context.applicationContext.cacheDir, "media_previews")
    private val dao = database.mediaDao()

    suspend fun builtin(cover: BuiltinCover): MediaAssetEntity {
        val id = "builtin:${cover.key}"
        dao.asset(id)?.let { return it }
        dao.insert(MediaAssetEntity(
            id = id, source = MediaSource.BUILT_IN, builtinKey = cover.key,
            mimeType = "image/webp", byteCount = 0, width = 640, height = 640, createdAt = clock.millis(),
        ))
        return requireNotNull(dao.asset(id))
    }

    /** 内容 URI、下载结果和生成结果统一复制；原图与普通附件使用同一去重存储。 */
    suspend fun importImage(
        openSource: () -> InputStream,
        source: MediaSource = MediaSource.IMPORTED,
        originalName: String? = null,
        generationPrompt: String? = null,
        generator: String? = null,
        onImported: suspend (MediaAssetEntity) -> Unit = {},
    ): MediaAssetEntity = importStream(openSource, source, originalName, "image/*", true, generationPrompt, generator, onImported)

    suspend fun importDocument(
        openSource: () -> InputStream, originalName: String, mimeHint: String?, requireImage: Boolean = false,
        onImported: suspend (MediaAssetEntity) -> Unit = {},
    ): MediaAssetEntity = importStream(openSource, MediaSource.IMPORTED, originalName, mimeHint, requireImage,
        onImported = onImported)

    private suspend fun importStream(
        openSource: () -> InputStream, source: MediaSource, originalName: String?, mimeHint: String?, requireImage: Boolean,
        generationPrompt: String? = null, generator: String? = null,
        onImported: suspend (MediaAssetEntity) -> Unit = {},
    ): MediaAssetEntity = withContext(Dispatchers.IO) {
        require(source != MediaSource.BUILT_IN)
        val staging = File(filesRoot, "media/staging")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建附件暂存目录" }
        val temporary = File.createTempFile("import-", ".part", staging)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val imageExpected = requireImage || mimeHint?.startsWith("image/") == true
            val limit = if (imageExpected) MAX_IMAGE_BYTES else MAX_FILE_BYTES
            openSource().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        size += read
                        require(size <= limit) { if (imageExpected) "单张图片不能超过 50 MiB" else "单个文件不能超过 100 MiB" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, options)
            val isImage = options.outWidth > 0 && options.outHeight > 0 && options.outMimeType != null
            require(!imageExpected || isImage) { "无法读取这张图片，请选择其他图片" }
            require(!isImage || size <= MAX_IMAGE_BYTES) { "单张图片不能超过 50 MiB" }
            val mime = if (isImage) requireNotNull(options.outMimeType) else mimeHint?.takeIf {
                Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+").matches(it)
            } ?: "application/octet-stream"
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val extension = when (if (isImage) mime else null) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/heif", "image/heic" -> "heic"
                else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { Regex("[a-zA-Z0-9]{1,10}").matches(it) } ?: "bin"
            }
            val relativePath = "media/originals/${hash.take(2)}/$hash.$extension"
            val destination = resolve(relativePath)
            check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建附件目录" }
            val asset = MediaAssetEntity(
                id = UUID.randomUUID().toString(), source = source, relativePath = relativePath,
                originalName = originalName, mimeType = mime, byteCount = size,
                width = if (isImage) options.outWidth else 0, height = if (isImage) options.outHeight else 0, sha256 = hash,
                generationPrompt = generationPrompt, generator = generator, createdAt = clock.millis(),
            )
            // 文件发布、去重元数据及所属会话关联与清理互斥，关联失败不能留下半条附件。
            MediaFiles.withLock {
                val existing = dao.assetByHash(hash)
                val finalFile = existing?.relativePath?.let(::resolve) ?: destination
                check(finalFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                if (!finalFile.exists()) check(temporary.renameTo(finalFile) || finalFile.exists()) { "附件保存失败" }
                try {
                    database.withTransaction {
                        dao.insert(asset)
                        requireNotNull(dao.assetByHash(hash)).also { onImported(it) }
                    }
                } catch (failure: Exception) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (!dao.pathIsRegistered(relativePath)) dao.queueCleanup(com.txl.mebot.data.local.MediaCleanupEntity(relativePath))
                    }
                    throw failure
                }
            }
        } finally {
            temporary.delete()
        }
    }

    suspend fun fileFor(assetId: String): File? = withContext(Dispatchers.IO) {
        dao.asset(assetId)?.relativePath?.let(::resolve)?.takeIf { it.isFile }
    }

    suspend fun clearDerivedCache(): Unit = withContext(Dispatchers.IO) {
        if (cacheRoot.exists()) check(cacheRoot.deleteRecursively()) { "预览缓存清理失败" }
    }

    /** 清理失败的路径继续留在队列，下次启动重试；与图片导入串行，保护复用中的文件。 */
    suspend fun cleanPendingDeletions(): Unit = withContext(Dispatchers.IO) {
        val paths = dao.cleanupPaths(128)
        for (path in paths) MediaFiles.withLock {
            database.withTransaction {
                if (dao.pathIsRegistered(path)) {
                    dao.finishCleanup(path)
                } else {
                    val file = runCatching { resolve(path) }.getOrNull()
                    val originals = File(filesRoot, "media/originals").canonicalPath + File.separator
                    if (file == null || !file.path.startsWith(originals) || !file.exists() || file.delete()) dao.finishCleanup(path)
                }
            }
        }
    }

    private fun resolve(relativePath: String): File {
        val root = filesRoot.canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "图片路径不在应用存储内" }
        return file
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
        private const val MAX_FILE_BYTES = 100L * 1024 * 1024
    }
}
