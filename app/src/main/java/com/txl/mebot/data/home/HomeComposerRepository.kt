package com.txl.mebot.data.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import java.time.Clock
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅保存首页输入和可靠本地附件；不创建业务记录、不调用模型。 */
class HomeComposerRepository(context: Context, private val database: MebotDatabase,
    private val media: LocalMediaStore, private val clock: Clock = Clock.systemUTC()) {
    private val resolver = context.applicationContext.contentResolver
    private val dao = database.homeComposer()
    suspend fun read(): HomeComposerDraftEntity = database.withTransaction {
        dao.insert(HomeComposerDraftEntity())
        requireNotNull(dao.draft())
    }
    fun observeAttachments() = dao.observeAttachments()
    suspend fun attachments() = dao.attachments()
    suspend fun saveInput(text: String, revision: Long): HomeComposerDraftEntity = database.withTransaction {
        check(dao.saveInput(text, revision, clock.millis()) == 1) { "草稿已变化，请重试保存" }
        requireNotNull(dao.draft())
    }
    suspend fun importUri(uri: Uri, imagesOnly: Boolean): MediaAssetEntity = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统选择器中的图片或文件" }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.trim()?.take(200)?.takeIf(String::isNotBlank) ?: "附件"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        media.importDocument({ requireNotNull(resolver.openInputStream(uri)) { "无法读取附件" } }, name, mime, imagesOnly) {
            attach(it, name, uri.toString())
        }
    }
    /** 在媒体发布的同一事务内关联，失败不留下半条草稿附件。 */
    suspend fun attach(asset: MediaAssetEntity, name: String, sourceUri: String? = null) = database.withTransaction {
        requireNotNull(dao.draft())
        requireNotNull(database.mediaDao().asset(asset.id))
        val items = dao.attachments()
        dao.attach(HomeComposerAttachmentEntity(assetId = asset.id, displayName = name,
            position = (items.maxOfOrNull { it.link.position } ?: -1L) + 1, sourceUri = sourceUri))
    }
    suspend fun remove(id: String) = database.withTransaction {
        val item = dao.attachments().firstOrNull { it.asset.id == id } ?: return@withTransaction
        dao.remove(id)
        if (database.mediaDao().deleteIfUnused(id) == 1) item.asset.relativePath?.let {
            database.mediaDao().queueCleanup(MediaCleanupEntity(it))
        }
    }
    suspend fun cleanPendingDeletions() = media.cleanPendingDeletions()
    suspend fun fileFor(id: String) = if (dao.attachments().any { it.asset.id == id }) media.fileFor(id) else null
}
