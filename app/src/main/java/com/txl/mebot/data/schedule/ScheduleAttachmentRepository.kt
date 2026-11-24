package com.txl.mebot.data.schedule

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.data.local.MediaCleanupEntity
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.ScheduleLocalAttachmentEntity
import com.txl.mebot.data.media.LocalMediaStore
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** 只处理本地附件、封面与会话关联；不调用或上传到模型服务。 */
class ScheduleAttachmentRepository(
    context: Context, private val database: MebotDatabase, private val media: LocalMediaStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val resolver = context.applicationContext.contentResolver
    private val dao = database.scheduleMediaDao()
    fun observe(sessionId: String) = dao.observeAttachments(sessionId).distinctUntilChanged()

    suspend fun importUri(sessionId: String, uri: Uri, imagesOnly: Boolean = false, asPending: Boolean = true): MediaAssetEntity = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统选择器中的图片或文件" }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.trim()?.take(200)?.takeIf { it.isNotBlank() } ?: "附件"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
        media.importDocument({ requireNotNull(resolver.openInputStream(uri)) { "无法读取所选文件" } }, name, mime, imagesOnly) {
            attach(sessionId, it, name, asPending)
        }
    }

    /** 与文件元数据同一事务调用，也可供本地导入测试使用。 */
    suspend fun attach(sessionId: String, asset: MediaAssetEntity, name: String = asset.originalName ?: "附件", asPending: Boolean = true) = database.withTransaction {
        requireNotNull(database.scheduleDao().session(sessionId)) { "原会话已关闭，请重新选择附件" }
        requireNotNull(database.mediaDao().asset(asset.id))
        val current = dao.attachments(sessionId)
        if (current.none { it.asset.id == asset.id }) dao.insert(ScheduleLocalAttachmentEntity(
            sessionId, asset.id, name, dao.nextPosition(sessionId), clock.millis(), pendingSend = asPending, draftOnly = asPending,
        ))
        else if (asPending) dao.markPending(sessionId, asset.id, true)
        val links = dao.attachments(sessionId)
        val cover = links.firstOrNull { it.link.isCover && it.isImage } ?: links.firstOrNull { it.isImage }
        if (cover != null) applyCover(sessionId, cover.asset.id)
        database.scheduleDao().touchSession(sessionId, clock.millis())
    }

    suspend fun chooseCover(sessionId: String, assetId: String) = database.withTransaction {
        val item = dao.attachments(sessionId).firstOrNull { it.asset.id == assetId }
        require(item?.isImage == true) { "只有当前任务中的图片可以用作封面" }
        applyCover(sessionId, assetId)
        database.scheduleDao().touchSession(sessionId, clock.millis())
    }

    suspend fun remove(sessionId: String, assetId: String) = database.withTransaction {
        requireNotNull(database.scheduleDao().session(sessionId))
        val old = dao.attachments(sessionId).firstOrNull { it.asset.id == assetId } ?: return@withTransaction
        dao.remove(sessionId, assetId)
        if (old.link.isCover || database.scheduleDao().taskForSession(sessionId)?.coverAssetId == assetId) {
            val next = dao.attachments(sessionId).firstOrNull { it.isImage }
            applyCover(sessionId, next?.asset?.id)
        }
        if (database.mediaDao().deleteIfUnused(assetId) == 1) old.asset.relativePath?.let {
            database.mediaDao().queueCleanup(MediaCleanupEntity(it))
        }
        database.scheduleDao().touchSession(sessionId, clock.millis())
    }

    suspend fun discardPending(sessionId: String, assetId: String) = database.withTransaction {
        val item = dao.attachments(sessionId).firstOrNull { it.asset.id == assetId && it.link.pendingSend } ?: return@withTransaction
        if (item.link.draftOnly) remove(sessionId, assetId) else dao.markPending(sessionId, assetId, false)
    }

    private suspend fun applyCover(sessionId: String, assetId: String?) {
        if (assetId != null) database.scheduleCoverDao().preferUserImage(sessionId, clock.millis())
        val task = database.scheduleDao().taskForSession(sessionId)
        val oldMedia = task?.let { database.scheduleDao().taskMedia(it.id, sessionId) }.orEmpty()
        dao.selectCover(sessionId, assetId)
        dao.updateTaskCover(sessionId, assetId, clock.millis())
        dao.updateVersionCovers(sessionId, assetId)
        dao.updateOccurrenceCovers(sessionId, assetId)
        for (asset in oldMedia) if (database.mediaDao().deleteIfUnused(asset.id) == 1) asset.relativePath?.let {
            database.mediaDao().queueCleanup(MediaCleanupEntity(it))
        }
    }
}
