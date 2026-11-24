package com.txl.mebot.ui.archive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.txl.mebot.data.archive.ArchiveAssetInfo
import com.txl.mebot.data.archive.ArchiveAttachmentRepository
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 用户主动下载的原图副本，不复用会被回收的查看缓存，不改变档案内的加密原件。 */
internal class ArchiveImageDownloads(private val context: Context, private val attachments: ArchiveAttachmentRepository) {
    suspend fun suggestedName(id: String): String = name(imageInfo(id))

    suspend fun saveToGallery(id: String): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) error("Use the system document picker on this Android version")
        val info = imageInfo(id)
        attachments.withDecryptedFile(id) { original ->
            // 原件通过认证之后才创建目标；未完成的系统媒体行不对相册可见。
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name(info))
                put(MediaStore.Images.Media.MIME_TYPE, info.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/mebot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = checkNotNull(resolver.insert(collection, values))
            try {
                copy(original, checkNotNull(resolver.openOutputStream(uri, "w")))
                currentCoroutineContext().ensureActive()
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                uri
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
                throw error
            }
        }
    }

    /** Android 8/9 由系统“创建文件”选择目标，不请求旧版全盘写入权限。 */
    suspend fun saveToDocument(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && uri.authority != "${context.packageName}.attachments")
        val resolver = context.contentResolver
        try {
            imageInfo(id)
            attachments.withDecryptedFile(id) { original ->
                copy(original, checkNotNull(resolver.openOutputStream(uri, "w")))
                currentCoroutineContext().ensureActive()
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
                    else resolver.delete(uri, null, null)
                }
            }
            throw error
        }
    }

    private suspend fun imageInfo(id: String): ArchiveAssetInfo = attachments.metadata(id).also {
        require(it.mimeType.startsWith("image/"))
    }

    private fun name(info: ArchiveAssetInfo): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(info.mimeType)
            ?: info.name.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
        return "mebot_${UUID.randomUUID()}" + (extension?.let { ".$it" } ?: "")
    }

    private suspend fun copy(original: File, stream: OutputStream) {
        stream.use { output ->
            original.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                    output.flush()
                } finally { buffer.fill(0) }
            }
        }
    }
}
