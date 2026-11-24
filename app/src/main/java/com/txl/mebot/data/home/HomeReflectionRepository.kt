package com.txl.mebot.data.home

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import com.txl.mebot.data.media.LocalMediaStore
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 原始寄语保存在主库；编辑只补丁更新对应字段，不覆盖已导入图片或其他字段。 */
class HomeReflectionRepository(private val context: Context, private val database: MebotDatabase,
    private val media: LocalMediaStore, private val clock: Clock = Clock.systemUTC()) {
    private val dao = database.homeReflection()
    private val history = database.homeReflectionHistory()
    fun observe() = dao.observe().map { it ?: HomeReflectionRecord() }
    suspend fun read() = dao.record() ?: HomeReflectionRecord()
    suspend fun historyPage(limit: Int, offset: Int = 0): List<HomeReflectionHistoryRecord> {
        require(limit in 1..101 && offset >= 0)
        return history.page(limit, offset)
    }
    suspend fun rememberCurrent() = database.withTransaction { remember(read().content) }
    suspend fun libraryPage(limit: Int, offset: Int = 0) = database.withTransaction { read() to historyPage(limit, offset) }
    suspend fun librarySnapshot(limit: Int) = database.withTransaction {
        require(limit > 0)
        read() to history.page(limit, 0)
    }
    suspend fun addText(text: String): HomeReflectionRecord = database.withTransaction {
        validateText(text)
        val record = write(text, HomeReflectionMode.TEXT, rememberBefore = true)
        remember(record.content)
        record
    }

    /** 编辑替换这一条记录；只有它正在展示（或是该模式的保留内容）时才同步首页。 */
    suspend fun editText(original: HomeReflectionHistoryEntity, text: String): HomeReflectionRecord = database.withTransaction {
        require(original.mode == HomeReflectionMode.TEXT)
        validateText(text)
        replaceEntry(original, original.copy(id = reflectionTextHistoryId(text), text = text))
    }
    suspend fun editImage(original: HomeReflectionHistoryEntity, uri: Uri,
        stillCurrent: () -> Boolean = { true }): HomeReflectionRecord = withContext(Dispatchers.IO) {
        require(original.mode == HomeReflectionMode.IMAGE)
        media.importImage({ requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开图片" } }, onImported = { asset ->
            check(stillCurrent()) { "编辑页已关闭，请重新选择图片" }
            database.withTransaction { replaceEntry(original, original.copy(id = reflectionImageHistoryId(asset.id), imageAssetId = asset.id)) }
        })
        read()
    }
    suspend fun deleteEntry(original: HomeReflectionHistoryEntity): HomeReflectionRecord = database.withTransaction {
        requireCurrentEntry(original)
        val before = read().content
        val active = reflectionHistoryId(before) == original.id
        // 当前展示删除后留空；另一模式仍保存的内容先放入历史，避免顺带丢失。
        if (active) when (original.mode) {
            HomeReflectionMode.TEXT -> remember(before.copy(mode = HomeReflectionMode.IMAGE), usedNow = false)
            HomeReflectionMode.IMAGE -> remember(before.copy(mode = HomeReflectionMode.TEXT), usedNow = false)
        }
        check(history.delete(original.id) == 1)
        val after = if (active) before.copy(text = "", imageAssetId = null, mode = HomeReflectionMode.TEXT)
        else when (original.mode) {
            HomeReflectionMode.TEXT -> if (before.text == original.text) before.copy(text = "") else before
            HomeReflectionMode.IMAGE -> if (before.imageAssetId == original.imageAssetId) before.copy(imageAssetId = null) else before
        }
        saveCurrent(before, after)
        (listOfNotNull(original.imageAssetId, before.imageAssetId.takeIf { it != after.imageAssetId })).distinct().forEach { releaseImage(it) }
        read()
    }

    private suspend fun replaceEntry(original: HomeReflectionHistoryEntity, replacement: HomeReflectionHistoryEntity): HomeReflectionRecord {
        val latest = requireCurrentEntry(original)
        val before = read().content
        val active = reflectionHistoryId(before) == original.id
        val existing = history.entry(replacement.id)
        val merged = replacement.copy(firstUsedAt = minOf(latest.firstUsedAt, existing?.firstUsedAt ?: latest.firstUsedAt),
            lastUsedAt = if (active) clock.millis() else maxOf(latest.lastUsedAt, existing?.lastUsedAt ?: latest.lastUsedAt))
        if (existing == null) history.insert(merged) else check(history.update(merged) == 1)
        if (original.id != replacement.id) check(history.delete(original.id) == 1)
        val after = when (original.mode) {
            HomeReflectionMode.TEXT -> if (before.text == original.text) before.copy(text = replacement.text) else before
            HomeReflectionMode.IMAGE -> if (before.imageAssetId == original.imageAssetId) before.copy(imageAssetId = replacement.imageAssetId) else before
        }
        saveCurrent(before, after)
        if (original.imageAssetId != replacement.imageAssetId) original.imageAssetId?.let { releaseImage(it) }
        return read()
    }
    private suspend fun requireCurrentEntry(original: HomeReflectionHistoryEntity): HomeReflectionHistoryEntity {
        val latest = history.entry(original.id)
        require(latest?.copy(lastUsedAt = original.lastUsedAt) == original) { "这条内容已更新或删除，请重新打开后再试" }
        return requireNotNull(latest)
    }
    private fun validateText(text: String) {
        require(text.isNotBlank()) { "请先写下一段文字" }
        require(text.length <= MAX_TEXT) { "文字最多${MAX_TEXT}字，当前输入已保留" }
    }
    private suspend fun saveCurrent(before: HomeReflectionEntity, after: HomeReflectionEntity) {
        if (before == after) return
        val saved = after.copy(revision = Math.addExact(before.revision, 1), updatedAt = clock.millis())
        check(dao.update(saved) == 1) { "保存失败，请重试" }
    }
    private suspend fun releaseImage(id: String) {
        val asset = database.mediaDao().asset(id)
        if (asset != null && database.mediaDao().deleteIfUnused(id) == 1) asset.relativePath?.let { database.mediaDao().queueCleanup(MediaCleanupEntity(it)) }
    }
    suspend fun write(text: String, mode: HomeReflectionMode, rememberBefore: Boolean = false): HomeReflectionRecord {
        require(text.length <= MAX_TEXT) { "文字最多${MAX_TEXT}字，当前输入已保留" }
        return change(rememberBefore = rememberBefore) {
            it.copy(text = text, mode = if (mode == HomeReflectionMode.IMAGE && it.imageAssetId == null) HomeReflectionMode.TEXT else mode)
        }
    }
    suspend fun importImage(uri: Uri, stillCurrent: () -> Boolean = { true }): HomeReflectionRecord = withContext(Dispatchers.IO) {
        media.importImage({ requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开图片" } },
            onImported = { asset ->
                check(stillCurrent()) { "编辑页已关闭，请重新添加图片" }
                change(rememberBefore = true, rememberAfter = true, rememberHiddenImage = true) {
                    it.copy(imageAssetId = asset.id, mode = HomeReflectionMode.IMAGE)
                }
            })
        read()
    }
    suspend fun removeImage() = change(rememberBefore = true, rememberAfter = true, rememberHiddenImage = true) {
        it.copy(imageAssetId = null, mode = HomeReflectionMode.TEXT)
    }

    /** 恢复一种内容不丢掉另一种；当前内容、历史引用及使用时间在同一事务中更新。 */
    suspend fun useHistory(id: String): HomeReflectionRecord = database.withTransaction {
        val entry = requireNotNull(history.entry(id)) { "这条历史记录已不存在" }
        change(rememberBefore = true, rememberAfter = true) { current ->
            when (entry.mode) {
                HomeReflectionMode.TEXT -> current.copy(text = entry.text, mode = HomeReflectionMode.TEXT)
                HomeReflectionMode.IMAGE -> current.copy(imageAssetId = requireNotNull(entry.imageAssetId), mode = HomeReflectionMode.IMAGE)
            }
        }
    }

    private suspend fun change(rememberBefore: Boolean = false, rememberAfter: Boolean = false, rememberHiddenImage: Boolean = false,
        patch: (HomeReflectionEntity) -> HomeReflectionEntity): HomeReflectionRecord = database.withTransaction {
        val stored = dao.record()
        val before = stored?.content ?: HomeReflectionEntity()
        val after = patch(before)
        val switched = before.mode != after.mode
        if (rememberBefore || switched) remember(before, usedNow = false)
        if (switched && before.mode == HomeReflectionMode.TEXT && before.text != after.text) remember(before.copy(text = after.text))
        if (rememberHiddenImage && before.imageAssetId != null) remember(before.copy(mode = HomeReflectionMode.IMAGE), usedNow = false)
        if (rememberAfter || switched) remember(after)
        if (after == before) return@withTransaction stored ?: HomeReflectionRecord()
        after.imageAssetId?.let { id ->
            val asset = requireNotNull(database.mediaDao().asset(id)) { "图片已不存在，请重新添加" }
            require(asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0 && asset.relativePath != null) { "请选择本地图片" }
        }
        val saved = after.copy(revision = Math.addExact(before.revision, 1), updatedAt = clock.millis())
        if (stored == null) dao.insert(saved) else check(dao.update(saved) == 1) { "保存失败，请重试" }
        if (before.imageAssetId != after.imageAssetId) before.imageAssetId?.let { old ->
            val asset = database.mediaDao().asset(old)
            if (asset != null && database.mediaDao().deleteIfUnused(old) == 1) asset.relativePath?.let {
                database.mediaDao().queueCleanup(MediaCleanupEntity(it))
            }
        }
        requireNotNull(dao.record())
    }

    private suspend fun remember(content: HomeReflectionEntity, usedNow: Boolean = true) {
        val time = if (usedNow || content.updatedAt == 0L) clock.millis() else content.updatedAt
        val entry = when (content.mode) {
            HomeReflectionMode.TEXT -> if (content.text.isBlank()) null else HomeReflectionHistoryEntity(
                reflectionTextHistoryId(content.text), HomeReflectionMode.TEXT, text = content.text, firstUsedAt = time, lastUsedAt = time)
            HomeReflectionMode.IMAGE -> content.imageAssetId?.let { HomeReflectionHistoryEntity(
                reflectionImageHistoryId(it), HomeReflectionMode.IMAGE, imageAssetId = it, firstUsedAt = time, lastUsedAt = time) }
        } ?: return
        history.insert(entry)
        if (usedNow) history.markUsed(entry.id, time)
    }
    suspend fun cleanImages() {
        try { media.cleanPendingDeletions() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* 清理队列保留，下一次维护重试；原内容已提交。 */ }
    }
    companion object { const val MAX_TEXT = 20_000 }
}
