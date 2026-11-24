package com.txl.mebot.data.dayima

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import java.io.InputStream
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DayimaImageImport(val draft: DayimaChatDraftEntity, val assetId: String)

/** 原件复制与草稿链接处于同一发布事务；图片从不进入模型传输。 */
class DayimaAttachmentRepository(context: Context, private val database: MebotDatabase,
    private val media: LocalMediaStore, private val clock: Clock = Clock.systemUTC()) {
    private val resolver = context.applicationContext.contentResolver
    private val dao get() = database.dayimaMediaDao()
    private val chats get() = database.dayimaDao()

    suspend fun importUri(draft: DayimaChatDraftEntity, uri: Uri): DayimaImageImport = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请从相册选择图片" }
        val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.trim()?.take(200)?.takeIf(String::isNotEmpty) ?: "图片"
        importImage(draft, { requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" } }, name)
    }

    suspend fun importImage(draft: DayimaChatDraftEntity, source: () -> InputStream, name: String = "图片"): DayimaImageImport {
        var result: DayimaImageImport? = null
        media.importImage(source, originalName = name) { asset ->
            result = DayimaImageImport(attachDraft(draft, asset.id, name), asset.id)
        }
        return requireNotNull(result)
    }

    suspend fun attachDraft(expected: DayimaChatDraftEntity, assetId: String, displayName: String): DayimaChatDraftEntity = database.withTransaction {
        activeDraft(expected)
        val asset = requireNotNull(database.mediaDao().asset(assetId))
        require(asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0)
        if (dao.draftLinks(expected.sessionId).none { it.assetId == assetId }) {
            check(dao.insertDraft(DayimaDraftMediaEntity(expected.sessionId, assetId, displayName.take(200),
                dao.nextDraftPosition(expected.sessionId), clock.millis())) != -1L)
            check(dao.bumpDraft(expected.sessionId, expected.revision, clock.millis()) == 1)
            touch(expected.sessionId)
        }
        requireNotNull(chats.draft(expected.sessionId))
    }

    suspend fun removeDraft(expected: DayimaChatDraftEntity, assetId: String): DayimaChatDraftEntity = database.withTransaction {
        activeDraft(expected)
        if (dao.removeDraft(expected.sessionId, assetId) != 0) {
            check(dao.bumpDraft(expected.sessionId, expected.revision, clock.millis()) == 1)
            releaseDayimaImages(database, listOf(assetId))
            touch(expected.sessionId)
        }
        requireNotNull(chats.draft(expected.sessionId))
    }

    suspend fun draftImages(sessionId: String) = dao.draftImages(sessionId)

    suspend fun removePeriodImage(periodId: String, assetId: String) = database.withTransaction {
        requireNotNull(chats.period(periodId)) { "开始记录已不存在" }
        if (dao.removePeriod(periodId, assetId) != 0) releaseDayimaImages(database, listOf(assetId))
    }

    suspend fun cleanPendingDeletions() = media.cleanPendingDeletions()

    private suspend fun activeDraft(expected: DayimaChatDraftEntity) {
        if (chats.session(expected.sessionId)?.status != DayimaChatStatus.ACTIVE || chats.draft(expected.sessionId) != expected)
            throw DayimaChatChanged()
    }
    private suspend fun touch(id: String) {
        val session = requireNotNull(chats.session(id))
        check(chats.updateSession(id, session.title, session.status, clock.millis(), session.revision) == 1)
    }
}

/** 调用方已持有主库事务；共享媒体仍有任何引用时不释放文件。 */
internal suspend fun releaseDayimaImages(database: MebotDatabase, assetIds: List<String>) {
    val media = database.mediaDao()
    for (id in assetIds.distinct()) {
        val asset = media.asset(id) ?: continue
        if (media.deleteIfUnused(id) == 1) asset.relativePath?.let { media.queueCleanup(MediaCleanupEntity(it)) }
    }
}
