package com.txl.mebot.data.subscriptions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.data.local.MediaCleanupEntity
import com.txl.mebot.data.local.SubscriptionConversationMediaEntity
import com.txl.mebot.data.media.LocalMediaStore
import java.io.InputStream
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 复用主库媒体存储的可靠复制与文件发布；正式接线时 media 与 storage 必须来自同一主库。 */
class SubscriptionAttachmentRepository(context: Context, private val storage: SubscriptionStorage,
    private val media: LocalMediaStore, private val clock: Clock = Clock.systemUTC()) {
    private val resolver = context.applicationContext.contentResolver
    private val dao = storage.conversations

    fun observe(id: String) = dao.observeMedia(id)

    suspend fun importUri(conversationId: String, uri: Uri, asPending: Boolean = true,
        onAttached: suspend (MediaAssetEntity) -> Unit = {}): MediaAssetEntity = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请从系统选择器选择图片" }
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.trim()?.take(200)?.takeIf(String::isNotBlank) ?: "订阅截图"
        importImage(conversationId, { requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" } }, name, asPending, onAttached)
    }

    suspend fun importImage(conversationId: String, openSource: () -> InputStream, name: String = "订阅截图", asPending: Boolean = true,
        onAttached: suspend (MediaAssetEntity) -> Unit = {}): MediaAssetEntity {
        requireNotNull(dao.conversation(conversationId)) { "会话不存在" }
        return media.importImage(openSource, originalName = name) {
            attach(conversationId, it.id, name, asPending)
            onAttached(it)
        }
    }

    suspend fun attach(conversationId: String, assetId: String, displayName: String, asPending: Boolean = true) = storage.database.withTransaction {
        requireNotNull(dao.conversation(conversationId)) { "会话不存在" }
        val asset = requireNotNull(storage.media.asset(assetId)) { "附件不存在" }
        require(asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0) { "请选择图片" }
        val name = displayName.trim().take(200).ifEmpty { "订阅截图" }
        if (dao.mediaLinks(conversationId).none { it.assetId == assetId }) {
            dao.insertMedia(SubscriptionConversationMediaEntity(conversationId, assetId, name,
                dao.nextMediaPosition(conversationId), asPending, clock.millis()))
        } else if (asPending) dao.markPending(conversationId, assetId, true)
        dao.touch(conversationId, clock.millis())
    }

    /** 已发送图片只取消待发状态；只释放本会话未使用、且其他业务也不再引用的图片。 */
    suspend fun discardPending(conversationId: String, assetId: String) = storage.database.withTransaction {
        val link = dao.mediaLinks(conversationId).firstOrNull { it.assetId == assetId && it.pendingSend } ?: return@withTransaction
        val removed = dao.removeUnsentMedia(conversationId, assetId)
        if (removed == 0) dao.markPending(conversationId, assetId, false)
        else if (!storage.records.referencesMedia(assetId)) {
            val asset = storage.media.asset(link.assetId)
            if (storage.media.deleteIfUnused(assetId) == 1) asset?.relativePath?.let { storage.media.queueCleanup(MediaCleanupEntity(it)) }
        }
        dao.touch(conversationId, clock.millis())
    }
}
