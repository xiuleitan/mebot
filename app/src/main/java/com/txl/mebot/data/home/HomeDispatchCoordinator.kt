package com.txl.mebot.data.home

import android.content.Context
import androidx.room.withTransaction
import com.txl.mebot.data.AppContainer
import com.txl.mebot.data.local.*
import com.txl.mebot.data.archive.ArchiveDraftContent
import com.txl.mebot.data.flashnote.AndroidFlashnoteTitleWork
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray

data class HomeSendSnapshot(val text: String, val revision: Long, val attachments: List<HomeComposerAttachment>) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(JSONArray().put(revision).put(text)
        .put(JSONArray(attachments.map { it.asset.id })).toString().toByteArray()).joinToString("") { "%02x".format(it) }
    override fun toString() = "HomeSendSnapshot(revision=$revision, attachments=${attachments.size}, text=[redacted])"
}

/** 仅交接输入；每个 Bot 的消息、草稿和保存都复用原数据层。 */
class HomeDispatchCoordinator(private val context: Context, private val container: AppContainer,
    private val archiveRecords: com.txl.mebot.data.archive.ArchiveRepository? = null,
    private val archiveFiles: com.txl.mebot.data.archive.ArchiveAttachmentRepository? = null) {
    private val db = container.database
    private val dao = db.homeRouting()
    private val archives get() = archiveRecords ?: container.archives
    private val files get() = archiveFiles ?: container.archiveAttachments
    suspend fun active() = dao.active()
    suspend fun dismiss(id: String) = dao.dismiss(id)
    suspend fun dispatch(bot: HomeBot, source: HomeSendSnapshot): HomeDispatchEntity = withContext(Dispatchers.IO) {
        val mention = HomeBotMention.parse(source.text)
        require(!mention.hasMention || mention.bot == bot) { "请先选择正确的 @Bot" }
        val message = mention.body
        require(message.isNotBlank()) { "请先输入文字" }
        if (bot == HomeBot.SUBSCRIPTIONS) require(message.length <= 5_000 && source.attachments.size <= 4) { "订阅每次最多发送5000字和4张图片" }
        if (bot in setOf(HomeBot.DAYIMA, HomeBot.SUBSCRIPTIONS)) require(source.attachments.all { it.isImage }) { "${bot.label}只支持图片附件，请移除普通文件或选择其他 Bot" }
        val snapshotKey = "${source.key}:${bot.id}"
        val ticket = db.withTransaction {
            dao.dispatch(snapshotKey)
                ?: HomeDispatchEntity(UUID.randomUUID().toString(), snapshotKey, bot.id,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), System.currentTimeMillis()).also { dao.insert(it) }
        }
        if (ticket.handedOff) return@withContext ticket
        // 档案保留原有加密格式；失败保留首页输入，重试使用同一附件 ID。
        val archiveAssets = if (bot == HomeBot.ARCHIVE) {
            archives.createSession(ticket.targetId)
            source.attachments.map { item ->
                val id = UUID.nameUUIDFromBytes("${ticket.id}:${item.asset.id}".toByteArray()).toString()
                val file = requireNotNull(container.media.fileFor(item.asset.id)) { "附件原件已不可用" }
                files.import(ticket.targetId, item.link.displayName, item.asset.mimeType, id, source = { file.inputStream() })
            }
        } else emptyList()
        val delivered = db.withTransaction {
            dao.dispatch(snapshotKey)?.takeIf { it.handedOff }?.let { return@withTransaction it }
            val draft = requireNotNull(db.homeComposer().draft())
            check(draft.revision == source.revision && draft.inputText == source.text &&
                db.homeComposer().attachments().map { it.asset.id } == source.attachments.map { it.asset.id }) { "首页草稿已变化，原内容已保留，请重新发送" }
            val id = ticket.targetId
            when (bot) {
                HomeBot.SCHEDULE -> {
                    container.schedules.openSession(id)
                    var target = container.conversations.openConversation(id)
                    source.attachments.forEach { container.scheduleAttachments.attach(id, it.asset, it.link.displayName) }
                    target = container.conversations.saveInput(id, message, target.revision)
                    container.conversations.sendUserMessage(id, ticket.messageId, message, target.revision)
                }
                HomeBot.FLASHNOTE -> {
                    var target = FlashnoteDraftEntity(id, updatedAt = System.currentTimeMillis())
                    db.flashnoteDao().insertDraft(target)
                    source.attachments.forEach { target = container.flashnotes.attach(target, it.asset, it.link.displayName) }
                    target = container.flashnotes.saveInput(id, "", message, target.revision)
                    container.flashnotes.save(target)
                }
                HomeBot.DAYIMA -> {
                    val now = System.currentTimeMillis()
                    db.dayimaDao().insertSession(DayimaChatSessionEntity(id, createdAt = now, updatedAt = now))
                    db.dayimaDao().insertDraft(DayimaChatDraftEntity(id, updatedAt = now))
                    var target = requireNotNull(db.dayimaDao().draft(id))
                    source.attachments.forEach { target = container.dayimaAttachments.attachDraft(target, it.asset.id, it.link.displayName) }
                    target = container.dayimaConversations.saveInput(id, message, target.revision)
                    container.dayimaConversations.send(id, message, ticket.messageId, target.revision)
                }
                HomeBot.SUBSCRIPTIONS -> {
                    container.subscriptionConversations.newConversation(id)
                    source.attachments.forEach { container.subscriptionAttachments.attach(id, it.asset.id, it.link.displayName) }
                    val target = requireNotNull(db.subscriptionConversations().draft(id))
                    val saved = container.subscriptionConversations.saveDraft(id, message, target.fieldsJson, target.revision)
                    container.subscriptionChat.send(id, ticket.messageId, message, source.attachments.map { it.asset.id }, saved.revision)
                }
                HomeBot.ARCHIVE -> {
                    val target = archives.draft(id)
                    val saved = archives.saveDraft(id, target.revision, ArchiveDraftContent(input = message))
                    archives.sendMessage(id, ticket.messageId, saved.revision, message, archiveAssets)
                }
            }
            check(db.homeComposer().saveInput("", source.revision, System.currentTimeMillis()) == 1)
            source.attachments.forEach { item ->
                db.homeComposer().remove(item.asset.id)
                if (db.mediaDao().deleteIfUnused(item.asset.id) == 1) item.asset.relativePath?.let { db.mediaDao().queueCleanup(MediaCleanupEntity(it)) }
            }
            dao.handedOff(ticket.id, bot == HomeBot.FLASHNOTE)
            requireNotNull(dao.dispatchById(ticket.id))
        }
        if (bot == HomeBot.FLASHNOTE) runCatching {
            container.flashnotes.note(delivered.targetId)?.let { AndroidFlashnoteTitleWork(context).enqueue(it.id, it.revision) }
        }
        delivered
    }
}
