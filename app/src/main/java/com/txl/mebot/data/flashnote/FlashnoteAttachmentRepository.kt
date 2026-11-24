package com.txl.mebot.data.flashnote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.txl.mebot.data.local.FlashnoteDraftEntity
import com.txl.mebot.data.media.LocalMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FlashnoteAttachmentImport(val draft: FlashnoteDraftEntity, val assetId: String)

/** 复用应用持久媒体存储，复制成功后与当前闪念草稿一起落库。 */
class FlashnoteAttachmentRepository(context: Context, private val notes: FlashnoteRepository, private val media: LocalMediaStore) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun importUri(draft: FlashnoteDraftEntity, uri: Uri, imagesOnly: Boolean): FlashnoteAttachmentImport = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统选择器中的图片或文件" }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.trim()?.take(200)?.takeIf { it.isNotBlank() } ?: "附件"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
        var saved: FlashnoteAttachmentImport? = null
        media.importDocument({ requireNotNull(resolver.openInputStream(uri)) { "无法读取附件" } }, name, mime, imagesOnly) {
            saved = FlashnoteAttachmentImport(notes.attach(draft, it, name), it.id)
        }
        requireNotNull(saved)
    }

    suspend fun cleanPendingDeletions() = media.cleanPendingDeletions()
}
